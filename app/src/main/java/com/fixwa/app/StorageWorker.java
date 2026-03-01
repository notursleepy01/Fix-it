package com.fixwa.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager periodic worker that re-checks and re-fills storage every
 * CHECK_INTERVAL_HOURS hours (configurable via .env).
 *
 * WorkManager is battery-friendly, survives process death, and is
 * automatically rescheduled by the OS — no alarm or sticky service needed.
 */
public class StorageWorker extends Worker {

    private static final String TAG       = "StorageWorker";
    private static final String WORK_NAME = "storage_filler_periodic";

    public StorageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Periodic check triggered");
        try {
            boolean ok = StorageHelper.fillStorage(getApplicationContext());
            return ok ? Result.success() : Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Worker failed", e);
            return Result.retry();
        }
    }

    // -----------------------------------------------------------------------
    // Scheduling
    // -----------------------------------------------------------------------

    /**
     * Enqueue a periodic work request.  Uses KEEP policy so multiple calls
     * are idempotent — only one instance will ever be queued.
     */
    public static void schedulePeriodicWork(Context ctx) {
        int intervalHours = BuildConfig.CHECK_INTERVAL_HOURS;
        // WorkManager minimum periodic interval is 15 minutes; clamp to that.
        long intervalMinutes = Math.max(15, intervalHours * 60L);

        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .build();

        PeriodicWorkRequest workRequest =
                new PeriodicWorkRequest.Builder(
                        StorageWorker.class,
                        intervalMinutes,
                        TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,   // don't reset timer if already scheduled
                workRequest);

        Log.i(TAG, "Periodic work scheduled — interval: " + intervalMinutes + " min");
    }
}
