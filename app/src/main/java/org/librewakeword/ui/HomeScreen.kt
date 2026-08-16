package org.librewakeword.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.librewakeword.HotwordUiState
import org.librewakeword.UiStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HotwordUiState,
    onOpenSettings: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizukuManager: () -> Unit,
    onOpenVoiceInputSettings: () -> Unit,
    onArm: () -> Unit,
    onStop: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LibreWakeWord") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Model & settings")
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
            StatusCard(
                state = state,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onOpenShizukuManager = onOpenShizukuManager,
                onOpenVoiceInputSettings = onOpenVoiceInputSettings,
                onArm = onArm,
                onStop = onStop,
                onOpenSettings = onOpenSettings,
            )
            IntegrationsCard(state = state, onOpenIntegrations = onOpenIntegrations)
            DiagnosticsRow(state)
        }
    }
}

@Composable
private fun StatusCard(
    state: HotwordUiState,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizukuManager: () -> Unit,
    onOpenVoiceInputSettings: () -> Unit,
    onArm: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val containerColor = when (state.status) {
        UiStatus.LISTENING -> MaterialTheme.colorScheme.primaryContainer
        UiStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        UiStatus.ARMING -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainerColor = when (state.status) {
        UiStatus.LISTENING -> MaterialTheme.colorScheme.onPrimaryContainer
        UiStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        UiStatus.ARMING -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = onContainerColor),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusIcon(state.status)
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = statusHeadline(state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(text = state.detail, style = MaterialTheme.typography.bodyMedium)
            if (state.status == UiStatus.LISTENING && state.lastDetectionTime != null) {
                Text(
                    text = "Last detection #${state.lastDetectionCount} at ${state.lastDetectionTime}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(text = "Model: ${state.modelDescription}", style = MaterialTheme.typography.bodySmall)

            when (state.status) {
                UiStatus.NEEDS_SHIZUKU ->
                    Button(onClick = onOpenShizukuManager) { Text("Open Shizuku") }
                UiStatus.NEEDS_SHIZUKU_PERMISSION ->
                    Button(onClick = onRequestShizukuPermission) { Text("Grant Shizuku permission") }
                UiStatus.NEEDS_MODEL ->
                    Button(onClick = onOpenSettings) { Text("Import a model") }
                UiStatus.NEEDS_VIS ->
                    Button(onClick = onOpenVoiceInputSettings) { Text("Set as assistant") }
                UiStatus.READY_TO_ARM, UiStatus.READY_TO_RESTORE_VIS ->
                    Button(onClick = onArm) { Text("Arm") }
                UiStatus.ARMING -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                UiStatus.LISTENING -> {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                    Text(
                        text = "Stopping fully closes the app (an Android limitation of the " +
                            "privileged arming trick) -- reopen and tap Arm to resume.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                UiStatus.STOPPED ->
                    Button(onClick = onArm) { Text("Arm") }
                UiStatus.ERROR ->
                    Button(onClick = onArm) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: UiStatus) {
    when (status) {
        UiStatus.LISTENING -> Icon(Icons.Filled.Mic, contentDescription = null)
        UiStatus.ERROR -> Icon(Icons.Filled.Error, contentDescription = null)
        UiStatus.ARMING -> Icon(Icons.Filled.Sync, contentDescription = null)
        UiStatus.READY_TO_ARM, UiStatus.READY_TO_RESTORE_VIS ->
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
        UiStatus.STOPPED -> Icon(Icons.Filled.MicOff, contentDescription = null)
        else -> Icon(Icons.Filled.Error, contentDescription = null)
    }
}

private fun statusHeadline(state: HotwordUiState): String = when (state.status) {
    UiStatus.NEEDS_SHIZUKU -> "Shizuku not running"
    UiStatus.NEEDS_SHIZUKU_PERMISSION -> "Needs Shizuku permission"
    UiStatus.NEEDS_VIS -> "Not set as voice assistant"
    UiStatus.READY_TO_RESTORE_VIS -> "Ready to restore and arm"
    UiStatus.NEEDS_MODEL -> "No wake word model"
    UiStatus.READY_TO_ARM -> "Ready to arm"
    UiStatus.ARMING -> "Arming…"
    UiStatus.LISTENING -> "Listening for “${state.phrase}”"
    UiStatus.STOPPED -> "Stopped"
    UiStatus.ERROR -> "Something went wrong"
}

@Composable
private fun IntegrationsCard(
    state: HotwordUiState,
    onOpenIntegrations: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Integrations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val enabledConsumers = state.consumerIntegrations.count { it.enabled }
            val enabledActions = (if (state.selectedVoiceActivity != null) 1 else 0) +
                (if (state.webhookEnabled) 1 else 0)
            Text(
                text = when {
                    enabledConsumers + enabledActions == 0 -> "No wake-word consumers or actions enabled."
                    else -> "$enabledConsumers app consumer(s) and $enabledActions action(s) enabled."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenIntegrations) { Text("Manage integrations") }
        }
    }
}

@Composable
private fun DiagnosticsRow(state: HotwordUiState) {
    Text(
        text = "Shizuku: ${if (state.shizukuRunning) "running" else "stopped"} · " +
            "permission: ${if (state.shizukuGranted) "granted" else "not granted"} · " +
            "assistant role: ${if (state.isCurrentVis) "active" else "inactive"} · " +
            "detector: ${if (state.recognitionStarted) "armed" else "idle"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
