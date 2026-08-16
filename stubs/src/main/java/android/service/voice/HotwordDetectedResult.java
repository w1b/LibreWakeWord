package android.service.voice;

import android.os.PersistableBundle;
import java.util.Collections;
import java.util.List;

public final class HotwordDetectedResult {
    private final int hotwordPhraseId;

    private HotwordDetectedResult(int hotwordPhraseId) {
        this.hotwordPhraseId = hotwordPhraseId;
    }

    public int getHotwordPhraseId() {
        return hotwordPhraseId;
    }

    public PersistableBundle getExtras() { return new PersistableBundle(); }
    public List<HotwordAudioStream> getAudioStreams() { return Collections.emptyList(); }

    public static final class Builder {
        private int hotwordPhraseId;

        public Builder setHotwordPhraseId(int hotwordPhraseId) {
            this.hotwordPhraseId = hotwordPhraseId;
            return this;
        }

        public Builder setExtras(PersistableBundle extras) { return this; }
        public Builder setAudioStreams(List<HotwordAudioStream> audioStreams) { return this; }

        public HotwordDetectedResult build() {
            return new HotwordDetectedResult(hotwordPhraseId);
        }
    }
}
