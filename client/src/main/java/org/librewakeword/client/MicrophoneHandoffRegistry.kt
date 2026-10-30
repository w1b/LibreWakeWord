package org.librewakeword.client

import android.os.SystemClock
import android.util.Log

/** Live microphone handoffs in this process, by event ID. */
internal object MicrophoneHandoffRegistry {
    private const val TAG = "WakewordHandoff"
    private const val EARLY_COMPLETION_LIFETIME_MILLIS = 10_000L

    internal class Handoff(
        val consumer: WakewordConsumerService,
        val event: WakewordEvent,
        val callback: IWakewordDeliveryCallback?,
    ) {
        var acknowledged = false
        val lifecycle = HandoffLifecycle()
    }

    private class Completion(
        val event: WakewordEvent,
        val callback: IWakewordDeliveryCallback?,
        val receivedAt: Long,
    )

    private val byEvent = HashMap<String, Handoff>()
    // LibreWakeWord sends the finalized prefix after the claim, or after a timeout if the claim
    // is slow. In the latter case it can arrive before the handoff service has started.
    private val earlyCompletions = HashMap<String, Completion>()

    /** Called by the handoff service once it is in the microphone foreground state. */
    fun begin(
        consumer: WakewordConsumerService,
        event: WakewordEvent,
        callback: IWakewordDeliveryCallback?,
    ): Handoff {
        val handoff = Handoff(consumer, event, callback)
        handoff.lifecycle.invokeOnClose {
            synchronized(this) { byEvent.remove(event.eventId, handoff) }
        }
        val replaced = synchronized(this) { byEvent.put(event.eventId, handoff) }
        replaced?.lifecycle?.close()
        return handoff
    }

    /** Runs on the consumer service's audio-priority handoff thread. */
    fun activate(handoff: Handoff) {
        try {
            handoff.lifecycle.invokeOnClaim { claim(handoff) }
            handoff.consumer.onMicrophoneHandoff(
                handoff.event,
                MicrophoneHandoff(handoff.event.eventId, handoff.lifecycle),
            )
            if (!handoff.lifecycle.isClaimed) {
                Log.w(TAG, "Consumer returned without claiming ${handoff.event.eventId}")
                fail(handoff)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Consumer handoff callback failed for ${handoff.event.eventId}", error)
            fail(handoff)
        }
    }

    /** Acknowledges the claim, which tells LibreWakeWord to release its bounded bridge. */
    private fun claim(handoff: Handoff) {
        val event = handoff.event
        val early = synchronized(this) { earlyCompletions.remove(event.eventId) }
        val context = handoff.consumer.applicationContext
        when {
            event.audio != null -> handoff.lifecycle.deliverPrefix(context, event.audio)
            // A raw DSP handler has no later second-stage prefix completion.
            event.stage == WakewordEvent.STAGE_CANDIDATE ->
                handoff.lifecycle.deliverPrefix(context, null)
            early != null -> handoff.lifecycle.deliverPrefix(context, early.event.audio) {
                early.callback?.let { acknowledge(it, event.eventId) }
            }
        }
        Log.i(TAG, "Consumer claimed microphone for ${event.eventId}")
        acknowledge(handoff)
    }

    /** Supplies the finalized prefix to a live handoff without a second app callback. */
    fun complete(
        consumer: WakewordConsumerService,
        event: WakewordEvent,
        callback: IWakewordDeliveryCallback?,
    ) {
        val handoff = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            earlyCompletions.values.removeAll { now - it.receivedAt > EARLY_COMPLETION_LIFETIME_MILLIS }
            byEvent[event.eventId].also {
                if (it == null) earlyCompletions[event.eventId] = Completion(event, callback, now)
            }
        } ?: return
        handoff.lifecycle.deliverPrefix(consumer.applicationContext, event.audio) {
            callback?.let { acknowledge(it, event.eventId) }
        }
    }

    /** A failed handoff must still unblock LibreWakeWord so it releases the microphone. */
    fun fail(handoff: Handoff) {
        acknowledge(handoff)
        handoff.lifecycle.close()
    }

    private fun acknowledge(handoff: Handoff) {
        synchronized(this) {
            if (handoff.acknowledged) return
            handoff.acknowledged = true
        }
        handoff.callback?.let { acknowledge(it, handoff.event.eventId) }
    }

    private fun acknowledge(callback: IWakewordDeliveryCallback, eventId: String) {
        runCatching { callback.acknowledge(eventId) }
            .onFailure { Log.w(TAG, "Could not acknowledge $eventId", it) }
    }
}
