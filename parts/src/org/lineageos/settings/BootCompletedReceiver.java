/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import org.lineageos.settings.doze.DozeUtils;
import org.lineageos.settings.thermal.ThermalUtils;
import org.lineageos.settings.refreshrate.RefreshUtils;
import org.lineageos.settings.utils.FileUtils;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final boolean DEBUG = false;
    private static final String TAG = "XiaomiParts";
    private static final String KEY_BYPASS_CHARGING = "bypass_charging";
    private static final String BYPASS_NODE = "/sys/class/power_supply/battery/input_suspend";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (DEBUG)
            Log.d(TAG, "Received boot completed intent");
        DozeUtils.onBootCompleted(context);
        ThermalUtils.startService(context);
        RefreshUtils.startService(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean bypassEnabled = prefs.getBoolean(KEY_BYPASS_CHARGING, false);
        if (FileUtils.fileExists(BYPASS_NODE)) {
            FileUtils.writeLine(BYPASS_NODE, bypassEnabled ? "1" : "0");
        }
    }
}
