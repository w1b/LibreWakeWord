# LibreWakeWord API sample

This is a runnable reference consumer, not a Logcat-only test harness. Launch it to request access for a declared integration, then enable that integration in LibreWakeWord. Received events, capture state, errors, and detector availability appear in the app. Audio attachments and continuous microphone captures are saved as standard WAV files with Play and Share controls.

## Included examples

| Component | Demonstrates |
| --- | --- |
| `MainActivity` | Access approval and a no-SDK `ACTION_VOICE_COMMAND` activity target |
| `SampleObserverService` | Immediate raw candidates, SODA-confirmed events, shared `detectionId` correlation, and bounded WAV attachments |
| `SampleAssistantService` | A confirmed handoff with one prefix-plus-live `InputStream` |
| `SampleLiveOnlyHandlerService` | A confirmed handoff containing only consumer-owned live frames |
| `SampleCandidateHandlerService` | Immediate DSP-stage microphone takeover without SODA or a prefix |
| `SampleCustomRecorderService` | The advanced custom-`AudioRecord`, manual-claim, and explicit-prefix APIs |

The observer can run alongside a handler. LibreWakeWord permits only one microphone handler at a time, so selecting a different handler replaces the active handler rather than running both microphones concurrently.

Webhooks are configured directly in LibreWakeWord and do not use an Android consumer service.
