# Security and privacy

## User-controlled integrations

Installing an app does not grant it wake-word access. The user must enable a discovered service in LibreWakeWord or approve that app's explicit access request. Requests may name only a service owned by the calling package.

Approvals bind the component name to the package's current signing identity and declared event, audio, role, and latency capabilities. Reinstalling under another signer or expanding a service's capabilities requires fresh approval. Activity selections are also tied to the target app's signing identity. Only one integration with the handler role can be enabled at once.

## Consumer transport

LibreWakeWord binds explicit components protected by its `org.librewakeword.permission.BIND_CONSUMER` signature permission. The SDK also verifies that Binder callbacks come from the UID owning the `org.librewakeword` package. Microphone foreground-service starts require a short-lived, one-use token minted inside the consumer process. LibreWakeWord starts the explicit SDK handoff service directly so Android attributes the sensitive start to the selected voice-interaction provider; the service consumes the token before invoking consumer code. The signature permission, caller check, and one-use capability are independent layers.

Consumers should still treat event contents as input and use `eventId` for idempotency.

Audio is exposed by a non-exported content provider using temporary, package-specific grants for individual URIs. Assets are read-only, expire after approximately five minutes, and are capped by both clip size and asset count. A URI is not a durable capability.

## Detection and verifier boundary

The active detector and SODA verifier run inside LibreWakeWord's voice-interaction process. The DSP lookback and live-edge bridge are bounded, retained audio is size-capped, and verifier work is serialized. The bridge exists only between a DSP trigger and rejection or consumer handoff. Consumer microphone audio begins in the consumer process and is never routed back through LibreWakeWord. SODA and model artifacts are trusted native inputs and should only be imported from sources the user trusts.

## Retained data

- Imported models and optional Voice Match state are app-private.
- Wake-prefix WAV files live in the cache and expire automatically.
- A bounded SQLite journal retains recent event metadata and unacknowledged consumer deliveries.
- Webhook jobs retain their small JSON payload until WorkManager completes or abandons the job.
- Webhooks never include audio.

LibreWakeWord does not retain a continuous microphone recording.

## Webhooks

Webhook destinations are configured by the device user. HTTPS is recommended. A configured secret adds a timestamped HMAC-SHA256 signature so receivers can authenticate the payload and enforce a replay window. Secrets remain in app-private preferences and are never included in the body or logs.

The receiver should compute HMAC-SHA256 over `timestamp + "." + exact_request_body`, compare it in constant time, and reject timestamps outside its chosen replay window.

## Privileged arming

Arming requires LibreWakeWord to be the selected voice-interaction service. Shizuku launches only the self-targeted instrumentation used for enrollment and detector creation. The adopted shell identity is held while armed because Android checks that identity across the detector lifecycle.

The current release remains debug-signed by project choice. A public binary release must use a stable protected release key; changing the LibreWakeWord signer changes the identity consumers may choose to trust.
