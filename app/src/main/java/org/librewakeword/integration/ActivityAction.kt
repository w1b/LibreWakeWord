package org.librewakeword.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

data class VoiceActivity(
    val component: ComponentName,
    val label: String,
    val signingCertificateSha256: String,
)

class ActivityAction(private val context: Context, private val store: IntegrationStore) {
    fun discover(): List<VoiceActivity> = store.discoverVoiceActivities()

    fun dispatch() {
        val component = store.activityComponent() ?: return
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
        } catch (error: Throwable) {
            Log.w(TAG, "Background activity launch failed; posting a user action", error)
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Wake word actions",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
                val pending = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = Notification.Builder(context, CHANNEL)
                    .setContentTitle("Wake word detected")
                    .setContentText("Tap to open ${component.packageName}")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
                manager.notify(FALLBACK_ID, notification)
            } catch (notificationError: Throwable) {
                Log.e(TAG, "Could not post fallback activity notification", notificationError)
            }
        }
    }

    companion object {
        const val CHANNEL = "wakeword_detections"
        private const val TAG = "WakewordActivity"
        private const val FALLBACK_ID = 1002
    }
}
