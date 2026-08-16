package android.hardware.soundtrigger;

import java.util.Locale;
import java.util.UUID;

// Compile-time-only stub matching the real (hidden/@SystemApi) framework class.
// Not packaged into the final APK; at runtime the real android.hardware.soundtrigger.SoundTrigger
// from the boot classpath is used instead.
public class SoundTrigger {

    public static final int RECOGNITION_MODE_VOICE_TRIGGER = 0x1;
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION = 0x2;
    public static final int RECOGNITION_MODE_USER_AUTHENTICATION = 0x4;
    public static final int RECOGNITION_MODE_GENERIC = 0x8;

    public static final int STATUS_OK = 0;

    public static class Keyphrase {
        public Keyphrase(int id, int recognitionModes, Locale locale, String text, int[] users) {}
    }

    public static class SoundModel {
        public static final int TYPE_KEYPHRASE = 0;
        public static final int TYPE_GENERIC_SOUND = 1;
        public static final int TYPE_UNKNOWN = -1;

        public SoundModel(UUID uuid, UUID vendorUuid, int type, byte[] data, int version) {}
    }

    public static class KeyphraseSoundModel extends SoundModel {
        public KeyphraseSoundModel(UUID uuid, UUID vendorUuid, byte[] data, Keyphrase[] keyphrases, int version) {
            super(uuid, vendorUuid, TYPE_KEYPHRASE, data, version);
        }
    }

    public static class GenericSoundModel extends SoundModel {
        public GenericSoundModel(UUID uuid, UUID vendorUuid, byte[] data, int version) {
            super(uuid, vendorUuid, TYPE_GENERIC_SOUND, data, version);
        }
    }

    public static class ModuleProperties {
    }
}
