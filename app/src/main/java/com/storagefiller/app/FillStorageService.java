package com.storagefiller.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that fills storage once and then stops itself.
 *
 * Running as a foreground service prevents Android from killing the process
 * while the (potentially slow) file write is in progress.  Once the work is
 * done the service stops itself — it does NOT stay resident.  WorkManager
 * handles periodic re-scheduling.
 */
public class FillStorageService extends Service {

    private static final String TAG              = "FillStorageService";
    private static final String CHANNEL_ID       = "storage_filler_channel";
    private static final int    NOTIFICATION_ID  = 1001;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");

        // Must call startForeground() before doing any real work (API 26+)
        startForeground(NOTIFICATION_ID, buildNotification());

        // Do the work on a background thread so we never block the main thread
        new Thread(() -> {
            try {
                StorageHelper.fillStorage(getApplicationContext());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error", e);
            } finally {
                // Schedule next periodic check via WorkManager (idempotent)
                StorageWorker.schedulePeriodicWork(getApplicationContext());
                stopSelf(startId);
                Log.i(TAG, "Service stopped");
            }
        }, "fill-storage-thread").start();

        // Do not restart the service if the process is killed during a write;
        // WorkManager will re-trigger on the next scheduled window.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Storage Filler",
                NotificationManager.IMPORTANCE_MIN   // silent, no heads-up
        );
        channel.setDescription("Maintains storage filler file");
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Storage Filler")
                .setContentText("Maintaining storage…")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    // -----------------------------------------------------------------------
    // Static helper — start this service from anywhere
    // -----------------------------------------------------------------------

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, FillStorageService.class);
        ctx.startForegroundService(intent);
    }
}
