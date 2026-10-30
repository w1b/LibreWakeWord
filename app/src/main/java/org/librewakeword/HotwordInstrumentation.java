package org.librewakeword;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.Process;
import android.util.Log;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The privileged arming session.
 *
 * <p>{@link HotwordUserService} (running as the shell uid inside Shizuku) launches this with
 * {@code am instrument}. Because shell is the instrumentation source, this process may adopt
 * shell's permissions for LibreWakeWord's whole uid. The voice-interaction service and the
 * sound-trigger middleware check those permissions on every call while the detector is armed, so
 * the identity is held until the user stops arming.
 *
 * <p>{@code am instrument} force-stops the package first. That kills the system's binding to
 * {@link WakewordVoiceInteractionService}, and VoiceInteractionManagerService does not rebind it.
 * The instrumentation therefore binds the service itself; the system only checks that
 * {@code voice_interaction_service} names a service owned by this uid.
 */
public class HotwordInstrumentation extends Instrumentation {
    private static final String TAG = "HotwordInstr";
    static final String ACTION_STOP_ARMING = "org.librewakeword.action.STOP_ARMING";
    /** Instrumentation argument: reopen the UI, since starting instrumentation kills it. */
    static final String ARG_OPEN_UI = "openUi";
    private static final int PER_USER_UID_RANGE = 100_000;

    // Set in onCreate(), which runs before Application.onCreate() in this process, so
    // ShizukuAutoArm can tell that it must not launch another arming session from here.
    static volatile boolean isInstrumentedProcess = false;

    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : arguments;
        isInstrumentedProcess = true;
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        try {
            runArming();
        } catch (Throwable t) {
            Log.e(TAG, "arming failed", t);
            WakewordState.setError(getTargetContext(), "Arming failed: " + rootCause(t));
        } finally {
            WakewordState.setArming(getTargetContext(), false);
            finish(0, new Bundle());
        }
    }

    private void runArming() throws Exception {
        Context context = getTargetContext();
        UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        // MANAGE_VOICE_KEYPHRASES: enrolling the keyphrase model.
        // RECORD_AUDIO + CAPTURE_AUDIO_HOTWORD: checked by the sound-trigger middleware for the
        // lifetime of the recognition session, and by audio policy for the capture-session
        // AudioRecord that feeds the second-stage verifier.
        // START_ACTIVITIES_FROM_BACKGROUND: lets the router lend a woken handler activity starts.
        ui.adoptShellPermissionIdentity(
                "android.permission.MANAGE_VOICE_KEYPHRASES",
                "android.permission.CAPTURE_AUDIO_HOTWORD",
                "android.permission.RECORD_AUDIO",
                "android.permission.START_ACTIVITIES_FROM_BACKGROUND");

        CountDownLatch stopRequested = new CountDownLatch(1);
        BroadcastReceiver stopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                stopRequested.countDown();
            }
        };
        context.registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP_ARMING),
                Context.RECEIVER_NOT_EXPORTED);
        try {
            if ("true".equals(arguments.getString(ARG_OPEN_UI))) {
                openUi(context);
            }
            enrollModel(context);
            WakewordVoiceInteractionService service = bindVoiceInteractionService(context);
            if (!service.armFromInstrumentation()) {
                throw new IllegalStateException("Detector did not start");
            }
            Log.i(TAG, "armed; holding shell identity until stopped");
            stopRequested.await();
            service.disarmFromInstrumentation();
            Log.i(TAG, "stopped");
        } finally {
            ui.dropShellPermissionIdentity();
            context.unregisterReceiver(stopReceiver);
        }
    }

    /** (Re)writes the configured model into the system keyphrase database for this user. */
    private void enrollModel(Context context) throws Exception {
        ModelConfig cfg = ModelConfig.load(context);
        byte[] modelData = ModelConfig.readModelBytes(context);
        IVoiceInteractionManagerService vims = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService("voiceinteraction"));
        SoundTrigger.Keyphrase keyphrase = new SoundTrigger.Keyphrase(cfg.keyphraseId,
                SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER, cfg.locale(), cfg.phrase,
                new int[]{Process.myUid() / PER_USER_UID_RANGE});
        SoundTrigger.KeyphraseSoundModel model = new SoundTrigger.KeyphraseSoundModel(
                UUID.randomUUID(), cfg.vendorUuid, modelData,
                new SoundTrigger.Keyphrase[]{keyphrase}, cfg.modelVersion);
        // Each enrollment uses a fresh model UUID, so remove the previous one first.
        vims.deleteKeyphraseSoundModel(cfg.keyphraseId, cfg.localeTag);
        int status = vims.updateKeyphraseSoundModel(model);
        if (status != SoundTrigger.STATUS_OK) {
            throw new IllegalStateException("updateKeyphraseSoundModel returned " + status);
        }
        Log.i(TAG, "enrolled \"" + cfg.phrase + "\" " + cfg.localeTag + " id=" + cfg.keyphraseId
                + " " + cfg.describeModel());
    }

    private WakewordVoiceInteractionService bindVoiceInteractionService(Context context)
            throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        // The action is required: VoiceInteractionService only returns its binder for it.
        Intent intent = new Intent(WakewordVoiceInteractionService.SERVICE_INTERFACE)
                .setClass(context, WakewordVoiceInteractionService.class);
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                || !connected.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Could not bind the voice interaction service");
        }
        WakewordVoiceInteractionService service = WakewordVoiceInteractionService.current();
        if (service == null) {
            throw new IllegalStateException("Voice interaction service is not running");
        }
        return service;
    }

    private static void openUi(Context context) {
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }
}
