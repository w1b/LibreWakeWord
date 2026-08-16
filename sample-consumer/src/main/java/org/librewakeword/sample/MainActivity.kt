package org.librewakeword.sample

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.librewakeword.client.RequestWakewordAccess
import org.librewakeword.client.WakewordAvailability
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var entries by mutableStateOf<List<SampleEntry>>(emptyList())
    private var availability by mutableStateOf(SampleAvailability())
    private var enabledComponents by mutableStateOf<Set<String>>(emptySet())
    private var resultMessage by mutableStateOf<String?>(null)
    private var playingRecording by mutableStateOf<String?>(null)
    private var player: MediaPlayer? = null
    private var receiverRegistered = false
    private var pendingHandler: Integration? = null
    private var requestedIntegration: Integration? = null

    private val historyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }
    private val requestWakewordAccess = registerForActivityResult(
        RequestWakewordAccess(),
    ) { approved ->
        val integration = requestedIntegration
        val label = integration?.title ?: "Integration"
        requestedIntegration = null
        if (approved && integration != null) {
            SampleHistory.markEnabled(
                this,
                integration.serviceClass.name,
                handler = integration.requiresMicrophone,
            )
        }
        resultMessage = if (approved) {
            "$label is enabled in LibreWakeWord"
        } else {
            "$label was not enabled"
        }
    }
    private val requestMicrophonePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val integration = pendingHandler.also { pendingHandler = null } ?: return@registerForActivityResult
        if (result[Manifest.permission.RECORD_AUDIO] == true ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            requestAccess(integration)
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for a handler",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVoiceCommand(intent)
        refresh()
        setContent {
            MaterialTheme {
                SampleScreen(
                    entries = entries,
                    availability = availability,
                    enabledComponents = enabledComponents,
                    resultMessage = resultMessage,
                    playingRecording = playingRecording,
                    onRequest = ::prepareAccessRequest,
                    onPlay = ::togglePlayback,
                    onShare = ::shareRecording,
                    onStopCapture = ::stopCapture,
                    onClear = { SampleHistory.clear(this) },
                    onResultMessageShown = { resultMessage = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceCommand(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                historyReceiver,
                IntentFilter(SampleHistory.ACTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        refresh()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(historyReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun handleVoiceCommand(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VOICE_COMMAND) return
        SampleHistory.put(
            this,
            SampleEntry(
                id = "activity-${UUID.randomUUID()}",
                occurredAtEpochMillis = System.currentTimeMillis(),
                title = "Voice-command activity launched",
                detail = "No SDK callback was required for this integration.",
            ),
        )
    }

    private fun refresh() {
        entries = SampleHistory.load(this)
        availability = SampleHistory.availability(this)
        enabledComponents = SampleHistory.accessSelections(this).enabledComponents()
        if (enabledComponents.isEmpty()) {
            enabledComponents = inferSelectionsFromHistory(entries)
        }
    }

    private fun prepareAccessRequest(integration: Integration) {
        if (!integration.requiresMicrophone) {
            requestAccess(integration)
            return
        }
        val missingPermissions = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingPermissions.isEmpty()) {
            requestAccess(integration)
            return
        }
        pendingHandler = integration
        requestMicrophonePermissions.launch(missingPermissions.toTypedArray())
    }

    private fun requestAccess(integration: Integration) {
        requestedIntegration = integration
        requestWakewordAccess.launch(ComponentName(this, integration.serviceClass))
    }

    private fun togglePlayback(entry: SampleEntry) {
        if (playingRecording == entry.recordingName) {
            stopPlayback()
            return
        }
        val name = entry.recordingName ?: return
        val recording = SampleHistory.recording(this, name) ?: return
        stopPlayback()
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                player = mediaPlayer
                mediaPlayer.setDataSource(recording.absolutePath)
                mediaPlayer.setOnPreparedListener {
                    playingRecording = name
                    it.start()
                }
                mediaPlayer.setOnCompletionListener { stopPlayback() }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    Toast.makeText(this, "Could not play recording", Toast.LENGTH_SHORT).show()
                    true
                }
                mediaPlayer.prepareAsync()
            }
        }.onFailure {
            stopPlayback()
            Toast.makeText(this, "Could not play recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playingRecording = null
    }

    private fun shareRecording(entry: SampleEntry) {
        val recording = entry.recordingName?.let { SampleHistory.recording(this, it) } ?: return
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.recordings",
            recording,
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share captured audio",
            ),
        )
    }

    private fun stopCapture(entry: SampleEntry) {
        SampleHistory.update(this, entry.id) {
            it.copy(detail = "Stopping capture…")
        }
        Thread(
            {
                if (!SampleCaptureController.stop(entry.id)) {
                    SampleHistory.update(this, entry.id) {
                        it.copy(detail = "Capture is no longer active.", active = false)
                    }
                }
            },
            "sample-capture-stop",
        ).start()
    }

    private fun inferSelectionsFromHistory(history: List<SampleEntry>): Set<String> {
        val approvedTitles = history
            .filter { it.title == "Access approved" }
            .map(SampleEntry::detail)
        val observer = integrations.firstOrNull {
            !it.requiresMicrophone && it.title in approvedTitles
        }
        val handler = approvedTitles.firstNotNullOfOrNull { approvedTitle ->
            integrations.firstOrNull {
                it.requiresMicrophone && it.title == approvedTitle
            }
        }
        return listOfNotNull(observer, handler).mapTo(mutableSetOf()) { it.serviceClass.name }
    }
}

private data class Integration(
    val title: String,
    val description: String,
    val serviceClass: Class<*>,
    val requiresMicrophone: Boolean,
)

private val integrations = listOf(
    Integration(
        title = "Candidate + confirmed observer",
        description = "Receives immediate DSP candidates, later audio attachments, and SODA-confirmed events.",
        serviceClass = SampleObserverService::class.java,
        requiresMicrophone = false,
    ),
    Integration(
        title = "Confirmed: wake word + live",
        description = "Returns one stream containing the bounded wake-word prefix followed by consumer-owned live audio.",
        serviceClass = SampleAssistantService::class.java,
        requiresMicrophone = true,
    ),
    Integration(
        title = "Confirmed: live only",
        description = "Starts the consumer microphone after confirmation and intentionally omits the wake-word prefix.",
        serviceClass = SampleLiveOnlyHandlerService::class.java,
        requiresMicrophone = true,
    ),
    Integration(
        title = "Raw DSP handler",
        description = "Takes the consumer microphone at the DSP boundary and bypasses SODA for minimum latency.",
        serviceClass = SampleCandidateHandlerService::class.java,
        requiresMicrophone = true,
    ),
    Integration(
        title = "Advanced custom recorder",
        description = "Uses a custom AudioRecord, manual claim(), and awaitWakewordPrefix() instead of the standard stream.",
        serviceClass = SampleCustomRecorderService::class.java,
        requiresMicrophone = true,
    ),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SampleScreen(
    entries: List<SampleEntry>,
    availability: SampleAvailability,
    enabledComponents: Set<String>,
    resultMessage: String?,
    playingRecording: String?,
    onRequest: (Integration) -> Unit,
    onPlay: (SampleEntry) -> Unit,
    onShare: (SampleEntry) -> Unit,
    onStopCapture: (SampleEntry) -> Unit,
    onClear: () -> Unit,
    onResultMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedPage by rememberSaveable {
        mutableIntStateOf(if (entries.any { !it.title.startsWith("Access ") }) 0 else 1)
    }
    LaunchedEffect(resultMessage) {
        resultMessage?.let {
            snackbarHostState.showSnackbar(it)
            onResultMessageShown()
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("LibreWakeWord API sample") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedPage == 0) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Activity") }
                    OutlinedButton(
                        onClick = { selectedPage = 1 },
                        modifier = Modifier.weight(1f),
                    ) { Text("Integrations") }
                } else {
                    OutlinedButton(
                        onClick = { selectedPage = 0 },
                        modifier = Modifier.weight(1f),
                    ) { Text("Activity") }
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Integrations") }
                }
            }
            if (selectedPage == 0) {
                ActivityPage(
                    modifier = Modifier.weight(1f),
                    entries = entries
                        .filterNot { it.title.startsWith("Access ") }
                        .sortedWith(
                            compareByDescending<SampleEntry> { it.occurredAtEpochMillis }
                                .thenBy(::activityPriority),
                        ),
                    availability = availability,
                    playingRecording = playingRecording,
                    onPlay = onPlay,
                    onShare = onShare,
                    onStopCapture = onStopCapture,
                    onClear = onClear,
                )
            } else {
                IntegrationsPage(
                    modifier = Modifier.weight(1f),
                    enabledComponents = enabledComponents,
                    onRequest = onRequest,
                )
            }
        }
    }
}

