package org.librewakeword.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread

/**
 * Base service for application integrations. Declare a subclass with
 * [WakewordContract.ACTION_CONSUMER] in the manifest. Observers override [onWakeword]; microphone
 * handlers override [onMicrophoneHandoff].
 *
 * Observer and availability callbacks run on the main thread. Microphone handoffs run serially on
 * a prestarted audio-priority thread so a busy UI cannot clip the start of a user's command.
 * Consumers must use [WakewordEvent.eventId] to make asynchronous work idempotent.
 */
abstract class WakewordConsumerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var microphoneHandoffThread: HandlerThread? = null
    private var microphoneHandoffHandler: Handler? = null
    private var isMicrophoneHandler = false

    private val binder = object : IWakewordConsumer.Stub() {
        override fun beginMicrophoneHandoff(
            event: WakewordEvent,
            callback: IWakewordDeliveryCallback?,
        ) {
            if (!isLibreWakewordCaller()) return
            val target = microphoneHandoffHandler ?: return
            target.post {
                val prepared = MicrophoneHandoffRegistry.begin(
                    this@WakewordConsumerService,
                    event,
                    callback,
                )
                target.postDelayed(
                    { MicrophoneHandoffRegistry.expire(prepared) },
                    HANDOFF_TOKEN_LIFETIME_MILLIS,
                )
            }
        }

        override fun completeMicrophoneHandoff(
            event: WakewordEvent,
            callback: IWakewordDeliveryCallback?,
        ) {
            if (!isLibreWakewordCaller()) return
            val target = microphoneHandoffHandler ?: return
            target.post {
                MicrophoneHandoffRegistry.complete(
                    this@WakewordConsumerService,
                    event,
                    callback,
                )
            }
        }

        override fun onWakewordEvent(
            event: WakewordEvent,
            callback: IWakewordDeliveryCallback?,
        ) {
            if (!isLibreWakewordCaller()) {
                Log.w(TAG, "Rejected wakeword callback from uid=${Binder.getCallingUid()}")
                return
            }
            mainHandler.post {
                try {
                    onWakeword(event)
                    callback?.acknowledge(event.eventId)
                } catch (error: Throwable) {
                    Log.e(TAG, "Consumer callback failed for ${event.eventId}", error)
                }
            }
        }

        override fun onAvailabilityChanged(availability: WakewordAvailability) {
            if (!isLibreWakewordCaller()) return
            if (isMicrophoneHandler &&
                availability.state == WakewordAvailability.STATE_ARMED
            ) {
                microphoneHandoffHandler?.post {
                    WakewordAudioCapture.warmUp(applicationContext)
                }
            }
            mainHandler.post { onWakewordAvailabilityChanged(availability) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val metadata = runCatching {
            packageManager.getServiceInfo(
                ComponentName(this, javaClass),
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            ).metaData
        }.getOrNull()
        isMicrophoneHandler =
            metadata?.getString(WakewordContract.META_ROLE) == WakewordContract.ROLE_HANDLER
        if (isMicrophoneHandler) {
            microphoneHandoffThread = HandlerThread(
                "wakeword-microphone-handoff",
                Process.THREAD_PRIORITY_AUDIO,
            ).also { thread ->
                thread.start()
                microphoneHandoffHandler = Handler(thread.looper)
            }
            ensureDefaultNotificationChannel()
        }
    }

    final override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == WakewordContract.ACTION_CONSUMER) binder else null
    }

    /**
     * Called after the SDK's fresh microphone foreground service is active.
     *
     * Most handlers should call [MicrophoneHandoff.openAudioCapture] synchronously, then move reads
     * to their audio worker and close the capture when listening ends. Advanced handlers using a
     * custom AudioRecord must construct and start it, then call [MicrophoneHandoff.claim]
     * immediately after it enters the recording state. The same event ID can be presented again
     * after a transport retry, so work must be idempotent.
     */
    @WorkerThread
    open fun onMicrophoneHandoff(event: WakewordEvent, handoff: MicrophoneHandoff) {
        handoff.claim()
        mainHandler.post {
            try {
                onWakeword(event)
            } finally {
                handoff.close()
            }
        }
    }

    /** Override to customize the foreground notification shown while the consumer owns the mic. */
    @MainThread
    open fun createMicrophoneHandoffNotification(event: WakewordEvent): Notification {
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        return Notification.Builder(this, HANDOFF_NOTIFICATION_CHANNEL)
            .setContentTitle("$appLabel is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    @MainThread
    open fun onWakewordAvailabilityChanged(availability: WakewordAvailability) = Unit

    @MainThread
    open fun onWakeword(event: WakewordEvent) = Unit

    override fun onDestroy() {
        MicrophoneHandoffRegistry.invalidate(this)
        microphoneHandoffHandler = null
        microphoneHandoffThread?.quitSafely()
        microphoneHandoffThread = null
        isMicrophoneHandler = false
        super.onDestroy()
    }

    /** Schedules capture only after the SDK handoff service has entered microphone foreground. */
    internal fun dispatchMicrophoneHandoff(prepared: MicrophoneHandoffRegistry.Prepared) {
        val target = microphoneHandoffHandler
        if (target == null) {
            MicrophoneHandoffRegistry.markFailed(prepared)
            prepared.lifecycle.close()
            return
        }
        target.post { MicrophoneHandoffRegistry.activate(prepared) }
    }

    private fun ensureDefaultNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                HANDOFF_NOTIFICATION_CHANNEL,
                "Wake word microphone",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun isLibreWakewordCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        return packageManager.getPackagesForUid(callingUid)
            ?.contains(WakewordContract.LIBREWAKEWORD_PACKAGE) == true
    }

    private companion object {
        const val TAG = "WakewordConsumer"
        const val HANDOFF_TOKEN_LIFETIME_MILLIS = 60_000L
        const val HANDOFF_NOTIFICATION_CHANNEL = "librewakeword_microphone_handoff"
    }
}
