# Architecture

LibreWakeWord separates always-on detection, bounded verification, event delivery, and consumer microphone ownership.

```text
DSP sound-trigger HAL
        │ keyphrase event + lookback capture session
        ▼
LibreWakeWord voice-interaction service
        ├── immediate candidate event
        ├── DSP lookback AudioRecord → SODA verification
        ├── bounded ordinary AudioRecord → live-edge bridge
        └── confirmed event or rejection
        ▼
LibreWakeWord routing coordinator
        ├── app observers
        ├── one microphone handler
        ├── ACTION_VOICE_COMMAND activity
        ├── webhook
        └── local feedback
```

## Arming

Only the current voice-interaction service (VIS) may enroll a keyphrase model and create an `AlwaysOnHotwordDetector`. The system checks this by uid against the `voice_interaction_service` secure setting. It does not check the default-assistant role. LibreWakeWord therefore writes that setting itself and leaves the role alone, so any activity-based assistant can stay the default assistant. The cost is that only one VIS can be active at a time, so an assistant that is itself a VIS can't run alongside LibreWakeWord.

The privileged part runs in three hops:

1. A Shizuku user service, running as the shell uid, writes `voice_interaction_service` and runs `am instrument` on LibreWakeWord's own `HotwordInstrumentation`.
2. Because shell is the instrumentation source, the instrumentation can adopt shell's `MANAGE_VOICE_KEYPHRASES`, `RECORD_AUDIO`, `CAPTURE_AUDIO_HOTWORD` and `START_ACTIVITIES_FROM_BACKGROUND` for LibreWakeWord's uid. The last one isn't needed for detection; it lets LibreWakeWord lend a woken handler activity starts (see below). It enrolls the model, binds the VIS itself, and creates the detector.
3. It holds that identity until the user stops arming, because the sound-trigger middleware rechecks the permissions for as long as recognition runs.

`am instrument` force-stops the package first. That kills the system's own binding to the VIS, and VoiceInteractionManagerService does not rebind it. That is why the instrumentation binds the service and calls `onReady()` itself.

The instrumentation is also what lets LibreWakeWord start activities and consumer microphone foreground services from the background. A process instrumented by shell inherits shell's background-start privileges. The VIS role contributes nothing here, since the system's binding is gone.

Calling the sound-trigger middleware directly would avoid the VIS entirely. It isn't possible: the HAL's SELinux policy only maps model memory created by `system_server` and a few HALs, never by an app or shell. The VIS path is the only route that gets `system_server` to load a keyphrase model.

Shizuku is not part of audio capture or consumer delivery. Shizuku and the `am instrument` process it hosts must stay alive while armed. After a reboot, LibreWakeWord re-arms as soon as Shizuku is running again.

## Verification

The DSP callback emits a candidate event immediately. Unless a candidate-stage handler owns the fast path, LibreWakeWord opens the event's hotword lookback session and feeds bounded 16 kHz mono PCM to the SODA verifier on a dedicated audio-priority worker. A SODA acceptance produces a confirmed event; rejection ends only the confirmed path and never suppresses the already-emitted candidate event.

During verification, a bounded ordinary recorder in the same LibreWakeWord process captures the current microphone edge while the delayed DSP lookback feeds SODA. Android's capture policy permits both same-UID recorders to receive real audio. The bridge runs only after a DSP trigger and stops at handoff or rejection; it has no always-on battery cost and never becomes an ongoing consumer stream.

The bounded wake audio becomes an expiring WAV asset for approved consumers. If SODA produces no terminal result within the three-second verification deadline, that candidate is rejected. The candidate event itself was already delivered immediately and is never delayed or conditionally emitted by this deadline. No ongoing consumer audio travels through LibreWakeWord.

## Candidate takeover

When a candidate handler is selected, the voice-interaction service skips second-stage verification and starts consumer handoff directly from the DSP callback. Candidate observers may still run, but no verifier prefix exists on this path.

## Microphone handoff

Android's sensitive foreground-service eligibility creates two distinct consumer components:

```text
while armed                              at a wake event
───────────                              ───────────────
warm consumer control service            fresh SDK handoff service
  • receives availability                  • started directly by LibreWakeWord
  • warms process resources                 • enters microphone FGS immediately
  • never activates the microphone          • invokes the consumer's handoff callback
                                             • keeps the control service bound
                                             • lives until MicrophoneHandoff.close()
```

While bound to the consumer's control service, LibreWakeWord starts the explicit SDK handoff service in the same process. It passes the event, a delivery callback and the control service's class in the start intent. The handoff service enters the microphone foreground-service state, finds the running control service, and calls it with the event and a scoped lease. It also binds the control service itself until the handoff closes, so LibreWakeWord unbinding a normal-latency handler can't destroy the code that is recording.

For 10 seconds after the handoff starts, LibreWakeWord also holds a `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` binding to the handler. That's how the system lets the active voice-interaction service show its UI, and it lets an ordinary `startActivity()` in the handler open the app's UI from the background. The handoff service has to be a separate component that LibreWakeWord starts. Android decides microphone eligibility for a background-started foreground service from whoever started it, and only LibreWakeWord holds the background-start privilege. A long-lived service promoting itself would be judged against the consumer's own background state.

For the standard API, LibreWakeWord first joins the delayed DSP lookback to its bounded ordinary bridge using their boot-time `AudioTimestamp` mappings. It then starts the consumer handoff while the bridge remains active. The SDK constructs and starts an ordinary consumer-process `AudioRecord`; once that recorder is active, the SDK claims the handoff and LibreWakeWord closes the bridge. The returned `InputStream` emits the finalized prefix and then the consumer recorder's first fully unsilenced frames in microphone-ownership order. Independent recorder clocks are not compared across that final transition, and waveform content is never inspected to choose either boundary.

### Known DSP live-edge defect

The current implementation is not sample-perfect when the DSP capture session reaches its live edge. In the final Pixel validation run, the composed prefix retained 186 zero samples (11.63 ms) from the DSP capture before the ordinary bridge, and the timestamp-based DSP-to-bridge join then omitted 59 samples (3.69 ms). The subsequent bridge-to-consumer transition omitted approximately 28–29 ms, for roughly 43–44 ms of total missing content. The consumer handoff remains within the project's current latency target, but the DSP zero span and DSP-to-bridge gap are a known accepted defect rather than intended behavior.

The live segment remains in the consumer process and never travels through LibreWakeWord. Advanced consumers may instead claim with their own `AudioRecord` and obtain the bounded prefix separately.

## Delivery core

Consumer services are discovered from explicit, versioned manifest metadata and must use LibreWakeWord's signature-level binding permission. Approval records the exact component, signing certificate, and capability declaration. One handler and any number of observers may be enabled.

Observer events are written to a bounded SQLite queue before delivery. Consumer acknowledgement removes the pending delivery; unacknowledged observer event IDs retry and survive process recreation. Microphone handoffs are deliberately live and non-durable, so they cannot replay after a restart. Bindings, callbacks, microphone starts, and webhook work stay off the detector thread.

Prefix URI grants target only the approved consumer package and expire with the bounded asset. Webhook delivery is independently durable through WorkManager. Activity integration starts an explicit component.
