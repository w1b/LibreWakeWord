package org.librewakeword.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import java.security.MessageDigest
import org.librewakeword.client.WakewordContract

data class ConsumerIntegration(
    val component: ComponentName,
    val label: String,
    val apiVersion: Int,
    val candidateEvents: Boolean,
    val confirmedEvents: Boolean,
    val audioPrefix: Boolean,
    val handler: Boolean,
    val lowLatency: Boolean,
    val signingCertificateSha256: String,
    val enabled: Boolean,
)

/** Discovers declared consumers and stores only explicit, user-approved enablement. */
class IntegrationStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var discoveryCache: List<ConsumerIntegration>? = null
    @Volatile private var voiceActivityCache: List<VoiceActivity>? = null

    fun discoverConsumers(): List<ConsumerIntegration> {
        val enabledObservers = preferences.getStringSet(KEY_ENABLED_OBSERVERS, emptySet()).orEmpty()
        val enabledHandler = preferences.getString(KEY_ENABLED_HANDLER, null)
        val discovered = discoveryCache ?: synchronized(this) {
            discoveryCache ?: scanConsumers().also { discoveryCache = it }
        }
        return discovered.map { integration ->
            val approval = approval(integration)
            integration.copy(
                enabled = if (integration.handler) approval == enabledHandler
                else approval in enabledObservers,
            )
        }.sortedWith(compareBy<ConsumerIntegration> { !it.enabled }.thenBy { it.label.lowercase() })
    }

    @Synchronized
    fun invalidateDiscovery() {
        discoveryCache = null
        voiceActivityCache = null
    }

    fun discoverVoiceActivities(): List<VoiceActivity> = voiceActivityCache ?: synchronized(this) {
        voiceActivityCache ?: scanVoiceActivities().also { voiceActivityCache = it }
    }

    private fun scanConsumers(): List<ConsumerIntegration> {
        val query = Intent(WakewordContract.ACTION_CONSUMER)
        return context.packageManager.queryIntentServices(
            query,
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        ).mapNotNull { resolved ->
            val info = resolved.serviceInfo ?: return@mapNotNull null
            val component = ComponentName(info.packageName, info.name)
            if (!info.exported || info.permission != WakewordContract.PERMISSION_BIND_CONSUMER ||
                context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_DENIED
            ) return@mapNotNull rejected(component, "service is not securely bindable")
            val metadata = info.metaData
            val version = metadata?.getInt(WakewordContract.META_API_VERSION, 0) ?: 0
            if (version != WakewordContract.API_VERSION) {
                return@mapNotNull rejected(component, "unsupported API version $version")
            }
            val eventTokens = (metadata.getString(WakewordContract.META_EVENTS)
                ?: WakewordContract.EVENTS_CONFIRMED
                ).split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            if (eventTokens.isEmpty() || eventTokens.any { it !in VALID_EVENTS }) {
                return@mapNotNull rejected(component, "invalid event declaration $eventTokens")
            }
            val audio = metadata.getString(WakewordContract.META_AUDIO)
                ?: WakewordContract.AUDIO_NONE
            val role = metadata.getString(WakewordContract.META_ROLE)
                ?: WakewordContract.ROLE_OBSERVER
            val latency = metadata.getString(WakewordContract.META_LATENCY)
                ?: WakewordContract.LATENCY_NORMAL
            if (audio !in VALID_AUDIO || role !in VALID_ROLES || latency !in VALID_LATENCIES) {
                return@mapNotNull rejected(
                    component,
                    "invalid audio=$audio role=$role latency=$latency",
                )
            }
            val handler = role == WakewordContract.ROLE_HANDLER
            val candidateEvents = WakewordContract.EVENTS_CANDIDATE in eventTokens
            val confirmedEvents = WakewordContract.EVENTS_CONFIRMED in eventTokens
            val audioPrefix = audio == WakewordContract.AUDIO_PREFIX
            if (handler && (eventTokens.size != 1 || (candidateEvents && audioPrefix))) {
                return@mapNotNull rejected(component, "invalid handler capabilities")
            }
            if (handler && !hasCompatibleHandoffService(info.packageName, info.processName)) {
                return@mapNotNull rejected(component, "microphone handoff service unavailable")
            }
            val certificate = signingIdentity(info.packageName)
                ?: return@mapNotNull rejected(component, "signing identity unavailable")
            ConsumerIntegration(
                component = component,
                label = resolved.loadLabel(context.packageManager).toString(),
                apiVersion = version,
                candidateEvents = candidateEvents,
                confirmedEvents = confirmedEvents,
                audioPrefix = audioPrefix,
                handler = handler,
                lowLatency = latency == WakewordContract.LATENCY_LOW,
                signingCertificateSha256 = certificate,
                enabled = false,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun enabledConsumers(): List<ConsumerIntegration> = discoverConsumers().filter { it.enabled }

    fun hasCandidateHandler(): Boolean = enabledConsumers().any {
        it.handler && it.candidateEvents
    }

    fun hasConfirmedHandler(): Boolean = enabledConsumers().any {
        it.handler && it.confirmedEvents
    }

    fun confirmedHandlerPackage(): String? = enabledConsumers().singleOrNull {
        it.handler && it.confirmedEvents
    }?.component?.packageName

    fun setConsumerEnabled(component: ComponentName, enabled: Boolean): Boolean {
        val discovered = discoverConsumers()
        val target = discovered.firstOrNull { it.component == component } ?: return false
        if (target.handler) {
            preferences.edit(commit = true) {
                if (enabled) {
                    putString(KEY_ENABLED_HANDLER, approval(target))
                } else if (preferences.getString(KEY_ENABLED_HANDLER, null)
                        ?.startsWith(componentPrefix(component)) == true
                ) {
                    remove(KEY_ENABLED_HANDLER)
                }
            }
        } else {
            val values = preferences.getStringSet(KEY_ENABLED_OBSERVERS, emptySet())
                .orEmpty().toMutableSet()
            removeApproval(values, component)
            if (enabled) values += approval(target)
            preferences.edit(commit = true) { putStringSet(KEY_ENABLED_OBSERVERS, values) }
        }
        return discoverConsumers().any { it.component == component && it.enabled == enabled }
    }

    fun activityComponent(): ComponentName? {
        val value = preferences.getString(KEY_ACTIVITY, null) ?: return null
        val separator = value.lastIndexOf('|')
        if (separator <= 0) return null
        val component = ComponentName.unflattenFromString(value.substring(0, separator))
            ?: return null
        val certificate = value.substring(separator + 1)
        return discoverVoiceActivities().firstOrNull {
            it.component == component && it.signingCertificateSha256 == certificate
        }?.component
    }

    fun setActivityComponent(component: ComponentName?) {
        preferences.edit(commit = true) {
            val certificate = discoverVoiceActivities()
                .firstOrNull { it.component == component }
                ?.signingCertificateSha256
            if (component == null || certificate == null) remove(KEY_ACTIVITY)
            else putString(KEY_ACTIVITY, "${component.flattenToString()}|$certificate")
        }
    }

    fun webhook(): WebhookConfiguration? {
        if (!preferences.getBoolean(KEY_WEBHOOK_ENABLED, false)) return null
        val url = preferences.getString(KEY_WEBHOOK_URL, null)?.trim().orEmpty()
        if (url.isEmpty()) return null
        return WebhookConfiguration(url, preferences.getString(KEY_WEBHOOK_SECRET, "").orEmpty())
    }

    fun webhookDraft(): WebhookConfiguration = WebhookConfiguration(
        preferences.getString(KEY_WEBHOOK_URL, "").orEmpty(),
        preferences.getString(KEY_WEBHOOK_SECRET, "").orEmpty(),
    )

    fun webhookEnabled(): Boolean = preferences.getBoolean(KEY_WEBHOOK_ENABLED, false)

    fun saveWebhook(url: String, secret: String, enabled: Boolean) {
        preferences.edit(commit = true) {
            putString(KEY_WEBHOOK_URL, url.trim())
            putString(KEY_WEBHOOK_SECRET, secret)
            putBoolean(KEY_WEBHOOK_ENABLED, enabled && url.isNotBlank())
        }
    }

    data class WebhookConfiguration(val url: String, val secret: String)

    private fun signingIdentity(packageName: String): String? {
        val info = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        }.getOrNull() ?: return null
        return info.signingInfo?.apkContentsSigners
            ?.map { signer ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signer.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }
            ?.sorted()
            ?.takeIf(List<String>::isNotEmpty)
            ?.joinToString("+")
    }

    private fun approval(integration: ConsumerIntegration) = buildString {
        append(componentPrefix(integration.component))
        append(integration.signingCertificateSha256)
        append("|v=").append(integration.apiVersion)
        append("|events=")
        if (integration.candidateEvents) append('c')
        if (integration.confirmedEvents) append('w')
        append("|audio=").append(if (integration.audioPrefix) 'p' else 'n')
        append("|role=").append(if (integration.handler) 'h' else 'o')
        append("|latency=").append(if (integration.lowLatency) 'l' else 'n')
    }

    private fun componentPrefix(component: ComponentName) =
        "${component.flattenToString()}|"

    private fun removeApproval(values: MutableSet<String>, component: ComponentName) {
        val prefix = componentPrefix(component)
        values.removeAll { it.startsWith(prefix) || it == component.flattenToString() }
    }

    private fun scanVoiceActivities(): List<VoiceActivity> {
        val certificates = HashMap<String, String?>()
        return context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VOICE_COMMAND).addCategory(Intent.CATEGORY_DEFAULT),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        ).mapNotNull { resolved ->
            val info = resolved.activityInfo?.takeIf { it.exported } ?: return@mapNotNull null
            val certificate = certificates.getOrPut(info.packageName) {
                signingIdentity(info.packageName)
            } ?: return@mapNotNull null
            VoiceActivity(
                ComponentName(info.packageName, info.name),
                resolved.loadLabel(context.packageManager).toString(),
                certificate,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun hasCompatibleHandoffService(packageName: String, handlerProcessName: String): Boolean {
        val component = ComponentName(
            packageName,
            WakewordContract.MICROPHONE_HANDOFF_SERVICE,
        )
        val info = runCatching {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0),
            )
        }.getOrNull() ?: return false
        return info.exported &&
            info.permission == WakewordContract.PERMISSION_BIND_CONSUMER &&
            info.processName == handlerProcessName &&
            info.foregroundServiceType and
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
    }

    private fun rejected(component: ComponentName, reason: String): ConsumerIntegration? {
        Log.w(TAG, "Ignoring ${component.flattenToShortString()}: $reason")
        return null
    }

    private companion object {
        const val PREFERENCES = "integrations_v2"
        const val KEY_ENABLED_OBSERVERS = "enabled_observers"
        const val KEY_ENABLED_HANDLER = "enabled_handler"
        const val KEY_ACTIVITY = "voice_activity"
        const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_WEBHOOK_SECRET = "webhook_secret"
        const val TAG = "WakewordIntegrations"
        val VALID_EVENTS = setOf(
            WakewordContract.EVENTS_CANDIDATE,
            WakewordContract.EVENTS_CONFIRMED,
        )
        val VALID_AUDIO = setOf(WakewordContract.AUDIO_NONE, WakewordContract.AUDIO_PREFIX)
        val VALID_ROLES = setOf(WakewordContract.ROLE_OBSERVER, WakewordContract.ROLE_HANDLER)
        val VALID_LATENCIES = setOf(
            WakewordContract.LATENCY_NORMAL,
            WakewordContract.LATENCY_LOW,
        )
    }
}
