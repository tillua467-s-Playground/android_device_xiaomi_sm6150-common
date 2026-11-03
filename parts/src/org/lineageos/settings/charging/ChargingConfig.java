/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charging;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;

import androidx.preference.PreferenceManager;

/**
 * All device-specific values come from properties supplied by the device tree,
 * so this framework stays inert on any other sm6150 device.
 */
public final class ChargingConfig {
    public static final String PREFS = "charging";

    public static final String KEY_MODE = "chg_mode";
    public static final String KEY_LIMIT = "chg_limit_w";
    public static final String KEY_COOL_TEMP = "chg_cool_temp";
    public static final String KEY_PEAK_W = "chg_peak_w";
    public static final String KEY_LAST_W = "chg_last_w";

    public static final String MODE_OFF = "off";
    public static final String MODE_EXTREME = "extreme";
    public static final String MODE_COOL = "cool";
    public static final String MODE_LIMIT = "limit";

    private ChargingConfig() {
    }

    public static boolean supported() {
        return SystemProperties.getBoolean("ro.charging.modes.enabled", false);
    }

    public static String path(String key, String def) {
        return SystemProperties.get("ro.charging.path." + key, def);
    }

    /** persist.vendor.chg.<key> */
    public static int value(String key, int def) {
        return SystemProperties.getInt("persist.vendor.chg." + key, def);
    }

    public static String stringValue(String key, String def) {
        return SystemProperties.get("persist.vendor.chg." + key, def);
    }

    public static int extremeMaxSoc() {
        return value("extreme.max_soc", 60);
    }

    public static int extremeStopTemp() {
        return value("extreme.stop_batt_temp", 430);
    }

    public static int extremeResumeTemp() {
        return value("extreme.resume_batt_temp", 390);
    }

    public static int coolTargetTemp(Context c) {
        return intPref(c, KEY_COOL_TEMP, value("cool.target_temp", 380));
    }

    public static int safetyMaxBatt() {
        return value("safety.max_batt_temp", 450);
    }

    public static int safetyMaxCpDie() {
        return value("safety.max_cp_die", 780);
    }

    public static int[] ladderW() {
        String raw = stringValue("limit.steps_w", "8000,12000,16000,20000,27500");
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        return prefs(c).getString(KEY_MODE, MODE_OFF);
    }

    public static void setMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_MODE, mode).apply();
    }

    public static int limitW(Context c) {
        return intPref(c, KEY_LIMIT, value("limit.default_w", 20000));
    }

    public static int intPref(Context c, String key, int def) {
        Object o = prefs(c).getAll().get(key);
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof String) {
            try {
                return Integer.parseInt((String) o);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public static String modeLabel(String mode) {
        switch (mode) {
            case MODE_EXTREME:
                return "Extreme";
            case MODE_COOL:
                return "Cool";
            case MODE_LIMIT:
                return "Force limit";
            default:
                return "Off";
        }
    }
}