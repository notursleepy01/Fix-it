package com.fixwa.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Transparent, no-history activity that runs exactly once on first launch.
 *
 * It does three things then immediately finishes:
 *   1. Requests battery optimisation exemption (so Android never kills us).
 *   2. Starts the FillStorageService right away.
 *   3. Schedules the WorkManager periodic job.
 *
 * After this, the launcher icon can be hidden — the app runs entirely in
 * the background via BootReceiver + WorkManager.
 */
public class StartupActivity extends Activity {

    private static final String TAG = "StartupActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "First launch — bootstrapping...");

        // 1. Request battery optimisation exemption if not already granted
        requestBatteryOptimisationExemption();

        // 2. Start the fill service immediately
        FillStorageService.start(this);

        // 3. Schedule periodic WorkManager job
        StorageWorker.schedulePeriodicWork(this);

        // Done — finish so nothing appears on screen
        finish();
    }

    private void requestBatteryOptimisationExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Log.i(TAG, "Requested battery optimisation exemption");
            } else {
                Log.i(TAG, "Battery optimisation already exempted");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not request battery exemption: " + e.getMessage());
        }
    }
}
