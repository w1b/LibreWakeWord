package org.librewakeword;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Starts the arming session through a Shizuku user service: on an explicit Arm tap, and
 * automatically whenever Shizuku's binder becomes available (app start, or Shizuku started
 * after a reboot) unless the user stopped arming.
 *
 * <p>The user service is a daemon rather than a {@code Shizuku.newProcess()} child because the
 * `am instrument -w` process it starts must stay alive for as long as the detector is armed.
 */
public final class ShizukuAutoArm {
    private static final String TAG = "HotwordShizukuArm";
    private static final AtomicBoolean launching = new AtomicBoolean(false);
    private static volatile boolean listenerRegistered;
    private static volatile Context appContext;

    public static synchronized void init(Context context) {
        appContext = context.getApplicationContext();
        // The instrumented process is the arming session itself.
        if (listenerRegistered || HotwordInstrumentation.isInstrumentedProcess) {
            return;
        }
        listenerRegistered = true;
        Shizuku.addBinderReceivedListenerSticky(() -> arm(false));
    }

    /** Arm button: clears a previous user stop. */
    public static void armNow() {
        Context ctx = appContext;
        if (ctx != null) {
            WakewordState.setUserStopped(ctx, false);
            WakewordState.clearError(ctx);
        }
        arm(true);
    }

    /** Boot and package replacement: arms only if the user hasn't stopped arming. */
    public static void autoArmIfReady() {
        arm(false);
    }

    private static void arm(boolean explicit) {
        Context ctx = appContext;
        if (ctx == null || WakewordVoiceInteractionService.isArmed()) {
            return;
        }
        if (!Shizuku.pingBinder()
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku not running or not granted");
            return;
        }
        WakewordState state = WakewordState.evaluate(ctx);
        if (state.code != WakewordState.Code.READY_TO_ARM) {
            Log.i(TAG, "not arming: " + state.label());
            return;
        }
        if (!launching.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, explicit ? "arming on request" : "arming automatically");
        WakewordState.setArming(ctx, true);
        // Starting instrumentation kills this process, so reopen the UI if it was visible.
        boolean openUi = explicit || isForeground();
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), HotwordUserService.class.getName()))
                .daemon(true)
                .processNameSuffix("hotword")
                .debuggable(false)
                // Bump whenever HotwordUserService changes so Shizuku replaces the daemon.
                .version(20);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IHotwordUserService.Stub.asInterface(binder).launchInstrumentation(openUi);
                } catch (Throwable t) {
                    Log.e(TAG, "launchInstrumentation failed", t);
                    WakewordState.setError(ctx, "Could not start arming: " + t.getMessage());
                } finally {
                    launching.set(false);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        try {
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            Log.e(TAG, "bindUserService failed", t);
            WakewordState.setError(ctx, "Could not reach Shizuku: " + t.getMessage());
            launching.set(false);
        }
    }

    private static boolean isForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private ShizukuAutoArm() {
    }
}
