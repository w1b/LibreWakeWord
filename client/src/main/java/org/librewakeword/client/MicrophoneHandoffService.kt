package org.librewakeword.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Microphone foreground service for one wake-word handoff.
 *
 * Handler apps declare this SDK service in their manifest, protected by LibreWakeWord's
 * signature permission so only LibreWakeWord can start it. It has to be a separate component
 * that LibreWakeWord starts: Android decides whether a background-started foreground service
 * may use the microphone from whoever started it, and LibreWakeWord holds that privilege while
 * armed. LibreWakeWord only starts it while bound to the handler's [WakewordConsumerService], so
 * that service is running in this process. This service then binds it too, so it stays alive
 * for the whole handoff even after LibreWakeWord unbinds.
 */
class MicrophoneHandoffService : Service() {
    private val active = HashMap<String, Int>()
    private val keepAlive = HashMap<String, ServiceConnection>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val event = intent?.getParcelableExtra(WakewordContract.EXTRA_EVENT, WakewordEvent::class.java)
        val callback = IWakewordDeliveryCallback.Stub.asInterface(
            intent?.extras?.getBinder(WakewordContract.EXTRA_DELIVERY_CALLBACK),
        )
        val consumer = intent?.getStringExtra(WakewordContract.EXTRA_CONSUMER_CLASS)
            ?.let(WakewordConsumerService::running)
        if (intent?.action != WakewordContract.ACTION_BEGIN_MICROPHONE_HANDOFF ||
            event == null || consumer == null
        ) {
            Log.w(TAG, "Rejected microphone handoff start for ${event?.eventId}")
            runCatching { callback?.acknowledge(event?.eventId) }
            abandon(startId)
            return START_NOT_STICKY
        }
        var handoff: MicrophoneHandoffRegistry.Handoff? = null
        try {
            if (active.isEmpty()) {
                startForeground(
                    NOTIFICATION_ID,
                    consumer.createMicrophoneHandoffNotification(event),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }
            handoff = MicrophoneHandoffRegistry.begin(consumer, event, callback)
            active[event.eventId] = startId
            keepConsumerAlive(event.eventId, consumer)
            handoff.lifecycle.invokeOnClose { mainExecutor.execute { finish(event.eventId, startId) } }
            consumer.dispatchMicrophoneHandoff(handoff)
        } catch (error: Throwable) {
            Log.e(TAG, "Microphone handoff failed for ${event.eventId}", error)
            if (handoff != null) {
                MicrophoneHandoffRegistry.fail(handoff)
            } else {
                runCatching { callback?.acknowledge(event.eventId) }
                abandon(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun keepConsumerAlive(eventId: String, consumer: WakewordConsumerService) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent(WakewordContract.ACTION_CONSUMER).setClass(this, consumer.javaClass)
        if (bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            keepAlive.put(eventId, connection)?.let(::unbindService)
        }
    }

    private fun finish(eventId: String, startId: Int) {
        keepAlive.remove(eventId)?.let(::unbindService)
        if (active.remove(eventId) != startId) return
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Stops a start that won't become a handoff. A service started with startForegroundService()
     * must enter the foreground before it stops, or Android crashes the app.
     */
    private fun abandon(startId: Int) {
        if (active.isEmpty()) {
            runCatching {
                ensureNotificationChannel(this)
                startForeground(
                    NOTIFICATION_ID,
                    Notification.Builder(this, NOTIFICATION_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }.onFailure { Log.w(TAG, "Could not enter foreground to stop cleanly", it) }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        keepAlive.values.forEach(::unbindService)
        keepAlive.clear()
        active.clear()
        super.onDestroy()
    }

    internal companion object {
        private const val TAG = "WakewordHandoff"
        private const val NOTIFICATION_ID = 0x4c5757
        const val NOTIFICATION_CHANNEL = "librewakeword_microphone_handoff"

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Wake word microphone",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
