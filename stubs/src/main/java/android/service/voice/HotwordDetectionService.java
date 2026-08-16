package android.service.voice;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.SharedMemory;

import java.util.function.IntConsumer;

public abstract class HotwordDetectionService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDetect(AlwaysOnHotwordDetector.EventPayload eventPayload, long timeoutMillis,
            Callback callback) {
        throw new UnsupportedOperationException("stub");
    }

    public void onUpdateState(PersistableBundle options, SharedMemory sharedMemory,
            long callbackTimeoutMillis, IntConsumer statusCallback) {}

    public void onStopDetection() {}

    public static final class Callback {
        public void onDetected(HotwordDetectedResult result) {}
        public void onRejected(HotwordRejectedResult result) {}
    }
}
