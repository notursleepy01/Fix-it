package com.fixwa.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Utility class that handles storage calculation and dummy-file management.
 *
 * Strategy:
 *   Primary:  MediaStore (Pictures/) — no permissions needed, survives app uninstall.
 *   Fallback: App-private external → internal storage.
 *
 * The filler file is sized so exactly KEEP_FREE_MB of free space remains.
 */
public final class StorageHelper {

    private static final String TAG            = "StorageHelper";
    private static final String FILENAME       = "fixwa_filler.bin";
    private static final String MEDIA_SUBDIR   = "Fix-WA";

    private StorageHelper() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static boolean fillStorage(Context ctx) {
        long keepFreeBytes = BuildConfig.KEEP_FREE_MB * 1024L * 1024L;

        // Use the primary shared storage for free-space measurement
        File extStorage = Environment.getExternalStorageDirectory();
        long freeBytes  = getFreeBytes(extStorage);

        // Find existing filler size via MediaStore
        long existingSize = getMediaStoreFileSize(ctx);
        long realFree     = freeBytes + existingSize;
        long targetSize   = Math.max(0, realFree - keepFreeBytes);

        Log.i(TAG, String.format(
                "free=%,d MB  existing=%,d MB  target=%,d MB",
                freeBytes    / (1024 * 1024),
                existingSize / (1024 * 1024),
                targetSize   / (1024 * 1024)));

        if (targetSize == 0) {
            Log.i(TAG, "Nothing to fill — deleting filler if present.");
            deleteMediaStoreFile(ctx);
            return true;
        }

        // Skip rewrite if within 1 MB of target
        if (Math.abs(existingSize - targetSize) < 1024L * 1024L) {
            Log.i(TAG, "Filler already correct size — skipping.");
            return true;
        }

        // Delete old entry first so we start fresh
        deleteMediaStoreFile(ctx);

        // Write via MediaStore — no permission needed on API 29+
        return writeMediaStoreFile(ctx, targetSize);
    }

    // -----------------------------------------------------------------------
    // MediaStore helpers
    // -----------------------------------------------------------------------

    /** Collection URI for Downloads — works for arbitrary binary files, no permission needed. */
    private static Uri getCollection() {
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /** Returns the size in bytes of our filler entry in MediaStore, or 0. */
    private static long getMediaStoreFileSize(Context ctx) {
        String[] proj = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE };
        String sel    = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] args = { FILENAME };

        try (Cursor c = ctx.getContentResolver().query(
                getCollection(), proj, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
            }
        } catch (Exception e) {
            Log.w(TAG, "getMediaStoreFileSize: " + e.getMessage());
        }
        return 0L;
    }

    /** Delete our filler entry from MediaStore. */
    private static void deleteMediaStoreFile(Context ctx) {
        String sel    = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] args = { FILENAME };
        try {
            int deleted = ctx.getContentResolver().delete(getCollection(), sel, args);
            if (deleted > 0) Log.i(TAG, "Filler deleted from MediaStore.");
        } catch (Exception e) {
            Log.w(TAG, "deleteMediaStoreFile: " + e.getMessage());
        }
    }

    /**
     * Insert a new file into MediaStore Downloads and write
     * {@code size} bytes of zeroes. No storage permission needed on API 29+.
     */
    private static boolean writeMediaStoreFile(Context ctx, long size) {
        ContentResolver cr = ctx.getContentResolver();

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME,  FILENAME);
        cv.put(MediaStore.MediaColumns.MIME_TYPE,     "application/octet-stream");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + MEDIA_SUBDIR);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri itemUri = null;
        try {
            itemUri = cr.insert(getCollection(), cv);
            if (itemUri == null) {
                Log.e(TAG, "MediaStore insert returned null");
                return false;
            }

            // Write actual bytes in 4 MB chunks so space is truly consumed
            final int CHUNK = 4 * 1024 * 1024;
            byte[] buf = new byte[CHUNK];
            try (OutputStream os = cr.openOutputStream(itemUri)) {
                if (os == null) throw new IOException("null OutputStream");
                long written = 0;
                while (written < size) {
                    int toWrite = (int) Math.min(CHUNK, size - written);
                    os.write(buf, 0, toWrite);
                    written += toWrite;
                }
            }

            // Publish — mark as no longer pending
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(itemUri, cv, null, null);

            Log.i(TAG, String.format("Filler written via MediaStore Downloads: %,d MB",
                    size / (1024 * 1024)));
            return true;

        } catch (Exception e) {
            Log.e(TAG, "writeMediaStoreFile failed: " + e.getMessage(), e);
            if (itemUri != null) {
                try { cr.delete(itemUri, null, null); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Fallback helpers
    // -----------------------------------------------------------------------

    private static long getFreeBytes(File dir) {
        StatFs stat = new StatFs(dir.getAbsolutePath());
        return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
    }
}
