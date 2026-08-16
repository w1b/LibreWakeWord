package android.service.voice;

public final class HotwordRejectedResult {
    private final int confidenceLevel;

    private HotwordRejectedResult(int confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public int getConfidenceLevel() {
        return confidenceLevel;
    }

    public static final class Builder {
        private int confidenceLevel;

        public Builder setConfidenceLevel(int confidenceLevel) {
            this.confidenceLevel = confidenceLevel;
            return this;
        }

        public HotwordRejectedResult build() {
            return new HotwordRejectedResult(confidenceLevel);
        }
    }
}
