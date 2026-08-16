package org.librewakeword;

import android.content.Context;
import android.util.Log;

public class HotwordUserService extends IHotwordUserService.Stub {
    private static final String TAG = "HotwordUserService";
    private static final String VIS_COMPONENT =
            "org.librewakeword/.WakewordVoiceInteractionService";
    private static final String INSTRUMENTATION_COMPONENT =
            "org.librewakeword/.HotwordInstrumentation";

    public HotwordUserService(Context context) {
        p("created with context: " + context);
    }

    public HotwordUserService() {
        p("created without context");
    }

    @Override
    public void launchInstrumentation() {
        try {
            Process settingsProc = new ProcessBuilder("settings", "put", "secure",
                    "voice_interaction_service", VIS_COMPONENT).start();
            int settingsRc = settingsProc.waitFor();
            p("restored voice_interaction_service setting, rc=" + settingsRc);

            Process proc = new ProcessBuilder("am", "instrument", "--no-hidden-api-checks", "-w",
                    INSTRUMENTATION_COMPONENT)
                    .redirectErrorStream(true)
                    .start();
            Thread drain = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        p("[am instrument] " + line);
                    }
                } catch (Throwable ignored) {
                }
            }, "am-instrument-drain");
            drain.setDaemon(true);
            drain.start();
            p("launchInstrumentation: started am instrument --no-hidden-api-checks -w");
        } catch (Throwable t) {
            p("launchInstrumentation THREW: " + Log.getStackTraceString(t));
        }
    }

    @Override public void destroy() { p("destroy() called, exiting"); System.exit(0); }

    private static void p(String msg) {
        Log.i(TAG, msg);
        System.out.println("[" + TAG + "] " + msg);
    }
}
