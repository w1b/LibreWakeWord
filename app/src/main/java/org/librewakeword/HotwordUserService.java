package org.librewakeword;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku user service, running as the shell uid. It selects LibreWakeWord's voice-interaction
 * service and starts {@link HotwordInstrumentation}. It is a daemon because `am instrument -w`
 * must outlive the call: the instrumentation (and the adopted shell identity) ends when this
 * process dies.
 */
public class HotwordUserService extends IHotwordUserService.Stub {
    private static final String TAG = "HotwordUserService";
    private static final String VIS_COMPONENT =
            "org.librewakeword/.WakewordVoiceInteractionService";
    private static final String INSTRUMENTATION_COMPONENT =
            "org.librewakeword/.HotwordInstrumentation";

    @Override
    public void launchInstrumentation(boolean openUi) {
        try {
            // Only the voice-interaction service setting is needed, not the default assistant
            // role. The role observer resets this setting at boot and whenever the role
            // changes, so it is written on every arm.
            int settingsRc = new ProcessBuilder("settings", "put", "secure",
                    "voice_interaction_service", VIS_COMPONENT).start().waitFor();
            Log.i(TAG, "selected voice interaction service, rc=" + settingsRc);

            Process proc = new ProcessBuilder("am", "instrument", "--no-hidden-api-checks", "-w",
                    "-e", HotwordInstrumentation.ARG_OPEN_UI, String.valueOf(openUi),
                    INSTRUMENTATION_COMPONENT)
                    .redirectErrorStream(true)
                    .start();
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG, "[am instrument] " + line);
                    }
                } catch (Throwable ignored) {
                }
            }, "am-instrument-drain");
            drain.setDaemon(true);
            drain.start();
        } catch (Throwable t) {
            Log.e(TAG, "launchInstrumentation failed", t);
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
