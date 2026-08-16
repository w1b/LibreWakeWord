package org.librewakeword;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** DSP-event capture-session input used by the SODA verifier and bounded handoff prefix. */
final class SodaDspInput implements AutoCloseable {
    private static final String TAG = "SodaDspInput";
    private static final int PCM16_BYTES = 2;
    private static final int SODA_READ_MILLIS = 10;
    private static final int SODA_SAMPLE_RATE = 16_000;
    // Hidden framework constant MediaRecorder.AudioSource.HOTWORD. This is the capture preset
    // used by the platform assistant for an AudioRecord attached to a DSP event capture session.
    private static final int HOTWORD_AUDIO_SOURCE = 1_999;
    private static final int CAPTURE_BUFFER_SECONDS = 10;
    private static final int MAX_CAPTURE_SECONDS = 5;
    private static final int MAX_DIAGNOSTIC_READS = 1_024;

    final int sampleRate;
    final int channelCount;

    private final AudioRecord record;
    private final int readBytes;
    private final int bytesPerFrame;
    private final AudioTimestamp audioTimestamp = new AudioTimestamp();
    private final ByteBuffer timestampMetadata = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder());
    private final PcmRingBuffer capturedPcm;
    private long framesRead;
    private long capturedEndElapsedRealtimeNanos = -1L;
    private long lastTimestampFramePosition = -1L;
    private final long[] diagnosticClientFrame = new long[MAX_DIAGNOSTIC_READS];
    private final long[] diagnosticTimestampFrame = new long[MAX_DIAGNOSTIC_READS];
    private final long[] diagnosticTimestampNanos = new long[MAX_DIAGNOSTIC_READS];
    private final long[] diagnosticReadStartedNanos = new long[MAX_DIAGNOSTIC_READS];
    private final long[] diagnosticReadFinishedNanos = new long[MAX_DIAGNOSTIC_READS];
    private final int[] diagnosticFrameCount = new int[MAX_DIAGNOSTIC_READS];
    private final int[] diagnosticTimestampStatus = new int[MAX_DIAGNOSTIC_READS];
    private final int[] diagnosticReadMode = new int[MAX_DIAGNOSTIC_READS];
    private int diagnosticReadCount;
    private boolean stopped;
    private boolean closed;

    @SuppressLint("MissingPermission")
    static SodaDspInput open(int captureSession, AudioFormat payloadFormat) {
        if (captureSession < 0) {
            throw new IllegalArgumentException("Invalid DSP capture session: " + captureSession);
        }
        if (payloadFormat == null
                || payloadFormat.getEncoding() != AudioFormat.ENCODING_PCM_16BIT
                || payloadFormat.getSampleRate() <= 0
                || payloadFormat.getChannelCount() <= 0) {
            throw new IllegalArgumentException("Unsupported DSP capture format: "
                    + payloadFormat);
        }
        if (payloadFormat.getSampleRate() != SODA_SAMPLE_RATE) {
            throw new IllegalArgumentException("Unsupported DSP sample rate: "
                    + payloadFormat.getSampleRate());
        }
        int sampleRate = payloadFormat.getSampleRate();
        int channelCount = payloadFormat.getChannelCount();
        AudioRecord record = null;
        try {
            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
            invoke(attributesBuilder, "setInternalCapturePreset", int.class,
                    HOTWORD_AUDIO_SOURCE);
            // Keep this construction equivalent to Android's trusted hotword audio provider:
            // HOTWORD attributes first, the DSP event's exact format, a ten-second client
            // buffer, and the event capture session last. Extra context/privacy overrides alter
            // framework routing decisions and do not belong on this special recorder.
            AudioRecord.Builder builder = new AudioRecord.Builder();
            invoke(builder, "setAudioAttributes", AudioAttributes.class,
                    attributesBuilder.build());
            builder
                    .setAudioFormat(payloadFormat)
                    .setBufferSizeInBytes(sampleRate * channelCount * PCM16_BYTES
                            * CAPTURE_BUFFER_SECONDS);
            invoke(builder, "setSessionId", int.class, captureSession);
            record = builder.build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("DSP capture-session AudioRecord was not initialized");
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("DSP capture-session AudioRecord did not start");
            }
            return new SodaDspInput(record, sampleRate, channelCount);
        } catch (Throwable error) {
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                }
                record.release();
            }
            throw new IllegalStateException(rootCause(error), error);
        }
    }

    private SodaDspInput(AudioRecord record, int sampleRate, int channelCount) {
        this.record = record;
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.bytesPerFrame = channelCount * PCM16_BYTES;
        this.readBytes = sampleRate * channelCount * PCM16_BYTES
                * SODA_READ_MILLIS / 1_000;
        this.capturedPcm = new PcmRingBuffer(
                sampleRate * channelCount * PCM16_BYTES * MAX_CAPTURE_SECONDS);
    }

    int sodaReadBytes() {
        return readBytes;
    }

    int read(byte[] destination) {
        return read(destination, AudioRecord.READ_BLOCKING);
    }

    private int read(byte[] destination, int readMode) {
        if (closed || (stopped && readMode == AudioRecord.READ_BLOCKING)) {
            return AudioRecord.ERROR_INVALID_OPERATION;
        }
        long readStartedNanos = android.os.SystemClock.elapsedRealtimeNanos();
        int count = record.read(destination, 0, Math.min(destination.length, readBytes),
                readMode);
        long readFinishedNanos = android.os.SystemClock.elapsedRealtimeNanos();
        if (count <= 0) return count;
        int completeBytes = count - count % bytesPerFrame;
        if (completeBytes == 0) return 0;
        int timestampStatus = record.getTimestamp(
                audioTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME);
        if (timestampStatus != AudioRecord.SUCCESS && capturedEndElapsedRealtimeNanos < 0L) {
            throw new IllegalStateException("DSP AudioRecord timestamp unavailable: "
                    + timestampStatus);
        }
        long chunkStartFrame = framesRead;
        long chunkFrames = completeBytes / bytesPerFrame;
        if (diagnosticReadCount < MAX_DIAGNOSTIC_READS) {
            int index = diagnosticReadCount++;
            diagnosticClientFrame[index] = chunkStartFrame;
            diagnosticFrameCount[index] = (int) chunkFrames;
            diagnosticTimestampStatus[index] = timestampStatus;
            diagnosticTimestampFrame[index] = timestampStatus == AudioRecord.SUCCESS
                    ? audioTimestamp.framePosition : -1L;
            diagnosticTimestampNanos[index] = timestampStatus == AudioRecord.SUCCESS
                    ? audioTimestamp.nanoTime : -1L;
            diagnosticReadStartedNanos[index] = readStartedNanos;
            diagnosticReadFinishedNanos[index] = readFinishedNanos;
            diagnosticReadMode[index] = readMode;
        }
        long firstFrameNanos;
        if (timestampStatus == AudioRecord.SUCCESS) {
            firstFrameNanos = audioTimestamp.nanoTime
                    + framesToNanos(chunkStartFrame - audioTimestamp.framePosition);
        } else {
            // AudioRecord may stop exposing timestamps after stop(), but any remaining client
            // buffer is still the chronological continuation of the last successful read.
            firstFrameNanos = capturedEndElapsedRealtimeNanos;
        }
        timestampMetadata.clear();
        timestampMetadata.putLong(chunkStartFrame);
        timestampMetadata.putLong(firstFrameNanos / 1_000L);
        timestampMetadata.flip();
        framesRead += chunkFrames;
        capturedPcm.append(destination, 0, completeBytes);
        if (timestampStatus == AudioRecord.SUCCESS) {
            capturedEndElapsedRealtimeNanos = audioTimestamp.nanoTime
                    + framesToNanos(framesRead - audioTimestamp.framePosition);
            lastTimestampFramePosition = audioTimestamp.framePosition;
        } else {
            capturedEndElapsedRealtimeNanos += framesToNanos(chunkFrames);
        }
        return completeBytes;
    }

    /** Returns the bounded PCM read from the DSP event's capture session. */
    Capture snapshot() {
        byte[] pcm = capturedPcm.snapshot();
        if (pcm.length == 0 || capturedEndElapsedRealtimeNanos < 0L) return null;
        long captureFrames = pcm.length / bytesPerFrame;
        long startNanos = capturedEndElapsedRealtimeNanos - framesToNanos(captureFrames);
        Log.i(TAG, "snapshot bytes=" + pcm.length
                + " streamFrames=" + framesRead
                + " timestampFrame=" + lastTimestampFramePosition
                + " queuedFrames=" + (lastTimestampFramePosition - framesRead)
                + " startNanos=" + startNanos
                + " endNanos=" + capturedEndElapsedRealtimeNanos);
        return new Capture(pcm, startNanos, capturedEndElapsedRealtimeNanos, channelCount);
    }

    /** Reads only through the first frame at or after a known boot-time boundary. */
    void drainThroughElapsedRealtimeNanos(long boundaryNanos) {
        if (closed || stopped) return;
        AudioTimestamp current = new AudioTimestamp();
        int status = record.getTimestamp(current, AudioTimestamp.TIMEBASE_BOOTTIME);
        if (status != AudioRecord.SUCCESS) {
            throw new IllegalStateException("DSP AudioRecord timestamp unavailable: " + status);
        }
        long deltaNanos = boundaryNanos - current.nanoTime;
        long targetFrame = current.framePosition
                - Math.floorDiv(-deltaNanos * sampleRate, 1_000_000_000L);
        long initialFrames = framesRead;
        long deadlineNanos = android.os.SystemClock.elapsedRealtimeNanos() + 150_000_000L;
        byte[] drainBuffer = new byte[readBytes];
        while (framesRead < targetFrame
                && android.os.SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
            int count = read(drainBuffer);
            if (count <= 0) break;
        }
        Log.i(TAG, "drained through live boundary target=" + targetFrame
                + " before=" + initialFrames + " after=" + framesRead
                + " overshoot=" + (framesRead - targetFrame));
    }

    /** Stops immediately; frames beyond the timestamped bridge boundary are intentionally ignored. */
    void stopAtBridgeBoundary() {
        if (closed || stopped) return;
        long startedNanos = android.os.SystemClock.elapsedRealtimeNanos();
        try {
            record.stop();
        } finally {
            stopped = true;
        }
        long stoppedNanos = android.os.SystemClock.elapsedRealtimeNanos();
        Log.i(TAG, "stopped at live bridge boundary stopMs="
                + ((stoppedNanos - startedNanos) / 1_000_000.0)
                + " frames=" + framesRead);
    }

    ByteBuffer timestampMetadata() {
        return timestampMetadata;
    }

    int timestampMetadataBytes() {
        return timestampMetadata.remaining();
    }

    String diagnosticTimeline() {
        StringBuilder result = new StringBuilder(128 + diagnosticReadCount * 96);
        result.append("index,clientFrame,frameCount,timestampStatus,timestampFrame,")
                .append("timestampNanos,readStartedNanos,readFinishedNanos,readMode\n");
        for (int index = 0; index < diagnosticReadCount; index++) {
            result.append(index).append(',')
                    .append(diagnosticClientFrame[index]).append(',')
                    .append(diagnosticFrameCount[index]).append(',')
                    .append(diagnosticTimestampStatus[index]).append(',')
                    .append(diagnosticTimestampFrame[index]).append(',')
                    .append(diagnosticTimestampNanos[index]).append(',')
                    .append(diagnosticReadStartedNanos[index]).append(',')
                    .append(diagnosticReadFinishedNanos[index]).append(',')
                    .append(diagnosticReadMode[index]).append('\n');
        }
        return result.toString();
    }

    private long framesToNanos(long frames) {
        return frames * 1_000_000_000L / sampleRate;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!stopped) {
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
        }
        record.release();
    }

    private static void invoke(Object target, String name,
            Class<?> parameterType, Object value) throws Exception {
        try {
            Method method = target.getClass().getMethod(name, parameterType);
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException(name + " failed: " + cause, cause);
        }
    }

    private static String rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getName() + ": " + current.getMessage();
    }

    /** Joins DSP history to ordinary live capture using only their shared boot-time clock. */
    static Capture composeWithLive(Capture history, WakewordLiveInput.Capture live,
            int sampleRate) {
        if (history == null) return live == null ? null : new Capture(
                live.pcm, live.startElapsedRealtimeNanos, live.endElapsedRealtimeNanos);
        if (live == null) return history;
        if (history.channelCount != 1) {
            throw new IllegalArgumentException("DSP history must be mono");
        }
        long historyFrames = history.pcm.length / PCM16_BYTES;
        long liveFrames = live.pcm.length / PCM16_BYTES;
        long deltaNanos = live.startElapsedRealtimeNanos
                - history.startElapsedRealtimeNanos;
        long historyFramesToBoundary = deltaNanos <= 0L ? 0L
                : (deltaNanos * sampleRate + 1_000_000_000L - 1L) / 1_000_000_000L;
        long keptHistoryFrames = Math.min(historyFrames, historyFramesToBoundary);
        long gapFrames = Math.max(0L, historyFramesToBoundary - historyFrames);
        long outputFrames = keptHistoryFrames + gapFrames + liveFrames;
        if (outputFrames > Integer.MAX_VALUE / PCM16_BYTES) {
            throw new IllegalArgumentException("Composed capture is too large");
        }
        byte[] output = Arrays.copyOf(history.pcm,
                (int) (outputFrames * PCM16_BYTES));
        int liveOffset = (int) ((keptHistoryFrames + gapFrames) * PCM16_BYTES);
        System.arraycopy(live.pcm, 0, output, liveOffset, live.pcm.length);
        long endNanos = history.startElapsedRealtimeNanos
                + outputFrames * 1_000_000_000L / sampleRate;
        return new Capture(output, history.startElapsedRealtimeNanos, endNanos);
    }

    static final class Capture {
        final byte[] pcm;
        final long startElapsedRealtimeNanos;
        final long endElapsedRealtimeNanos;
        final int channelCount;

        Capture(byte[] pcm, long startElapsedRealtimeNanos, long endElapsedRealtimeNanos) {
            this(pcm, startElapsedRealtimeNanos, endElapsedRealtimeNanos, 1);
        }

        Capture(byte[] pcm, long startElapsedRealtimeNanos, long endElapsedRealtimeNanos,
                int channelCount) {
            if (channelCount <= 0) {
                throw new IllegalArgumentException("channelCount must be positive");
            }
            this.pcm = pcm;
            this.startElapsedRealtimeNanos = startElapsedRealtimeNanos;
            this.endElapsedRealtimeNanos = endElapsedRealtimeNanos;
            this.channelCount = channelCount;
        }

        Capture selectedChannel(int channel) {
            if (channel < 0 || channel >= channelCount) {
                throw new IllegalArgumentException("Invalid selected channel " + channel
                        + " for " + channelCount + "-channel capture");
            }
            if (channelCount == 1) return this;
            int sourceFrameBytes = channelCount * PCM16_BYTES;
            int frameCount = pcm.length / sourceFrameBytes;
            byte[] mono = new byte[frameCount * PCM16_BYTES];
            for (int frame = 0; frame < frameCount; frame++) {
                int source = frame * sourceFrameBytes + channel * PCM16_BYTES;
                int destination = frame * PCM16_BYTES;
                mono[destination] = pcm[source];
                mono[destination + 1] = pcm[source + 1];
            }
            return new Capture(mono, startElapsedRealtimeNanos,
                    endElapsedRealtimeNanos);
        }
    }

    /** Fixed-size chronological byte ring; samples are never inspected. */
    static final class PcmRingBuffer {
        private final byte[] data;
        private int start;
        private int size;

        PcmRingBuffer(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            data = new byte[capacity];
        }

        void append(byte[] source, int offset, int length) {
            if (offset < 0 || length < 0 || offset > source.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length >= data.length) {
                System.arraycopy(source, offset + length - data.length, data, 0, data.length);
                start = 0;
                size = data.length;
                return;
            }
            int overflow = Math.max(0, size + length - data.length);
            start = (start + overflow) % data.length;
            size -= overflow;
            int end = (start + size) % data.length;
            int first = Math.min(length, data.length - end);
            System.arraycopy(source, offset, data, end, first);
            if (first < length) {
                System.arraycopy(source, offset + first, data, 0, length - first);
            }
            size += length;
        }

        byte[] snapshot() {
            byte[] result = new byte[size];
            int first = Math.min(size, data.length - start);
            System.arraycopy(data, start, result, 0, first);
            if (first < size) {
                System.arraycopy(data, 0, result, first, size - first);
            }
            return result;
        }
    }
}
