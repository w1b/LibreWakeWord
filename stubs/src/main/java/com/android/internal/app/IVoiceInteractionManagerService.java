package com.android.internal.app;

import android.hardware.soundtrigger.SoundTrigger;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

// Compile-time-only stub; the real framework interface is used at runtime.
public interface IVoiceInteractionManagerService extends IInterface {
    int updateKeyphraseSoundModel(SoundTrigger.KeyphraseSoundModel model) throws RemoteException;
    int deleteKeyphraseSoundModel(int keyphraseId, String bcp47Locale) throws RemoteException;

    abstract class Stub extends Binder implements IVoiceInteractionManagerService {
        public static IVoiceInteractionManagerService asInterface(IBinder obj) { return null; }
        @Override public IBinder asBinder() { return this; }
    }
}
