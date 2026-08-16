package org.librewakeword.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    onImport: (Uri) -> Unit,
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
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val secondStageImportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportSecondStage(uri)
        }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onEnrollLocalVoiceMatch() else onVoiceMatchPermissionDenied()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model & settings") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Wake word model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = state.modelDescription, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import model file")
                    }
                    state.importMessage?.let { message ->
                        Text(text = message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onDismissImportMessage) { Text("Dismiss") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Local Voice Match",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Optional. Trains a text-dependent speaker model entirely on " +
                            "this device. No Google account or voice upload is used. When " +
                            "enabled, SODA must recognize both the wake phrase and your voice.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = state.voiceMatchDescription,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.voiceMatchHasModel) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Require my enrolled voice")
                            Switch(
                                checked = state.voiceMatchEnabled,
                                onCheckedChange = onSetLocalVoiceMatchEnabled,
                                enabled = !state.voiceMatchEnrolling,
                            )
                        }
                    }
                    if (state.voiceMatchEnrolling) {
                        Text(
                            text = state.voiceMatchPrompt ?: "Listening…",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (state.voiceMatchTotal <= 0) 0f
                                else state.voiceMatchCompleted.toFloat() /
                                    state.voiceMatchTotal.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Keep this screen open and speak normally after each prompt.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Button(
                            onClick = {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    onEnrollLocalVoiceMatch()
                                } else {
                                    microphonePermissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO,
                                    )
                                }
                            },
                        ) {
                            Text(if (state.voiceMatchHasModel) "Retrain my voice" else "Enroll my voice")
                        }
                    }
                    if (state.voiceMatchHasModel && !state.voiceMatchEnrolling) {
                        TextButton(onClick = onDeleteLocalVoiceMatch) {
                            Text("Delete local voice model")
                        }
                    }
                    state.voiceMatchMessage?.let { message ->
                        Text(text = message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Second-stage hotword model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Used by the isolated SODA verification stage after a DSP trigger. " +
                                "This is separate from the DSP keyphrase model above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = state.secondStageModelDescription,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { secondStageImportLauncher.launch(arrayOf("*/*")) },
                    ) {
                        Text("Import second-stage model")
                    }
                    Text(
                        text = "For en-US, this refreshes the live x_google resource selected by " +
                                "Velvet’s recovered SODA resource configuration. The download is " +
                                "validated by SODA before it replaces the installed model.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onRefreshPublishedSecondStage) {
                        Text("Refresh published en-US model")
                    }
                    state.secondStageImportMessage?.let { message ->
                        Text(text = message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onDismissSecondStageImportMessage) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Model metadata", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = state.phrase,
                        onValueChange = {
                            onFieldsChanged(it, state.localeTag, state.keyphraseId, state.vendorUuid, state.modelVersion)
                        },
                        label = { Text("Wake phrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.localeTag,
                        onValueChange = {
                            onFieldsChanged(state.phrase, it, state.keyphraseId, state.vendorUuid, state.modelVersion)
                        },
                        label = { Text("Locale tag") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.keyphraseId,
                        onValueChange = {
                            onFieldsChanged(state.phrase, state.localeTag, it, state.vendorUuid, state.modelVersion)
                        },
                        label = { Text("Keyphrase id") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.vendorUuid,
                        onValueChange = {
                            onFieldsChanged(state.phrase, state.localeTag, state.keyphraseId, it, state.modelVersion)
                        },
                        label = { Text("Vendor UUID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.modelVersion,
                        onValueChange = {
                            onFieldsChanged(state.phrase, state.localeTag, state.keyphraseId, state.vendorUuid, it)
                        },
                        label = { Text("Model version") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.metadataError?.let { error ->
                        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onSave) { Text("Save") }
                }
            }
        }
    }
}
