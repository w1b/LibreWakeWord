package org.librewakeword.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.librewakeword.HotwordUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: HotwordUiState,
    onBack: () -> Unit,
    onFieldsChanged: (phrase: String, localeTag: String, keyphraseId: String, vendorUuid: String, modelVersion: String) -> Unit,
    onSave: () -> Unit,
    onSetActiveModel: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onImportMicroWakeWord: (List<Uri>) -> Unit,
    onDismissImportMessage: () -> Unit,
    onImportSecondStage: (Uri) -> Unit,
    onRefreshPublishedSecondStage: () -> Unit,
    onDismissSecondStageImportMessage: () -> Unit,
    onEnrollLocalVoiceMatch: () -> Unit,
    onVoiceMatchPermissionDenied: () -> Unit,
    onSetLocalVoiceMatchEnabled: (Boolean) -> Unit,
    onDeleteLocalVoiceMatch: () -> Unit,
) {
    val context = LocalContext.current
    val googleImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val microWakeWordImport =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onImportMicroWakeWord(uris)
        }
    val secondStageImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportSecondStage(uri)
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onEnrollLocalVoiceMatch() else onVoiceMatchPermissionDenied()
    }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Wake word")
            if (state.storedModels.isEmpty()) {
                Note("No wake word model yet. Add one below.")
            }
            state.storedModels.forEach { model ->
                ListItem(
                    headlineContent = { Text(model.phrase) },
                    supportingContent = { Text(model.description) },
                    leadingContent = {
                        RadioButton(selected = model.active, onClick = { onSetActiveModel(model.id) })
                    },
                    modifier = Modifier.clickable { onSetActiveModel(model.id) },
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { microWakeWordImport.launch(arrayOf("*/*")) }) {
                    Text("Add microWakeWord model")
                }
                Text(
                    "Select its .tflite and .json files together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { googleImport.launch(arrayOf("*/*")) }) {
                    Text("Add Google model")
                }
                Message(state.importMessage, onDismissImportMessage)
            }

            if (state.usesSecondStage) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader("Hey Google verification")
                Note("SODA re-checks every DSP detection. ${state.secondStageModelDescription}")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { secondStageImport.launch(arrayOf("*/*")) }) {
                        Text("Import SODA model")
                    }
                    TextButton(onClick = onRefreshPublishedSecondStage) { Text("Download en-US") }
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Message(state.secondStageImportMessage, onDismissSecondStageImportMessage)
                }

                ListItem(
                    headlineContent = { Text("Voice Match") },
                    supportingContent = {
                        Text("Only accept your voice. Trained on this phone. ${state.voiceMatchDescription}")
                    },
                    trailingContent = if (state.voiceMatchHasModel) {
                        {
                            Switch(
                                checked = state.voiceMatchEnabled,
                                onCheckedChange = onSetLocalVoiceMatchEnabled,
                                enabled = !state.voiceMatchEnrolling,
                            )
                        }
                    } else null,
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.voiceMatchEnrolling) {
                        Text(state.voiceMatchPrompt ?: "Listening…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(
                            progress = {
                                if (state.voiceMatchTotal <= 0) 0f
                                else state.voiceMatchCompleted.toFloat() / state.voiceMatchTotal.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Keep this screen open and speak after each prompt.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    onEnrollLocalVoiceMatch()
                                } else {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }) {
                                Text(if (state.voiceMatchHasModel) "Retrain" else "Enroll my voice")
                            }
                            if (state.voiceMatchHasModel) {
                                TextButton(onClick = onDeleteLocalVoiceMatch) { Text("Delete") }
                            }
                        }
                    }
                    state.voiceMatchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Advanced") },
                    supportingContent = { Text("Keyphrase enrollment metadata") },
                    trailingContent = {
                        Icon(
                            if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { advancedOpen = !advancedOpen },
                )
                if (advancedOpen) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Field("Wake phrase", state.phrase) {
                            onFieldsChanged(it, state.localeTag, state.keyphraseId, state.vendorUuid, state.modelVersion)
                        }
                        Field("Locale tag", state.localeTag) {
                            onFieldsChanged(state.phrase, it, state.keyphraseId, state.vendorUuid, state.modelVersion)
                        }
                        Field("Keyphrase id", state.keyphraseId, numeric = true) {
                            onFieldsChanged(state.phrase, state.localeTag, it, state.vendorUuid, state.modelVersion)
                        }
                        Field("Vendor UUID", state.vendorUuid) {
                            onFieldsChanged(state.phrase, state.localeTag, state.keyphraseId, it, state.modelVersion)
                        }
                        Field("Model version", state.modelVersion, numeric = true) {
                            onFieldsChanged(state.phrase, state.localeTag, state.keyphraseId, state.vendorUuid, it)
                        }
                        state.metadataError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = onSave) { Text("Save") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Message(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun Field(label: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
