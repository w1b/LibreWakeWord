package android.media.soundtrigger;

import android.media.AudioFormat;

// Compile-time-only stub; real hidden framework class is used at runtime.
public class SoundTriggerDetector {

    public static final int RECOGNITION_FLAG_NONE = 0;
    public static final int RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO = 0x1;
    public static final int RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS = 0x2;
    public static final int RECOGNITION_FLAG_ENABLE_AUDIO_ECHO_CANCELLATION = 0x4;
    public static final int RECOGNITION_FLAG_ENABLE_AUDIO_NOISE_SUPPRESSION = 0x8;
    public static final int RECOGNITION_FLAG_RUN_IN_BATTERY_SAVER = 0x10;

    public boolean startRecognition(int recognitionFlags) { return false; }
    public boolean stopRecognition() { return false; }

    public abstract static class Callback {
        public abstract void onAvailabilityChanged(int status);
        public abstract void onDetected(EventPayload eventPayload);
        public abstract void onError();
        public abstract void onRecognitionPaused();
        public abstract void onRecognitionResumed();
    }

    public static class EventPayload {
        public AudioFormat getCaptureAudioFormat() { return null; }
        public byte[] getTriggerAudio() { return null; }
    }
}
