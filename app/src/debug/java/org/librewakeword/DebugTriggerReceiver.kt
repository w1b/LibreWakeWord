package org.librewakeword

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Simulates a verified wake word without speaking, for testing consumer delivery and microphone
 * handoff. It follows the same coordinator calls as WakewordVoiceInteractionService, with one
 * second of 440 Hz tone as the prefix. It must run while armed: background foreground-service
 * starts rely on the arming instrumentation.
 *
 *     adb shell am broadcast -n org.librewakeword/.DebugTriggerReceiver
 *
 * With `--es enable <component>` it instead enables that consumer (replacing the current
 * handler, as the Integrations screen does).
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val application = context.applicationContext as HotwordApplication
                val enable = intent.getStringExtra("enable")
                if (enable != null) enable(application, enable) else trigger(application)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun enable(application: HotwordApplication, component: String) {
        val coordinator = application.detectionCoordinator
        val enabled = ComponentName.unflattenFromString(component)
            ?.let { coordinator.integrationStore.setConsumerEnabled(it, true) } == true
        coordinator.refreshIntegrations()
        Log.i(TAG, "enable $component -> $enabled")
    }

    private fun trigger(application: HotwordApplication) {
        val coordinator = application.detectionCoordinator
        val detectionId = "debug-${UUID.randomUUID()}"
        coordinator.onCandidate(
            detectionId,
            SystemClock.elapsedRealtimeNanos(),
            System.currentTimeMillis(),
        )
        var handoffStarted = false
        if (coordinator.integrationStore.hasConfirmedHandler()) {
            val claimed = CountDownLatch(1)
            coordinator.beginConfirmedHandoff(detectionId, claimed::countDown)
            Log.i(TAG, "handoff claimed=${claimed.await(1, TimeUnit.SECONDS)} id=$detectionId")
            handoffStarted = true
        }
        coordinator.processCapturedCompletion(
            detectionId,
            true,
            SAMPLE_RATE,
            1,
            tone(),
            null,
            handoffStarted,
        )
    }

    private fun tone(): ByteArray {
        val pcm = ByteArray(SAMPLE_RATE * 2)
        for (i in 0 until SAMPLE_RATE) {
            val sample = (sin(2 * PI * 440 * i / SAMPLE_RATE) * 8_000).toInt()
            pcm[2 * i] = sample.toByte()
            pcm[2 * i + 1] = (sample shr 8).toByte()
        }
        return pcm
    }

    private companion object {
        const val TAG = "DebugTrigger"
        const val SAMPLE_RATE = 16_000
    }
}
