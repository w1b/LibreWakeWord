# Android consumer API

LibreWakeWord API v1 is a declaration-driven Android service API. An app declares the events and resources it needs, the user approves that exact declaration, and LibreWakeWord delivers immutable events through the client SDK. The app remains responsible for its own work and, for handlers, its own microphone capture.

## Choose an integration

| Use case | Integration |
| --- | --- |
| Run an existing app | `ACTION_VOICE_COMMAND` activity; no SDK required |
| Trigger remote automation | webhook |
| Observe every DSP trigger | candidate observer |
| Observe verified wake words | confirmed observer |
| Analyze bounded wake-word audio | observer with `AUDIO=prefix` |
| Start a microphone after verification | confirmed handler |
| Take the microphone at the DSP boundary | candidate handler |

Any number of observers may be enabled. Exactly one handler may be enabled because a handler takes microphone ownership away from LibreWakeWord's detection path.

## Add the client library

Until the artifact is hosted publicly, publish it from this checkout:

```bash
./gradlew :client:publishReleasePublicationToMavenLocal
```

```kotlin
repositories { mavenLocal() }

dependencies {
    implementation("org.librewakeword:client:0.1.0")
}
```

The minimum supported Android version is API 33.

## Run the reference consumer

The [`sample-consumer`](../sample-consumer) app makes each materially different Android path selectable and observable without Logcat. Its activity history shows raw candidates, later confirmed events, bounded attachment copies, microphone handoff progress, and failures. Every saved attachment or continuous capture is a standard WAV with in-app Play and Share controls.

| Example component | Path demonstrated |
| --- | --- |
| `MainActivity` | No-SDK `ACTION_VOICE_COMMAND` launch and access requests |
| `SampleObserverService` | Immediate candidates, confirmed events, correlation IDs, and expiring bounded audio |
| `SampleAssistantService` | Confirmed `openAudioCapture(includeWakeword=true)` |
| `SampleLiveOnlyHandlerService` | Confirmed `openAudioCapture(includeWakeword=false)` |
| `SampleCandidateHandlerService` | Raw DSP handoff that bypasses verification |
| `SampleCustomRecorderService` | Custom `AudioRecord`, manual `claim()`, and `awaitWakewordPrefix()` |

Webhooks do not involve an Android consumer component and are configured directly in LibreWakeWord.

## Observe wake words

Declare an exported service protected by LibreWakeWord's signature permission:

```xml
<service
    android:name=".WakewordObserverService"
    android:exported="true"
    android:label="My wake-word integration"
    android:permission="org.librewakeword.permission.BIND_CONSUMER">
    <intent-filter>
        <action android:name="org.librewakeword.action.WAKEWORD_CONSUMER" />
    </intent-filter>

    <meta-data android:name="org.librewakeword.consumer.API_VERSION" android:value="1" />
    <meta-data android:name="org.librewakeword.consumer.EVENTS" android:value="confirmed" />
    <meta-data android:name="org.librewakeword.consumer.AUDIO" android:value="none" />
    <meta-data android:name="org.librewakeword.consumer.ROLE" android:value="observer" />
    <meta-data android:name="org.librewakeword.consumer.LATENCY" android:value="normal" />
</service>
```

Then override one callback:

```kotlin
class WakewordObserverService : WakewordConsumerService() {
    override fun onWakeword(event: WakewordEvent) {
        durableQueue.enqueue(event) // Persist asynchronous work before returning.
    }
}
```

Callbacks run on the main thread. Returning normally acknowledges delivery. Persist or enqueue long-running work before returning; do not block the callback with network or model inference.

## Handle wake words and own the microphone

