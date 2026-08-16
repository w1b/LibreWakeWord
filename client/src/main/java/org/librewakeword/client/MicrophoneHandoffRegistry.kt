package org.librewakeword.client

import android.os.SystemClock
import android.util.Log
import java.util.IdentityHashMap

/** Process-local rendezvous between the warm control service and the fresh microphone service. */
internal object MicrophoneHandoffRegistry {
    private const val TAG = "WakewordHandoff"
    private const val TOKEN_LIFETIME_MILLIS = 60_000L

    internal enum class State { PENDING, ACTIVATING, CLAIMED, FAILED }

    internal class Prepared(
        val token: String,
        val consumer: WakewordConsumerService,
        val event: WakewordEvent,
        var deliveryCallback: IWakewordDeliveryCallback?,
        val expiresAtElapsedRealtime: Long,
    ) {
        var state = State.PENDING
        var foregroundReady = false
        val lifecycle = HandoffLifecycle()
    }

    private val byToken = HashMap<String, Prepared>()
    private val waitingForPreparation = HashMap<String, (Prepared) -> Unit>()
    private val byConsumer =
        IdentityHashMap<WakewordConsumerService, MutableMap<String, Prepared>>()

    fun begin(
        consumer: WakewordConsumerService,
        event: WakewordEvent,
        callback: IWakewordDeliveryCallback?,
    ): Prepared {
        val token = event.eventId
        val prepared = Prepared(
            token,
            consumer,
            event,
            callback,
            SystemClock.elapsedRealtime() + TOKEN_LIFETIME_MILLIS,
        )
        prepared.lifecycle.invokeOnClose {
            synchronized(this) {
                if (current(prepared) === prepared) remove(prepared)
            }
        }
        var replaced: Prepared? = null
        val waiter = synchronized(this) {
            val events = byConsumer.getOrPut(consumer, ::HashMap)
            replaced = events.put(event.eventId, prepared)
            replaced?.let { previous ->
                byToken.remove(previous.token)
                previous.deliveryCallback = null
            }
            byToken[token] = prepared
            waitingForPreparation.remove(token)
        }
        replaced?.lifecycle?.close()
        waiter?.let {
            synchronized(this) { byToken.remove(token, prepared) }
            it(prepared)
        }
        return prepared
    }

    /** Returns a prepared event, or registers a one-shot callback if the service arrived first. */
    @Synchronized
    fun consumeOrAwait(
        token: String?,
        eventId: String?,
        onPrepared: (Prepared) -> Unit,
    ): Prepared? {
        if (token.isNullOrEmpty() || token != eventId) return null
        val prepared = byToken.remove(token)
        if (prepared != null) {
            if (SystemClock.elapsedRealtime() <= prepared.expiresAtElapsedRealtime) return prepared
            remove(prepared)
            return null
        }
        waitingForPreparation[token] = onPrepared
        return null
    }

    @Synchronized
    fun cancelAwait(token: String): Boolean = waitingForPreparation.remove(token) != null

    /** Called after the fresh service has entered microphone foreground state. */
    fun foregroundReady(prepared: Prepared): Boolean {
        val dispatch = synchronized(this) {
            if (current(prepared) !== prepared) return false
            prepared.foregroundReady = true
            when (prepared.state) {
                State.PENDING -> {
                    prepared.state = State.ACTIVATING
                    true
                }
                State.ACTIVATING, State.CLAIMED -> false
                State.FAILED -> return false
            }
        }
        if (dispatch) prepared.consumer.dispatchMicrophoneHandoff(prepared)
        return true
    }

