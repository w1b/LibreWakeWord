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

Android restricts keyphrase enrollment and `AlwaysOnHotwordDetector` creation to the active voice-interaction service and privileged callers. LibreWakeWord is selected as the default voice-interaction service. A Shizuku daemon launches the app's instrumentation with hidden-API checks disabled; instrumentation adopts the required shell permissions, enrolls the user-supplied model, creates the detector, and retains that identity while armed.

Shizuku launches the privileged operation. It is not part of audio capture or consumer delivery. The hardware DSP remains armed through Android's sound-trigger stack.

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
  • never activates the microphone          • invokes the prepared consumer callback
                                             • lives until MicrophoneHandoff.close()
```

LibreWakeWord asks the authenticated control binding to mint a short-lived, one-use token and then starts the explicit SDK service in the same consumer process. The new service validates the token, enters the microphone foreground-service state, and calls the consumer with the complete event and a scoped lease. This fresh component preserves the voice-interaction start allowance; promoting a long-lived bound service itself does not reliably retain that allowance on Android 17.

For the standard API, LibreWakeWord first joins the delayed DSP lookback to its bounded ordinary bridge using their boot-time `AudioTimestamp` mappings. It then starts the consumer handoff while the bridge remains active. The SDK constructs and starts an ordinary consumer-process `AudioRecord`; once that recorder is active, the SDK claims the handoff and LibreWakeWord closes the bridge. The returned `InputStream` emits the finalized prefix and then the consumer recorder's first fully unsilenced frames in microphone-ownership order. Independent recorder clocks are not compared across that final transition, and waveform content is never inspected to choose either boundary.

### Known DSP live-edge defect

The current implementation is not sample-perfect when the DSP capture session reaches its live edge. In the final Pixel validation run, the composed prefix retained 186 zero samples (11.63 ms) from the DSP capture before the ordinary bridge, and the timestamp-based DSP-to-bridge join then omitted 59 samples (3.69 ms). The subsequent bridge-to-consumer transition omitted approximately 28–29 ms, for roughly 43–44 ms of total missing content. The consumer handoff remains within the project's current latency target, but the DSP zero span and DSP-to-bridge gap are a known accepted defect rather than intended behavior.

The live segment remains in the consumer process and never travels through LibreWakeWord. Advanced consumers may instead claim with their own `AudioRecord` and obtain the bounded prefix separately.

## Delivery core

Consumer services are discovered from explicit, versioned manifest metadata and must use LibreWakeWord's signature-level binding permission. Approval records the exact component, signing certificate, and capability declaration. One handler and any number of observers may be enabled.

Observer events are written to a bounded SQLite queue before delivery. Consumer acknowledgement removes the pending delivery; unacknowledged observer event IDs retry and survive process recreation. Microphone handoffs are deliberately live and non-durable, so they cannot replay after a restart. Bindings, callbacks, microphone starts, and webhook work stay off the detector thread.

Prefix URI grants target only the approved consumer package and expire with the bounded asset. Webhook delivery is independently durable through WorkManager. Activity integration resolves an explicit component and falls back to a notification when Android disallows background launch.
