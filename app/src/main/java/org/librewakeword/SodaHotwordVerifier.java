package org.librewakeword;

import android.content.Context;
import android.os.SystemClock;
import android.os.Build;
import android.util.Log;

import com.google.android.libraries.assistant.soda.Soda;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class SodaHotwordVerifier {
    private static final String TAG = "SodaVerifier";
    private static final int PCM16_SAMPLE_FORMAT = 1;
    private static final int HOTWORD_CAPTURE_MODE = 2;
    private static final int EVENT_WAIT_MILLIS = 1200;
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final boolean ENABLE_HOTWORD_CLEANER = false;

    /**
     * A SODA engine has to be initialized before a DSP callback arrives. Android gives the HDS
     * three seconds to return a decision; constructing and configuring SODA in that window is
     * both needlessly slow and unlike the Pixel implementation.
     */
    static Engine initialize(Context context, byte[] model, boolean enableSecondStageYGoogle) {
        return initialize(context, model, null, enableSecondStageYGoogle);
    }

    static Engine initialize(Context context, byte[] model, byte[] speakerModel,
            boolean enableSecondStageYGoogle) {
        Callback callback = new Callback();
        Soda soda = new Soda(context, callback);
        try {
            byte[] initResult = soda.init(speakerModel == null || speakerModel.length == 0
                    ? coreConfig(context, model, DEFAULT_SAMPLE_RATE)
                    : speakerVerificationCoreConfig(context, model, speakerModel,
                            DEFAULT_SAMPLE_RATE));
            ParsedMessage init = ParsedMessage.parse(initResult);
            int initCode = init.firstInt(1, -1);
            String initMessage = init.firstString(2, "");
            Log.i(TAG, "preinit code=" + initCode + " message=" + initMessage
                    + " bytes=" + initResult.length);
            if (initCode != 0) {
                throw new IllegalStateException("soda init failed code=" + initCode
                        + " message=" + initMessage);
            }
            return new Engine(soda, callback, enableSecondStageYGoogle,
                    speakerModel != null && speakerModel.length > 0);
        } catch (Throwable t) {
            soda.close();
            throw t;
        }
    }

    /** Validate a replacement resource using the exact HDS initialization configuration. */
    static void validateModel(Context context, byte[] model) {
        Engine engine = initialize(context, model, true);
        engine.close();
    }

    static final class Engine implements AutoCloseable {
        private final Soda soda;
        private final Callback callback;
        private final boolean enableSecondStageYGoogle;
        private final boolean requireSpeakerVerification;
        private boolean closed;

        private Engine(Soda soda, Callback callback, boolean enableSecondStageYGoogle,
                boolean requireSpeakerVerification) {
            this.soda = soda;
            this.callback = callback;
            this.enableSecondStageYGoogle = enableSecondStageYGoogle;
            this.requireSpeakerVerification = requireSpeakerVerification;
        }

        /** Streams until SODA emits a terminal result or the bounded verification deadline. */
        synchronized SecondStageHotwordVerifier.Result verify(SodaDspInput stream,
                long deadlineElapsedRealtime, Runnable onAccepted) {
            if (closed) {
                return SecondStageHotwordVerifier.Result.reject("SODA engine is closed");
            }
            callback.reset(enableSecondStageYGoogle, requireSpeakerVerification, onAccepted);
            int total = 0;
            try {
                byte[] startResult = soda.startCapture(clientConfig(stream.sampleRate,
                        stream.channelCount));
                ParsedMessage start = ParsedMessage.parse(startResult);
                int startCode = start.firstInt(1, 0);
                String startMessage = start.firstString(2, "");
                Log.i(TAG, "start code=" + startCode + " message=" + startMessage
                        + " bytes=" + startResult.length + " streamingSession=true"
                        + " sampleRate=" + stream.sampleRate + " channelCount="
                        + stream.channelCount);
                if (startCode != 0) {
                    return SecondStageHotwordVerifier.Result.reject(
                            "soda start failed code=" + startCode + " message=" + startMessage);
                }

                // Stock SODA's InputStream adapter consumes exact-format PCM in 10 ms reads.
                byte[] buffer = new byte[stream.sodaReadBytes()];
                ByteBuffer direct = ByteBuffer.allocateDirect(buffer.length);
                while (!callback.isDone() && SystemClock.elapsedRealtime()
                        < deadlineElapsedRealtime) {
                    int n = stream.read(buffer);
                    if (n <= 0) {
                        Log.w(TAG, "stream read returned " + n + " total=" + total);
                        break;
                    }
                    direct.clear();
                    direct.put(buffer, 0, n);
                    direct.flip();
                    ByteBuffer timestamps = stream.timestampMetadata();
                    if (!soda.addTimestampedAudio(direct, n, timestamps,
                            stream.timestampMetadataBytes())) {
                        Log.w(TAG, "stream addTimestampedAudio returned false total=" + total
                                + " size=" + n);
                    }
                    total += n;
                }
                long remaining = deadlineElapsedRealtime - SystemClock.elapsedRealtime();
                if (!callback.isDone() && remaining > 0) {
                    callback.await(remaining);
                }
                callback.clearAcceptanceListener();
                SecondStageHotwordVerifier.Result result = callback.result();
                Log.i(TAG, "stream pushed bytes=" + total + " done=" + callback.isDone()
                        + " result=" + result.detail);
                return result;
            } catch (Throwable t) {
                return SecondStageHotwordVerifier.Result.reject("soda stream " + rootCause(t));
            }
        }

        /** Ends the current SODA capture after its result and bounded audio have been consumed. */
        synchronized void finishCapture() {
            if (closed) return;
            try {
                soda.stopCapture();
            } catch (Throwable error) {
                Log.w(TAG, "could not stop SODA capture", error);
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                soda.close();
            }
        }
    }

    private static byte[] coreConfig(Context context, byte[] model, int sampleRate) {
        return coreConfig(context, model, sampleRate, false);
    }

    /**
     * SODA's local enrollment API only supports DEFAULT_TEXT_DEPENDENT enrollment. In proto3 the
     * enum value is zero, but this proto tracks scalar presence. Stock explicitly sets field 2,
     * so the zero must still be serialized rather than represented by an empty submessage. The
     * generated filk message-info table identifies VoiceMatchEnrollmentConfig as root field 17.
     */
    static byte[] textDependentEnrollmentCoreConfig(Context context, byte[] model,
            int sampleRate) {
        return textDependentEnrollmentCoreConfig(context, model, sampleRate, 17);
    }

    static byte[] textDependentEnrollmentCoreConfig(Context context, byte[] model,
            int sampleRate, int enrollmentRootField) {
        return coreConfig(context, model, sampleRate, enrollmentRootField);
    }

    private static byte[] coreConfig(Context context, byte[] model, int sampleRate,
            boolean textDependentEnrollment) {
        return coreConfig(context, model, sampleRate, textDependentEnrollment ? 17 : 0);
    }

    private static byte[] coreConfig(Context context, byte[] model, int sampleRate,
            int enrollmentRootField) {
        return coreConfig(context, model, null, sampleRate, enrollmentRootField);
    }

    static byte[] speakerVerificationCoreConfig(Context context, byte[] model,
            byte[] speakerModel, int sampleRate) {
        if (speakerModel == null || speakerModel.length == 0) {
            throw new IllegalArgumentException("Speaker model is empty");
        }
        return coreConfig(context, model, speakerModel, sampleRate, 0);
    }

    private static byte[] coreConfig(Context context, byte[] model, byte[] speakerModel,
            int sampleRate, int enrollmentRootField) {
        byte[] speakerModels = ProtoWriter.empty();
        if (speakerModel != null) {
            byte[] mapEntry = new ProtoWriter()
                    .string(1, "0")
                    .bytes(2, speakerModel)
                    .toByteArray();
            speakerModels = new ProtoWriter()
                    .message(3, mapEntry)
                    .toByteArray();
        }
        byte[] hotwordData = new ProtoWriter()
                .bytes(2, model)
                .message(3, speakerModels)
                .toByteArray();
        ProtoWriter hotwordingWriter = new ProtoWriter()
                .message(2, hotwordData)
                .int32(3, 1200)
                .bool(23, true);
        if (ENABLE_HOTWORD_CLEANER) {
            hotwordingWriter.message(4, new ProtoWriter()
                    .bool(1, true)
                    .bool(3, false)
                    .toByteArray());
        }
        byte[] hotwording = hotwordingWriter
                // GSA marks the SODA model payload as shared-memory backed.
                .bool(16, true)
                .toByteArray();
        byte[] coreAudio = new ProtoWriter()
                .message(1, audioFormat(sampleRate, 1))
                .toByteArray();
        byte[] clientInfo = new ProtoWriter()
                .string(2, "Android")
                .string(3, Build.MODEL == null ? "" : Build.MODEL)
                .string(4, "")
                .int32(6, 0)
                .toByteArray();
        // filk.fikq (root field 6) contains fikr at field 4. This is the exact
        // 4,000-ms runtime value created by GSA's cpmp.e().
        byte[] hotwordRuntime = new ProtoWriter()
                .message(4, new ProtoWriter().int32(1, 4000).toByteArray())
                .toByteArray();
        ProtoWriter root = new ProtoWriter()
                .message(1, coreAudio)
                .message(2, hotwording)
                .message(6, hotwordRuntime)
                .string(7, "")
                .message(10, clientInfo);
        if (enrollmentRootField > 0) {
            root.message(enrollmentRootField, new ProtoWriter()
                    .int32(2, 0) // DEFAULT_TEXT_DEPENDENT, explicitly present
                    .toByteArray());
        }
        return root.toByteArray();
    }

    static byte[] clientConfig(int sampleRate, int channelCount) {
        // Pixel's fipl/fipi/fipk SODA capture proto. In particular, fipi's first oneof
        // contains fipk; the previous hand-written layout put a different message there.
        byte[] audio = new ProtoWriter()
                .int32(2, PCM16_SAMPLE_FORMAT)
                .int32(3, sampleRate)
                .int32(4, channelCount)
                .toByteArray();
        byte[] fipk = new ProtoWriter()
                // Pixel GSA's timestamped HDS mode. Audio is accompanied by one
                // (framePosition, bootTimeMicros) pair for every 10 ms chunk.
                .int32(1, 2)
                .int32(2, HOTWORD_CAPTURE_MODE)
                .message(4, audio)
                .int32(8, 0)
                .int32(9, 0)
                .toByteArray();
        byte[] fipi = new ProtoWriter()
                .message(1, fipk)
                .toByteArray();
        byte[] captureMode = new ProtoWriter()
                .int32(2, 2)
                .toByteArray();
        return new ProtoWriter()
                .bool(2, true)
                .bool(3, true)
                .message(4, fipi)
                .message(10, captureMode)
                .toByteArray();
    }

    private static byte[] audioFormat(int sampleRate, int channelCount) {
        return new ProtoWriter()
                .int32(2, PCM16_SAMPLE_FORMAT)
                .int32(3, sampleRate)
                .int32(4, channelCount)
                .toByteArray();
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getName() + ": " + cur.getMessage();
    }

    private static final class Callback implements Soda.Callback {
        private volatile CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean accepted = new AtomicBoolean(false);
        private volatile String detail = "no SODA event";
        private int streamingEventCount;
        private int progressEventCount;
        private boolean enableSecondStageYGoogle;
        private boolean requireSpeakerVerification;
        private DetectedAudio detectedAudio;
        private final AtomicReference<Runnable> acceptanceListener = new AtomicReference<>();

        synchronized void reset(boolean enableSecondStageYGoogle,
                boolean requireSpeakerVerification, Runnable acceptanceListener) {
            this.enableSecondStageYGoogle = enableSecondStageYGoogle;
            this.requireSpeakerVerification = requireSpeakerVerification;
            this.acceptanceListener.set(acceptanceListener);
            this.done = new CountDownLatch(1);
            this.accepted.set(false);
            this.detail = "no SODA hotword event before deadline";
            this.streamingEventCount = 0;
            this.progressEventCount = 0;
            this.detectedAudio = null;
        }

        @Override
        public void onStart() {
            Log.i(TAG, "callback start");
        }

        @Override
        public void onEvent(byte[] event) {
            ParsedMessage sodaEvent = ParsedMessage.parse(event);
            Set<Integer> fields = sodaEvent.fields();
            if (fields.size() == 1 && fields.contains(8)) {
                streamingEventCount++;
                if (streamingEventCount == 1) {
                    Log.i(TAG, "streaming event field=8 bytes=" + event.length);
                }
            } else if (fields.size() == 1 && fields.contains(5)) {
                progressEventCount++;
                if (progressEventCount == 1) {
                    Log.i(TAG, "SODA progress event field=5 bytes=" + event.length);
                }
            } else {
                Log.i(TAG, "event fields=" + fields + " bytes=" + event.length);
            }
            byte[] hotword = sodaEvent.firstBytes(1);
            if (hotword != null) {
                ParsedMessage hotwordEvent = ParsedMessage.parse(hotword);
                ParsedMessage metadata = ParsedMessage.parse(hotwordEvent.firstBytes(1));
                // Pixel's fimq/fimo event uses field 1 for fimo and fimo field 2 for the
                // keyphrase. It does not make a decision merely from a field-name heuristic.
                String phrase = metadata.firstString(2, "");
                boolean yGoogle = phrase.toLowerCase(Locale.US).contains("hotword1");
                byte[] speakerBytes = hotwordEvent.firstBytes(3);
                ParsedMessage speakerInfo = ParsedMessage.parse(speakerBytes);
                boolean speakerDetected = speakerInfo.firstInt(1, 0) != 0;
                float speakerScore = speakerInfo.firstFloat(3, Float.NaN);
                boolean acceptedNow = (!yGoogle || enableSecondStageYGoogle)
                        && (!requireSpeakerVerification || speakerDetected);
                accepted.set(acceptedNow);
                Runnable listener = acceptanceListener.getAndSet(null);
                if (acceptedNow && listener != null) {
                    try {
                        // Microphone activation is latency-sensitive. Signal as soon as all
                        // acceptance fields are known; parsing/copying the returned audio and
                        // constructing diagnostics are not part of the decision.
                        listener.run();
                    } catch (Throwable error) {
                        // Capture startup is best-effort here. The verifier's caller retries it
                        // synchronously before handing the microphone to a consumer.
                        Log.e(TAG, "early accepted-capture callback failed", error);
                    }
                }
                detectedAudio = parseDetectedAudio(hotwordEvent);
                String hotwordDetail = "hotwordFields=" + hotwordEvent.fields()
                        + " metadataFields=" + metadata.fields()
                        + " metadata=" + metadata.summary()
                        + " field2=" + ParsedMessage.parse(hotwordEvent.firstBytes(2)).summary()
                        + " field4=" + ParsedMessage.parse(hotwordEvent.firstBytes(4)).summary()
                        + " field5=" + hotwordEvent.firstInt(5, Integer.MIN_VALUE)
                        + " speakerInfoPresent=" + (speakerBytes != null)
                        + " speakerDetected=" + speakerDetected
                        + " speakerScore=" + speakerScore
                        + " detectedAudio=" + (detectedAudio == null
                                ? "missing" : detectedAudio.describe())
                        + " streamingEvents=" + streamingEventCount;
                Log.i(TAG, "hotword event " + hotwordDetail);
                detail = "soda hotword fields=" + hotwordEvent.fields()
                        + " phrase=" + phrase + " yGoogle=" + yGoogle
                        + " speakerDetected=" + speakerDetected
                        + " speakerScore=" + speakerScore;
                done.countDown();
                return;
            }
            byte[] peak = sodaEvent.firstBytes(19);
            if (peak != null) {
                ParsedMessage peakEvent = ParsedMessage.parse(peak);
                Log.i(TAG, "peak fields=" + peakEvent.fields() + " " + peakEvent.summary());
            }
            byte[] ignore = sodaEvent.firstBytes(29);
            if (ignore != null) {
                ParsedMessage ignoreEvent = ParsedMessage.parse(ignore);
                detail = "soda ignore fields=" + ignoreEvent.fields() + " "
                        + ignoreEvent.summary();
                accepted.set(false);
                acceptanceListener.set(null);
                done.countDown();
            }
        }

        @Override
        public void onStop(int reason) {
            Log.i(TAG, "callback stop reason=" + reason);
            acceptanceListener.set(null);
            if (!accepted.get() && "no SODA event".equals(detail)) {
                detail = "soda stopped reason=" + reason;
            }
            done.countDown();
        }

        @Override
        public void onShutdown() {
            Log.i(TAG, "callback shutdown");
        }

        void await() throws InterruptedException {
            done.await(EVENT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        }

        void await(long timeoutMillis) throws InterruptedException {
            done.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        }

        boolean isDone() {
            return done.getCount() == 0;
        }

        void clearAcceptanceListener() {
            acceptanceListener.set(null);
        }

        SecondStageHotwordVerifier.Result result() {
            return new SecondStageHotwordVerifier.Result(accepted.get(), detail, detectedAudio);
        }

        private static DetectedAudio parseDetectedAudio(ParsedMessage hotwordEvent) {
            // fimq.field2 = finf; finf.field1 = figl. In timestamped capture mode,
            // figl.field3 is the boot-time microsecond timestamp of the first PCM frame.
            ParsedMessage hotwordAudio = ParsedMessage.parse(hotwordEvent.firstBytes(2));
            ParsedMessage audio = ParsedMessage.parse(hotwordAudio.firstBytes(1));
            byte[] pcm = audio.firstBytes(1);
            ParsedMessage format = ParsedMessage.parse(audio.firstBytes(2));
            long startTimeElapsedRealtimeMicros = audio.firstLong(3, -1L);
            int trailingAudioMillis = hotwordAudio.firstInt(2, -1);
            int hotwordDurationMillis = hotwordAudio.firstInt(3, -1);
            long sourceEndFrame = hotwordAudio.firstLong(9, -1L);
            int sampleFormat = format.firstInt(2, -1);
            int sampleRate = format.firstInt(3, -1);
            int channelCount = format.firstInt(4, -1);
            if (pcm == null || sampleFormat != PCM16_SAMPLE_FORMAT
                    || sampleRate <= 0 || channelCount <= 0) {
                return null;
            }

            int selectedChannel = 0;
            ParsedMessage channelMetrics = ParsedMessage.parse(hotwordEvent.firstBytes(4));
            java.util.List<byte[]> channels = channelMetrics.allBytes(1);
            for (int index = 0; index < channels.size(); index++) {
                if (ParsedMessage.parse(channels.get(index)).firstInt(1, 0) != 0) {
                    selectedChannel = index;
                    break;
                }
            }
            if (selectedChannel >= channelCount) selectedChannel = 0;
            return new DetectedAudio(pcm, sampleRate, channelCount, selectedChannel,
                    startTimeElapsedRealtimeMicros, trailingAudioMillis,
                    hotwordDurationMillis, sourceEndFrame);
        }
    }

    static final class DetectedAudio {
        final byte[] pcm;
        final int sampleRate;
        final int channelCount;
        final int selectedChannel;
        final long startTimeElapsedRealtimeMicros;
        final int trailingAudioMillis;
        final int hotwordDurationMillis;
        final long sourceEndFrame;

        DetectedAudio(byte[] pcm, int sampleRate, int channelCount, int selectedChannel,
                long startTimeElapsedRealtimeMicros, int trailingAudioMillis,
                int hotwordDurationMillis, long sourceEndFrame) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.selectedChannel = selectedChannel;
            this.startTimeElapsedRealtimeMicros = startTimeElapsedRealtimeMicros;
            this.trailingAudioMillis = trailingAudioMillis;
            this.hotwordDurationMillis = hotwordDurationMillis;
            this.sourceEndFrame = sourceEndFrame;
        }

        /** Frame immediately after the hotword in the PCM submitted to SODA. */
        long hotwordEndSourceFrame() {
            if (sourceEndFrame < 0L || trailingAudioMillis < 0) return -1L;
            return sourceEndFrame
                    - (long) trailingAudioMillis * sampleRate / 1_000L;
        }

        byte[] selectedChannelPcm16() {
            if (channelCount == 1) return pcm.clone();
            int frameBytes = channelCount * 2;
            int frameCount = pcm.length / frameBytes;
            byte[] mono = new byte[frameCount * 2];
            for (int frame = 0; frame < frameCount; frame++) {
                int source = frame * frameBytes + selectedChannel * 2;
                mono[frame * 2] = pcm[source];
                mono[frame * 2 + 1] = pcm[source + 1];
            }
            return mono;
        }

        String describe() {
            return "bytes=" + pcm.length + " sampleRate=" + sampleRate
                    + " channels=" + channelCount + " selected=" + selectedChannel
                    + " startElapsedMicros=" + startTimeElapsedRealtimeMicros
                    + " trailingMs=" + trailingAudioMillis
                    + " hotwordMs=" + hotwordDurationMillis
                    + " sourceEndFrame=" + sourceEndFrame
                    + " hotwordEndFrame=" + hotwordEndSourceFrame();
        }
    }

    private static final class ParsedMessage {
        private final java.util.Map<Integer, java.util.List<Value>> values =
                new java.util.HashMap<>();

        static ParsedMessage parse(byte[] bytes) {
            ParsedMessage message = new ParsedMessage();
            if (bytes == null) {
                return message;
            }
            int[] pos = new int[]{0};
            while (pos[0] < bytes.length) {
                long tag = readVarint(bytes, pos);
                if (tag == 0) {
                    break;
                }
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                Value value;
                if (wire == 0) {
                    value = Value.varint(readVarint(bytes, pos));
                } else if (wire == 2) {
                    int len = (int) readVarint(bytes, pos);
                    if (len < 0 || pos[0] + len > bytes.length) {
                        break;
                    }
                    byte[] data = new byte[len];
                    System.arraycopy(bytes, pos[0], data, 0, len);
                    pos[0] += len;
                    value = Value.bytes(data);
                } else if (wire == 5) {
                    if (pos[0] + 4 > bytes.length) {
                        break;
                    }
                    int fixed = (bytes[pos[0]] & 0xff)
                            | ((bytes[pos[0] + 1] & 0xff) << 8)
                            | ((bytes[pos[0] + 2] & 0xff) << 16)
                            | ((bytes[pos[0] + 3] & 0xff) << 24);
                    pos[0] += 4;
                    value = Value.fixed32(fixed);
                } else {
                    break;
                }
                message.values.computeIfAbsent(field, k -> new java.util.ArrayList<>())
                        .add(value);
            }
            return message;
        }

        Set<Integer> fields() {
            return new HashSet<>(values.keySet());
        }

        int firstInt(int field, int fallback) {
            java.util.List<Value> list = values.get(field);
            if (list == null || list.isEmpty() || !list.get(0).hasVarint) {
                return fallback;
            }
            return (int) list.get(0).varint;
        }

        long firstLong(int field, long fallback) {
            java.util.List<Value> list = values.get(field);
            if (list == null || list.isEmpty() || !list.get(0).hasVarint) {
                return fallback;
            }
            return list.get(0).varint;
        }

        float firstFloat(int field, float fallback) {
            java.util.List<Value> list = values.get(field);
            if (list == null || list.isEmpty() || !list.get(0).hasFixed32) {
                return fallback;
            }
            return Float.intBitsToFloat(list.get(0).fixed32);
        }

        String firstString(int field, String fallback) {
            byte[] bytes = firstBytes(field);
            if (bytes == null) {
                return fallback;
            }
            try {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable t) {
                return fallback;
            }
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (java.util.Map.Entry<Integer, java.util.List<Value>> entry : values.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(entry.getKey()).append("=");
                java.util.List<Value> list = entry.getValue();
                if (list.size() > 1) {
                    sb.append("[");
                }
                int limit = Math.min(list.size(), 3);
                for (int i = 0; i < limit; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(list.get(i).summary());
                }
                if (list.size() > limit) {
                    sb.append(", ... x").append(list.size());
                }
                if (list.size() > 1) {
                    sb.append("]");
                }
            }
            sb.append("}");
            return sb.toString();
        }

        byte[] firstBytes(int field) {
            java.util.List<Value> list = values.get(field);
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0).bytes;
        }

        java.util.List<byte[]> allBytes(int field) {
            java.util.List<Value> list = values.get(field);
            if (list == null || list.isEmpty()) return java.util.Collections.emptyList();
            java.util.ArrayList<byte[]> result = new java.util.ArrayList<>(list.size());
            for (Value value : list) {
                if (value.bytes != null) result.add(value.bytes);
            }
            return result;
        }

        private static long readVarint(byte[] bytes, int[] pos) {
            long result = 0;
            int shift = 0;
            while (pos[0] < bytes.length && shift < 64) {
                int b = bytes[pos[0]++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            return result;
        }
    }

    private static final class Value {
        final boolean hasVarint;
        final long varint;
        final boolean hasFixed32;
        final int fixed32;
        final byte[] bytes;

        private Value(boolean hasVarint, long varint, boolean hasFixed32, int fixed32,
                byte[] bytes) {
            this.hasVarint = hasVarint;
            this.varint = varint;
            this.hasFixed32 = hasFixed32;
            this.fixed32 = fixed32;
            this.bytes = bytes;
        }

        static Value varint(long value) {
            return new Value(true, value, false, 0, null);
        }

        static Value fixed32(int value) {
            return new Value(false, 0, true, value, null);
        }

        static Value bytes(byte[] value) {
            return new Value(false, 0, false, 0, value);
        }

        String summary() {
            if (hasVarint) {
                return Long.toString(varint);
            }
            if (hasFixed32) {
                return Float.toString(Float.intBitsToFloat(fixed32))
                        + "f/0x" + Integer.toHexString(fixed32);
            }
            if (bytes == null) {
                return "null";
            }
            if (looksPrintable(bytes)) {
                return "\"" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + "\"";
            }
            return "bytes(" + bytes.length + ")";
        }

        private static boolean looksPrintable(byte[] bytes) {
            if (bytes.length == 0 || bytes.length > 80) {
                return false;
            }
            for (byte b : bytes) {
                int c = b & 0xff;
                if (c < 0x20 || c > 0x7e) {
                    return false;
                }
            }
            return true;
        }
    }

    private SodaHotwordVerifier() {
        throw new AssertionError();
    }
}
