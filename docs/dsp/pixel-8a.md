# Pixel 8a (Tensor G3 AoC)

What the Pixel 8a's always-on DSP (the AoC) will load as a keyphrase model, and what it computes. Everything below was measured on this device and build; other Pixels and firmware need re-measuring.

| Measured on | |
| --- | --- |
| Device | Pixel 8a (`akita`), Tensor G3 (`zuma`) |
| Build | `google/akita/akita:17/CP3A.261005.005/2026102101` (SDK 37) |
| Sound-trigger HAL | `ISoundTriggerHw/default`, "Google Pixel team", version 630075724 |
| Date | 2026-11-05 |

## Load path

```text
LibreWakeWord VIS ── updateKeyphraseSoundModel / startRecognition
  └─ system_server SoundTriggerService → soundtrigger_middleware
       └─ StHal (audioserver, android.hardware.audio.service-aidl.aoc)
            └─ AoC firmware hotword_input_processor.cc  ── TFLite Micro interpreter
```

The keyphrase model's `data` bytes reach the AoC unchanged. The HAL sends them in 16 KiB chunks (`cmd 0x93`, logged as `Update size of hotword model as N`) and then commits them (`cmd 0x6b`). The AoC checks the CRC (see below), builds a TFLite Micro interpreter, and calls `AllocateTensors()`. `startRecognition` starts the hotword pipeline. In the AoC's own log this is user 3: PDM microphones in low-power mode, a 16 kHz mono stream, and the "Hotword Cleaner" overlay.

The AoC firmware logs to logcat under tag `AOC`, with source file and line, so load failures are visible as they happen.

## Model container

A Pixel sound model is a TFLite flatbuffer followed by a **4-byte little-endian zlib CRC32 of the flatbuffer**:

```text
[ TFL3 flatbuffer ............................ ][ crc32(flatbuffer) LE ]
```

- There is **no signature or allowlist**. Any well-formed model with a correct CRC is loaded and executed. Changing one constant (the classifier threshold) in Google's model changed detection behaviour as expected.
- **Maximum size: 114 KiB (116,736 bytes) for the whole sealed blob**, CRC included. 116,612 bytes loads and 116,740 does not. A larger blob is silently truncated to the buffer size, then fails as `Wrong CRC` (`rc = 1`), and the HAL reports `ToLoadState: failure due to 3`. Google's "Hey Google" model is 110,628 bytes.

Model-level metadata (`min_runtime_version`, `CONVERSION_METADATA`) is not checked; models from a 2.21 converter load fine.

## Graph contract

Google's model, and every probe that loaded, uses the structure below. It has not yet been tested which parts of it the firmware requires. Probes kept the outer graph and replaced only the network subgraph.

```text
subgraph 0 (main)
  in:  int16[160]                     10 ms of 16 kHz mono PCM per invoke
  Framer(frame_size=400, frame_step=160, prefill=false) → int16[1,400], ready:bool
  IF ready → frontend subgraph : else → zeros subgraph   → int16[120] features, ready:bool
  IF ready → network subgraph  : else → "no detection"   → detected:bool[1], keyword:int32[1]
  out: detected:bool[1], keyword:int32[1], frontend_ready:bool[]

frontend subgraph (TFLM signal ops, one 25 ms frame every 10 ms)
  RESHAPE → Window(shift=12) → FftAutoScale → Rfft(fft_length=512) → Energy(5..250)
  → FilterBank(40 ch) → FilterBankSquareRoot → FilterBankSpectralSubtraction → Pcan(snr_shift=6)
  → CAST(uint32→int16) → Stacker(left=1, right=1, step=2) → int16[120] every 20 ms

network subgraph
  in: int16[120] (scale 1/64) → QUANTIZE(int8) → … int8 layers … → int8[1,2] logits
  → SOFTMAX(int16) → QUANTIZE(int32, scale 1/32767) → BasicClassifier(scores, 1, threshold)
  out: detected:bool[1], keyword:int32[1]
```

