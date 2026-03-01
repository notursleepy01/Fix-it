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

        File extStorage = Environment.getExternalStorageDirectory();
        long freeBytes  = getFreeBytes(extStorage);

        // Sum up all existing filler files (published + pending)
        long existingTotal = getTotalFillerSize(ctx);
        long realFree      = freeBytes + existingTotal;
        long targetTotal   = Math.max(0, realFree - keepFreeBytes);

        Log.i(TAG, String.format(
                "free=%,d MB  existing=%,d MB  target=%,d MB",
                freeBytes     / (1024 * 1024),
                existingTotal / (1024 * 1024),
                targetTotal   / (1024 * 1024)));

        if (targetTotal == 0) {
            Log.i(TAG, "Nothing to fill.");
            return true;
        }

        // Skip if already within 1 MB of target
        if (Math.abs(existingTotal - targetTotal) < 1024L * 1024L) {
            Log.i(TAG, "Filler already correct size — skipping.");
            return true;
        }

        // Resize existing files to share the target evenly, or create new ones
        return resizeFillers(ctx, targetTotal);
    }

    // -----------------------------------------------------------------------
    // MediaStore helpers
    // -----------------------------------------------------------------------

    /** Collection URI for Downloads — works for arbitrary binary files, no permission needed. */
    private static Uri getCollection() {
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /** Query all our filler files (published + pending) and return a map of id→size. */
    private static java.util.Map<Long, Long> queryFillers(Context ctx) {
        java.util.Map<Long, Long> map = new java.util.LinkedHashMap<>();
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING
        };
        // Match any display name starting with our base name
        String sel    = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = { FILENAME + "%" };

        try (Cursor c = ctx.getContentResolver().query(
                getCollection(), proj, sel, args, null)) {
            while (c != null && c.moveToNext()) {
                long id   = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                long size = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
                map.put(id, size);
            }
        } catch (Exception e) {
            Log.w(TAG, "queryFillers: " + e.getMessage());
        }
        return map;
    }

    /** Sum of all existing filler file sizes. */
    private static long getTotalFillerSize(Context ctx) {
        long total = 0;
        for (long size : queryFillers(ctx).values()) total += size;
        return total;
    }

    /**
     * Resize existing filler files so they together equal {@code targetTotal}.
     * Each file gets an equal share (targetTotal / count).
     * If no files exist yet, creates one.
     */
    private static boolean resizeFillers(Context ctx, long targetTotal) {
        java.util.Map<Long, Long> existing = queryFillers(ctx);

        if (existing.isEmpty()) {
            // No existing files — create a single new one
            return writeMediaStoreFile(ctx, targetTotal);
        }

        int count        = existing.size();
        long sharePerFile = targetTotal / count;
        boolean ok       = true;

        for (long id : existing.keySet()) {
            Uri uri = ContentUris.withAppendedId(getCollection(), id);
            ok &= resizeUri(ctx, uri, sharePerFile);
        }
        return ok;
    }

    /** Resize an existing MediaStore entry to {@code newSize} bytes using fallocate. */
    private static boolean resizeUri(Context ctx, Uri uri, long newSize) {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) return false;
            Os.posix_fallocate(pfd.getFileDescriptor(), 0, newSize);
            Log.i(TAG, String.format("Resized %s → %,d MB", uri, newSize / (1024 * 1024)));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "resizeUri fallocate failed, trying truncate: " + e.getMessage());
            // Fallback: truncate/extend with RandomAccessFile via fd
            try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw")) {
                if (pfd == null) return false;
                android.system.OsConstants.class.getField("F_OK"); // dummy to keep import
                new java.io.FileOutputStream(pfd.getFileDescriptor()).getChannel().truncate(newSize);
                return true;
            } catch (Exception e2) {
                Log.e(TAG, "resizeUri failed: " + e2.getMessage());
                return false;
            }
        }
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
