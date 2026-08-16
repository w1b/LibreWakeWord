package org.librewakeword;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import com.google.speech.micro.GoogleHotwordData;
import com.google.speech.micro.GoogleHotwordRecognizer;

/** The stock local GoogleHotwordRecognizer TD-SID enrollment/adaptation loop. */
final class LocalVoiceMatchTrainer {
    static final int UTTERANCE_COUNT = 4;
    private static final int SAMPLE_RATE = 16_000;
    private static final long UTTERANCE_TIMEOUT_MILLIS = 8_000;
    private static final String[] PROMPTS = {
            "Hey Google", "OK Google", "Hey Google", "OK Google"
    };

    static {
        System.loadLibrary("google_speech_micro_jni");
    }

    interface Progress {
        void onListening(int index, int total, String phrase, int attempt);
        void onAccepted(int completed, int total, float adaptationScore);
    }

    static byte[] enroll(Context context, Progress progress) throws Exception {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is required for Local Voice Match");
        }
        byte[] hotwordModel = SecondStageModel.read(context);
        GoogleHotwordData data = new GoogleHotwordData(hotwordModel, SAMPLE_RATE, 1, 0);
        byte[] speakerModel = null;
        try {
            if (!data.isSpeakerVerificationEnabled()) {
                throw new IllegalStateException(
                        "The installed hotword model does not support local speaker verification");
            }
            for (int utterance = 0; utterance < UTTERANCE_COUNT; utterance++) {
                EnrollmentResult result = null;
                Throwable lastError = null;
                for (int attempt = 1; attempt <= 2 && result == null; attempt++) {
                    progress.onListening(utterance, UTTERANCE_COUNT, PROMPTS[utterance], attempt);
                    SystemClock.sleep(900);
                    try {
                        result = recordOne(data, speakerModel);
                    } catch (Throwable error) {
                        lastError = error;
                    }
                }
                if (result == null) {
                    throw new IllegalStateException("Could not recognize “" + PROMPTS[utterance]
                            + "”" + (lastError == null ? "" : ": " + lastError.getMessage()),
                            lastError);
                }
                speakerModel = result.model;
                progress.onAccepted(utterance + 1, UTTERANCE_COUNT, result.score);
                SystemClock.sleep(650);
            }
            if (!data.isSpeakerModelCompatible(speakerModel)) {
                throw new IllegalStateException("SODA rejected the completed local voice model");
            }
            return speakerModel;
        } finally {
            data.close();
        }
    }

    // enroll() checks the runtime permission immediately before entering this private loop. A
    // revocation race is still surfaced as SecurityException to the enrollment UI.
    @SuppressLint("MissingPermission")
    private static EnrollmentResult recordOne(GoogleHotwordData data, byte[] existingModel)
            throws Exception {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            throw new IllegalStateException("No usable 16 kHz microphone input");
        }
        int bufferBytes = Math.max(minimum * 2, Math.max(3_200, data.idealBufferBytes() * 4));
        AudioRecord audio = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .build();
        if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
            audio.release();
            throw new IllegalStateException("Could not initialize the microphone");
        }
        GoogleHotwordRecognizer recognizer = existingModel == null
                ? new GoogleHotwordRecognizer(data)
                : new GoogleHotwordRecognizer(data, new byte[][]{existingModel});
        try {
            recognizer.setEnrollmentMode(true);
            byte[] chunk = new byte[Math.max(640, data.idealBufferBytes())];
            long deadline = SystemClock.elapsedRealtime() + UTTERANCE_TIMEOUT_MILLIS;
            boolean detected = false;
            audio.startRecording();
            while (!detected && SystemClock.elapsedRealtime() < deadline) {
                int count = audio.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    throw new IllegalStateException("Microphone read failed: " + count);
                }
                if (count == 0) continue;
                if ((count & 1) != 0) count--;
                GoogleHotwordRecognizer.GoogleHotwordResult result =
                        recognizer.process(chunk, 0, count);
                detected = result != null && result.hotwordDetected();
            }
            if (!detected) {
                throw new IllegalStateException("wake phrase not detected before timeout");
            }
            if (existingModel == null) {
                byte[] created = recognizer.newSpeakerFromProcessedAudio();
                if (created == null || created.length == 0) {
                    throw new IllegalStateException("speaker model creation failed");
                }
                return new EnrollmentResult(created, Float.NaN);
            }
            GoogleHotwordRecognizer.AdaptSpeakerModelResult adapted =
                    recognizer.adaptSpeakerModel(0);
            if (adapted == null || adapted.getSpeakerModel() == null
                    || adapted.getSpeakerModel().length == 0) {
                throw new IllegalStateException("speaker model adaptation failed");
            }
            return new EnrollmentResult(adapted.getSpeakerModel(), adapted.getModelScore());
        } finally {
            if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audio.stop();
            }
            audio.release();
            recognizer.close();
        }
    }

    private static final class EnrollmentResult {
        final byte[] model;
        final float score;

        EnrollmentResult(byte[] model, float score) {
            this.model = model;
            this.score = score;
        }
    }

    private LocalVoiceMatchTrainer() {
        throw new AssertionError();
    }
}
