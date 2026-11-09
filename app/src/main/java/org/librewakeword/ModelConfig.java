package org.librewakeword;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;
import org.librewakeword.dsp.MicroWakeWord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ModelConfig {
    /** Google's model, verified by SODA, or a microWakeWord model converted for the DSP. */
    enum Engine { GOOGLE, MICRO_WAKE_WORD }

    public static final String DEFAULT_VENDOR_UUID = "7038ddc8-30f2-11e6-b0ac-40a8f03d3f15";
    // Pixel's SODA maps x_google to keyphrase 2.
    public static final int DEFAULT_KEYPHRASE_ID = 2;
    public static final int DEFAULT_MODEL_VERSION = 36;
    public static final String DEFAULT_PHRASE = "Hey Google";
    public static final String DEFAULT_LOCALE = "en-US";

    private static final String PREFS = "model_config";
    private static final String KEY_PHRASE = "phrase";
    private static final String KEY_LOCALE = "locale";
    private static final String KEY_KEYPHRASE_ID = "keyphrase_id";
    private static final String KEY_VENDOR_UUID = "vendor_uuid";
    private static final String KEY_MODEL_VERSION = "model_version";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_WAKE_WORD = "wake_word";
    private static final String KEY_CUTOFF = "probability_cutoff";
    private static final String KEY_SLIDING_WINDOW = "sliding_window_size";
    private static final String MODEL_DIR = "models";
    private static final String MODEL_FILE = "current.sound_model";
    private static final String MICRO_WAKE_WORD_FILE = "microwakeword.tflite";
    private static final int MAX_MODEL_BYTES = 32 * 1024 * 1024;

    public final String phrase;
    public final String localeTag;
    final int keyphraseId;
    final UUID vendorUuid;
    final int modelVersion;
    final File modelFile;
    final Engine engine;
    /** microWakeWord detection threshold and sliding window, from its manifest. */
    final double probabilityCutoff;
    final int slidingWindowSize;

    private ModelConfig(String phrase, String localeTag, int keyphraseId, UUID vendorUuid,
            int modelVersion, File modelFile, Engine engine,
            double probabilityCutoff, int slidingWindowSize) {
        this.phrase = phrase;
        this.localeTag = localeTag;
        this.keyphraseId = keyphraseId;
        this.vendorUuid = vendorUuid;
        this.modelVersion = modelVersion;
        this.modelFile = modelFile;
        this.engine = engine;
        this.probabilityCutoff = probabilityCutoff;
        this.slidingWindowSize = slidingWindowSize;
    }

    /** The active model's configuration. */
    public static ModelConfig load(Context ctx) {
        return load(ctx, activeEngine(ctx));
    }

    /** The configuration of the stored model for [engine], active or not. */
    static ModelConfig load(Context ctx, Engine engine) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String localeTag = prefs.getString(KEY_LOCALE, DEFAULT_LOCALE);
        int keyphraseId = prefs.getInt(KEY_KEYPHRASE_ID, DEFAULT_KEYPHRASE_ID);
        int modelVersion = prefs.getInt(KEY_MODEL_VERSION, DEFAULT_MODEL_VERSION);
        UUID vendorUuid = parseUuid(prefs.getString(KEY_VENDOR_UUID, DEFAULT_VENDOR_UUID));
        // A microWakeWord model brings its own phrase; the Google metadata stays untouched.
        String phrase = engine == Engine.MICRO_WAKE_WORD
                ? prefs.getString(KEY_WAKE_WORD, "")
                : prefs.getString(KEY_PHRASE, DEFAULT_PHRASE);
        File file = modelFile(ctx, engine);
        File modelFile = file.isFile() && file.length() > 0 ? file : null;
        return new ModelConfig(phrase, localeTag, keyphraseId, vendorUuid, modelVersion,
                modelFile, engine, Double.parseDouble(prefs.getString(KEY_CUTOFF, "0")),
                prefs.getInt(KEY_SLIDING_WINDOW, 1));
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

    /**
     * Imports a microWakeWord model from its .tflite and .json (in either order) and makes it the
     * active model. It is converted here to report problems now, and again at every enrollment.
     */
    static ModelConfig importMicroWakeWord(Context ctx, List<Uri> uris) throws Exception {
        if (uris.size() != 2) {
            throw new IllegalArgumentException("Select the model's .tflite and .json files");
        }
        byte[] first = readAll(ctx, uris.get(0));
        byte[] second = readAll(ctx, uris.get(1));
        boolean firstIsModel = isTflite(first);
        byte[] tflite = firstIsModel ? first : second;
        byte[] manifest = firstIsModel ? second : first;
        if (!isTflite(tflite)) {
            throw new IllegalArgumentException("No .tflite model among the selected files");
        }
        JSONObject json = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
        JSONObject micro = json.getJSONObject("micro");
        if (micro.optInt("feature_step_size", 10) != 10) {
            throw new IllegalArgumentException("Only models with 10 ms feature steps are supported");
        }
        String wakeWord = json.getString("wake_word");
        double cutoff = micro.getDouble("probability_cutoff");
        int window = micro.getInt("sliding_window_size");
        MicroWakeWord.INSTANCE.convert(tflite, cutoff, window);

        File dest = modelFile(ctx, Engine.MICRO_WAKE_WORD);
        writeAtomically(dest, new ByteArrayInputStream(tflite));
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENGINE, Engine.MICRO_WAKE_WORD.name())
                .putString(KEY_WAKE_WORD, wakeWord)
                .putString(KEY_CUTOFF, Double.toString(cutoff))
                .putInt(KEY_SLIDING_WINDOW, window)
                .apply();
        return load(ctx);
    }

    private static boolean isTflite(byte[] data) {
        return data.length >= 8 && data[4] == 'T' && data[5] == 'F' && data[6] == 'L'
                && data[7] == '3';
    }

    private static byte[] readAll(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Could not open " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_MODEL_BYTES) {
                    throw new IllegalArgumentException("Selected file exceeds " + MAX_MODEL_BYTES
                            + " bytes");
                }
            }
            return out.toByteArray();
        }
    }

    private static ModelConfig importModel(Context ctx, InputStream in) throws Exception {
        writeAtomically(modelFile(ctx, Engine.GOOGLE), in);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENGINE, Engine.GOOGLE.name())
                .apply();
        return load(ctx);
    }

    private static void writeAtomically(File dest, InputStream in) throws Exception {
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create model dir: " + dir);
        }
        File tmp = new File(dir, dest.getName() + ".tmp");
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
        if (engine == Engine.MICRO_WAKE_WORD) {
            return "microWakeWord, runs on the DSP alone (cutoff "
                    + String.format(Locale.ROOT, "%.2f", probabilityCutoff) + ")";
        }
        return "Google model, checked by SODA";
    }

    /** The sound model to enroll: Google's as imported, microWakeWord converted now. */
    static byte[] readModelBytes(Context ctx) throws Exception {
        ModelConfig cfg = load(ctx);
        if (!cfg.hasUsableModel()) {
            throw new IllegalStateException("No wake word model imported");
        }
        byte[] data = Files.readAllBytes(cfg.modelFile.toPath());
        if (cfg.engine == Engine.MICRO_WAKE_WORD) {
            return MicroWakeWord.INSTANCE.convert(data, cfg.probabilityCutoff,
                    cfg.slidingWindowSize);
        }
        return data;
    }

    static Engine activeEngine(Context ctx) {
        return Engine.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENGINE, Engine.GOOGLE.name()));
    }

    /** Makes a stored model the active one. */
    static void setActiveEngine(Context ctx, Engine engine) {
        if (!load(ctx, engine).hasUsableModel()) {
            throw new IllegalStateException("No " + engine + " model stored");
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENGINE, engine.name())
                .apply();
    }

    private static File modelFile(Context ctx, Engine engine) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR),
                engine == Engine.MICRO_WAKE_WORD ? MICRO_WAKE_WORD_FILE : MODEL_FILE);
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

    private ModelConfig() {
        throw new AssertionError();
    }
}
