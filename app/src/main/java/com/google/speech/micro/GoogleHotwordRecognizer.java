package com.google.speech.micro;

public class GoogleHotwordRecognizer {
    private GoogleHotwordData hotwordData;
    private long nativeHotwordRecognizer;

    public GoogleHotwordRecognizer(GoogleHotwordData hotwordData) {
        this.hotwordData = hotwordData;
        nativeHotwordRecognizer = nativeNew(hotwordData);
    }

    public GoogleHotwordRecognizer(GoogleHotwordData hotwordData, byte[][] speakerModels) {
        this.hotwordData = hotwordData;
        nativeHotwordRecognizer = nativeNew(hotwordData, speakerModels);
    }

    public GoogleHotwordResult process(byte[] samples) {
        return process(samples, 0, samples.length);
    }

    public GoogleHotwordResult process(byte[] samples, int offset, int length) {
        if ((offset & 1) != 0 || (length & 1) != 0) {
            throw new IllegalArgumentException("Samples must be 2-bytes.");
        }
        return nativeProcess(nativeHotwordRecognizer, samples, offset, length);
    }

    public void reset() {
        nativeReset(nativeHotwordRecognizer);
    }

    public void setEnrollmentMode(boolean enabled) {
        nativeSetEnrollmentMode(nativeHotwordRecognizer, enabled);
    }

    public byte[] newSpeakerFromProcessedAudio() {
        return nativeNewSpeakerFromProcessedAudio(nativeHotwordRecognizer);
    }

    public AdaptSpeakerModelResult adaptSpeakerModel(int speakerIndex) {
        return nativeAdaptSpeakerModel(nativeHotwordRecognizer, speakerIndex);
    }

    public synchronized void close() {
        if (nativeHotwordRecognizer != 0L) {
            nativeClose(nativeHotwordRecognizer);
            nativeHotwordRecognizer = 0L;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static native long nativeNew(GoogleHotwordData hotwordData);
    private static native long nativeNew(GoogleHotwordData hotwordData, byte[][] speakerModels);
    private static native GoogleHotwordResult nativeProcess(long nativeHotwordRecognizer,
            byte[] samples, int offset, int length);
    private static native void nativeReset(long nativeHotwordRecognizer);
    private static native void nativeSetEnrollmentMode(long nativeHotwordRecognizer,
            boolean enabled);
    private static native byte[] nativeNewSpeakerFromProcessedAudio(long nativeHotwordRecognizer);
    private static native AdaptSpeakerModelResult nativeAdaptSpeakerModel(
            long nativeHotwordRecognizer, int speakerIndex);
    private static native void nativeClose(long nativeHotwordRecognizer);

    public static class AdaptSpeakerModelResult {
        private float modelScore;
        private byte[] speakerModel;

        public float getModelScore() {
            return modelScore;
        }

        public byte[] getSpeakerModel() {
            return speakerModel;
        }
    }

    public static class GoogleHotwordResult {
        private int bytesConsumed;
        private boolean hotwordDetected;
        private HotwordResult[] hotwordResults;
        private SpeakerResult speakerResult;
        private boolean speechDetected;

        public int getBytesConsumed() {
            return bytesConsumed;
        }

        public HotwordResult[] getHotwordResults() {
            return hotwordResults;
        }

        public SpeakerResult getSpeakerResult() {
            return speakerResult;
        }

        public boolean hotwordDetected() {
            return hotwordDetected;
        }

        public boolean speechDetected() {
            return speechDetected;
        }

        public static class HotwordResult {
            private float backgroundPower;
            private boolean hotwordEndHint;
            private long hotwordEndTime;
            private boolean hotwordFired;
            private boolean hotwordLowConfidence;
            private float hotwordPower;
            private float hotwordScore;
            private long hotwordStartTime;
            private String phrase;

            public float getBackgroundPower() {
                return backgroundPower;
            }

            public long getHotwordEndTime() {
                return hotwordEndTime;
            }

            public float getHotwordPower() {
                return hotwordPower;
            }

            public float getHotwordScore() {
                return hotwordScore;
            }

            public long getHotwordStartTime() {
                return hotwordStartTime;
            }

            public String getPhrase() {
                return phrase;
            }

            public boolean hotwordEndHint() {
                return hotwordEndHint;
            }

            public boolean hotwordFired() {
                return hotwordFired;
            }

            public boolean hotwordLowConfidence() {
                return hotwordLowConfidence;
            }
        }

        public static class SpeakerResult {
            private float adaptationThreshold;
            private boolean authenticated;
            private float authenticationThreshold;
            private int bestSpeaker;
            private ConfidenceInterval[] confidenceIntervals;
            private boolean shouldAdapt;
            private boolean speakerDetected;
            private float[] speakerScores;
            private float threshold;

            public boolean hasSpeakerModel() {
                return bestSpeaker >= 0;
            }

            public int getBestSpeaker() {
                return bestSpeaker;
            }

            public float getThreshold() {
                return threshold;
            }

            public boolean isAuthenticated() {
                return authenticated;
            }

            public boolean isSpeakerDetected() {
                return speakerDetected;
            }
        }

        public static class ConfidenceInterval {
            private boolean fired;
            private int label;
            private float left;
            private float right;
        }
    }
}