    /** Runs on the consumer service's prestarted audio-priority handoff thread. */
    fun activate(prepared: Prepared): Boolean {
        synchronized(this) {
            if (current(prepared) !== prepared ||
                prepared.state != State.ACTIVATING ||
                !prepared.foregroundReady
            ) return false
        }
        return try {
            prepared.lifecycle.invokeOnClaim { claim(prepared) }
            prepared.consumer.onMicrophoneHandoff(
                prepared.event,
                MicrophoneHandoff(prepared.event.eventId, prepared.lifecycle),
            )
            if (!prepared.lifecycle.isClaimed) {
                Log.w(TAG, "Consumer returned without claiming ${prepared.event.eventId}")
                releaseWithoutClaim(prepared)
                prepared.lifecycle.close()
                return false
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Consumer handoff callback failed for ${prepared.event.eventId}", error)
            releaseWithoutClaim(prepared)
            prepared.lifecycle.close()
            false
        }
    }

    /** Claims microphone ownership and acknowledges the delivery at the earliest safe instant. */
    private fun claim(prepared: Prepared) {
        var acknowledge: IWakewordDeliveryCallback? = null
        synchronized(this) {
            if (current(prepared) !== prepared || prepared.state != State.ACTIVATING) return
            prepared.state = State.CLAIMED
            acknowledge = prepared.deliveryCallback
            prepared.deliveryCallback = null
            if (prepared.event.audio != null) {
                prepared.lifecycle.deliverPrefix(
                    prepared.consumer,
                    prepared.event.audio,
                )
            } else if (prepared.event.stage == WakewordEvent.STAGE_CANDIDATE) {
                // A raw DSP handler has no later second-stage prefix completion.
                prepared.lifecycle.deliverPrefix(prepared.consumer, null)
            }
        }
        Log.i(TAG, "Consumer claimed microphone for ${prepared.event.eventId}")
        acknowledge?.let { acknowledge(it, prepared.event.eventId) }
    }

    /** A failed consumer must still unblock LibreWakeWord so it releases the microphone. */
    private fun releaseWithoutClaim(prepared: Prepared) {
        var acknowledge: IWakewordDeliveryCallback? = null
        synchronized(this) {
            if (current(prepared) !== prepared) return
            prepared.state = State.CLAIMED
            acknowledge = prepared.deliveryCallback
            prepared.deliveryCallback = null
        }
        acknowledge?.let { acknowledge(it, prepared.event.eventId) }
    }

    /** Supplies the finalized prefix to a live handoff without a second app callback. */
    fun complete(
        consumer: WakewordConsumerService,
        event: WakewordEvent,
        callback: IWakewordDeliveryCallback?,
    ) {
        val prepared = synchronized(this) {
            byConsumer[consumer]?.get(event.eventId)
        }
        if (prepared == null) {
            callback?.let { acknowledge(it, event.eventId) }
            return
        }
        prepared.lifecycle.deliverPrefix(
            consumer,
            event.audio,
        ) {
            callback?.let { acknowledge(it, event.eventId) }
        }
    }

    fun markFailed(prepared: Prepared) {
        val acknowledge = synchronized(this) {
            if (current(prepared) !== prepared) return
            prepared.state = State.FAILED
            prepared.deliveryCallback.also { prepared.deliveryCallback = null }
        }
        acknowledge?.let { acknowledge(it, prepared.event.eventId) }
    }

    @Synchronized
    fun expire(prepared: Prepared) {
        if (SystemClock.elapsedRealtime() >= prepared.expiresAtElapsedRealtime &&
            current(prepared) === prepared
        ) {
            remove(prepared)
            prepared.lifecycle.close()
        }
    }

    fun invalidate(consumer: WakewordConsumerService) {
        val prepared = synchronized(this) {
            byConsumer.remove(consumer)?.values?.toList().orEmpty().also { entries ->
                entries.forEach {
                    byToken.remove(it.token)
                    waitingForPreparation.remove(it.token)
                    it.deliveryCallback = null
                }
            }
        }
        prepared.forEach { it.lifecycle.close() }
    }

    private fun current(prepared: Prepared): Prepared? =
        byConsumer[prepared.consumer]?.get(prepared.event.eventId)

    private fun remove(prepared: Prepared) {
        byToken.remove(prepared.token)
        waitingForPreparation.remove(prepared.token)
        byConsumer[prepared.consumer]?.let { events ->
            events.remove(prepared.event.eventId, prepared)
            if (events.isEmpty()) byConsumer.remove(prepared.consumer)
        }
        prepared.deliveryCallback = null
    }

    private fun acknowledge(callback: IWakewordDeliveryCallback, eventId: String) {
        runCatching { callback.acknowledge(eventId) }
            .onFailure { Log.w(TAG, "Could not acknowledge $eventId", it) }
    }
}
