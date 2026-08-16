package org.librewakeword

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.os.Bundle
import org.librewakeword.client.RequestWakewordAccess

/** User-owned approval boundary for third-party consumer integrations. */
class AccessRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getParcelableExtra(
            RequestWakewordAccess.EXTRA_CONSUMER,
            ComponentName::class.java,
        )
        val caller = callingPackage
        if (requested == null || caller == null || caller != requested.packageName) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val application = application as HotwordApplication
        val store = application.detectionCoordinator.integrationStore
        store.invalidateDiscovery()
        val integration = store.discoverConsumers()
            .firstOrNull { it.component == requested }
        if (integration == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val access = buildString {
            append(if (integration.handler) "Handle wake words" else "Observe wake words")
            append("\n\nEvents: ")
            append(
                when {
                    integration.candidateEvents && integration.confirmedEvents ->
                        "DSP candidates and confirmed wake words"
                    integration.candidateEvents -> "DSP candidates"
                    else -> "Confirmed wake words"
                },
            )
            append("\nAudio: ")
            append(if (integration.audioPrefix) "Bounded wake-word prefix" else "None")
            if (integration.lowLatency) append("\nReadiness: Keep service prepared while armed")
        }
        AlertDialog.Builder(this)
            .setTitle("Allow ${integration.label}?")
            .setMessage(access)
            .setNegativeButton("Cancel") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setPositiveButton("Allow") { _, _ ->
                application.detectionCoordinator.integrationStore.setConsumerEnabled(requested, true)
                application.detectionCoordinator.refreshIntegrations()
                setResult(RESULT_OK)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }
}
