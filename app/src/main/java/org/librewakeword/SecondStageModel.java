package org.librewakeword;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;

import javax.net.ssl.HttpsURLConnection;

final class SecondStageModel {
    private static final String TAG = "SecondStageModel";
    private static final String DIR = "models";
    private static final String FILE = "x_hotword.data";
    private static final String PREFS = "second_stage_model";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final String KEY_SHA256 = "sha256";
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;

    /*
     * The en-US entry in Velvet's HotwordModelConfigProto asset. Velvet registers it with its
     * SodaResourceManager as MDD group "hotword_en-US", then downloads the file named
     * "hotword.data" from this URL. This is the resource endpoint used by the real cascade,
     * rather than a guessed Assistant RPC.
     *
     * The resource itself is mutable on Google's side (the current response is newer than the
     * factory-image x_hotword.data); this URL is only the locale-selection bootstrap supplied by
     * the recovered Velvet configuration.
     */
    private static final String ENGLISH_HIGH_TRAFFIC_URL =
            "https://www.gstatic.com/android-search/hotword/x_google/"
                    + "2dffe6834b5f79de5f001f1cc71b08ea/hotword.data";

    static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), DIR), FILE);
    }

    static boolean hasModel(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() > 0;
    }

    static String describe(Context context) {
        File file = modelFile(context);
        if (!file.isFile()) {
            return "No second-stage model imported";
        }
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            String hash = preferences.getString(KEY_SHA256, "");
            if (hash.length() == 0) {
                hash = sha256(file);
                preferences.edit().putString(KEY_SHA256, hash).apply();
            }
            String description = file.length() + " bytes, sha256=" + hash;
            String source = preferences.getString(KEY_SOURCE, "");
            return source.length() == 0 ? description : description + ", source=" + source;
        } catch (Throwable t) {
            return file.length() + " bytes";
        }
    }

    static void importFile(Context context, File source) throws Exception {
        if (!source.isFile() || source.length() == 0) {
            throw new IllegalArgumentException("Second-stage model missing or empty: " + source);
        }
        try (InputStream in = new FileInputStream(source)) {
            importStream(context, in);
        }
    }

    static void importUri(Context context, Uri source) throws Exception {
        InputStream stream = context.getContentResolver().openInputStream(source);
        if (stream == null) {
            throw new IllegalArgumentException("Could not open selected second-stage model");
        }
        try (InputStream in = stream) {
            importStream(context, in);
        }
    }

    /**
     * Fetches the current published x_google SODA resource selected by Velvet's en-US model
     * configuration. The downloaded bytes are initialized by SODA before they replace the
     * working file, so a bad response never disarms an otherwise working verifier.
     */
    static FetchResult refreshPublishedEnglishModel(Context context) throws Exception {
        File dest = modelFile(context);
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create model directory: " + dir);
        }
        File tmp = new File(dir, FILE + ".download");
        if (tmp.exists() && !tmp.delete()) {
            throw new IllegalStateException("Could not clear old download: " + tmp);
        }

        HttpsURLConnection connection = null;
        try {
            URL url = new URL(ENGLISH_HIGH_TRAFFIC_URL);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int response = connection.getResponseCode();
            if (response != HttpsURLConnection.HTTP_OK) {
                throw new IllegalStateException("Published model request returned HTTP " + response);
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength <= 0 || declaredLength > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("Unexpected published model size: " + declaredLength);
            }

            long bytes = copyBounded(connection.getInputStream(), tmp, MAX_DOWNLOAD_BYTES);
            if (bytes != declaredLength) {
                throw new IllegalStateException("Published model short read: " + bytes + " of "
                        + declaredLength + " bytes");
            }
            byte[] model = readFile(tmp);
            // This exercises the same SODA init shape used by the isolated HDS, before commit.
            SodaHotwordVerifier.validateModel(context, model);

            String downloadedSha = sha256(tmp);
            boolean changed = !dest.isFile() || !downloadedSha.equals(sha256(dest));
            if (changed) {
                replaceAtomically(dest, tmp);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SOURCE, "Velvet SRM published en-US resource")
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .putString(KEY_SHA256, downloadedSha)
                    .apply();
            return new FetchResult(changed, bytes, downloadedSha, ENGLISH_HIGH_TRAFFIC_URL,
                    connection.getLastModified());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void importStream(Context context, InputStream in) throws Exception {
        File dest = modelFile(context);
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create model dir: " + dir);
        }
        File tmp = new File(dir, FILE + ".tmp");
        if (tmp.exists() && !tmp.delete()) {
            throw new IllegalStateException("Could not clear stale model import");
        }
        try {
            copyBounded(in, tmp, MAX_DOWNLOAD_BYTES);
            SodaHotwordVerifier.validateModel(context, readFile(tmp));
            String hash = sha256(tmp);
            replaceAtomically(dest, tmp);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(KEY_SOURCE)
                    .remove(KEY_FETCHED_AT)
                    .putString(KEY_SHA256, hash)
                    .apply();
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    static byte[] read(Context context) throws Exception {
        return readFile(modelFile(context));
    }

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off != bytes.length) {
                throw new IllegalStateException("Short read: " + off + " of " + bytes.length);
            }
            return bytes;
        }
    }

    private static long copyBounded(InputStream input, File dest, int maxBytes) throws Exception {
        long total = 0;
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                total += count;
                if (total > maxBytes) {
                    throw new IllegalStateException(
                            "Second-stage model exceeds " + maxBytes + " bytes");
                }
                out.write(buffer, 0, count);
            }
        }
        if (total == 0) {
            throw new IllegalStateException("Second-stage model was empty");
        }
        return total;
    }

    private static void replaceAtomically(File dest, File tmp) throws Exception {
        File dir = dest.getParentFile();
        File backup = new File(dir, FILE + ".previous");
        if (backup.exists()) {
            if (!dest.exists()) {
                if (!backup.renameTo(dest)) {
                    throw new IllegalStateException(
                            "Could not restore previous second-stage model backup");
                }
            } else if (!backup.delete()) {
                throw new IllegalStateException("Could not remove stale model backup");
            }
        }
        boolean hadExisting = dest.exists();
        if (hadExisting && !dest.renameTo(backup)) {
            throw new IllegalStateException("Could not preserve existing second-stage model");
        }
        if (!tmp.renameTo(dest)) {
            boolean restored = !hadExisting || backup.renameTo(dest);
            throw new IllegalStateException(restored
                    ? "Could not install second-stage model"
                    : "Could not install second-stage model or restore its backup at " + backup);
        }
        if (backup.exists() && !backup.delete()) {
            Log.w(TAG, "Installed model, but could not delete old backup " + backup);
        }
    }

    static final class FetchResult {
        final boolean changed;
        final long bytes;
        final String sha256;
        final String sourceUrl;
        final long remoteLastModified;

        FetchResult(boolean changed, long bytes, String sha256, String sourceUrl,
                long remoteLastModified) {
            this.changed = changed;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.sourceUrl = sourceUrl;
            this.remoteLastModified = remoteLastModified;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private SecondStageModel() {
        throw new AssertionError();
    }
}
