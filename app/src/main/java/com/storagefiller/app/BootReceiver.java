package com.storagefiller.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives BOOT_COMPLETED and LOCKED_BOOT_COMPLETED so the service starts
 * as early as possible after a reboot — even before the user unlocks the
 * device (directBootAware="true" in the manifest).
 *
 * Also handles MY_PACKAGE_REPLACED so the file is refreshed after an update.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "Received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            // Start the foreground service — it will schedule WorkManager too
            FillStorageService.start(context);
        }
    }
}