A handler uses the same control service declaration with `ROLE=handler`. It also declares Android's microphone foreground-service permissions and the SDK's fixed handoff service:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<application ...>
    <service
        android:name=".AssistantWakewordService"
        android:exported="true"
        android:label="My assistant"
        android:permission="org.librewakeword.permission.BIND_CONSUMER">
        <intent-filter>
            <action android:name="org.librewakeword.action.WAKEWORD_CONSUMER" />
        </intent-filter>
        <meta-data android:name="org.librewakeword.consumer.API_VERSION" android:value="1" />
        <meta-data android:name="org.librewakeword.consumer.EVENTS" android:value="confirmed" />
        <meta-data android:name="org.librewakeword.consumer.AUDIO" android:value="prefix" />
        <meta-data android:name="org.librewakeword.consumer.ROLE" android:value="handler" />
        <meta-data android:name="org.librewakeword.consumer.LATENCY" android:value="low" />
    </service>

    <service
        android:name="org.librewakeword.client.MicrophoneHandoffService"
        android:exported="true"
        android:foregroundServiceType="microphone"
        android:permission="org.librewakeword.permission.BIND_CONSUMER"
        android:stopWithTask="false" />
</application>
```

The two services must run in the same process. LibreWakeWord rejects a handler declaration that is missing the handoff service, signature permission, or microphone foreground-service type.

Open the SDK capture synchronously in the handoff callback, then read it on your audio worker:

```kotlin
class AssistantWakewordService : WakewordConsumerService() {
    private val captureExecutor = Executors.newSingleThreadExecutor()

