# DSP support by device

LibreWakeWord runs wake word models on the phone's always-on DSP through Android's sound-trigger API (`AlwaysOnHotwordDetector` → SoundTriggerService → the vendor's sound-trigger HAL). Everything past the HAL is vendor-specific: the model format, which ops the DSP runs, whether it computes them correctly, and what audio it hears. Each device gets its own page, measured on hardware.

| Device | DSP | Status |
| --- | --- | --- |
| [Pixel 8a](pixel-8a.md) | Tensor G3 AoC, TFLite Micro | microWakeWord models convert and run |

Other Tensor Pixels likely share most of the 8a's findings, but need re-measuring before relying on them.
