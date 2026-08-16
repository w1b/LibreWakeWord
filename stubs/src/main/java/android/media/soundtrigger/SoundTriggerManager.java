package android.media.soundtrigger;

import android.hardware.soundtrigger.SoundTrigger;
import android.os.Handler;
import java.util.UUID;

// Compile-time-only stub; real hidden framework class is used at runtime.
public class SoundTriggerManager {
    public int loadSoundModel(SoundTrigger.SoundModel soundModel) { return 0; }
    public int unloadSoundModel(UUID soundModelId) { return 0; }
    public int stopRecognition(UUID soundModelId) { return 0; }
    public int getModelState(UUID soundModelId) { return 0; }
    public void updateModel(Model model) { }
    public SoundTrigger.ModuleProperties getModuleProperties() { return null; }

    public SoundTriggerDetector createSoundTriggerDetector(
            UUID soundModelId, SoundTriggerDetector.Callback callback, Handler handler) {
        return null;
    }

    public static class Model {
        public static Model create(UUID modelUuid, UUID vendorUuid, byte[] data, int version) {
            return null;
        }
    }
}