    override fun onMicrophoneHandoff(event: WakewordEvent, handoff: MicrophoneHandoff) {
        try {
            val capture = handoff.openAudioCapture(
                this,
                includeWakeword = true,
            )
            captureExecutor.execute {
                capture.use { captureCommand(it, event) }
            }
        } catch (error: Throwable) {
            handoff.close()
            throw error
        }
    }
}
```

`openAudioCapture` constructs and starts an ordinary `VOICE_RECOGNITION` `AudioRecord` in the consumer process, then claims the handoff while LibreWakeWord finalizes its bounded lookback tail. It returns an `InputStream` of 16 kHz, mono, PCM16 samples. With `includeWakeword=true`, reads return LibreWakeWord's bounded wake prefix first and then the consumer's live microphone frames. With `includeWakeword=false`, prefix samples are omitted and reads begin with the consumer recorder's first frames. A candidate handler has no second-stage prefix, so its live stream begins immediately either way.

LibreWakeWord joins its DSP lookback and short ordinary-recorder bridge before delivery. Once the consumer's recorder is active and claims the handoff, LibreWakeWord closes the bridge. The SDK emits the finalized prefix followed by the consumer recorder's first fully unsilenced frames; prefix loading and joining happen inside the API, so consumers do not open a content URI, decode WAV, or concatenate buffers for this path. Live audio never crosses into or back through the LibreWakeWord process.

Closing the capture also closes its handoff. The foreground notification remains until then; override `createMicrophoneHandoffNotification` to customize it. Close the handoff on every success, failure, cancellation, and shutdown path.

The callback includes the detection event. Prefix finalization is an internal transport operation, not a second app callback: a successful handler invocation is never followed by another `onWakeword` or `onMicrophoneHandoff` call for that event. If the handler does not override `onMicrophoneHandoff`, the base class calls `onWakeword` and releases the handoff without capturing.

Advanced handlers that require another sample format, audio source, or recorder configuration can own the handoff manually:

```kotlin
val recorder = createCustomAudioRecord()
recorder.startRecording()
check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
check(handoff.claim())
```

Construct the recorder first, start it, and call `claim()` immediately after it enters the recording state. LibreWakeWord uses the claim to finalize and release its bounded bridge. Close both objects on every exit path. This manual path supplies no automatic splice. If the handler declares `AUDIO=prefix`, it can call `handoff.awaitWakewordPrefix()` from its audio worker to obtain an immutable in-memory PCM prefix, then join it according to the custom recorder's own buffering and timing behavior.

### Normal and low latency

`LATENCY=normal` binds the control service only when an event must be delivered. It does not retain a service binding or ask the consumer to keep prepared resources, but recorder construction and process startup may be on the wake path. Android may still keep the process cached normally.

`LATENCY=low` keeps the control service bound while detection is armed and lets the SDK warm the process's first `AudioRecord` construction before a wake word. Consumers may also prepare their own speech or model resources from `STATE_ARMED`; they must not activate the microphone. Low latency trades resident process/native-resource memory for lower and more consistent wake response. Release consumer-prepared resources on `STATE_INACTIVE` and again from `Service.onDestroy`.

## Event model and delivery

Manifest metadata has these values:

| Key | Values | Default |
| --- | --- | --- |
| `API_VERSION` | `1` | required |
| `EVENTS` | `candidate`, `confirmed`, `candidate,confirmed` | `confirmed` |
| `AUDIO` | `none`, `prefix` | `none` |
| `ROLE` | `observer`, `handler` | `observer` |
| `LATENCY` | `normal`, `low` | `normal` |

A handler subscribes to exactly one stage. A candidate handler must use `AUDIO=none`, because it takes ownership before verifier audio exists. Unknown API versions and invalid combinations are not offered to the user.

`eventId` identifies one delivery and is its idempotency key. `detectionId` correlates the candidate and confirmed events produced by one physical trigger. `occurredAtElapsedRealtimeNanos` is the monotonic DSP-trigger timestamp; `occurredAtEpochMillis` is a wall-clock convenience.

Observer delivery is at least once. Unacknowledged observer events are persisted across a LibreWakeWord process restart and retried. Consumers must tolerate an `eventId` they have already accepted. For durable side effects, store the ID transactionally with the queued work.

A microphone handoff is a live, time-bounded transition and is never replayed after either process restarts. A handler should still deduplicate `eventId` because a transport retry can occur before the handoff is acknowledged.

A candidate is emitted immediately for every DSP trigger, including candidates later rejected by verification. A candidate handler bypasses verification for the fastest microphone takeover and receives no prefix. Confirmed events are separate and emitted only when verification accepts the candidate.

A candidate observer requesting `AUDIO=prefix` first receives the immediate candidate without audio. If verification capture subsequently becomes available, it receives a second immutable candidate event with a different `eventId`, the same `detectionId`, and `audio` populated. This preserves immediate upper-bound notification without withholding the later bounded capture.

## Read bounded wake-word audio

With `AUDIO=prefix`, `event.audio` is a temporary, read-only WAV asset:

```kotlin
event.audio?.let { audio ->
    contentResolver.openInputStream(audio.uri)?.use { input ->
        analyzeWakeword(input, audio.sampleRateHz, audio.channelCount)
    }
}
```

The descriptor includes MIME type, PCM encoding, sample rate, channel count, frame count, and expiry time. Copy the bytes you need during delivery. The URI expires after approximately five minutes and must not be treated as durable storage.

LibreWakeWord supplies only the bounded audio retained during verification and microphone transfer. It never proxies an ongoing microphone stream. Handlers should use `openAudioCapture` instead of manually joining this asset to live audio.

Android audio delivery leaves a short, device-dependent content gap across the bounded recorder transitions. Deterministic end-to-end development runs on the current Pixel measured roughly 8–50 ms total from DSP lookback through the bridge to consumer-owned capture. This range is a device result, not an Android platform guarantee. Consumers should tolerate a brief boundary gap; LibreWakeWord deliberately does not expose a gap-measurement API.

Known limitation: the current Pixel implementation can include a short zero-filled span while the DSP lookback reaches its live edge. A final-build validation run contained 186 zero samples (11.63 ms) in the DSP portion, followed by a 59-sample (3.69 ms) DSP-to-bridge gap and an approximately 28–29 ms bridge-to-consumer gap. This is an accepted implementation defect, not intentional padding or part of the API contract.

## Ask the user for access

An app can open LibreWakeWord's approval screen for one of its declared components:

```kotlin
private val requestWakewordAccess =
    registerForActivityResult(RequestWakewordAccess()) { approved ->
        updateSettings(approved)
    }

requestWakewordAccess.launch(ComponentName(this, AssistantWakewordService::class.java))
```

Approval is bound to the component, signing identity, and declared events, audio, role, and latency. A signer change or capability expansion requires approval again.

## Activity and webhook integrations

An app exposing a default `ACTION_VOICE_COMMAND` activity requires no SDK integration. The user can select it in LibreWakeWord. If Android blocks a background activity launch, LibreWakeWord posts a user-action notification instead.

Webhooks use CloudEvents 1.0 structured JSON with content type `application/cloudevents+json`. When configured with a secret, requests include:

```text
LWW-Signature: t=<unix-seconds>,v1=<hex HMAC-SHA256 of "timestamp.payload">
```

Transient failures use WorkManager retry. Webhooks never contain wake-word audio.