- `BasicClassifier` is a Google custom op: no open-source kernel. It fires when `scores[index] >= threshold`. Its inputs are the int32 scores (scale 1/32767), the class index (int32[1]) and the threshold (int32[1], same scale). Google's model uses index 1 and threshold 7808 (≈ 0.24). A threshold of 0 makes the DSP fire as soon as the network runs. Measured with constant-score networks: scores [0.4, 0.6] fire at index 1 / threshold 0.5 but not at 0.65, and fire at index 0 only when swapped. An out-of-range index (2, 5) always fires.
- The Pixel frontend has **no log step**: features are PCAN output cast to int16. microWakeWord's and TFLM micro_speech's frontends add `FilterBankLog`, which the AoC also supports.
- Signal ops are registered under their **bare names** (`Framer`, `Pcan`, …). Upstream TFLM's Python runtime and op resolver use `Signal`-prefixed names (`SignalFramer`, …). Rename them before running a Pixel model on the host interpreter.
- The HAL's recognition event has no extra information: `coarseConfidenceLevel = 0` and empty `data`. Nothing from `BasicClassifier` beyond "detected" reaches Android.

## Op support

The AoC's TFLite Micro build registers a **minimal op set**, presumably only what Google's model needs plus the signal library. An unregistered op fails at load with `AllocateTensors() failed` / `hotword: load model failed: rc = 4` and does not name the op.

How each op was tested: a converter-produced int8 probe network uses the op on top of already-confirmed ops. It is grafted into Google's model in place of the network subgraph, with threshold 0. "Supported" means it loaded **and** fired, so it also runs without error. Every probe network was first checked on the host TFLM interpreter.

