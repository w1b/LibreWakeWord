package org.librewakeword;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class ModelConfig {
    public static final String DEFAULT_VENDOR_UUID = "7038ddc8-30f2-11e6-b0ac-40a8f03d3f15";
    // Pixel's SODA maps x_google to keyphrase 2.
    public static final int DEFAULT_KEYPHRASE_ID = 2;
    public static final int DEFAULT_MODEL_VERSION = 36;
    public static final String DEFAULT_PHRASE = "Hey Google";
    public static final String DEFAULT_LOCALE = "en-US";

    private static final String TAG = "LibreWakeWordModel";
    private static final String PREFS = "model_config";
    private static final String KEY_PHRASE = "phrase";
    private static final String KEY_LOCALE = "locale";
    private static final String KEY_KEYPHRASE_ID = "keyphrase_id";
    private static final String KEY_VENDOR_UUID = "vendor_uuid";
    private static final String KEY_MODEL_VERSION = "model_version";
    private static final String KEY_SHA256 = "sha256";
    private static final String MODEL_DIR = "models";
    private static final String MODEL_FILE = "current.sound_model";
    private static final int MAX_MODEL_BYTES = 32 * 1024 * 1024;

    public final String phrase;
    public final String localeTag;
    final int keyphraseId;
    final UUID vendorUuid;
    final int modelVersion;
    final String sha256;
    final File modelFile;

    private ModelConfig(String phrase, String localeTag, int keyphraseId, UUID vendorUuid,
            int modelVersion, String sha256, File modelFile) {
        this.phrase = phrase;
        this.localeTag = localeTag;
        this.keyphraseId = keyphraseId;
        this.vendorUuid = vendorUuid;
        this.modelVersion = modelVersion;
        this.sha256 = sha256;
        this.modelFile = modelFile;
    }

    public static ModelConfig load(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String phrase = prefs.getString(KEY_PHRASE, DEFAULT_PHRASE);
        String localeTag = prefs.getString(KEY_LOCALE, DEFAULT_LOCALE);
        int keyphraseId = prefs.getInt(KEY_KEYPHRASE_ID, DEFAULT_KEYPHRASE_ID);
        int modelVersion = prefs.getInt(KEY_MODEL_VERSION, DEFAULT_MODEL_VERSION);
        UUID vendorUuid = parseUuid(prefs.getString(KEY_VENDOR_UUID, DEFAULT_VENDOR_UUID));
        File privateModel = privateModelFile(ctx);
        File modelFile = privateModel.isFile() && privateModel.length() > 0 ? privateModel : null;
        String sha256 = prefs.getString(KEY_SHA256, "");
        if (sha256.length() == 0 && modelFile != null && modelFile.isFile()) {
            try {
                sha256 = sha256(modelFile);
            } catch (Throwable t) {
                Log.w(TAG, "sha256 failed", t);
            }
        }
        return new ModelConfig(phrase, localeTag, keyphraseId, vendorUuid, modelVersion, sha256,
                modelFile);
    }

    static void saveMetadata(Context ctx, String phrase, String localeTag, int keyphraseId,
            String vendorUuid, int modelVersion) {
        UUID parsed = parseUuid(vendorUuid);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PHRASE, emptyToDefault(phrase, DEFAULT_PHRASE))
                .putString(KEY_LOCALE, emptyToDefault(localeTag, DEFAULT_LOCALE))
                .putInt(KEY_KEYPHRASE_ID, keyphraseId)
                .putString(KEY_VENDOR_UUID, parsed.toString())
                .putInt(KEY_MODEL_VERSION, modelVersion)
                .apply();
    }

    static ModelConfig importModel(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalArgumentException("Could not open selected model");
        }
        try (InputStream input = in) {
            return importModel(ctx, input);
        }
    }

    private static ModelConfig importModel(Context ctx, InputStream in) throws Exception {
        File dir = new File(ctx.getFilesDir(), MODEL_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create model dir: " + dir);
        }
        File dest = privateModelFile(ctx);
        File tmp = new File(dir, MODEL_FILE + ".tmp");
        if (tmp.exists() && !tmp.delete()) {
            throw new IllegalStateException("Could not clear stale model import");
        }
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_MODEL_BYTES) {
                    throw new IllegalArgumentException(
                            "Selected model exceeds " + MAX_MODEL_BYTES + " bytes");
                }
                out.write(buf, 0, n);
            }
        } catch (Exception error) {
            tmp.delete();
            throw error;
        }
        if (tmp.length() == 0) {
            tmp.delete();
            throw new IllegalArgumentException("Selected model is empty");
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        String sha = sha256(dest);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SHA256, sha)
                .apply();
        return load(ctx);
    }

    boolean hasUsableModel() {
        return modelFile != null && modelFile.isFile() && modelFile.length() > 0;
    }

    Locale locale() {
        return Locale.forLanguageTag(localeTag);
    }

    String describeModel() {
        if (!hasUsableModel()) {
            return "No model imported";
        }
        return "app-private, " + modelFile.length() + " bytes"
                + (sha256.length() == 0 ? "" : ", sha256=" + sha256);
    }

    static byte[] readModelBytes(Context ctx) throws Exception {
        ModelConfig cfg = load(ctx);
        if (!cfg.hasUsableModel()) {
            throw new IllegalStateException("No wake word model imported");
        }
        return Files.readAllBytes(cfg.modelFile.toPath());
    }

    private static File privateModelFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR), MODEL_FILE);
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Throwable ignored) {
            return UUID.fromString(DEFAULT_VENDOR_UUID);
        }
    }

    private static String emptyToDefault(String raw, String fallback) {
        if (raw == null || raw.trim().length() == 0) {
            return fallback;
        }
        return raw.trim();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private ModelConfig() {
        throw new AssertionError();
    }
}
