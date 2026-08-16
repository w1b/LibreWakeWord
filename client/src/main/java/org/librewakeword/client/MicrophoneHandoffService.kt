package org.librewakeword.client

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Fresh foreground-service boundary used only for microphone ownership transfer.
 *
 * Handler apps declare this SDK service in their manifest. Their warm consumer service remains a
 * control endpoint; keeping this component fresh preserves Android's while-in-use capability.
 */
class MicrophoneHandoffService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = HashMap<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent
            ?.takeIf { it.action == WakewordContract.ACTION_BEGIN_MICROPHONE_HANDOFF }
            ?.getStringExtra(WakewordContract.EXTRA_HANDOFF_TOKEN)
        val eventId = intent?.getStringExtra(WakewordContract.EXTRA_EVENT_ID)
        if (token.isNullOrEmpty() || eventId.isNullOrEmpty() || token != eventId) {
            Log.w(TAG, "Rejected malformed microphone handoff start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val prepared = MicrophoneHandoffRegistry.consumeOrAwait(token, eventId) { ready ->
            mainHandler.post { startPrepared(intent, ready, startId) }
        }
        if (prepared == null) {
            mainHandler.postDelayed({
                if (MicrophoneHandoffRegistry.cancelAwait(token)) {
                    Log.w(TAG, "Timed out waiting for authenticated microphone handoff")
                    stopSelf(startId)
                }
            }, PREPARATION_TIMEOUT_MILLIS)
            return START_NOT_STICKY
        }
        startPrepared(intent, prepared, startId)
        return START_NOT_STICKY
    }

    private fun startPrepared(intent: Intent?, prepared: MicrophoneHandoffRegistry.Prepared, startId: Int) {
        try {
            val foregroundStartedAt = SystemClock.elapsedRealtimeNanos()
            if (active.isEmpty()) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    prepared.consumer.createMicrophoneHandoffNotification(prepared.event),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }
            Log.i(
                TAG,
                "foreground ready event=${prepared.event.eventId} callMs=" +
                    "%.1f".format(
                        (SystemClock.elapsedRealtimeNanos() - foregroundStartedAt) / 1_000_000.0,
                    ),
            )
            active[prepared.event.eventId] = startId
            prepared.lifecycle.invokeOnClose {
                finishHandoff(prepared.event.eventId, startId)
            }
            IMicrophoneHandoffStatus.Stub.asInterface(
                intent?.extras?.getBinder(WakewordContract.EXTRA_HANDOFF_STATUS),
            )?.foregroundReady(prepared.event.eventId)
            if (!MicrophoneHandoffRegistry.foregroundReady(prepared)) {
                error("Consumer rejected microphone handoff")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Microphone handoff failed for ${prepared.event.eventId}", error)
            MicrophoneHandoffRegistry.markFailed(prepared)
            prepared.lifecycle.close()
            stopSelf(startId)
        }
    }

    private fun finishHandoff(eventId: String, startId: Int) {
        mainHandler.post {
            if (active.remove(eventId) != startId) return@post
            if (active.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        active.clear()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WakewordHandoff"
        const val NOTIFICATION_ID = 0x4c5757
        const val PREPARATION_TIMEOUT_MILLIS = 1_000L
    }
}
