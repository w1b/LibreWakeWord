package org.librewakeword;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "LibreWakeWordStartup";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i(TAG, "received " + action);
        ShizukuAutoArm.init(context);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Replacing this package terminates the previous instrumentation process. Clear only
            // that old attempt: Shizuku's sticky callback may already have started a new one by
            // the time this receiver runs.
            WakewordState.clearArmingFromPreviousPackageVersion(context);
            ShizukuAutoArm.autoArmIfReady();
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ShizukuAutoArm.autoArmIfReady();
        }
    }
}
