# Contributing

## Build and check

Use JDK 17 and Android SDK 37. Keep models, firmware extracts, device logs, and proprietary runtime binaries out of commits.

```bash
./gradlew \
  :client:assembleRelease \
  :app:assembleDebug \
  :sample-consumer:assembleDebug \
  :app:lintDebug
```

Run connected tests only on a device where replacing LibreWakeWord and its data is acceptable:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Connected tests may install the reference consumer, clear app data, and leave the device without an imported model afterward. Never add a model or extracted user audio to test resources.

## API changes

The consumer protocol is explicitly versioned. Before the first public release it may change without compatibility shims; afterward, preserve old AIDL field ordering and make additive changes under a new negotiated API version. Every API change should update:

- `client` parcelables, AIDL, and KDoc;
- manifest metadata documentation;
- `sample-consumer`;
- connected delivery tests; and
- `docs/api.md`.

Callbacks must stay off the detector thread. Audio remains bounded; an API change must not turn LibreWakeWord into an ongoing microphone streaming service.

## Device reports

Include the device, OS build, API level, sound-trigger model metadata, and relevant filtered logs. Do not attach vendor models, speaker models, firmware binaries, or recordings without clear rights to redistribute them.
