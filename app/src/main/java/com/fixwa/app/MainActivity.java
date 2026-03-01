package com.fixwa.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * One-time launcher activity.
 *
 * Flow:
 *   1. User taps "Fix WA" button.
 *   2. Requests battery optimisation exemption (system dialog).
 *   3. Starts FillStorageService + schedules WorkManager.
 *   4. Disables its own launcher alias so it never appears in the app drawer again.
 *   5. Finishes itself.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        Button btnActivate = findViewById(R.id.btn_activate);

        btnActivate.setOnClickListener(v -> activate(btnActivate));
    }

    private void activate(Button btn) {
        btn.setEnabled(false);
        tvStatus.setText("Starting…");

        // 1. Request battery optimisation exemption
        requestBatteryExemption();

        // 2. Start the service
        FillStorageService.start(this);

        // 3. Schedule periodic WorkManager job
        StorageWorker.schedulePeriodicWork(this);

        tvStatus.setText("Done! Fix-WA is now running in the background.\nThis icon will disappear.");

        // 4. Hide launcher icon after a short delay so user can see the status
        btn.postDelayed(this::hideLauncherAndFinish, 2000);
    }

    private void hideLauncherAndFinish() {
        try {
            // Disable the launcher activity alias so it vanishes from the app drawer
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            Log.i(TAG, "Launcher icon hidden");
        } catch (Exception e) {
            Log.w(TAG, "Could not hide launcher: " + e.getMessage());
        }
        finish();
    }

    private void requestBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Battery exemption request failed: " + e.getMessage());
        }
    }
}
