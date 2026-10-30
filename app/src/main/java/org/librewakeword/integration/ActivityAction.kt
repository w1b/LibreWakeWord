package org.librewakeword.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

data class VoiceActivity(
    val component: ComponentName,
    val label: String,
    val signingCertificateSha256: String,
)

/**
 * Opens the selected ACTION_VOICE_COMMAND activity. Background activity starts are allowed
 * because the arming instrumentation runs with shell's START_ACTIVITIES_FROM_BACKGROUND.
 */
class ActivityAction(private val context: Context, private val store: IntegrationStore) {
    fun dispatch() {
        val component = store.activityComponent() ?: return
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not open ${component.flattenToShortString()}", error)
        }
    }

    private companion object {
        const val TAG = "WakewordActivity"
    }
}
