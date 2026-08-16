package android.service.voice;

import android.media.AudioFormat;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;

// Compile-time-only stub; the framework implementation is used on device.
public final class HotwordAudioStream {
    public AudioFormat getAudioFormat() { throw new RuntimeException("stub"); }
    public ParcelFileDescriptor getAudioStreamParcelFileDescriptor() {
        throw new RuntimeException("stub");
    }
    public byte[] getInitialAudio() { return new byte[0]; }
    public PersistableBundle getMetadata() { throw new RuntimeException("stub"); }

    public static final class Builder {
        public Builder(AudioFormat format, ParcelFileDescriptor stream) {}
        public Builder setInitialAudio(byte[] audio) { return this; }
        public Builder setMetadata(PersistableBundle metadata) { return this; }
        public HotwordAudioStream build() { throw new RuntimeException("stub"); }
    }
}
