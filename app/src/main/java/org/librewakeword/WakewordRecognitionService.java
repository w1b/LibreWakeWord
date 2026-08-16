package org.librewakeword;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionService;

public class WakewordRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
