package org.librewakeword;

import android.annotation.SuppressLint;
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
import android.os.Process;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.File;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.librewakeword.detection.DetectionCoordinator;
import org.librewakeword.integration.IntegrationStore;

@SuppressLint("PrivateApi")
public class HotwordInstrumentation extends Instrumentation {
    private static final String TAG = "HotwordInstr";
    public static final String ACTION_STOP_ARMING = "org.librewakeword.action.STOP_ARMING";
    private Bundle arguments;

    // Set before start() so it's true by the time Application.onCreate() runs in this
    // process (Instrumentation.onCreate() runs before callApplicationOnCreate() per
    // ActivityThread.handleBindApplication()) -- lets ShizukuAutoArm recognize "this process
    // IS the instrumentation target" and not re-trigger itself, which otherwise cascades into
    // repeated overlapping `am instrument` launches fighting over the same UiAutomation slot.
    public static volatile boolean isInstrumentedProcess = false;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : arguments;
        isInstrumentedProcess = true;
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            runArming(result);
        } catch (Throwable t) {
            p("FATAL: " + Log.getStackTraceString(t));
            result.putString("fatal", Log.getStackTraceString(t));
        } finally {
            finish(0, result);
        }
    }

    private void runArming(Bundle result) throws Exception {
        p("starting uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " target=" + getTargetContext());
        p("before adopt MANAGE_VOICE_KEYPHRASES="
                + getTargetContext().checkSelfPermission("android.permission.MANAGE_VOICE_KEYPHRASES"));
        p("before adopt CAPTURE_AUDIO_HOTWORD="
                + getTargetContext().checkSelfPermission("android.permission.CAPTURE_AUDIO_HOTWORD"));
        UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        ui.adoptShellPermissionIdentity(
                "android.permission.MANAGE_VOICE_KEYPHRASES",
                "android.permission.MANAGE_HOTWORD_DETECTION",
                "android.permission.CAPTURE_AUDIO_HOTWORD",
                "android.permission.ACCESS_VOICE_INTERACTION_SERVICE",
                "android.permission.RECORD_AUDIO");
        p("after adopt MANAGE_VOICE_KEYPHRASES="
                + getTargetContext().checkSelfPermission("android.permission.MANAGE_VOICE_KEYPHRASES"));
        p("after adopt CAPTURE_AUDIO_HOTWORD="
                + getTargetContext().checkSelfPermission("android.permission.CAPTURE_AUDIO_HOTWORD"));

        if (handleImportCommand(result)) {
            return;
        }
        launchUi();

        ModelConfig cfg = ModelConfig.load(getTargetContext());
        p("model config phrase=\"" + cfg.phrase + "\" locale=" + cfg.localeTag
                + " keyphraseId=" + cfg.keyphraseId + " vendorUuid=" + cfg.vendorUuid
                + " version=" + cfg.modelVersion + " model=" + cfg.describeModel());
        byte[] modelData = ModelConfig.readModelBytes(getTargetContext());
        p("model bytes=" + modelData.length);
        int userId = currentUserId();
        SoundTrigger.Keyphrase kp = new SoundTrigger.Keyphrase(
                cfg.keyphraseId,
                SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER,
                cfg.locale(), cfg.phrase, new int[]{userId});
        SoundTrigger.KeyphraseSoundModel model = new SoundTrigger.KeyphraseSoundModel(
                UUID.randomUUID(), cfg.vendorUuid, modelData, new SoundTrigger.Keyphrase[]{kp},
                cfg.modelVersion);

        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManagerClass
                .getMethod("getService", String.class)
                .invoke(null, "voiceinteraction");
        p("voiceinteraction binder=" + binder);
        Object svc = IVoiceInteractionManagerService.Stub.asInterface(binder);
        p("voiceinteraction svc=" + svc);
        CountDownLatch stopLatch = new CountDownLatch(1);
        BroadcastReceiver stopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                p("stop broadcast received (" + ACTION_STOP_ARMING + ")");
                stopLatch.countDown();
            }
        };
        // Stop is an in-app control. A permission supplied to registerReceiver is required of
        // the sender (not the receiver), so requiring DUMP here silently rejected the app's own
        // button broadcast. Keep the receiver process-local/non-exported instead.
        getTargetContext().registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP_ARMING),
                Context.RECEIVER_NOT_EXPORTED);

        boolean identityDropped = false;
        try {
            if ("true".equals(arguments.getString("skipModelUpdate"))) {
                p("keeping the already-enrolled sound model");
            } else {
                deleteExistingModelBestEffort(svc, cfg);
                int rc = updateKeyphraseSoundModel(svc, model, cfg, modelData, userId);
                p("updateKeyphraseSoundModel -> " + rc);
                result.putString("update", String.valueOf(rc));
            }
            bindOwnVoiceInteractionService(result);
            boolean readied = WakewordVoiceInteractionService.readyFromInstrumentation();
            p("readyFromInstrumentation -> " + readied);
            Thread.sleep(3000);
            boolean kicked = WakewordVoiceInteractionService.createDetectorFromInstrumentation();
            p("createDetectorFromInstrumentation -> " + kicked);

            boolean started = false;
            for (int waited = 0; waited < 8000; waited += 500) {
                if (WakewordVoiceInteractionService.isRecognitionStarted()) {
                    started = true;
                    break;
                }
                Thread.sleep(500);
            }
            p("recognition started within 8s -> " + started);
            result.putString("recognitionStarted", String.valueOf(started));

            p("armed. holding shell identity indefinitely -- stop via broadcast "
                    + ACTION_STOP_ARMING + " (or app/process kill)");
            while (!stopLatch.await(300, TimeUnit.SECONDS)) {
                p("still armed, holding shell identity, waiting for stop");
            }
            boolean stopped = WakewordVoiceInteractionService.stopFromInstrumentation();
            p("stopFromInstrumentation -> " + stopped);
            p("stop requested, dropping identity");
        } catch (Throwable t) {
            p("runArming THREW: " + rootCause(t));
            p(Log.getStackTraceString(t));
            result.putString("updateException", rootCause(t));
        } finally {
            WakewordVoiceInteractionService.clearInstrumentationArmingAllowed();
            WakewordState.setArming(getTargetContext(), false);
            if (!identityDropped) {
                ui.dropShellPermissionIdentity();
            }
            try {
                getTargetContext().unregisterReceiver(stopReceiver);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean handleImportCommand(Bundle result) throws Exception {
        boolean handled = false;
        String importPath = arguments.getString("importModelPath");
        if (importPath != null && importPath.length() != 0) {
            ModelConfig cfg = ModelConfig.importModelFile(getTargetContext(), new File(importPath));
            p("importModelPath -> " + cfg.describeModel());
            result.putString("importedModel", cfg.describeModel());
            handled = true;
        }

        String secondStagePath = arguments.getString("importSecondStageModelPath");
        if (secondStagePath != null && secondStagePath.length() != 0) {
            SecondStageModel.importFile(getTargetContext(), new File(secondStagePath));
            String description = SecondStageModel.describe(getTargetContext());
            p("importSecondStageModelPath -> " + description);
            result.putString("importedSecondStageModel", description);
            handled = true;
        }
        String enableConsumer = arguments.getString("enableConsumer");
        if (enableConsumer != null && !enableConsumer.isEmpty()) {
            ComponentName component = ComponentName.unflattenFromString(enableConsumer);
            if (component == null) {
                throw new IllegalArgumentException("Invalid enableConsumer: " + enableConsumer);
            }
            IntegrationStore store = new IntegrationStore(getTargetContext());
            if (!store.setConsumerEnabled(component, true)) {
                throw new IllegalStateException("Consumer could not be enabled: "
                        + component.flattenToString());
            }
            p("enabled consumer " + component.flattenToShortString());
            result.putString("enabledConsumer", component.flattenToString());
            handled = true;
        }
        String disableConsumer = arguments.getString("disableConsumer");
        if (disableConsumer != null && !disableConsumer.isEmpty()) {
            ComponentName component = ComponentName.unflattenFromString(disableConsumer);
            if (component == null) {
                throw new IllegalArgumentException("Invalid disableConsumer: " + disableConsumer);
            }
            IntegrationStore store = new IntegrationStore(getTargetContext());
            if (!store.setConsumerEnabled(component, false)) {
                throw new IllegalStateException("Consumer could not be disabled: "
                        + component.flattenToString());
            }
            p("disabled consumer " + component.flattenToShortString());
            result.putString("disabledConsumer", component.flattenToString());
            handled = true;
        }
        String webhookUrl = arguments.getString("webhookUrl");
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            new IntegrationStore(getTargetContext()).saveWebhook(
                    webhookUrl, arguments.getString("webhookSecret", ""), true);
            p("configured webhook " + webhookUrl);
            result.putString("webhookUrl", webhookUrl);
            handled = true;
        }
        if ("true".equals(arguments.getString("disableWebhook"))) {
            new IntegrationStore(getTargetContext()).saveWebhook("", "", false);
            p("disabled webhook");
            result.putString("webhook", "disabled");
            handled = true;
        }
        if ("true".equals(arguments.getString("syntheticEvent"))) {
            DetectionCoordinator coordinator = ((HotwordApplication) getTargetContext()
                    .getApplicationContext()).getDetectionCoordinator();
            String detectionId = "shell-test-" + System.nanoTime();
            coordinator.refreshIntegrations();
            coordinator.onCandidate(detectionId, SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis());
            byte[] audio = new byte[6400];
            SharedMemory memory = SharedMemory.create("lww-shell-test", audio.length);
            java.nio.ByteBuffer buffer = memory.mapReadWrite();
            try {
                buffer.put(audio);
            } finally {
                SharedMemory.unmap(buffer);
            }
            coordinator.processCompletion(detectionId, true, 16000, 1, audio.length, memory,
                    () -> {
                        p("synthetic event completed");
                        return kotlin.Unit.INSTANCE;
                    });
            Thread.sleep(4000);
            result.putString("syntheticEvent", detectionId);
            handled = true;
        }
        return handled && "true".equals(arguments.getString("importOnly"));
    }

    private int updateKeyphraseSoundModel(Object svc, SoundTrigger.KeyphraseSoundModel legacyModel,
            ModelConfig cfg, byte[] modelData, int userId) throws Exception {
        Method[] methods = svc.getClass().getMethods();
        for (Method method : methods) {
            if (!"updateKeyphraseSoundModel".equals(method.getName())
                    || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            Object model = parameterType.isInstance(legacyModel)
                    ? legacyModel
                    : buildCompatibleKeyphraseSoundModel(parameterType, cfg, modelData, userId);
            if (model == null) {
                p("updateKeyphraseSoundModel candidate unsupported param=" + parameterType);
                continue;
            }
            p("updateKeyphraseSoundModel invoking param=" + parameterType.getName()
                    + " modelClass=" + model.getClass().getName());
            Object result = method.invoke(svc, model);
            return ((Integer) result).intValue();
        }
        dumpSoundModelMethods("updateKeyphraseSoundModel not found", svc);
        throw new NoSuchMethodException("No compatible updateKeyphraseSoundModel on "
                + svc.getClass());
    }

    private Object buildCompatibleKeyphraseSoundModel(Class<?> targetType, ModelConfig cfg,
            byte[] modelData, int userId) {
        try {
            if (!"android.hardware.soundtrigger.KeyphraseSoundModel".equals(
                    targetType.getName())) {
                return null;
            }
            Class<?> keyphraseClass = Class.forName("android.hardware.soundtrigger.Keyphrase");
            Object keyphrase = buildCompatibleKeyphrase(keyphraseClass, cfg, userId);
            Object keyphraseArray = Array.newInstance(keyphraseClass, 1);
            Array.set(keyphraseArray, 0, keyphrase);
            for (Constructor<?> constructor : targetType.getConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                try {
                    if (matches(params, UUID.class, UUID.class, byte[].class,
                            keyphraseArray.getClass(), int.class)) {
                        return constructor.newInstance(UUID.randomUUID(), cfg.vendorUuid,
                                modelData, keyphraseArray, cfg.modelVersion);
                    }
                    if (matches(params, UUID.class, byte[].class, keyphraseArray.getClass())) {
                        return constructor.newInstance(UUID.randomUUID(), modelData,
                                keyphraseArray);
                    }
                } catch (Throwable t) {
                    p("KeyphraseSoundModel constructor failed " + constructor + ": "
                            + rootCause(t));
                }
            }
            p("No usable constructor for " + targetType + "; constructors="
                    + constructorsToString(targetType));
        } catch (Throwable t) {
            p("buildCompatibleKeyphraseSoundModel failed: " + rootCause(t));
        }
        return null;
    }

    private Object buildCompatibleKeyphrase(Class<?> keyphraseClass, ModelConfig cfg, int userId)
            throws Exception {
        for (Constructor<?> constructor : keyphraseClass.getConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (matches(params, int.class, int.class, Locale.class, String.class, int[].class)) {
                return constructor.newInstance(cfg.keyphraseId,
                        SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER, cfg.locale(), cfg.phrase,
                        new int[]{userId});
            }
            if (matches(params, int.class, int.class, String.class, String.class, int[].class)) {
                return constructor.newInstance(cfg.keyphraseId,
                        SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER, cfg.localeTag, cfg.phrase,
                        new int[]{userId});
            }
        }
        throw new NoSuchMethodException("No usable constructor for " + keyphraseClass
                + "; constructors=" + constructorsToString(keyphraseClass));
    }

    private boolean matches(Class<?>[] actual, Class<?>... expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!actual[i].equals(expected[i])) {
                return false;
            }
        }
        return true;
    }

    private String constructorsToString(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        for (Constructor<?> constructor : cls.getConstructors()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(constructor);
        }
        return sb.toString();
    }

    private void dumpSoundModelMethods(String label, Object svc) {
        p(label + "; serviceClass=" + svc.getClass().getName());
        for (Method method : svc.getClass().getMethods()) {
            String name = method.getName();
            if (name.toLowerCase(Locale.US).contains("soundmodel")
                    || name.toLowerCase(Locale.US).contains("keyphrase")) {
                p("  method: " + method);
            }
        }
    }

    private void deleteExistingModelBestEffort(Object svc, ModelConfig cfg) {
        int[] ids = legacyIdsToRemove(cfg);
        for (int id : ids) {
            try {
                Method delete = svc.getClass().getMethod("deleteKeyphraseSoundModel",
                        int.class, String.class);
                Object rc = delete.invoke(svc, id, cfg.localeTag);
                p("deleteKeyphraseSoundModel id=" + id + " -> " + rc);
            } catch (Throwable t) {
                p("deleteKeyphraseSoundModel id=" + id + " skipped/failed: "
                        + rootCause(t));
            }
        }
    }

    private static int[] legacyIdsToRemove(ModelConfig cfg) {
        if (cfg.keyphraseId == 2
                && ModelConfig.DEFAULT_VENDOR_UUID.equals(cfg.vendorUuid.toString())
                && ModelConfig.DEFAULT_PHRASE.equalsIgnoreCase(cfg.phrase)) {
            // Early LibreWakeWord test builds registered the Pixel x_google model as 102.
            // That leaves a duplicate that Android may select ahead of the HDS-compatible ID 2.
            return new int[]{2, 102};
        }
        return new int[]{cfg.keyphraseId};
    }

    private int currentUserId() {
        try {
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            Method myUserId = userHandle.getDeclaredMethod("myUserId");
            myUserId.setAccessible(true);
            Object result = myUserId.invoke(null);
            int id = ((Integer) result).intValue();
            p("current user id -> " + id);
            return id;
        } catch (Throwable t) {
            p("current user id unavailable, falling back to 0: " + rootCause(t));
            return 0;
        }
    }

    private void launchUi() {
        try {
            Intent intent = new Intent(getTargetContext(), MainActivity.class);
            intent.putExtra("mode", "vis");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            getTargetContext().startActivity(intent);
            p("launchUi: started MainActivity in vis mode");
        } catch (Throwable t) {
            p("launchUi THREW: " + rootCause(t));
        }
    }

    private void bindOwnVoiceInteractionService(Bundle result) {
        CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                p("bindOwnVoiceInteractionService connected name=" + name + " binder=" + service);
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                p("bindOwnVoiceInteractionService disconnected name=" + name);
            }
        };
        try {
            Intent intent = new Intent("android.service.voice.VoiceInteractionService");
            intent.setComponent(new ComponentName(getTargetContext(),
                    WakewordVoiceInteractionService.class));
            boolean bound = getTargetContext().bindService(intent, connection,
                    Context.BIND_AUTO_CREATE);
            p("bindOwnVoiceInteractionService bindService -> " + bound);
            result.putString("bindOwnService", String.valueOf(bound));
            boolean connected = latch.await(2, TimeUnit.SECONDS);
            p("bindOwnVoiceInteractionService connectedWithin2s -> " + connected);
            result.putString("bindOwnServiceConnected", String.valueOf(connected));
        } catch (Throwable t) {
            p("bindOwnVoiceInteractionService THREW: " + rootCause(t));
            p(Log.getStackTraceString(t));
            result.putString("bindOwnServiceException", rootCause(t));
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getClass().getName() + ": " + cur.getMessage();
    }

    private static void p(String msg) {
        Log.i(TAG, msg);
        System.out.println("[HotwordInstr] " + msg);
    }
}
