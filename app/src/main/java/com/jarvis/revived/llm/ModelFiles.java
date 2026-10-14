package com.jarvis.revived.llm;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Where the model lives: app-private storage, {@code files/models/model.gguf}.
 *
 * The user picks a .gguf once with the system file picker (no storage
 * permission needed); it is copied in atomically. Play-compliant: no
 * MANAGE_EXTERNAL_STORAGE, no hard-coded Download paths.
 */
public final class ModelFiles {

    public interface Progress {
        void onProgress(long copiedBytes, long totalBytes);
    }

    private static final String DIR = "models";
    private static final String NAME = "model.gguf";
    private static final byte[] GGUF_MAGIC = {'G', 'G', 'U', 'F'};
    /** Anything smaller is certainly not a usable chat model. */
    private static final long MIN_BYTES = 50L * 1024 * 1024;

    private ModelFiles() {
    }

    public static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), DIR), NAME);
    }

    public static boolean hasModel(Context context) {
        File f = modelFile(context);
        return f.isFile() && f.length() >= MIN_BYTES;
    }

    /** Human-readable display name of a picked document, or "". */
    public static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
    }

    /**
     * Copies a picked GGUF into app storage: .tmp first, check magic + size,
     * fsync, then rename over the old model. Blocking; call off the main thread.
     */
    public static File importFrom(Context context, Uri uri, Progress progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        File target = modelFile(context);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create model folder");
        }

        long total = -1L;
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) total = c.getLong(0);
        } catch (RuntimeException ignored) {
            // size unknown; progress will show bytes only
        }

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        if (tmp.exists() && !tmp.delete()) throw new IOException("Cannot clear old temp file");

        long copied = 0L;
        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp, false)) {
            if (in == null) throw new IOException("Cannot open the picked file");
            byte[] buf = new byte[1 << 20];
            int n;
            boolean first = true;
            while ((n = in.read(buf)) > 0) {
                if (first) {
                    first = false;
                    if (n < 4 || buf[0] != GGUF_MAGIC[0] || buf[1] != GGUF_MAGIC[1]
                            || buf[2] != GGUF_MAGIC[2] || buf[3] != GGUF_MAGIC[3]) {
                        throw new IOException("That file is not a GGUF model");
                    }
                }
                out.write(buf, 0, n);
                copied += n;
                if (progress != null) progress.onProgress(copied, total);
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }

        if (copied < MIN_BYTES || (total > 0 && copied != total)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException(String.format(Locale.US, "Copy incomplete (%d of %d bytes)", copied, total));
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot replace the old model");
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot move the model into place");
        }
        return target;
    }
}
