# LibreWakeWord

LibreWakeWord keeps Android's hardware wake-word detector listening and lets you choose what happens when it fires. It occupies the default voice-interaction role so the DSP can stay armed. It is not a conversational assistant: no speech recognition, and no always-on software microphone. It borrows the mic only to verify a DSP hit and to hand capture to an app you approved.

## What it can trigger

- An ordinary voice-command activity, with no LibreWakeWord-specific code in the target app.
- A declared app consumer at either the raw DSP-candidate or verified wake-word stage.
- A voice assistant that starts its own foreground microphone capture after detection.
- A consumer that receives a bounded wake-word audio prefix for tone or other analysis.
- A signed CloudEvents webhook with durable retry.

Only one microphone **handler** can be enabled at a time. Any number of passive **observers** can be enabled alongside it. Every integration is explicitly approved by the user and tied to the consumer app's signing certificate.

## Requirements

- Android 13 or newer.
- Hardware and a vendor sound-trigger HAL capable of enrolling a DSP keyphrase model.
- [Shizuku](https://github.com/thedjchi/Shizuku) to launch the privileged arming operation without root.
- A DSP keyphrase model and matching phrase, locale, keyphrase ID, vendor UUID, and model version.
- A compatible second-stage verifier model and runtime. No wake-word model is bundled.

Models and runtimes are hardware/vendor specific. Only use artifacts that you are entitled to use. The repository currently carries local development copies of the SODA runtime; they must be removed before public distribution. See the [artifact roadmap](docs/artifact-roadmap.md).

## Setup

1. Build and install LibreWakeWord, then open it once.
2. Start Shizuku and approve LibreWakeWord's request.
3. In **Settings**, import the DSP model and its metadata.
4. Import the second-stage model. Voice Match enrollment is optional.
5. Select LibreWakeWord as Android's default assistant. This role is required to access the always-on detector; it does not make LibreWakeWord a conversational assistant.
6. Tap **Arm**.
7. Configure apps, an activity, or a webhook under **Integrations**.

After a reboot, restart Shizuku. LibreWakeWord will re-arm when Shizuku becomes available and its permission remains granted.

## Consumer API

The `client` module is a small Android library containing the versioned AIDL contract, parcelables, access-request contract, and `WakewordConsumerService` base class. Low-latency handlers can keep their control endpoint warm while the SDK pays the process's first `AudioRecord` construction cost ahead of a wake. An SDK-owned handoff service establishes Android's microphone foreground capability, and `MicrophoneHandoff.openAudioCapture()` returns one consumer-owned PCM stream whose optional bounded wake-word prefix is followed by live audio. LibreWakeWord never proxies the live portion. Start with [docs/api.md](docs/api.md). The runnable [`sample-consumer`](sample-consumer) demonstrates every distinct Android integration path and presents received events and playable WAV captures in its own UI.

For local development:

```bash
./gradlew :client:publishReleasePublicationToMavenLocal
```

```kotlin
repositories { mavenLocal() }

dependencies {
    implementation("org.librewakeword:client:0.1.0")
}
```

The artifact is not yet hosted in a public Maven repository.

## Building

The project uses Android SDK 37, Java 17, and the checked-in Gradle wrapper:

```bash
./gradlew :app:assembleDebug :sample-consumer:assembleDebug
```

Debug and release variants intentionally use the existing debug signing key for now. Replace that configuration before distributing a release.

## Project structure

- `app` — detector, verifier, routing core, user interface, and integrations.
- `client` — public consumer SDK and AIDL protocol.
- `sample-consumer` — runnable reference for activity launch, observation, standard handoff variants, and advanced custom capture.
- `stubs` — compile-only declarations for hidden/System API framework classes; never packaged.

See [docs/architecture.md](docs/architecture.md) for the detection path and [docs/security.md](docs/security.md) for trust boundaries and retained data.

## License

LibreWakeWord is licensed under the [GNU Affero General Public License v3.0 or later](LICENSE). This is a strong copyleft license and permits commercial use; distributors and network-service operators must satisfy its corresponding-source requirements.

## Current hardware scope

The production-facing API is hardware independent, but enrolling a DSP model is not. The current arming and verifier implementation has only been exercised on Tensor Pixel hardware and may need device-specific work elsewhere. Contributions should include the device, OS build, model metadata, and an end-to-end test result without redistributing proprietary model data.
