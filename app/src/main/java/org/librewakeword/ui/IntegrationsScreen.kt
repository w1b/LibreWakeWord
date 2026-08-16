package org.librewakeword.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.librewakeword.HotwordUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    state: HotwordUiState,
    onBack: () -> Unit,
    onSetConsumerEnabled: (String, Boolean) -> Unit,
    onSetVoiceActivity: (String?) -> Unit,
    onWebhookChanged: (String, String, Boolean) -> Unit,
    onSaveWebhook: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Integrations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle("App consumers")
            Text(
                "Apps declare the events and audio they need. Enabling a handler replaces any other enabled handler; observers can run together.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.consumerIntegrations.isEmpty()) {
                Text("No compatible consumer services are installed.")
            }
            state.consumerIntegrations.forEach { integration ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(integration.label, fontWeight = FontWeight.SemiBold)
                            Text(integration.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = integration.enabled,
                            onCheckedChange = { onSetConsumerEnabled(integration.component, it) },
                        )
                    }
                }
            }

            SectionTitle("Open an app")
            RadioChoice(
                label = "Do not open an activity",
                selected = state.selectedVoiceActivity == null,
                onClick = { onSetVoiceActivity(null) },
            )
            state.voiceActivities.forEach { activity ->
                RadioChoice(
                    label = activity.label,
                    selected = state.selectedVoiceActivity == activity.component,
                    onClick = { onSetVoiceActivity(activity.component) },
                )
            }

            SectionTitle("Webhook")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send confirmed events")
                    Text("CloudEvents JSON with retry", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.webhookEnabled,
                    onCheckedChange = {
                        onWebhookChanged(state.webhookUrl, state.webhookSecret, it)
                    },
                )
            }
            OutlinedTextField(
                value = state.webhookUrl,
                onValueChange = { onWebhookChanged(it, state.webhookSecret, state.webhookEnabled) },
                label = { Text("Webhook URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.webhookSecret,
                onValueChange = { onWebhookChanged(state.webhookUrl, it, state.webhookEnabled) },
                label = { Text("Signing secret (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSaveWebhook) { Text("Save webhook") }
            state.integrationMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RadioChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
