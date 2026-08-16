package org.librewakeword;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Auto-launches the arming instrumentation via a Shizuku daemon UserService whenever Shizuku's
 * binder becomes available (app start, or Shizuku (re)started after a reboot), so re-arming
 * after a reboot doesn't require typing an adb command by hand -- only re-starting Shizuku
 * itself is manual.
 *
 * Goes through HotwordUserService.launchInstrumentation() (a .daemon(true) Shizuku UserService)
 * rather than Shizuku.newProcess() directly: newProcess()'s spawned child was empirically dying
 * within milliseconds, before the target app's UiAutomationConnection could be used. The daemon
 * UserService is a stable long-lived host process for the "am instrument -w" child instead.
 */
public class ShizukuAutoArm {
    private static final String TAG = "HotwordShizukuArm";
    private static final AtomicBoolean launching = new AtomicBoolean(false);
    private static volatile boolean listenerRegistered;
    private static volatile Context appContext;

    public static synchronized void init(Context context) {
        appContext = context.getApplicationContext();
        if (isAutoArmDisabledForDebug(appContext)) {
            Log.i(TAG, "auto-arm disabled by global debug setting");
            return;
        }
        if (listenerRegistered) {
            return;
        }
        if (HotwordInstrumentation.isInstrumentedProcess) {
            Log.i(TAG, "this process is the instrumentation target, not registering auto-arm");
            return;
        }
        listenerRegistered = true;
        Shizuku.addBinderReceivedListenerSticky(() -> onShizukuAvailable(false));
        Shizuku.addBinderDeadListener(() -> Log.i(TAG, "Shizuku binder dead"));
    }

    /** Manual re-arm entry point for the UI (e.g. an "Arm" button). Unlike the automatic
     * binder-received trigger, this always clears the user-stopped flag first: an explicit tap
     * means the user wants it armed regardless of a prior Stop. Without this, evaluate() would
     * keep returning STOPPED and onShizukuAvailable() below would keep bailing out. */
    public static void armNow() {
        Context ctx = appContext;
        if (ctx != null) {
            WakewordState.setUserStopped(ctx, false);
        }
        onShizukuAvailable(true);
    }

    /**
     * Re-evaluate automatic arming after boot or package replacement. This deliberately obeys
     * the debug auto-arm switch; only an explicit UI Arm tap may bypass that switch.
     */
    public static void autoArmIfReady() {
        onShizukuAvailable(false);
    }

    private static void onShizukuAvailable(boolean explicitlyRequested) {
        if (!explicitlyRequested && isAutoArmDisabledForDebug(appContext)) {
            Log.i(TAG, "auto-arm disabled by global debug setting");
            return;
        }
        if (WakewordVoiceInteractionService.isRecognitionStarted()) {
            Log.i(TAG, "already armed, skipping auto-launch");
            return;
        }
        Context ctx = appContext;
        if (ctx == null) {
            Log.i(TAG, "no application context yet");
            return;
        }
        if (!Shizuku.pingBinder()) {
            Log.i(TAG, "Shizuku not running yet");
            return;
        }
        int shizukuUid;
        try {
            shizukuUid = Shizuku.getUid();
        } catch (Throwable error) {
            Log.i(TAG, "Shizuku binder is not ready yet", error);
            return;
        }
        Log.i(TAG, (explicitlyRequested ? "manual arm requested" : "Shizuku binder available")
                + ", uid=" + shizukuUid);
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission not granted yet -- open the app once to grant it");
            return;
        }
        WakewordState state = WakewordState.evaluate(ctx);
        if (state.code != WakewordState.Code.READY_TO_ARM
                && state.code != WakewordState.Code.READY_TO_RESTORE_VIS) {
            Log.i(TAG, "not ready to arm: " + state.label());
            return;
        }
        if (!launching.compareAndSet(false, true)) {
            Log.i(TAG, "auto-launch already in flight, skipping");
            return;
        }
        WakewordState.setArming(ctx, true);
        bindAndLaunch();
    }

    private static void bindAndLaunch() {
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName("org.librewakeword", HotwordUserService.class.getName()))
                .daemon(true)
                .processNameSuffix("hotword")
                .debuggable(false)
                .version(19);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IHotwordUserService svc = IHotwordUserService.Stub.asInterface(binder);
                    svc.launchInstrumentation();
                    Log.i(TAG, "launchInstrumentation() sent via daemon UserService");
                } catch (Throwable t) {
                    Log.e(TAG, "launchInstrumentation call failed", t);
                    WakewordState.setError(appContext, "launchInstrumentation failed: " + t.getMessage());
                } finally {
                    launching.set(false);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "auto-arm UserService disconnected");
            }
        };
        try {
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            Log.e(TAG, "bindUserService failed", t);
            WakewordState.setError(appContext, "bindUserService failed: " + t.getMessage());
            launching.set(false);
        }
    }

    private static boolean isAutoArmDisabledForDebug(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    "librewakeword_disable_auto_arm", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private ShizukuAutoArm() {
    }
}
