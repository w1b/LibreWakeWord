package org.librewakeword.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.librewakeword.client.IWakewordConsumer
import org.librewakeword.client.IWakewordDeliveryCallback
import org.librewakeword.client.WakewordAvailability
import org.librewakeword.client.WakewordContract
import org.librewakeword.client.WakewordEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Asynchronous reverse-binding transport. Consumer work never runs on the detection thread. */
class ConsumerRouter(
    private val context: Context,
    private val store: IntegrationStore,
    private val journal: EventJournal,
) {
    private val connections = ConcurrentHashMap<ComponentName, ConsumerConnection>()
    @Volatile private var enabledConsumers: List<ConsumerIntegration> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private class PendingDelivery(
        val event: WakewordEvent,
        var persisted: Boolean,
        val kind: DeliveryKind,
        private val onHandoffClaimed: Runnable?,
    ) {
        val key = "${event.eventId}:${kind.name}"
        private val claimSignalled = AtomicBoolean(false)

        fun signalHandoffClaimed() {
            if (claimSignalled.compareAndSet(false, true)) onHandoffClaimed?.run()
        }
    }

    private enum class DeliveryKind { EVENT, HANDOFF_START, HANDOFF_COMPLETION }

    init {
        handler.post(::restorePendingDeliveries)
    }

    fun refreshWarmConnections(armed: Boolean, phrase: String?) {
        val enabledList = store.enabledConsumers()
        enabledConsumers = enabledList
        val enabled = enabledList.associateBy { it.component }
        val desired = if (armed) {
            enabled.values.filter { it.lowLatency }.associateBy { it.component }
        } else emptyMap()
        connections.entries.toList().forEach { (component, connection) ->
            if (component !in enabled) {
                connection.cancelPending()
                connection.close()
                connections.remove(component, connection)
            } else if (component !in desired) {
                connection.setWarm(false, phrase)
                if (connection.isIdle()) {
                    connection.close()
                    connections.remove(component, connection)
                }
            }
        }
        desired.values.forEach { integration ->
            connection(integration).setWarm(true, phrase)
        }
    }

    fun route(
        event: WakewordEvent,
        candidateAudioUpdate: Boolean = false,
        handlerFilter: Boolean? = null,
        allowHandoff: Boolean = true,
        onHandoffClaimed: Runnable? = null,
        handoffCompletion: Boolean = false,
    ): Int {
        val selected = enabledConsumers
            .asSequence()
            .filter { handlerFilter == null || it.handler == handlerFilter }
            .filter { integration ->
                when (event.stage) {
                    WakewordEvent.STAGE_CANDIDATE -> integration.candidateEvents &&
                        (!candidateAudioUpdate || (integration.audioPrefix && !integration.handler))
                    WakewordEvent.STAGE_CONFIRMED -> integration.confirmedEvents
                    else -> false
                }
            }
            .sortedByDescending { it.handler }
            .toList()
        var queued = 0
        selected.forEach { integration ->
            try {
                val delivered = if (integration.audioPrefix) event else event.copy(audio = null)
                Log.i(
                    TAG,
                    "Queueing stage=${event.stage} event=${event.eventId} for " +
                        integration.component.flattenToShortString(),
                )
                delivered.audio?.let { audio ->
                    context.grantUriPermission(
                        integration.component.packageName,
                        audio.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val persisted = if (integration.handler) false else {
                    journal.queueDelivery(integration.component.flattenToString(), delivered)
                    true
                }
                val startsHandoff = allowHandoff && integration.handler && (
                    (integration.candidateEvents &&
                        event.stage == WakewordEvent.STAGE_CANDIDATE) ||
                        (!integration.candidateEvents &&
                            event.stage == WakewordEvent.STAGE_CONFIRMED)
                    )
                val kind = when {
                    handoffCompletion && integration.handler ->
                        DeliveryKind.HANDOFF_COMPLETION
                    startsHandoff -> DeliveryKind.HANDOFF_START
                    else -> DeliveryKind.EVENT
                }
                connection(integration).enqueue(
                    delivered,
                    persisted,
                    kind,
                    if (kind == DeliveryKind.HANDOFF_START) onHandoffClaimed else null,
                )
                queued++
            } catch (error: Throwable) {
                // A package may disappear, lose its grant target, or have a broken local queue
                // between discovery and delivery. One integration must never suppress the
                // remaining observers, the selected activity, local feedback, or the webhook.
                Log.e(
                    TAG,
                    "Could not queue ${event.eventId} for " +
                        integration.component.flattenToShortString(),
                    error,
                )
            }
        }
        return queued
    }

    /** Finalizes an active handoff without emitting a second public consumer event. */
    fun completeHandoff(event: WakewordEvent): Int = route(
        event,
        handlerFilter = true,
        allowHandoff = false,
        handoffCompletion = true,
    )

    private fun connection(integration: ConsumerIntegration): ConsumerConnection =
        connections.computeIfAbsent(integration.component) { ConsumerConnection(integration) }

    private inner class ConsumerConnection(val integration: ConsumerIntegration) : ServiceConnection {
        val pending = LinkedHashMap<String, PendingDelivery>()
        private val inFlight = HashSet<String>()
        var availability: WakewordAvailability? = null
        private var consumer: IWakewordConsumer? = null
        private var bound = false
        private var binding = false
        private var warm = false
        private var retryScheduled = false

        @Synchronized
        fun setWarm(warm: Boolean, phrase: String?) {
            this.warm = warm
            if (warm || availability != null) {
                availability = WakewordAvailability(
                    state = if (warm) WakewordAvailability.STATE_ARMED
                    else WakewordAvailability.STATE_INACTIVE,
                    phrase = phrase,
                )
                consumer?.let { target ->
                    runCatching { target.onAvailabilityChanged(availability) }
                }
            }
            if (warm) ensureBound()
        }

        @Synchronized
        fun isIdle(): Boolean = pending.isEmpty()

        @Synchronized
        fun cancelPending() {
            pending.values.forEach { delivery ->
                delivery.signalHandoffClaimed()
                val event = delivery.event
                journal.acknowledgeDelivery(
                    integration.component.flattenToString(),
                    event.eventId,
                )
            }
            pending.clear()
            inFlight.clear()
        }

        @Synchronized
        fun enqueue(
            event: WakewordEvent,
            persisted: Boolean,
            kind: DeliveryKind = DeliveryKind.EVENT,
            onHandoffClaimed: Runnable? = null,
        ) {
            val delivery = PendingDelivery(
                event,
                persisted,
                kind,
                onHandoffClaimed,
            )
            pending.put(delivery.key, delivery)?.signalHandoffClaimed()
            ensureBound()
            drain()
        }

        @Synchronized
        fun ensureBound() {
            if (bound || binding) return
            binding = true
            val intent = Intent(WakewordContract.ACTION_CONSUMER).setComponent(integration.component)
            try {
                bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
                binding = false
                if (!bound) {
                    Log.w(TAG, "Could not bind ${integration.component.flattenToShortString()}")
                    scheduleRetry()
                }
            } catch (error: Throwable) {
                binding = false
                Log.e(TAG, "Binding ${integration.component.flattenToShortString()} failed", error)
                scheduleRetry()
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(this) {
                binding = false
                bound = true
                consumer = IWakewordConsumer.Stub.asInterface(service)
                availability?.let { runCatching { consumer?.onAvailabilityChanged(it) } }
                drain()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(this) {
                consumer = null
                inFlight.clear()
            }
            // The binding remains registered; Android reconnects it when the service returns.
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(this) {
                consumer = null
                inFlight.clear()
                if (bound) runCatching { context.unbindService(this) }
                bound = false
                binding = false
                scheduleRetry()
            }
        }

        override fun onNullBinding(name: ComponentName) = onBindingDied(name)

        @Synchronized
        private fun drain() {
            val target = consumer ?: return
            pending.values.toList().forEach { delivery ->
                val event = delivery.event
                if (!inFlight.add(delivery.key)) return@forEach
                try {
                    if (!integration.handler && !delivery.persisted) {
                        journal.queueDelivery(
                            integration.component.flattenToString(),
                            event,
                        )
                        delivery.persisted = true
                    }
                    val callback = object : IWakewordDeliveryCallback.Stub() {
                        override fun acknowledge(eventId: String) {
                            synchronized(this@ConsumerConnection) {
                                pending.remove(delivery.key)
                                inFlight.remove(delivery.key)
                                if (delivery.persisted) {
                                    journal.acknowledgeDelivery(
                                        integration.component.flattenToString(),
                                        event.eventId,
                                    )
                                }
                                if (!warm && pending.isEmpty()) {
                                    close()
                                    connections.remove(integration.component, this@ConsumerConnection)
                                }
                            }
                            delivery.signalHandoffClaimed()
                        }
                    }
                    when (delivery.kind) {
                        DeliveryKind.HANDOFF_START -> {
                            val handoffStartedAt = SystemClock.elapsedRealtimeNanos()
                            lendBackgroundActivityStarts()
                            // The consumer's control service is bound (this is draining),
                            // so the SDK handoff service can find it in-process.
                            context.startForegroundService(
                                Intent(WakewordContract.ACTION_BEGIN_MICROPHONE_HANDOFF)
                                    .setClassName(
                                        integration.component.packageName,
                                        WakewordContract.MICROPHONE_HANDOFF_SERVICE,
                                    )
                                    .putExtra(WakewordContract.EXTRA_EVENT, event)
                                    .putExtra(
                                        WakewordContract.EXTRA_CONSUMER_CLASS,
                                        integration.component.className,
                                    )
                                    .putExtras(Bundle().apply {
                                        putBinder(
                                            WakewordContract.EXTRA_DELIVERY_CALLBACK,
                                            callback.asBinder(),
                                        )
                                    }),
                            )
                            Log.i(
                                TAG,
                                "Requested microphone handoff event=${event.eventId} " +
                                    "transportMs=${
                                        millis(
                                            SystemClock.elapsedRealtimeNanos() -
                                                handoffStartedAt,
                                        )
                                    }",
                            )
                        }
                        DeliveryKind.HANDOFF_COMPLETION ->
                            target.completeMicrophoneHandoff(
                                event,
                                callback,
                            )
                        DeliveryKind.EVENT -> target.onWakewordEvent(event, callback)
                    }
                    handler.postDelayed({
                        synchronized(this@ConsumerConnection) {
                            if (pending.containsKey(delivery.key) &&
                                inFlight.remove(delivery.key)
                            ) {
                                if (integration.handler) {
                                    drop(delivery.key)
                                } else {
                                    drain()
                                }
                            }
                        }
                    }, if (integration.handler) HANDLER_ACK_TIMEOUT_MILLIS else ACK_TIMEOUT_MILLIS)
                } catch (error: Throwable) {
                    inFlight.remove(delivery.key)
                    Log.w(TAG, "Delivery to ${integration.component.flattenToShortString()} failed", error)
                    if (integration.handler) {
                        drop(delivery.key)
                        return
                    }
                    consumer = null
                    if (bound) runCatching { context.unbindService(this) }
                    bound = false
                    binding = false
                    scheduleRetry()
                    return
                }
            }
        }

        /**
         * Lets the handler start its own activities (its assistant UI, say) for a short window
         * after the wake word, as Android does for the active voice-interaction service.
         * Background apps can't start activities on their own; this binding lends them
         * LibreWakeWord's privilege and requires the START_ACTIVITIES_FROM_BACKGROUND permission
         * adopted by the arming session.
         */
        private fun lendBackgroundActivityStarts() {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            try {
                val bound = context.bindService(
                    Intent(WakewordContract.ACTION_CONSUMER).setComponent(integration.component),
                    connection,
                    Context.BIND_AUTO_CREATE or BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                )
                if (bound) {
                    handler.postDelayed(
                        { runCatching { context.unbindService(connection) } },
                        ACTIVITY_START_WINDOW_MILLIS,
                    )
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Could not let ${integration.component.flattenToShortString()} " +
                    "start activities", error)
            }
        }

        @Synchronized
        private fun drop(deliveryKey: String) {
            val delivery = pending.remove(deliveryKey) ?: return
            delivery.signalHandoffClaimed()
            inFlight.remove(deliveryKey)
            if (delivery.persisted) {
                journal.acknowledgeDelivery(
                    integration.component.flattenToString(),
                    delivery.event.eventId,
                )
            }
        }

        @Synchronized
        private fun scheduleRetry() {
            if (retryScheduled || (!warm && pending.isEmpty())) return
            retryScheduled = true
            handler.postDelayed({
                synchronized(this) {
                    retryScheduled = false
                    if (!bound && !binding && (warm || pending.isNotEmpty())) ensureBound()
                }
            }, BIND_RETRY_MILLIS)
        }

        @Synchronized
        fun close() {
            if (bound || binding) runCatching { context.unbindService(this) }
            consumer = null
            inFlight.clear()
            bound = false
            binding = false
        }
    }

    private fun restorePendingDeliveries() {
        val enabled = store.enabledConsumers().associateBy { it.component.flattenToString() }
        journal.pendingDeliveries().forEach { (component, event) ->
            val integration = enabled[component]
            if (integration == null || integration.handler) {
                // Microphone ownership is a live transition, never a durable job. Replaying a
                // handler delivery after process restoration could start capture long after wake.
                journal.acknowledgeDelivery(component, event.eventId)
            } else {
                event.audio?.let { audio ->
                    context.grantUriPermission(
                        integration.component.packageName,
                        audio.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                connection(integration).enqueue(event, persisted = true)
            }
        }
    }

    private companion object {
        const val TAG = "WakewordConsumers"
        const val ACTIVITY_START_WINDOW_MILLIS = 10_000L
        // Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, a hidden system API.
        const val BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS = 0x00100000
        const val ACK_TIMEOUT_MILLIS = 10_000L
        const val HANDLER_ACK_TIMEOUT_MILLIS = 2_000L
        const val BIND_RETRY_MILLIS = 1_000L
        fun millis(nanos: Long): String = "%.1f".format(nanos / 1_000_000.0)
    }
}
