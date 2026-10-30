package org.librewakeword.detection

import android.content.Context
import android.util.Log
import org.librewakeword.DetectionNotifier
import org.librewakeword.ModelConfig
import org.librewakeword.WakewordVoiceInteractionService
import org.librewakeword.client.WakewordEvent
import org.librewakeword.integration.ActivityAction
import org.librewakeword.integration.AudioAssetStore
import org.librewakeword.integration.ConsumerRouter
import org.librewakeword.integration.EventJournal
import org.librewakeword.integration.IntegrationStore
import org.librewakeword.integration.WebhookWorker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DetectionCoordinator(private val context: Context) {
    val integrationStore = IntegrationStore(context)
    private val audioStore = AudioAssetStore(context)
    private val journal = EventJournal(context)
    private val router = ConsumerRouter(context, integrationStore, journal)
    private val activityAction = ActivityAction(context, integrationStore)
    private val candidateEvents = ConcurrentHashMap<String, WakewordEvent>()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var armed = false
    @Volatile private var eventModel = ModelConfig.load(context)

    fun onCandidate(detectionId: String, elapsedNanos: Long, epochMillis: Long) {
        val event = candidate(detectionId, elapsedNanos, epochMillis)
        candidateEvents[detectionId] = event
        router.route(event)
        worker.schedule({ candidateEvents.remove(detectionId, event) }, 10, TimeUnit.SECONDS)
    }

    fun onRejected(detectionId: String) {
        candidateEvents.remove(detectionId)
    }

    fun onTerminalCandidate(detectionId: String, elapsedNanos: Long, epochMillis: Long) {
        router.route(candidate(detectionId, elapsedNanos, epochMillis))
    }

    fun processCapturedCompletion(
        detectionId: String,
        accepted: Boolean,
        sampleRate: Int,
        channelCount: Int,
        audio: ByteArray,
        audioStartElapsedRealtimeNanos: Long?,
        handoffAlreadyStarted: Boolean,
    ) {
        worker.execute {
            complete(
                detectionId,
                accepted,
                sampleRate,
                channelCount,
                audio,
                audioStartElapsedRealtimeNanos,
                handoffAlreadyStarted,
            )
        }
    }

    /** Starts microphone transfer before the bounded prefix is finalized. */
    fun beginConfirmedHandoff(
        detectionId: String,
        claimed: Runnable,
    ) {
        worker.execute {
            try {
                val base = candidateEvents[detectionId] ?: fallbackCandidate(detectionId)
                val provisional = base.copy(
                    eventId = eventId(detectionId, "confirmed"),
                    stage = WakewordEvent.STAGE_CONFIRMED,
                    audio = null,
                )
                val routes = router.route(
                    provisional,
                    handlerFilter = true,
                    allowHandoff = true,
                    onHandoffClaimed = claimed,
                )
                if (routes == 0) claimed.run()
            } catch (error: Throwable) {
                Log.e(TAG, "Could not begin confirmed handoff $detectionId", error)
                claimed.run()
            }
        }
    }

    private fun complete(
        detectionId: String,
        accepted: Boolean,
        sampleRate: Int,
        channelCount: Int,
        pcm: ByteArray,
        audioStartElapsedRealtimeNanos: Long?,
        handoffAlreadyStarted: Boolean,
    ) {
        try {
            val audio = audioStore.create(
                pcm,
                sampleRate,
                channelCount,
                audioStartElapsedRealtimeNanos,
            )
            val candidate = candidateEvents.remove(detectionId)
            val enrichedCandidate = if (candidate != null && audio != null) {
                candidate.copy(
                    eventId = eventId(detectionId, "candidate-audio"),
                    audio = audio,
                )
            } else null
            val base = candidate ?: fallbackCandidate(detectionId)
            var confirmed: WakewordEvent? = null
            if (accepted) {
                confirmed = base.copy(
                    eventId = eventId(detectionId, "confirmed"),
                    stage = WakewordEvent.STAGE_CONFIRMED,
                    audio = audio,
                )
                if (handoffAlreadyStarted) {
                    router.completeHandoff(confirmed)
                } else {
                    router.route(
                        confirmed,
                        handlerFilter = true,
                        allowHandoff = true,
                    )
                }
            }
            enrichedCandidate?.let {
                router.route(it, candidateAudioUpdate = true)
            }
            confirmed?.let(::dispatchConfirmedObserversAndActions)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not complete detection $detectionId", error)
        }
    }

    private fun dispatchConfirmedObserversAndActions(event: WakewordEvent) {
        router.route(event, handlerFilter = false)
        DetectionNotifier.fire(context, event)
        activityAction.dispatch()
        integrationStore.webhook()?.let { WebhookWorker.enqueue(context, it, event) }
    }

    fun setArmed(armed: Boolean) {
        this.armed = armed
        eventModel = ModelConfig.load(context)
        router.refreshWarmConnections(armed, eventModel.phrase)
    }

    fun refreshIntegrations() {
        eventModel = ModelConfig.load(context)
        // On the UI/package-receiver main thread, update the detector mode before exposing the
        // new routing table. This prevents a short window where a candidate handler is routed
        // while the old verifier path still owns the DSP capture session (or vice versa).
        WakewordVoiceInteractionService.onIntegrationConfigurationChanged()
        router.refreshWarmConnections(armed, eventModel.phrase)
    }

    fun onPackagesChanged() {
        integrationStore.invalidateDiscovery()
        refreshIntegrations()
    }

    private fun fallbackCandidate(detectionId: String): WakewordEvent {
        val model = eventModel
        return WakewordEvent(
            eventId = eventId(detectionId, "candidate"),
            detectionId = detectionId,
            stage = WakewordEvent.STAGE_CANDIDATE,
            occurredAtElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(),
            occurredAtEpochMillis = System.currentTimeMillis(),
            phrase = model.phrase,
            localeTag = model.localeTag,
        )
    }

    private fun candidate(detectionId: String, elapsedNanos: Long, epochMillis: Long): WakewordEvent {
        val model = eventModel
        return WakewordEvent(
            eventId = eventId(detectionId, "candidate"),
            detectionId = detectionId,
            stage = WakewordEvent.STAGE_CANDIDATE,
            occurredAtElapsedRealtimeNanos = elapsedNanos,
            occurredAtEpochMillis = epochMillis,
            phrase = model.phrase,
            localeTag = model.localeTag,
        )
    }

    private companion object {
        const val TAG = "DetectionCoordinator"
        fun eventId(detectionId: String, kind: String) = "$detectionId:$kind"
    }
}
