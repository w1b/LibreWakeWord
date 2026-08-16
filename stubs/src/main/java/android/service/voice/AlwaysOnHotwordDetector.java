package android.service.voice;

public class AlwaysOnHotwordDetector {
    public static final int RECOGNITION_FLAG_NONE = 0;
    public static final int RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO = 1;
    public static final int RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS = 2;

    public static final int STATE_INVALID = -3;
    public static final int STATE_KEYPHRASE_UNSUPPORTED = -2;
    public static final int STATE_KEYPHRASE_UNENROLLED = 1;
    public static final int STATE_KEYPHRASE_ENROLLED = 2;

    public boolean startRecognition() {
        throw new RuntimeException("stub");
    }

    public boolean startRecognition(int recognitionFlags) {
        throw new RuntimeException("stub");
    }

    public boolean stopRecognition() {
        throw new RuntimeException("stub");
    }

    public void destroy() {
        throw new RuntimeException("stub");
    }

    public abstract static class Callback {
        public abstract void onAvailabilityChanged(int status);
        public abstract void onDetected(EventPayload eventPayload);
        public abstract void onError();
        public void onFailure(SoundTriggerFailure soundTriggerFailure) {}
        public void onHotwordDetectionServiceInitialized(int status) {}
        public void onHotwordDetectionServiceRestarted() {}
        public abstract void onRecognitionPaused();
        public abstract void onRecognitionResumed();
        public void onRejected(HotwordRejectedResult result) {}
    }

    public static class EventPayload {
        public HotwordDetectedResult getHotwordDetectedResult() { throw new RuntimeException("stub"); }
    }
}
