package com.fixwa.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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

        // Delete ALL existing filler entries (published + pending) first
        // so we always have a clean single file with the exact right size
        deleteAllFillers(ctx);

        // Measure free space after deletion
        long freeBytes  = getFreeBytes(Environment.getExternalStorageDirectory());
        long targetSize = Math.max(0, freeBytes - keepFreeBytes);

        Log.i(TAG, String.format(
                "free=%,d MB  keepFree=%,d MB  target=%,d MB",
                freeBytes    / (1024 * 1024),
                keepFreeBytes / (1024 * 1024),
                targetSize   / (1024 * 1024)));

        if (targetSize == 0) {
            Log.i(TAG, "Nothing to fill — less than KEEP_FREE_MB available.");
            return true;
        }

        return writeMediaStoreFile(ctx, targetSize);
    }

    // -----------------------------------------------------------------------
    // MediaStore helpers
    // -----------------------------------------------------------------------

    /** Collection URI for Downloads — works for arbitrary binary files, no permission needed. */
    private static Uri getCollection() {
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /** Delete ALL filler entries (published + pending) from MediaStore. */
    private static void deleteAllFillers(Context ctx) {
        // Collect all IDs first, then delete each by URI
        // (querying with LIKE catches both fixwa_filler.bin and .pending-xxx-fixwa_filler.bin)
        String[] proj = { MediaStore.MediaColumns._ID };
        String sel    = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = { "%" + FILENAME + "%" };

        List<Long> ids = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(
                getCollection(), proj, sel, args, null)) {
            while (c != null && c.moveToNext()) {
                ids.add(c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)));
            }
        } catch (Exception e) {
            Log.w(TAG, "deleteAllFillers query: " + e.getMessage());
        }

        for (long id : ids) {
            Uri uri = ContentUris.withAppendedId(getCollection(), id);
            try {
                ctx.getContentResolver().delete(uri, null, null);
                Log.i(TAG, "Deleted filler id=" + id);
            } catch (Exception e) {
                Log.w(TAG, "Could not delete id=" + id + ": " + e.getMessage());
            }
        }
        Log.i(TAG, "Deleted " + ids.size() + " filler entries.");
    }

    /**
     * Insert a new file into MediaStore Downloads and write
     * {@code size} bytes. No storage permission needed on API 29+.
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

            // Try fallocate first — instant on ext4/f2fs, no actual I/O
            boolean allocated = false;
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(itemUri, "rw")) {
                if (pfd != null) {
                    Os.posix_fallocate(pfd.getFileDescriptor(), 0, size);
                    allocated = true;
                    Log.i(TAG, "fallocate succeeded");
                }
            } catch (Exception e) {
                Log.w(TAG, "fallocate failed, falling back to write: " + e.getMessage());
            }

            // Fallback: write in large chunks if fallocate not supported
            if (!allocated) {
                final int CHUNK = 8 * 1024 * 1024; // 8 MB chunks
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
            }

            // Publish — mark as no longer pending
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(itemUri, cv, null, null);

            Log.i(TAG, String.format("Filler ready: %,d MB", size / (1024 * 1024)));
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
