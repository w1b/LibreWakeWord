package org.librewakeword;

import android.content.pm.ApplicationInfo;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.service.voice.AlwaysOnHotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.SoundTriggerFailure;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the system DSP detector and the bounded, post-trigger verification capture.
 *
 * <p>Android only lets the current voice-interaction service create an
 * {@link AlwaysOnHotwordDetector}; this class exists to be that service. It does nothing until
 * {@link HotwordInstrumentation} arms it, because the detector needs the adopted shell identity.
 */
public class WakewordVoiceInteractionService extends VoiceInteractionService {
    private static final String TAG = "HotwordVIS";
    private static final long VERIFICATION_WINDOW_MILLIS = 3_000L;
    private static volatile WakewordVoiceInteractionService current;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object verifierLock = new Object();
    private final Object liveInputLock = new Object();
    private final AtomicBoolean verificationInFlight = new AtomicBoolean(false);
    private final ExecutorService verificationExecutor = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                task.run();
            }, "wakeword-verifier"));

    private AlwaysOnHotwordDetector detector;
    private SodaHotwordVerifier.Engine verifier;
    private WakewordLiveInput preparedLiveInput;
    /** True while recognition is started or a one-shot candidate is being handled. */
    private volatile boolean armed;
    private boolean recognitionStarted;
    private boolean candidateHandlerMode;
    private long detectorGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        Log.i(TAG, "onCreate uid=" + Process.myUid());
        DetectionNotifier.ensureChannel(this);
    }

    @Override
    public void onShutdown() {
        Log.i(TAG, "onShutdown");
        destroyDetector();
        super.onShutdown();
    }

    @Override
    public void onDestroy() {
        destroyDetector();
        verificationExecutor.shutdownNow();
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }

    static WakewordVoiceInteractionService current() {
        return current;
    }

    static boolean isArmed() {
        WakewordVoiceInteractionService service = current;
        return service != null && service.armed;
    }

    /**
     * Creates and starts the detector. Called from the instrumentation thread once the shell
     * identity is adopted; blocks until recognition has started or failed.
     */
    boolean armFromInstrumentation() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            // The system never delivers onReady() to this instance (see HotwordInstrumentation),
            // and the base class only connects to the system service in onReady().
            onReady();
            createDetector("instrumentation");
            done.countDown();
        });
        done.await();
        for (int waited = 0; waited < 5_000 && !armed; waited += 100) {
            Thread.sleep(100);
        }
        return armed;
    }

    void disarmFromInstrumentation() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            destroyDetector();
            done.countDown();
        });
        done.await(2, TimeUnit.SECONDS);
    }

    /** Reloads a newly imported SODA resource while leaving the DSP registration intact. */
    static void onSecondStageModelChanged() {
        WakewordVoiceInteractionService service = current;
        if (service == null) {
            Log.i(TAG, "second-stage model changed while VIS is not running");
            return;
        }
        service.mainHandler.post(() -> service.replaceVerifier("model changed"));
    }

    public static void onIntegrationConfigurationChanged() {
        WakewordVoiceInteractionService service = current;
        if (service == null || service.detector == null) {
            return;
        }
        if (Looper.myLooper() == service.mainHandler.getLooper()) {
            service.applyIntegrationConfiguration();
        } else {
            service.mainHandler.post(service::applyIntegrationConfiguration);
        }
    }

    /** Temporarily removes the detector so enrollment phrases cannot launch a consumer. */
    static boolean pauseForLocalVoiceMatchEnrollment() {
        WakewordVoiceInteractionService service = current;
        if (service == null || service.detector == null) {
            return false;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        service.mainHandler.post(() -> {
            try {
                service.destroyDetector();
            } finally {
                stopped.countDown();
            }
        });
        try {
            stopped.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    static void resumeAfterLocalVoiceMatchEnrollment(boolean wasRunning) {
        WakewordVoiceInteractionService service = current;
        if (wasRunning && service != null) {
            service.mainHandler.post(() ->
                    service.createDetector("voice-match enrollment finished"));
        }
    }

    private void createDetector(String source) {
        try {
            if (detector != null) {
                Log.i(TAG, "detector already exists: " + detector);
                return;
            }
            candidateHandlerMode = ((HotwordApplication) getApplication())
                    .getDetectionCoordinator().getIntegrationStore().hasCandidateHandler();
            if (!candidateHandlerMode) {
                replaceVerifier("detector creation");
                synchronized (verifierLock) {
                    if (verifier == null) {
                        throw new IllegalStateException("Second-stage verifier is unavailable");
                    }
                }
                prepareLiveInput("detector creation");
            } else {
                closeVerifier();
                closePreparedLiveInput();
            }
            ModelConfig cfg = ModelConfig.load(this);
            Method create = VoiceInteractionService.class.getDeclaredMethod(
                    "createAlwaysOnHotwordDetector",
                    String.class,
                    Locale.class,
                    Executor.class,
                    AlwaysOnHotwordDetector.Callback.class);
            create.setAccessible(true);
            detector = (AlwaysOnHotwordDetector) create.invoke(this, cfg.phrase, cfg.locale(),
                    getMainExecutor(), new DetectorCallback());
            detectorGeneration++;
            // Recognition starts from onAvailabilityChanged(STATE_KEYPHRASE_ENROLLED).
            Log.i(TAG, "created detector source=" + source
                    + " candidateHandlerMode=" + candidateHandlerMode);
        } catch (Throwable t) {
            closeVerifier();
            closePreparedLiveInput();
            Log.e(TAG, "createDetector failed source=" + source, t);
        }
    }

    private void replaceVerifier(String reason) {
        if (candidateHandlerMode) {
            closeVerifier();
            return;
        }
        try {
            byte[] model = SecondStageModel.read(this);
            byte[] speakerModel = VoiceMatchModel.isEnabled(this)
                    ? VoiceMatchModel.read(this) : new byte[0];
            SodaHotwordVerifier.Engine replacement = SodaHotwordVerifier.initialize(
                    this, model, speakerModel, true);
            SodaHotwordVerifier.Engine previous;
            synchronized (verifierLock) {
                previous = verifier;
                verifier = replacement;
                detectorGeneration++;
            }
            if (previous != null) {
                previous.close();
            }
            Log.i(TAG, "replaced second-stage verifier after " + reason
                    + " modelBytes=" + model.length
                    + " speakerModelBytes=" + speakerModel.length);
        } catch (Throwable error) {
            Log.e(TAG, "could not replace second-stage verifier after " + reason, error);
        }
    }

    private void closeVerifier() {
        SodaHotwordVerifier.Engine previous;
        synchronized (verifierLock) {
            previous = verifier;
            verifier = null;
            detectorGeneration++;
        }
        if (previous != null) {
            previous.close();
        }
    }

    private void prepareLiveInput(String reason) {
        synchronized (liveInputLock) {
            if (preparedLiveInput != null) return;
            preparedLiveInput = WakewordLiveInput.create(this);
            Log.i(TAG, "prepared bounded live input after " + reason);
        }
    }

    private WakewordLiveInput takePreparedLiveInput() {
        synchronized (liveInputLock) {
            WakewordLiveInput result = preparedLiveInput;
            preparedLiveInput = null;
            return result;
        }
    }

    private void closePreparedLiveInput() {
        WakewordLiveInput previous;
        synchronized (liveInputLock) {
            previous = preparedLiveInput;
            preparedLiveInput = null;
        }
        if (previous != null) previous.close();
    }

    private void startDetector(String reason) {
        if (detector == null || recognitionStarted) {
            return;
        }
        try {
            // Do not ask the framework to restart recognition automatically. The DSP event's
            // capture session remains the source of truth for SODA and the bounded handoff
            // prefix. Starting the next recognition before that recorder opens causes the Pixel
            // sound-trigger HAL to replace the event client, invalidating its capture session.
            recognitionStarted = detector.startRecognition(
                    AlwaysOnHotwordDetector.RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO);
            Log.i(TAG, "startRecognition reason=" + reason + " -> " + recognitionStarted);
        } catch (Throwable t) {
            Log.e(TAG, "startRecognition failed; reason=" + reason, t);
        }
        if (recognitionStarted && !armed) {
            armed = true;
            ((HotwordApplication) getApplication()).getDetectionCoordinator().setArmed(true);
        }
    }

    private void destroyDetector() {
        detectorGeneration++;
        try {
            if (detector != null) {
                detector.destroy();
            }
        } catch (Throwable t) {
            Log.e(TAG, "destroyDetector failed", t);
        } finally {
            if (armed) {
                armed = false;
                ((HotwordApplication) getApplication()).getDetectionCoordinator().setArmed(false);
            }
            detector = null;
            recognitionStarted = false;
            candidateHandlerMode = false;
            closeVerifier();
            closePreparedLiveInput();
        }
    }

    private void applyIntegrationConfiguration() {
        boolean shouldUseCandidateHandler = ((HotwordApplication) getApplication())
                .getDetectionCoordinator().getIntegrationStore().hasCandidateHandler();
        if (shouldUseCandidateHandler != candidateHandlerMode) {
            destroyDetector();
            createDetector("integrations changed");
        }
    }

    private void verifyCandidate(String detectionId, long requestGeneration,
            Integer captureSession, AudioFormat audioFormat) {
        WakewordLiveInput liveInput = null;
        byte[] capturedAudio = new byte[0];
        Long capturedAudioStartElapsedRealtimeNanos = null;
        int sampleRate = audioFormat != null && audioFormat.getSampleRate() > 0
                ? audioFormat.getSampleRate() : 16_000;
        int channelCount = audioFormat != null && audioFormat.getChannelCount() > 0
                ? audioFormat.getChannelCount() : 1;
        try {
            SodaHotwordVerifier.Engine engine;
            synchronized (verifierLock) {
                engine = verifier;
            }
            if (engine == null || captureSession == null || captureSession < 0) {
                Log.w(TAG, "cannot verify candidate id=" + detectionId + " engine="
                        + (engine != null) + " captureSession=" + captureSession);
                ((HotwordApplication) getApplication()).getDetectionCoordinator()
                        .onRejected(detectionId);
                return;
            }

            SecondStageHotwordVerifier.Result result;
            try (SodaDspInput stream = SodaDspInput.open(captureSession, audioFormat)) {
                sampleRate = stream.sampleRate;
                channelCount = stream.channelCount;
                // The capture-session recorder must start first so it acquires the DSP event's
                // finite history. Immediately start the preconstructed ordinary recorder next,
                // in parallel with SODA startup. This is the earliest ordering that preserves
                // that history while guaranteeing timestamped overlap at its live edge. The
                // ordinary recorder is active only for a hardware DSP candidate and is closed and
                // discarded if SODA rejects it; it is never kept open while the detector is idle.
                liveInput = takePreparedLiveInput();
                if (liveInput == null) liveInput = WakewordLiveInput.create(this);
                WakewordLiveInput acceptedLiveInput = liveInput;
                Runnable requestLiveCapture = () -> mainHandler.postAtFrontOfQueue(() -> {
                    try {
                        acceptedLiveInput.start();
                    } catch (Throwable error) {
                        // Accepted-event handling retries synchronously and turns a real startup
                        // failure into a rejected capture instead of crashing the main thread.
                        Log.e(TAG, "could not start bounded live input", error);
                    }
                });
                requestLiveCapture.run();
                result = engine.verify(stream,
                        SystemClock.elapsedRealtime() + VERIFICATION_WINDOW_MILLIS,
                        requestLiveCapture);
                boolean handoffClaimed = false;
                boolean handoffStarted = false;
                if (!result.accepted
                        && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    SodaDspInput.Capture rejectedCapture = stream.snapshot();
                    if (rejectedCapture != null) {
                        writeDiagnostic("dsp-verification-raw.pcm", rejectedCapture.pcm);
                        for (int channel = 0; channel < rejectedCapture.channelCount; channel++) {
                            writeDiagnostic("dsp-verification-channel-" + channel + ".pcm",
                                    rejectedCapture.selectedChannel(channel).pcm);
                        }
                    }
                    writeDiagnostic("dsp-read-timeline.csv",
                            stream.diagnosticTimeline().getBytes(StandardCharsets.UTF_8));
                }
                if (requestGeneration != detectorGeneration) {
                    engine.finishCapture();
                    Log.i(TAG, "discarding candidate after detector state changed id="
                            + detectionId);
                    ((HotwordApplication) getApplication()).getDetectionCoordinator()
                            .onRejected(detectionId);
                    return;
                }

                if (result.accepted) {
                    // The event recorder is authoritative for DSP history, but Pixel's
                    // sound-trigger HAL emits a zero-filled block when that finite history catches
                    // the live edge. Start the preconstructed ordinary recorder first, drain DSP
                    // history only through its timestamped first frame, and never read the HAL's
                    // exhausted history block.
                    // Idempotent: normally the SODA acceptance callback already started this
                    // recorder. This call covers a delayed main-loop dispatch without changing
                    // the public handoff contract.
                    liveInput.start();
                    if (!liveInput.awaitFirstRealFrame(150L)) {
                        throw new IllegalStateException(
                                "Bounded live recorder did not produce audio in time");
                    }
                    WakewordLiveInput.Capture initialLiveCapture = liveInput.snapshot();
                    if (initialLiveCapture == null) {
                        throw new IllegalStateException("Bounded live recorder is empty");
                    }
                    stream.drainThroughElapsedRealtimeNanos(
                            initialLiveCapture.startElapsedRealtimeNanos);
                    stream.stopAtBridgeBoundary();

                    boolean hasConfirmedHandler = ((HotwordApplication) getApplication())
                            .getDetectionCoordinator().getIntegrationStore()
                            .hasConfirmedHandler();
                    if (hasConfirmedHandler) {
                        CountDownLatch claimSignal = new CountDownLatch(1);
                        ((HotwordApplication) getApplication()).getDetectionCoordinator()
                                .beginConfirmedHandoff(detectionId, claimSignal::countDown);
                        handoffClaimed = await(claimSignal, 1_000L);
                        handoffStarted = true;
                    }
                    liveInput.close();
                    WakewordLiveInput.Capture liveCapture = liveInput.snapshot();
                    SodaDspInput.Capture rawCapture = stream.snapshot();
                    int selectedChannel = result.detectedAudio == null
                            ? 0 : result.detectedAudio.selectedChannel;
                    if (selectedChannel < 0 || selectedChannel >= stream.channelCount) {
                        selectedChannel = 0;
                    }
                    SodaDspInput.Capture historyCapture = rawCapture == null
                            ? null : rawCapture.selectedChannel(selectedChannel);
                    SodaDspInput.Capture capture = SodaDspInput.composeWithLive(
                            historyCapture, liveCapture, stream.sampleRate);
                    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        if (rawCapture != null) {
                            writeDiagnostic("dsp-capture-session-raw.pcm", rawCapture.pcm);
                        }
                        if (historyCapture != null) {
                            writeDiagnostic("dsp-capture-session.pcm", historyCapture.pcm);
                        }
                        if (liveCapture != null) {
                            writeDiagnostic("bounded-live-capture.pcm", liveCapture.pcm);
                        }
                        if (capture != null) {
                            writeDiagnostic("composed-wakeword-prefix.pcm", capture.pcm);
                        }
                        writeDiagnostic("dsp-read-timeline.csv",
                                stream.diagnosticTimeline().getBytes(StandardCharsets.UTF_8));
                        if (result.detectedAudio != null) {
                            writeDiagnostic("soda-detected.pcm",
                                    result.detectedAudio.selectedChannelPcm16());
                        }
                    }
                    if (capture != null) {
                        capturedAudio = capture.pcm;
                        capturedAudioStartElapsedRealtimeNanos =
                                capture.startElapsedRealtimeNanos;
                        sampleRate = stream.sampleRate;
                        channelCount = 1;
                    } else if (result.detectedAudio != null) {
                        capturedAudio = result.detectedAudio.selectedChannelPcm16();
                        capturedAudioStartElapsedRealtimeNanos =
                                result.detectedAudio.startTimeElapsedRealtimeMicros >= 0L
                                        ? result.detectedAudio.startTimeElapsedRealtimeMicros
                                                * 1_000L
                                        : null;
                        sampleRate = result.detectedAudio.sampleRate;
                        channelCount = 1;
                    } else {
                        Log.w(TAG, "accepted SODA event had no captured audio id="
                                + detectionId);
                    }
                } else {
                    stream.close();
                }
                engine.finishCapture();
                Log.i(TAG, "direct handoff started=" + handoffStarted
                        + " claimed=" + handoffClaimed
                        + " prefixBytes=" + capturedAudio.length);
                if (result.detectedAudio != null) {
                    Log.i(TAG, "SODA initial audio " + result.detectedAudio.describe());
                }
                Log.i(TAG, (result.accepted ? "confirmed" : "rejected")
                        + " candidate id=" + detectionId + " detail=" + result.detail
                        + " audioBytes=" + capturedAudio.length
                        + " captureSessionPrefix=" + result.accepted);
                ((HotwordApplication) getApplication()).getDetectionCoordinator()
                        .processCapturedCompletion(detectionId, result.accepted, sampleRate,
                                channelCount, capturedAudio,
                                capturedAudioStartElapsedRealtimeNanos,
                                handoffStarted);
            }
        } catch (Throwable error) {
            Log.e(TAG, "candidate verification failed id=" + detectionId, error);
            ((HotwordApplication) getApplication()).getDetectionCoordinator()
                    .processCapturedCompletion(detectionId, false, sampleRate,
                            channelCount, capturedAudio,
                            capturedAudioStartElapsedRealtimeNanos,
                            false);
        } finally {
            if (liveInput != null) liveInput.close();
            try {
                if (detector != null && detectorGeneration == requestGeneration
                        && !candidateHandlerMode) {
                    prepareLiveInput("candidate completion");
                }
            } catch (Throwable error) {
                Log.e(TAG, "could not prepare next bounded live input", error);
            }
            verificationInFlight.set(false);
            rearmAfterCandidate(requestGeneration);
        }
    }

    private void rearmAfterCandidate(long completedGeneration) {
        mainHandler.post(() -> {
            if (detector == null || detectorGeneration != completedGeneration) {
                Log.i(TAG, "not rearming stale candidate generation="
                        + completedGeneration + " current=" + detectorGeneration);
                return;
            }
            startDetector("candidate complete");
        });
    }

    private void writeDiagnostic(String name, byte[] content) {
        File target = new File(getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(content);
        } catch (Throwable error) {
            Log.w(TAG, "could not write debug capture " + name, error);
        }
    }

    private static boolean await(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private final class DetectorCallback extends AlwaysOnHotwordDetector.Callback {
        @Override
        public void onAvailabilityChanged(int status) {
            Log.i(TAG, "onAvailabilityChanged status=" + status);
            if (status == AlwaysOnHotwordDetector.STATE_KEYPHRASE_ENROLLED) {
                mainHandler.post(() -> startDetector("availability"));
            }
        }

        @Override
        public void onDetected(AlwaysOnHotwordDetector.EventPayload eventPayload) {
            // Recognition was intentionally configured as one-shot so this event's capture
            // session stays valid until verification and handoff finish.
            recognitionStarted = false;
            long elapsedNanos = SystemClock.elapsedRealtimeNanos();
            long epochMillis = System.currentTimeMillis();
            String detectionId = UUID.randomUUID().toString();
            Integer captureSession = eventPayload.getCaptureSession();
            AudioFormat audioFormat = eventPayload.getCaptureAudioFormat();
            Log.i(TAG, "DSP candidate id=" + detectionId + " captureSession=" + captureSession
                    + " format=" + audioFormat);

            HotwordApplication application = (HotwordApplication) getApplication();
            if (candidateHandlerMode) {
                application.getDetectionCoordinator().onTerminalCandidate(
                        detectionId, elapsedNanos, epochMillis);
                Log.i(TAG, "routed terminal DSP candidate id=" + detectionId);
                rearmAfterCandidate(detectorGeneration);
                return;
            }

            application.getDetectionCoordinator().onCandidate(
                    detectionId, elapsedNanos, epochMillis);
            if (!verificationInFlight.compareAndSet(false, true)) {
                Log.w(TAG, "rejecting overlapping candidate id=" + detectionId);
                application.getDetectionCoordinator().onRejected(detectionId);
                // The active candidate owns the one-shot detector's capture session and rearms
                // recognition from its finally block. Rearming here could replace that session
                // while SODA is still consuming its finite DSP history.
                return;
            }
            long generation = detectorGeneration;
            try {
                verificationExecutor.execute(() -> verifyCandidate(
                        detectionId, generation, captureSession, audioFormat));
            } catch (Throwable error) {
                verificationInFlight.set(false);
                application.getDetectionCoordinator().onRejected(detectionId);
                rearmAfterCandidate(generation);
                Log.e(TAG, "could not queue candidate verification id=" + detectionId, error);
            }
        }

        @Override
        public void onError() {
            Log.e(TAG, "onError");
        }

        @Override
        public void onFailure(SoundTriggerFailure soundTriggerFailure) {
            Log.e(TAG, "onFailure soundTriggerFailure=" + soundTriggerFailure);
        }

        @Override
        public void onHotwordDetectionServiceInitialized(int status) {
            Log.i(TAG, "unexpected HDS initialization status=" + status);
        }

        @Override
        public void onHotwordDetectionServiceRestarted() {
            Log.i(TAG, "unexpected HDS restart");
        }

        @Override
        public void onRecognitionPaused() {
            Log.i(TAG, "onRecognitionPaused");
        }

        @Override
        public void onRecognitionResumed() {
            Log.i(TAG, "onRecognitionResumed");
        }

        @Override
        public void onRejected(HotwordRejectedResult result) {
            Log.i(TAG, "unexpected framework rejection result=" + result);
        }
    }
}
