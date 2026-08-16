package android.media.soundtrigger_middleware;

import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.SoundModel;
import android.os.IBinder;
import android.os.RemoteException;

// Compile-time-only stub matching the real hidden AIDL interface; not packaged into the APK.
public interface ISoundTriggerModule {
    int loadModel(SoundModel model) throws RemoteException;
    int loadPhraseModel(PhraseSoundModel model) throws RemoteException;
    void unloadModel(int modelHandle) throws RemoteException;
    IBinder startRecognition(int modelHandle, RecognitionConfig config) throws RemoteException;
    void stopRecognition(int modelHandle) throws RemoteException;
    void detach() throws RemoteException;
}
