package android.media.soundtrigger_middleware;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

// Compile-time-only stub matching the real hidden AIDL interface; not packaged into the APK.
// At runtime the real android.media.soundtrigger_middleware.ISoundTriggerCallback$Stub is used,
// and our subclass's overrides bind to it by matching method signatures exactly.
public interface ISoundTriggerCallback extends IInterface {
    void onRecognition(int modelHandle, RecognitionEventSys event, int captureSession) throws RemoteException;
    void onPhraseRecognition(int modelHandle, PhraseRecognitionEventSys event, int captureSession) throws RemoteException;
    void onResourcesAvailable() throws RemoteException;
    void onModelUnloaded(int modelHandle) throws RemoteException;
    void onModuleDied() throws RemoteException;

    abstract class Stub extends Binder implements ISoundTriggerCallback {
        public static ISoundTriggerCallback asInterface(IBinder obj) { return null; }
        @Override public IBinder asBinder() { return this; }
    }
}
