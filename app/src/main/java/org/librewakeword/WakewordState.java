package org.librewakeword;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * Arming state shown in the UI and used to gate automatic arming.
 *
 * <p>Everything except "is the detector armed" is persisted with commit(): arming starts with
 * {@code am instrument}, which kills this process and starts a new one, and a stop or failure
 * ends that process right after being recorded.
 */
final class WakewordState {
    private static final long ARMING_STALE_AFTER_MS = 45_000L;
    private static final String PREFS = "wakeword_state";
    private static final String KEY_USER_STOPPED = "user_stopped";
    private static final String KEY_ARMING = "arming";
    private static final String KEY_ARMING_STARTED_AT = "arming_started_at";
    private static final String KEY_ERROR = "error";

    enum Code {
        NEEDS_SHIZUKU,
        NEEDS_SHIZUKU_PERMISSION,
        NEEDS_MODEL,
        READY_TO_ARM,
        ARMING,
        LISTENING,
        STOPPED,
        ERROR
    }

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
        SharedPreferences prefs = prefs(ctx);
        if (WakewordVoiceInteractionService.isArmed()) {
            return new WakewordState(Code.LISTENING, "The DSP is listening", modelConfig);
        }
        String error = prefs.getString(KEY_ERROR, "");
        if (!error.isEmpty()) {
            return new WakewordState(Code.ERROR, error, modelConfig);
        }
        if (prefs.getBoolean(KEY_USER_STOPPED, false)) {
            return new WakewordState(Code.STOPPED,
                    "Stopped. LibreWakeWord won't re-arm on its own until you tap Arm.",
                    modelConfig);
        }
        if (prefs.getBoolean(KEY_ARMING, false)) {
            long ageMs = System.currentTimeMillis() - prefs.getLong(KEY_ARMING_STARTED_AT, 0);
            if (ageMs < ARMING_STALE_AFTER_MS) {
                return new WakewordState(Code.ARMING, "Arming the DSP through Shizuku",
                        modelConfig);
            }
            setArming(ctx, false);
        }
        if (!Shizuku.pingBinder()) {
            return new WakewordState(Code.NEEDS_SHIZUKU,
                    "Shizuku isn't running",
                    modelConfig);
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return new WakewordState(Code.NEEDS_SHIZUKU_PERMISSION,
                    "Allow LibreWakeWord in Shizuku", modelConfig);
        }
        if (!modelConfig.hasUsableModel()) {
            return new WakewordState(Code.NEEDS_MODEL, "Import a wake word model in settings",
                    modelConfig);
        }
        return new WakewordState(Code.READY_TO_ARM, "Ready to arm", modelConfig);
    }

    @SuppressLint("ApplySharedPref")
    static void setArming(Context ctx, boolean arming) {
        SharedPreferences.Editor editor = prefs(ctx).edit().putBoolean(KEY_ARMING, arming);
        if (arming) {
            editor.putLong(KEY_ARMING_STARTED_AT, System.currentTimeMillis()).remove(KEY_ERROR);
        }
        editor.commit();
    }

    /** Clears an arming attempt that the package replacement killed. */
    static void clearArmingFromPreviousPackageVersion(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (prefs(ctx).getLong(KEY_ARMING_STARTED_AT, 0) <= info.lastUpdateTime) {
                setArming(ctx, false);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Unreachable: this package is running.
        }
    }

    @SuppressLint("ApplySharedPref")
    static void setError(Context ctx, String error) {
        prefs(ctx).edit().putBoolean(KEY_ARMING, false).putString(KEY_ERROR, error).commit();
    }

    @SuppressLint("ApplySharedPref")
    static void clearError(Context ctx) {
        prefs(ctx).edit().remove(KEY_ERROR).commit();
    }

    /** A user stop is remembered so automatic arming (boot, Shizuku start) doesn't undo it. */
    @SuppressLint("ApplySharedPref")
    static void setUserStopped(Context ctx, boolean stopped) {
        prefs(ctx).edit().putBoolean(KEY_USER_STOPPED, stopped).commit();
    }

    String label() {
        return code.name() + ": " + detail;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
