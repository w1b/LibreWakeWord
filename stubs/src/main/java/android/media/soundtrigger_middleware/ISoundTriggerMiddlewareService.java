package android.media.soundtrigger_middleware;

import android.media.permission.Identity;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

// Compile-time-only stub matching the real hidden AIDL interface; not packaged into the APK.
public interface ISoundTriggerMiddlewareService extends IInterface {
    SoundTriggerModuleDescriptor[] listModulesAsOriginator(Identity identity) throws RemoteException;
    ISoundTriggerModule attachAsOriginator(int handle, Identity identity, ISoundTriggerCallback callback) throws RemoteException;

    abstract class Stub extends Binder implements ISoundTriggerMiddlewareService {
        public static ISoundTriggerMiddlewareService asInterface(IBinder obj) { return null; }
        @Override public IBinder asBinder() { return this; }
    }
}