| Status | Ops |
| --- | --- |
| **Supported** (loads and runs) | `FULLY_CONNECTED` (incl. fused `RELU`/`RELU6`; per-channel weights load but compute wrongly, see below), `SVDF` (int8 in/out, int16 state and time weights, rank 1 to 6 seen), `SOFTMAX` (int8→int16), `QUANTIZE` (int8←int16, int16→int32), `RESHAPE`, `CAST`, `IF` |
| **Supported signal ops** | `Framer`, `Window`, `FftAutoScale`, `Rfft`, `Energy`, `FilterBank`, `FilterBankSquareRoot`, `FilterBankSpectralSubtraction`, `Pcan`, `Stacker`, `FilterBankLog` |
| **Supported Google custom ops** | `BasicClassifier` (`DummyNN` is in Google's opcode table but unused) |
| **Not registered** | `ADD`, `SUB`, `MUL`, `MAXIMUM`, `MINIMUM`, `LOGISTIC`, `TANH`, `LEAKY_RELU`, `REVERSE_V2`, `CONCATENATION`, `PACK`, `SPLIT`, `STRIDED_SLICE`, `PAD`, `TRANSPOSE`, `GATHER`, `MEAN`, `SUM`, `REDUCE_MAX`, `BATCH_MATMUL`, `L2_NORMALIZATION`, `CONV_2D`, `DEPTHWISE_CONV_2D`, `AVERAGE_POOL_2D`, `MAX_POOL_2D`, `DEQUANTIZE`, resource variables (`VAR_HANDLE` / `READ_VARIABLE` / `ASSIGN_VARIABLE` / `CALL_ONCE`, tested with int8 state), signal `Delay` |
| **Untested** | `UNIDIRECTIONAL_SEQUENCE_LSTM` (TF 2.21's int8 quantizer segfaults converting it), `OverlapAdd`, `Irfft`, `FilterBankLog` option edge cases |

What this means for model design:

- A network must be a **plain chain** of `SVDF` and `FULLY_CONNECTED` layers (ReLU/ReLU6 fused), ending in `SOFTMAX`. No convolutions, residual adds, concatenation, gating or pooling.
- **Streaming memory** can come only from `SVDF`'s internal state (as in Google's model) or from `Stacker` context frames. Resource variables, which microWakeWord uses, are unavailable.
- microWakeWord, LiveKit and openWakeWord models **cannot run as published**. Their training data and pipelines can be reused to train an SVDF/FC network, which the standard TFLite converter does not emit directly: SVDF layers need a custom exporter.
- Weights and frontend constants must fit in the 114 KiB blob.
- `FULLY_CONNECTED` weights must be **per-tensor**. With per-channel weight scales the AoC applies channel 0's scale (weights and bias) to every output channel; upstream TFLM, and so the host, does not.

## microWakeWord models

microWakeWord (MixedNet) models convert into the op set above. The result is built entirely from the converter's own frontend and graph; no Google model bytes are involved.

- The frontend reproduces micro_frontend (30 ms window, 10 ms step, 40 channels, noise reduction, PCAN, log) bit for bit. tflite-micro's Python signal helpers truncate the filterbank weights where micro_frontend rounds them; the port follows micro_frontend.
- All nine models in esphome/micro-wake-word-models `v2` (including `experiments/`) and `okay_nabu` 20241226.3 (the one Home Assistant Voice PE ships) and `stop` from the microWakeWord GitHub releases convert (36–81 KB) and track the originals on synthesized speech within 0.06 probability per step.
- Detection matches microWakeWord runtimes: the network's per-step probability is put on the runtime's 1/256 grid and averaged over the last `sliding_window_size` steps by an SVDF (time weights 1/N, history starting at zero, as in ESPHome), and BasicClassifier compares the mean with `probability_cutoff`.
- As in ESPHome, nothing is detected during the first 100 inferences (3 s) after a recognition start. ESPHome only counts inferences below the cutoff; the converted model counts all of them.
- On the device the converted network computes exactly what it does on the host. The frontend's int8 features differ by a few LSB in the first frames after a start, which doesn't measurably change scores.
- Live on the Pixel 8a, spoken at arm's length: okay_nabu v2 detected 6 of 6, okay_nabu 20241226.3 13 of 16, where the original model on the recorded audio detects all 16. The misses were the borderline utterances (original model 0.93–0.96 against the 0.85 cutoff). Cause unknown; the DSP also fires a consistent 280–420 ms after the original model would on the recording, so the model may read the audio at a different point of the AoC pipeline than the HOTWORD stream.

## Audio path

- **Capture-session audio** (`AudioRecord` on the detection's `captureSession`, `HOTWORD` attributes) is 16 kHz **stereo**: two different microphones (correlation ≈0.3, about 15 dB apart in level), starting 2 s before the detection (AoC: "Synced hotword ring buffer to last (2000) msec").
- The hotword path is **quiet**: speech at arm's length peaked around −46 dBFS on the louder channel, with a −65 to −70 dBFS room floor. microWakeWord models still scored 0.996+ on it (PCAN normalizes level), so no extra gain is needed.
- An ordinary `AudioRecord` with the `HOTWORD` preset (1999, via `setInternalCapturePreset`) records the hotword path while a model is armed. Recording several sources at once is not useful: concurrent recordings get the same stream (the first-opened source's routing).
- **The phone's own notification sounds reach the hotword path** at −31 to −35 dBFS, louder than speech. The AoC only logs hotword echo-cancellation activity (`hotword_aec.cc`, "Eraser") around the first notification. That explains Google's model reacting to notification sounds. okay_nabu scored 0.00 on them. Not yet verified: whether the model's own input (as opposed to an `AudioRecord` on the same path) has the echo removed.

## Other observations

- A failed load makes `system_server` restart the sound-trigger HAL (`Exception caught from HAL, rebooting HAL`). This is harmless. The next load works normally.
- After a failed load the AoC logs `Cannot get DMIC power state for mic index 2/3` and `AUD_CAP_PDM … rc = -1`. These follow every failure and are not its cause.
- `No Data Section to power ON/OFF for app_id=23` (the Hotword Cleaner overlay) appears on every start and stop, including with Google's model. It is benign.
- Variable tensors (SVDF state, so also the sliding window) are **reset on every `startRecognition`**: a network ramping up over 200 steps fired 1.8 s after each restart, as on the host.
- Working memory: a 101 KB, 3-layer FC network (120→240→240→2) allocates and runs. The arena limit and per-invoke compute budget have not been measured.

## Open questions

- Which parts of the outer graph contract are required (the three outputs, `Framer`/`IF`, the `BasicClassifier` tail)? Could the network run every 10 ms without the `IF` gating?
- `BasicClassifier` with more than two classes, and the meaning of its `keyword` output.
- Do vendor UUID `7038ddc8-30f2-11e6-b0ac-40a8f03d3f15`, keyphrase ID 2 and model version 36 matter to the HAL or AoC?
- Tensor arena size and compute (MIPS) budget.
- Other Pixel generations and firmware versions.
