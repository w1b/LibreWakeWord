package org.librewakeword

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import org.librewakeword.integration.ActivityAction

/** UI-facing mirror of [WakewordState.Code], kept separate so the ui/ package (a different
 * Java package) never needs access to the package-private WakewordState class. */
enum class UiStatus {
    NEEDS_SHIZUKU, NEEDS_SHIZUKU_PERMISSION, NEEDS_VIS, READY_TO_RESTORE_VIS,
    NEEDS_MODEL, READY_TO_ARM, ARMING, LISTENING, STOPPED, ERROR,
}

data class ConsumerIntegrationUi(
    val component: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
    val handler: Boolean,
)

data class VoiceActivityUi(val component: String, val label: String)

data class HotwordUiState(
    val status: UiStatus = UiStatus.NEEDS_SHIZUKU,
    val detail: String = "",
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val isCurrentVis: Boolean = false,
    val recognitionStarted: Boolean = false,
    val modelDescription: String = "No model imported",
    val secondStageModelDescription: String = "No second-stage model imported",
    val consumerIntegrations: List<ConsumerIntegrationUi> = emptyList(),
    val voiceActivities: List<VoiceActivityUi> = emptyList(),
    val selectedVoiceActivity: String? = null,
    val webhookUrl: String = "",
    val webhookSecret: String = "",
    val webhookEnabled: Boolean = false,
    val integrationMessage: String? = null,
    val phrase: String = ModelConfig.DEFAULT_PHRASE,
    val localeTag: String = ModelConfig.DEFAULT_LOCALE,
    val keyphraseId: String = ModelConfig.DEFAULT_KEYPHRASE_ID.toString(),
    val vendorUuid: String = ModelConfig.DEFAULT_VENDOR_UUID,
    val modelVersion: String = ModelConfig.DEFAULT_MODEL_VERSION.toString(),
    val lastDetectionCount: Int = 0,
    val lastDetectionTime: String? = null,
    val metadataError: String? = null,
    val importMessage: String? = null,
    val secondStageImportMessage: String? = null,
    val voiceMatchDescription: String = "Not enrolled",
    val voiceMatchHasModel: Boolean = false,
    val voiceMatchEnabled: Boolean = false,
    val voiceMatchEnrolling: Boolean = false,
    val voiceMatchCompleted: Int = 0,
    val voiceMatchTotal: Int = LocalVoiceMatchTrainer.UTTERANCE_COUNT,
    val voiceMatchPrompt: String? = null,
    val voiceMatchMessage: String? = null,
)

class HotwordViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HotwordUiState())
    val uiState: StateFlow<HotwordUiState> = _uiState.asStateFlow()

    private val detectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val count = intent.getIntExtra(DetectionNotifier.EXTRA_COUNT, -1)
            val time = intent.getStringExtra(DetectionNotifier.EXTRA_TIME)
            _uiState.update { it.copy(lastDetectionCount = count, lastDetectionTime = time) }
            refresh()
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuAutoArm.armNow()
            }
            refresh()
        }

    init {
        val app = getApplication<Application>()
        app.registerReceiver(
            detectedReceiver,
            IntentFilter(DetectionNotifier.ACTION_DETECTED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        Shizuku.addRequestPermissionResultListener(permissionListener)
        loadModelFields()
        loadIntegrationFields()
        refresh()
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestShizukuPermission()
        }
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(detectedReceiver)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun loadModelFields() {
        val cfg = ModelConfig.load(getApplication())
        _uiState.update {
            it.copy(
                phrase = cfg.phrase,
                localeTag = cfg.localeTag,
                keyphraseId = cfg.keyphraseId.toString(),
                vendorUuid = cfg.vendorUuid.toString(),
                modelVersion = cfg.modelVersion.toString(),
            )
        }
    }

    private fun loadIntegrationFields() {
        val app = getApplication<HotwordApplication>()
        val store = app.detectionCoordinator.integrationStore
        val webhook = store.webhookDraft()
        _uiState.update {
            it.copy(
                webhookUrl = webhook.url,
                webhookSecret = webhook.secret,
                webhookEnabled = store.webhookEnabled(),
            )
        }
    }

    fun refresh() {
        val ctx = getApplication<Application>()
        val state = WakewordState.evaluate(ctx)
        val shizukuUp = Shizuku.pingBinder()
        val shizukuGranted =
            shizukuUp && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val app = getApplication<HotwordApplication>()
        val integrationStore = app.detectionCoordinator.integrationStore
        val consumers = integrationStore.discoverConsumers().map { consumer ->
            ConsumerIntegrationUi(
                component = consumer.component.flattenToString(),
                label = consumer.label,
                description = buildString {
                    append(if (consumer.handler) "Handler" else "Observer")
                    append(" · ")
                    append(
                        when {
                            consumer.candidateEvents && consumer.confirmedEvents -> "DSP candidates + confirmed"
                            consumer.candidateEvents -> "DSP candidates"
                            else -> "Confirmed wake words"
                        },
                    )
                    if (consumer.audioPrefix) append(" · audio prefix")
                    if (consumer.lowLatency) append(" · kept ready")
                },
                enabled = consumer.enabled,
                handler = consumer.handler,
            )
        }
        val voiceActivities = ActivityAction(ctx, integrationStore).discover().map {
            VoiceActivityUi(it.component.flattenToString(), it.label)
        }
        _uiState.update {
            it.copy(
                status = UiStatus.valueOf(state.code.name),
                detail = state.detail,
                shizukuRunning = shizukuUp,
                shizukuGranted = shizukuGranted,
                isCurrentVis = WakewordState.isCurrentVis(ctx),
                recognitionStarted = WakewordVoiceInteractionService.isRecognitionStarted(),
                modelDescription = state.modelConfig.describeModel(),
                secondStageModelDescription = SecondStageModel.describe(ctx),
                voiceMatchDescription = VoiceMatchModel.describe(ctx),
                voiceMatchHasModel = VoiceMatchModel.hasModel(ctx),
                voiceMatchEnabled = VoiceMatchModel.isEnabled(ctx),
                consumerIntegrations = consumers,
                voiceActivities = voiceActivities,
                selectedVoiceActivity = integrationStore.activityComponent()?.flattenToString(),
            )
        }
    }

    fun requestShizukuPermission() {
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    fun openShizukuManager() {
        val ctx = getApplication<Application>()
        val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    fun openVoiceInputSettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    fun armNow() {
        WakewordState.clearError()
        ShizukuAutoArm.armNow()
        refresh()
    }

    fun stopArming() {
        val ctx = getApplication<Application>()
        // Persisted: the broadcast below makes the privileged instrumentation finish, which
        // kills this whole process as a side effect (see HotwordInstrumentation). Without this
        // flag surviving that death, ShizukuAutoArm would just re-arm on the next app launch.
        WakewordState.setUserStopped(ctx, true)
        Log.i("HotwordViewModel", "Stop tapped; sending ${HotwordInstrumentation.ACTION_STOP_ARMING}")
        val intent = Intent(HotwordInstrumentation.ACTION_STOP_ARMING)
        intent.setPackage(ctx.packageName)
        ctx.sendBroadcast(intent)
    }

    fun setConsumerEnabled(component: String, enabled: Boolean) {
        val app = getApplication<HotwordApplication>()
        val parsed = ComponentName.unflattenFromString(component) ?: return
        app.detectionCoordinator.integrationStore.setConsumerEnabled(parsed, enabled)
        app.detectionCoordinator.refreshIntegrations()
        refresh()
    }

    fun setVoiceActivity(component: String?) {
        val store = getApplication<HotwordApplication>().detectionCoordinator.integrationStore
        store.setActivityComponent(component?.let(ComponentName::unflattenFromString))
        refresh()
    }

    fun updateWebhook(url: String, secret: String, enabled: Boolean) {
        _uiState.update {
            it.copy(webhookUrl = url, webhookSecret = secret, webhookEnabled = enabled)
        }
    }

    fun saveWebhook() {
        val state = _uiState.value
        val uri = runCatching { java.net.URI(state.webhookUrl.trim()) }.getOrNull()
        val invalidUrl = uri == null ||
            uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrBlank()
        if (state.webhookEnabled && invalidUrl) {
            _uiState.update {
                it.copy(integrationMessage = "Webhook URL must be an absolute http or https URL.")
            }
            return
        }
        getApplication<HotwordApplication>().detectionCoordinator.integrationStore.saveWebhook(
            state.webhookUrl,
            state.webhookSecret,
            state.webhookEnabled,
        )
        _uiState.update { it.copy(integrationMessage = "Webhook settings saved.") }
        refresh()
    }

    fun updateMetadataFields(
        phrase: String,
        localeTag: String,
        keyphraseId: String,
        vendorUuid: String,
        modelVersion: String,
    ) {
        _uiState.update {
            it.copy(
                phrase = phrase,
                localeTag = localeTag,
                keyphraseId = keyphraseId,
                vendorUuid = vendorUuid,
                modelVersion = modelVersion,
            )
        }
    }

    fun saveMetadata() {
        val s = _uiState.value
        try {
            val keyphraseId = s.keyphraseId.trim().toInt()
            require(keyphraseId in 0..63) {
                "Keyphrase id must be between 0 and 63 for Android HDS results"
            }
            val modelVersion = s.modelVersion.trim().toInt()
            ModelConfig.saveMetadata(
                getApplication(), s.phrase, s.localeTag, keyphraseId, s.vendorUuid, modelVersion,
            )
            _uiState.update { it.copy(metadataError = null) }
            refresh()
        } catch (t: Throwable) {
            _uiState.update { it.copy(metadataError = "Invalid metadata: ${t.message}") }
        }
    }

    fun importModel(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(importMessage = "Importing DSP model…") }
            try {
                val config = withContext(Dispatchers.IO) {
                    ModelConfig.importModel(context, uri)
                }
                _uiState.update {
                    it.copy(importMessage = "Imported model: ${config.modelFile.length()} bytes")
                }
                refresh()
            } catch (t: Throwable) {
                _uiState.update { it.copy(importMessage = "Import failed: ${t.message}") }
            }
        }
    }

    fun clearImportMessage() {
        _uiState.update { it.copy(importMessage = null) }
    }

    fun importSecondStageModel(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update {
                it.copy(secondStageImportMessage = "Validating second-stage model…")
            }
            try {
                withContext(Dispatchers.IO) {
                    SecondStageModel.importUri(context, uri)
                }
                WakewordVoiceInteractionService.onSecondStageModelChanged()
                _uiState.update {
                    it.copy(
                        secondStageImportMessage = "Imported second-stage model: " +
                            SecondStageModel.describe(context),
                    )
                }
                refresh()
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(secondStageImportMessage = "Second-stage import failed: ${t.message}")
                }
            }
        }
    }

    fun refreshPublishedSecondStageModel() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(secondStageImportMessage = "Fetching published en-US SODA model…") }
            try {
                val result = withContext(Dispatchers.IO) {
                    SecondStageModel.refreshPublishedEnglishModel(ctx)
                }
                WakewordVoiceInteractionService.onSecondStageModelChanged()
                _uiState.update {
                    it.copy(secondStageImportMessage = if (result.changed) {
                        "Installed published SODA model: ${result.bytes} bytes, sha256=${result.sha256}"
                    } else {
                        "Published SODA model is already installed: sha256=${result.sha256}"
                    })
                }
                refresh()
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(secondStageImportMessage = "Published model refresh failed: ${t.message}")
                }
            }
        }
    }

    fun clearSecondStageImportMessage() {
        _uiState.update { it.copy(secondStageImportMessage = null) }
    }

    fun startLocalVoiceMatchEnrollment() {
        if (_uiState.value.voiceMatchEnrolling) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            var detectorWasRunning = false
            _uiState.update {
                it.copy(
                    voiceMatchEnrolling = true,
                    voiceMatchCompleted = 0,
                    voiceMatchPrompt = "Preparing microphone…",
                    voiceMatchMessage = null,
                )
            }
            try {
                detectorWasRunning = WakewordVoiceInteractionService
                    .pauseForLocalVoiceMatchEnrollment()
                val model = LocalVoiceMatchTrainer.enroll(
                    ctx,
                    object : LocalVoiceMatchTrainer.Progress {
                        override fun onListening(index: Int, total: Int, phrase: String, attempt: Int) {
                            _uiState.update {
                                it.copy(
                                    voiceMatchCompleted = index,
                                    voiceMatchTotal = total,
                                    voiceMatchPrompt = if (attempt == 1) {
                                        "Say “$phrase”"
                                    } else {
                                        "Try again: say “$phrase”"
                                    },
                                )
                            }
                        }

                        override fun onAccepted(completed: Int, total: Int, adaptationScore: Float) {
                            _uiState.update {
                                it.copy(
                                    voiceMatchCompleted = completed,
                                    voiceMatchTotal = total,
                                    voiceMatchPrompt = "Phrase $completed of $total accepted",
                                )
                            }
                        }
                    },
                )
                VoiceMatchModel.install(ctx, model)
                _uiState.update {
                    it.copy(
                        voiceMatchCompleted = LocalVoiceMatchTrainer.UTTERANCE_COUNT,
                        voiceMatchPrompt = null,
                        voiceMatchMessage = "Local Voice Match enrolled and enabled.",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        voiceMatchPrompt = null,
                        voiceMatchMessage = "Enrollment failed: ${t.message}",
                    )
                }
            } finally {
                _uiState.update { it.copy(voiceMatchEnrolling = false) }
                WakewordVoiceInteractionService.resumeAfterLocalVoiceMatchEnrollment(
                    detectorWasRunning,
                )
                refresh()
            }
        }
    }

    fun setLocalVoiceMatchEnabled(enabled: Boolean) {
        try {
            VoiceMatchModel.setEnabled(getApplication(), enabled)
            WakewordVoiceInteractionService.onSecondStageModelChanged()
            _uiState.update { it.copy(voiceMatchMessage = null) }
            refresh()
        } catch (t: Throwable) {
            _uiState.update { it.copy(voiceMatchMessage = "Could not change Voice Match: ${t.message}") }
        }
    }

    fun deleteLocalVoiceMatch() {
        try {
            VoiceMatchModel.delete(getApplication())
            WakewordVoiceInteractionService.onSecondStageModelChanged()
            _uiState.update { it.copy(voiceMatchMessage = "Local voice model deleted.") }
            refresh()
        } catch (t: Throwable) {
            _uiState.update { it.copy(voiceMatchMessage = "Delete failed: ${t.message}") }
        }
    }

    fun localVoiceMatchPermissionDenied() {
        _uiState.update {
            it.copy(voiceMatchMessage = "Microphone permission is required for Local Voice Match.")
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1
    }
}
