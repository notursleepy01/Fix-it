package com.storagefiller.app;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Utility class that handles storage calculation and dummy-file management.
 *
 * Strategy:
 *   1. Try the app's private external storage directory (no permission needed).
 *   2. Fall back to internal app storage (getFilesDir).
 *
 * The dummy file is sized so that exactly KEEP_FREE_MB of free space remains
 * on the target volume after the file is written.
 */
public final class StorageHelper {

    private static final String TAG      = "StorageHelper";
    private static final String FILENAME = "filler.bin";

    private StorageHelper() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Create (or resize) the filler file so that
     *   freeSpaceAfter == KEEP_FREE_MB
     * Returns true if the file is in the desired state when we exit.
     */
    public static boolean fillStorage(Context ctx) {
        File dir = chooseDirectory(ctx);
        if (dir == null) {
            Log.e(TAG, "No writable directory found");
            return false;
        }

        long keepFreeBytes = BuildConfig.KEEP_FREE_MB * 1024L * 1024L;
        long freeBytes     = getFreeBytes(dir);
        File filler        = new File(dir, FILENAME);

        // How large should the filler be?
        //   currentFree  = freeBytes
        //   fillerSize   = max(0, currentFree - keepFreeBytes)  [if file doesn't exist yet]
        // But if the file already exists we need to account for space it already occupies:
        //   realFree     = freeBytes + existingFillerSize
        //   newFillerSize = max(0, realFree - keepFreeBytes)
        long existingSize  = filler.exists() ? filler.length() : 0L;
        long realFree      = freeBytes + existingSize;          // space we can use
        long targetSize    = Math.max(0, realFree - keepFreeBytes);

        Log.i(TAG, String.format(
                "dir=%s  free=%,d MB  existing=%,d MB  target=%,d MB",
                dir, freeBytes / (1024 * 1024),
                existingSize  / (1024 * 1024),
                targetSize    / (1024 * 1024)));

        if (targetSize == 0) {
            Log.i(TAG, "Less than KEEP_FREE_MB available — nothing to fill.");
            deleteFiller(filler);
            return true;
        }

        // Only rewrite if size differs by more than 1 MB (avoid thrashing)
        if (Math.abs(existingSize - targetSize) < 1024L * 1024L) {
            Log.i(TAG, "Filler already correct size — skipping write.");
            return true;
        }

        return writeFiller(filler, targetSize);
    }

    /** Delete the filler file if it exists (e.g. for cleanup). */
    public static void deleteFiller(Context ctx) {
        File dir = chooseDirectory(ctx);
        if (dir == null) return;
        deleteFiller(new File(dir, FILENAME));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Pick the best writable directory. */
    private static File chooseDirectory(Context ctx) {
        // 1) External app-private storage (no permission on API 26+)
        File extDir = ctx.getExternalFilesDir(null);
        if (extDir != null
                && Environment.getExternalStorageState(extDir)
                              .equals(Environment.MEDIA_MOUNTED)
                && extDir.canWrite()) {
            return extDir;
        }
        // 2) Internal app storage (always available)
        File intDir = ctx.getFilesDir();
        if (intDir != null && (intDir.exists() || intDir.mkdirs()) && intDir.canWrite()) {
            return intDir;
        }
        return null;
    }

    /** Available (free) bytes on the volume that contains {@code dir}. */
    private static long getFreeBytes(File dir) {
        StatFs stat = new StatFs(dir.getAbsolutePath());
        return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
    }

    /**
     * Write (or truncate) {@code file} to exactly {@code size} bytes using
     * {@link RandomAccessFile#setLength}, which is an O(1) operation on
     * most file systems (sparse file / fallocate equivalent).
     */
    private static boolean writeFiller(File file, long size) {
        try {
            file.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(size);
            }
            Log.i(TAG, String.format("Filler written: %s (%,d MB)",
                    file.getAbsolutePath(), size / (1024 * 1024)));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write filler: " + e.getMessage(), e);
            return false;
        }
    }

    private static void deleteFiller(File filler) {
        if (filler.exists()) {
            filler.delete();
            Log.i(TAG, "Filler deleted.");
        }
    }
}
