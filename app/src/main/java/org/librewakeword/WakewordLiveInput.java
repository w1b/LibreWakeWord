package org.librewakeword;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded ordinary microphone capture bridging DSP history to a consumer-owned recorder.
 *
 * <p>The AudioRecord is constructed while the detector is idle, activated only after SODA
 * accepts a wake word, and released as soon as the consumer claims the microphone.</p>
 */
final class WakewordLiveInput implements AutoCloseable {
    private static final String TAG = "WakewordLiveInput";
    static final int SAMPLE_RATE = 16_000;
    private static final int PCM16_BYTES = 2;
    private static final int READ_MILLIS = 2;
    private static final int READ_BYTES = SAMPLE_RATE * PCM16_BYTES * READ_MILLIS / 1_000;
    // Audio policy can report a newly activated client as unsilenced one HAL block before routed
    // samples reach AudioRecord. DSP history overlaps this bounded activation period, so begin the
    // ordinary-clock segment only after two complete, continuously-unsilenced reads.
    private static final int ACTIVATION_SETTLE_MILLIS = 20;
    private static final int ACTIVATION_SETTLE_READS =
            ACTIVATION_SETTLE_MILLIS / READ_MILLIS;
    private static final int MAX_CAPTURE_SECONDS = 5;

    private final AudioRecord record;
    private final AudioManager audioManager;
    private final SodaDspInput.PcmRingBuffer capturedPcm =
            new SodaDspInput.PcmRingBuffer(SAMPLE_RATE * PCM16_BYTES * MAX_CAPTURE_SECONDS);
    private final Object stateLock = new Object();
    private final CountDownLatch firstRealFrame = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private final AudioManager.AudioRecordingCallback recordingCallback =
            new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(
                        List<AudioRecordingConfiguration> configurations) {
                    updateSilenced(configurations);
                }
            };

    private Thread pump;
    // Written by AudioManager's callback thread and read by the audio pump.
    private volatile Boolean clientSilenced;
    private long clientFramesRead;
    private long capturedStartNanos = -1L;
    private long capturedEndNanos = -1L;
    private long activationClockCorrectionNanos = -1L;
    private int continuouslyUnsilencedReads;
    private boolean started;
    private boolean closed;
    private Throwable failure;

    @SuppressLint("MissingPermission")
    static WakewordLiveInput create(Context context) {
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            throw new IllegalStateException("Invalid live AudioRecord minimum: " + minimum);
        }
        AudioRecord record = new AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minimum, 4_096))
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("Live AudioRecord was not initialized");
        }
        return new WakewordLiveInput(record,
                context.getSystemService(AudioManager.class));
    }

    private WakewordLiveInput(AudioRecord record, AudioManager audioManager) {
        this.record = record;
        this.audioManager = audioManager;
    }

    @SuppressLint("MissingPermission")
    void start() {
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("Live input is closed");
            if (started) return;
            started = true;
        }
        try {
            audioManager.registerAudioRecordingCallback(recordingCallback, null);
            pump = new Thread(this::pump, "wakeword-live-prefix");
            pump.start();
        } catch (Throwable error) {
            synchronized (stateLock) {
                failure = error;
            }
            firstRealFrame.countDown();
            close();
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not start live AudioRecord", error);
        }
    }

    boolean awaitFirstRealFrame(long timeoutMillis) throws InterruptedException {
        boolean ready = firstRealFrame.await(timeoutMillis, TimeUnit.MILLISECONDS);
        synchronized (stateLock) {
            if (failure != null) {
                throw new IllegalStateException("Live microphone capture failed", failure);
            }
        }
        return ready;
    }

    Capture snapshot() {
        synchronized (stateLock) {
            byte[] pcm = capturedPcm.snapshot();
            if (pcm.length == 0 || capturedStartNanos < 0L || capturedEndNanos < 0L) {
                return null;
            }
            return new Capture(pcm, capturedStartNanos, capturedEndNanos);
        }
    }

    private void pump() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] buffer = new byte[READ_BYTES];
        long activationReadStartedNanos = -1L;
        try {
            // Starting and reading on one audio-priority thread keeps scheduler delay out of the
            // activation clock. start() remains non-blocking while failures are surfaced through
            // awaitFirstRealFrame().
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Live AudioRecord did not start");
            }
            refreshSilenced();
            while (!stopping.get()) {
                Boolean silencedAtStart = clientSilenced;
                long readStartedNanos = android.os.SystemClock.elapsedRealtimeNanos();
                if (activationReadStartedNanos < 0L) {
                    activationReadStartedNanos = readStartedNanos;
                }
                int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) {
                    if (!stopping.get()) throw new IOException("AudioRecord.read returned " + count);
                    break;
                }
                int completeBytes = count - count % PCM16_BYTES;
                if (completeBytes == 0) continue;
                long chunkStartFrame = clientFramesRead;
                long chunkFrames = completeBytes / PCM16_BYTES;
                clientFramesRead += chunkFrames;
                if (clientSilenced == null) refreshSilenced();
                Boolean silencedAtEnd = clientSilenced;
                int timestampStatus = record.getTimestamp(
                        timestamp, AudioTimestamp.TIMEBASE_BOOTTIME);
                if (activationClockCorrectionNanos < 0L
                        && timestampStatus == AudioRecord.SUCCESS) {
                    activationClockCorrectionNanos = activationClockCorrectionNanos(
                            activationReadStartedNanos,
                            timestamp.nanoTime,
                            0L,
                            timestamp.framePosition,
                            SAMPLE_RATE);
                    Log.i(TAG, "activation clock correctionNanos="
                            + activationClockCorrectionNanos);
                }
                if (!Boolean.FALSE.equals(silencedAtStart)
                        || !Boolean.FALSE.equals(silencedAtEnd)) {
                    continuouslyUnsilencedReads = 0;
                    continue;
                }
                continuouslyUnsilencedReads++;
                if (continuouslyUnsilencedReads <= ACTIVATION_SETTLE_READS) {
                    continue;
                }
                if (timestampStatus != AudioRecord.SUCCESS) continue;
                long chunkStartNanos = timestamp.nanoTime
                        + framesToNanos(chunkStartFrame - timestamp.framePosition)
                        + activationClockCorrectionNanos;
                synchronized (stateLock) {
                    if (capturedStartNanos < 0L) capturedStartNanos = chunkStartNanos;
                    capturedPcm.append(buffer, 0, completeBytes);
                    capturedEndNanos = chunkStartNanos + framesToNanos(chunkFrames);
                }
                firstRealFrame.countDown();
            }
        } catch (Throwable error) {
            if (!stopping.get()) {
                synchronized (stateLock) {
                    failure = error;
                }
                firstRealFrame.countDown();
                Log.e(TAG, "live prefix capture failed", error);
            }
        }
    }

    private void refreshSilenced() {
        updateSilenced(audioManager.getActiveRecordingConfigurations());
    }

    private void updateSilenced(List<AudioRecordingConfiguration> configurations) {
        for (AudioRecordingConfiguration configuration : configurations) {
            if (configuration.getClientAudioSessionId() == record.getAudioSessionId()) {
                Boolean previous = clientSilenced;
                clientSilenced = configuration.isClientSilenced();
                if (!clientSilenced.equals(previous)) {
                    Log.i(TAG, "Audio policy clientSilenced=" + clientSilenced
                            + " session=" + record.getAudioSessionId());
                }
                return;
            }
        }
    }

    private static long framesToNanos(long frames) {
        return frames * 1_000_000_000L / SAMPLE_RATE;
    }

    /**
     * Corrects the initial AudioTimestamp for capture-route activation.
     *
     * <p>Some HALs start the AudioRecord frame clock before microphone samples reach that route.
     * The first blocking read spans both that activation interval and frames reported after
     * capture starts. Subtracting the latter leaves the route-specific activation delay without
     * inspecting PCM or relying on a device constant. AudioRecord is started and read on the same
     * audio-priority thread so unrelated thread scheduling cannot shorten the correction.</p>
     */
    static long activationClockCorrectionNanos(long readStartedNanos,
            long timestampNanos, long clientFrame, long timestampFrame, int sampleRate) {
        if (sampleRate <= 0 || timestampNanos <= readStartedNanos) return 0L;
        long timestampedFrames = Math.max(0L, timestampFrame - clientFrame);
        long timestampedNanos = timestampedFrames * 1_000_000_000L / sampleRate;
        return Math.max(0L, timestampNanos - readStartedNanos - timestampedNanos);
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
        }
        stopping.set(true);
        if (started) {
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
        }
        Thread thread = pump;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(250L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback);
        } catch (Throwable ignored) {
        }
        record.release();
    }

    static final class Capture {
        final byte[] pcm;
        final long startElapsedRealtimeNanos;
        final long endElapsedRealtimeNanos;

        Capture(byte[] pcm, long startElapsedRealtimeNanos, long endElapsedRealtimeNanos) {
            this.pcm = pcm;
            this.startElapsedRealtimeNanos = startElapsedRealtimeNanos;
            this.endElapsedRealtimeNanos = endElapsedRealtimeNanos;
        }
    }
}
