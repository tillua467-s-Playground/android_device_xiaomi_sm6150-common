/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Enforces "stock on unplug" and re-applies the saved mode on the next plug.
 */
public class PowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (!ChargingConfig.MODE_OFF.equals(ChargingConfig.mode(context))) {
                Intent svc = new Intent(context, ChargingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            context.stopService(new Intent(context, ChargingService.class));
            ChargingController.restoreStock();
        }
    }
}