@Composable
private fun ActivityPage(
    modifier: Modifier,
    entries: List<SampleEntry>,
    availability: SampleAvailability,
    playingRecording: String?,
    onPlay: (SampleEntry) -> Unit,
    onShare: (SampleEntry) -> Unit,
    onStopCapture: (SampleEntry) -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Detector state", fontWeight = FontWeight.Bold)
                    Text(
                        when (availability.state) {
                            WakewordAvailability.STATE_ARMED -> "Armed${availability.phrase?.let { " for “$it”" } ?: ""}"
                            else -> "Inactive or no low-latency handler connected"
                        },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Recent activity",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = onClear,
                    enabled = entries.isNotEmpty() && entries.none(SampleEntry::active),
                ) {
                    Text("Clear")
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "No events or captures yet. Enable an integration, then trigger the wake word.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(entries, key = SampleEntry::id) { entry ->
                HistoryCard(
                    entry = entry,
                    isPlaying = playingRecording == entry.recordingName,
                    onPlay = onPlay,
                    onShare = onShare,
                    onStopCapture = onStopCapture,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun IntegrationsPage(
    modifier: Modifier,
    enabledComponents: Set<String>,
    onRequest: (Integration) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Approving an integration here enables it in LibreWakeWord. The observer can run beside one microphone handler.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        items(integrations, key = { it.serviceClass.name }) { integration ->
            IntegrationCard(
                integration = integration,
                enabled = integration.serviceClass.name in enabledComponents,
                onRequest = onRequest,
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "No-SDK activity integration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "This activity exposes ACTION_VOICE_COMMAND. Selecting it in LibreWakeWord launches this screen and adds a visible activity entry.",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Webhooks are configured in LibreWakeWord; an Android consumer service is not involved.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun IntegrationCard(
    integration: Integration,
    enabled: Boolean,
    onRequest: (Integration) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                integration.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(integration.description)
            Spacer(Modifier.height(10.dp))
            if (enabled) {
                Text(
                    "Enabled",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onRequest(integration) }) {
                    Text("Enable again")
                }
            } else {
                Button(onClick = { onRequest(integration) }) {
                    Text("Enable")
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: SampleEntry,
    isPlaying: Boolean,
    onPlay: (SampleEntry) -> Unit,
    onShare: (SampleEntry) -> Unit,
    onStopCapture: (SampleEntry) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                entry.displayTitle(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(Date(entry.occurredAtEpochMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(entry.detail)
            if (entry.active) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onStopCapture(entry) }) {
                    Text("Stop")
                }
            } else if (entry.recordingName != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(entry) }) {
                        Text(if (isPlaying) "Stop" else "Play")
                    }
                    OutlinedButton(onClick = { onShare(entry) }) {
                        Text("Share WAV")
                    }
                }
            }
        }
    }
}

private fun activityPriority(entry: SampleEntry): Int = when {
    entry.title.startsWith("Continuous capture") || entry.title.contains("handler:") -> 0
    entry.recordingName != null -> 1
    else -> 2
}

private fun SampleEntry.displayTitle(): String = when {
    title == "Confirmed wake word" -> "Observer attachment: confirmed wake word"
    title == "Raw DSP candidate" && recordingName != null -> "Observer attachment: DSP candidate audio"
    title == "Raw DSP candidate" -> "Observer event: raw DSP candidate"
    title == "Confirmed handler: wake word + live audio" -> "Continuous capture: wake word + live mic"
    else -> title
}
