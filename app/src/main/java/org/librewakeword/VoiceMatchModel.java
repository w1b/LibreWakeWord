package org.librewakeword;

import android.content.Context;

import com.google.speech.micro.GoogleHotwordData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Private storage and the independent enable switch for local text-dependent Voice Match. */
final class VoiceMatchModel {
    private static final String DIRECTORY = "voice_match";
    private static final String FILE_NAME = "tdsid.model";
    private static final String PREFS = "local_voice_match";
    private static final String KEY_ENABLED = "enabled";

    static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILE_NAME);
    }

    static boolean hasModel(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() > 0;
    }

    static boolean isEnabled(Context context) {
        return hasModel(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        if (enabled && !hasModel(context)) {
            throw new IllegalStateException("Enroll a local voice before enabling Voice Match");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    static String describe(Context context) {
        if (!hasModel(context)) {
            return "Not enrolled";
        }
        return (isEnabled(context) ? "Enabled" : "Enrolled, disabled") + " · local TD-SID · "
                + modelFile(context).length() + " bytes";
    }

    static byte[] read(Context context) throws Exception {
        File file = modelFile(context);
        if (!file.isFile() || file.length() <= 0 || file.length() > 64 * 1024) {
            throw new IllegalStateException("Local Voice Match model is missing or invalid");
        }
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int at = 0;
            while (at < bytes.length) {
                int count = input.read(bytes, at, bytes.length - at);
                if (count < 0) break;
                at += count;
            }
            if (at != bytes.length) {
                throw new IllegalStateException("Short Voice Match model read");
            }
        }
        return bytes;
    }

    static void install(Context context, byte[] speakerModel) throws Exception {
        if (speakerModel == null || speakerModel.length == 0) {
            throw new IllegalArgumentException("Local Voice Match produced an empty model");
        }
        byte[] hotwordModel = SecondStageModel.read(context);
        GoogleHotwordData data = new GoogleHotwordData(hotwordModel, 16_000, 1, 0);
        try {
            if (!data.isSpeakerModelCompatible(speakerModel)) {
                throw new IllegalArgumentException(
                        "The enrolled voice is incompatible with the installed hotword model");
            }
        } finally {
            data.close();
        }

        File destination = modelFile(context);
        File directory = destination.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create local Voice Match storage");
        }
        File temporary = new File(directory, FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(speakerModel);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Could not replace the old local voice model");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Could not commit the local voice model");
        }
        setEnabled(context, true);
    }

    static void delete(Context context) {
        setEnabled(context, false);
        File file = modelFile(context);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Could not delete the local Voice Match model");
        }
    }

    private VoiceMatchModel() {
        throw new AssertionError();
    }
}
