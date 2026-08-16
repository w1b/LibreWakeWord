package org.librewakeword;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.provider.Settings;

import rikka.shizuku.Shizuku;

final class WakewordState {
    private static final long ARMING_STALE_AFTER_MS = 45_000L;
    private static final String PREFS = "wakeword_state";
    private static final String KEY_USER_STOPPED = "user_stopped";
    private static final String KEY_ARMING = "arming";
    private static final String KEY_ARMING_STARTED_AT = "arming_started_at";

    enum Code {
        NEEDS_SHIZUKU,
        NEEDS_SHIZUKU_PERMISSION,
        NEEDS_VIS,
        READY_TO_RESTORE_VIS,
        NEEDS_MODEL,
        READY_TO_ARM,
        ARMING,
        LISTENING,
        STOPPED,
        ERROR
    }

    private static volatile String lastError = "";

    final Code code;
    final String detail;
    final ModelConfig modelConfig;

    private WakewordState(Code code, String detail, ModelConfig modelConfig) {
        this.code = code;
        this.detail = detail;
        this.modelConfig = modelConfig;
    }

    static WakewordState evaluate(Context ctx) {
        ModelConfig modelConfig = ModelConfig.load(ctx);
        if (lastError.length() > 0) {
            return new WakewordState(Code.ERROR, lastError, modelConfig);
        }
        if (WakewordVoiceInteractionService.isRecognitionStarted()) {
            return new WakewordState(Code.LISTENING, "DSP recognition is armed", modelConfig);
        }
        if (isUserStopped(ctx)) {
            return new WakewordState(Code.STOPPED, "Stopped -- tap Arm to resume listening",
                    modelConfig);
        }
        if (isArming(ctx)) {
            long armingStartedAtMs = getArmingStartedAt(ctx);
            long ageMs = System.currentTimeMillis() - armingStartedAtMs;
            if (armingStartedAtMs > 0 && ageMs < ARMING_STALE_AFTER_MS) {
                return new WakewordState(Code.ARMING, "Launching privileged arming session",
                        modelConfig);
            }
            setArming(ctx, false);
        }
        if (!Shizuku.pingBinder()) {
            return new WakewordState(Code.NEEDS_SHIZUKU, "Start Shizuku after boot",
                    modelConfig);
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return new WakewordState(Code.NEEDS_SHIZUKU_PERMISSION,
                    "Grant LibreWakeWord access in Shizuku", modelConfig);
        }
        if (!modelConfig.hasUsableModel()) {
            return new WakewordState(Code.NEEDS_MODEL,
                    "Import a DSP keyphrase model and confirm metadata", modelConfig);
        }
        if (!isCurrentVis(ctx) && isSelectedAssistant(ctx)) {
            return new WakewordState(Code.READY_TO_RESTORE_VIS,
                    "Ready to restore active voice interaction service and arm", modelConfig);
        }
        if (!isCurrentVis(ctx)) {
            return new WakewordState(Code.NEEDS_VIS,
                    "Set LibreWakeWord as the active voice interaction service", modelConfig);
        }
        return new WakewordState(Code.READY_TO_ARM, "Ready to arm DSP recognition", modelConfig);
    }

    /** Persisted (survives process death): arming spans the app process restart that
     * `am instrument` triggers to launch HotwordInstrumentation (see HotwordInstrumentation),
     * so an in-memory-only flag would reset to false right as the new process comes up,
     * flashing READY_TO_ARM before the detector actually starts. Uses commit() since setArming
     * (true) is called right before kicking off that same instrumentation launch. */
    @SuppressLint("ApplySharedPref")
    static void setArming(Context ctx, boolean value) {
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ARMING, value);
        if (value) {
            editor.putLong(KEY_ARMING_STARTED_AT, System.currentTimeMillis());
            lastError = "";
        } else {
            editor.putLong(KEY_ARMING_STARTED_AT, 0);
        }
        editor.commit();
    }

    /** Clears only an arming attempt that predates the APK currently installed on the device. */
    static void clearArmingFromPreviousPackageVersion(Context ctx) {
        long startedAt = getArmingStartedAt(ctx);
        if (startedAt <= 0L) return;
        try {
            PackageInfo packageInfo = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), 0);
            if (startedAt <= packageInfo.lastUpdateTime) {
                setArming(ctx, false);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // The package necessarily exists while its own replacement receiver is running.
        }
    }

    static void setError(Context ctx, String error) {
        setArming(ctx, false);
        lastError = error == null ? "" : error;
    }

    private static boolean isArming(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ARMING, false);
    }

    private static long getArmingStartedAt(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_ARMING_STARTED_AT, 0);
    }

    static void clearError() {
        lastError = "";
    }

    /** Persisted (survives process death) so that killing the instrumented process via the
     * Stop button doesn't get silently undone by ShizukuAutoArm's next auto-arm trigger.
     * Uses commit() rather than apply(): when stopped=true this is called immediately before
     * a broadcast that leads to ActivityManager force-killing this process (see
     * HotwordInstrumentation), which can race ahead of apply()'s async write-behind and lose it. */
    @SuppressLint("ApplySharedPref")
    static void setUserStopped(Context ctx, boolean stopped) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_USER_STOPPED, stopped)
                .commit();
    }

    static boolean isUserStopped(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_STOPPED, false);
    }

    static boolean isCurrentVis(Context ctx) {
        String expected = ctx.getPackageName() + "/.WakewordVoiceInteractionService";
        String current = Settings.Secure.getString(ctx.getContentResolver(),
                "voice_interaction_service");
        return expected.equals(current);
    }

    static boolean isSelectedAssistant(Context ctx) {
        String expected = ctx.getPackageName() + "/.WakewordVoiceInteractionService";
        String current = Settings.Secure.getString(ctx.getContentResolver(), "assistant");
        return expected.equals(current);
    }

    String label() {
        return code.name() + ": " + detail;
    }
}
