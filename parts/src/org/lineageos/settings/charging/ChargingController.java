/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charging;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Thin sysfs layer. Every write is clamped; nothing here disables a kernel
 * protection (JEITA, SMB5 thermal regulation, SMB1390 hardware limit).
 */
public final class ChargingController {
    private static final String TAG = "ChargingController";

    public static final int ILIM_MIN = 300_000;
    public static final int ILIM_MAX = 2_750_000;
    public static final int CTM_MIN = 400_000;
    public static final int CTM_MAX = 3_000_000;

    private static final String DEF_BATT_TEMP =
            "/sys/class/power_supply/battery/temp";
    private static final String DEF_SOC =
            "/sys/class/power_supply/battery/capacity";
    private static final String DEF_STATUS =
            "/sys/class/power_supply/battery/status";
    private static final String DEF_VBAT =
            "/sys/class/power_supply/battery/voltage_now";
    private static final String DEF_IBAT =
            "/sys/class/power_supply/battery/current_now";
    private static final String DEF_CP_DIE =
            "/sys/class/power_supply/charge_pump_master/cp_die_temp";
    private static final String DEF_CP_ILIM =
            "/sys/class/power_supply/charge_pump_master/cp_ilim";
    private static final String DEF_CP_ENABLE =
            "/sys/class/power_supply/charge_pump_master/cp_enable";
    private static final String DEF_CTM =
            "/sys/class/power_supply/usb/ctm_current_max";
    private static final String DEF_VBUS =
            "/sys/class/power_supply/usb/voltage_now";
    private static final String DEF_IIN =
            "/sys/class/power_supply/usb/input_current_now";
    private static final String DEF_TEMP_LEVEL =
            "/sys/class/power_supply/battery/system_temp_level";
    private static final String DEF_DISABLE_THERMAL =
            "/sys/module/qpnp_smb5/parameters/disable_thermal";
    private static final String DEF_HEARTBEAT =
            "/sys/class/power_supply/battery/chg_mode_heartbeat";
    private static final String DEF_REAL_TYPE =
            "/sys/class/power_supply/usb/real_type";
    private static final String DEF_HVDCP3_TYPE =
            "/sys/class/power_supply/usb/hvdcp3_type";

    private ChargingController() {
    }

    private static String p(String key, String def) {
        return ChargingConfig.path(key, def);
    }

    public static int read(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            if (line == null) return -1;
            return Integer.parseInt(line.trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static boolean write(String path, int value) {
        FileWriter w = null;
        try {
            w = new FileWriter(path);
            w.write(Integer.toString(value));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "write " + path + "=" + value + " failed: " + e);
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static boolean exists(String path) {
        return new File(path).exists();
    }

    public static int soc() {
        return read(p("soc", DEF_SOC));
    }

    public static int battTemp() {
        return read(p("batt_temp", DEF_BATT_TEMP));
    }

    public static int cpDieTemp() {
        return read(p("cp_die", DEF_CP_DIE));
    }

    public static int cpIlim() {
        return read(p("cp_ilim", DEF_CP_ILIM));
    }

    /**
     * Reads the battery cooling device state.
     *
     * Not used: system_app has no read access to sysfs_thermal and granting it
     * would mean relabelling cooling_device13, which broke hal_thermal and
     * thermal-engine.  Kept for reference only.
     */
    public static int coolingState() {
        return -1;
    }

    public static boolean isCharging() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(p("status", DEF_STATUS)));
            String line = r.readLine();
            return line != null && !line.trim().equalsIgnoreCase("Discharging");
        } catch (Exception e) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static double inputWatt() {
        int uv = read(p("vbus", DEF_VBUS));
        int ua = read(p("ibus", DEF_IIN));
        if (uv <= 0 || ua <= 0) return 0.0;
        return (uv / 1.0e6) * (ua / 1.0e6);
    }

    public static double batteryWatt() {
        int uv = read(p("vbat", DEF_VBAT));
        int ua = read(p("ibat", DEF_IBAT));
        if (uv <= 0 || ua == 0) return 0.0;
        return (uv / 1.0e6) * (Math.abs(ua) / 1.0e6);
    }

    public static String chargerType() {
        int real = read(p("real_type", DEF_REAL_TYPE));
        int qc = read(p("hvdcp3_type", DEF_HVDCP3_TYPE));
        String name;
        switch (real) {
            case 1: name = "SDP"; break;
            case 2: name = "CDP"; break;
            case 3: name = "DCP"; break;
            case 4: name = "HVDCP2"; break;
            case 5: name = "HVDCP3"; break;
            case 6: name = "PD"; break;
            case 7: name = "PPS"; break;
            default: name = "unknown"; break;
        }
        if (qc > 0) name += " (class " + (qc == 2 ? "B" : String.valueOf(qc)) + ")";
        return name;
    }

    /* ---- actuator -------------------------------------------------- */

    public static void ensureModeActive() {
        /* block mi_thermald before touching the level */
        write(p("disable_thermal", DEF_DISABLE_THERMAL), 1);
        write(p("temp_level", DEF_TEMP_LEVEL), 0);
    }

    public static boolean setOverride(int ua) {
        int v = Math.max(ILIM_MIN, Math.min(ILIM_MAX, ua));
        return write(p("cp_ilim", DEF_CP_ILIM), v);
    }

    public static void clearOverride() {
        write(p("cp_ilim", DEF_CP_ILIM), 0);
    }

    public static void setCpEnabled(boolean on) {
        write(p("cp_enable", DEF_CP_ENABLE), on ? 1 : 0);
    }

    public static int ctm() {
        return read(p("ctm", DEF_CTM));
    }

    public static boolean setCtm(int ua) {
        int v = Math.max(CTM_MIN, Math.min(CTM_MAX, ua));
        return write(p("ctm", DEF_CTM), v);
    }

    public static void heartbeat() {
        write(p("heartbeat", DEF_HEARTBEAT), 1);
    }

    public static void heartbeatDisarm() {
        write(p("heartbeat", DEF_HEARTBEAT), 0);
    }

    public static void restoreStock() {
        clearOverride();
        write(p("ctm", DEF_CTM), -1);
        setCpEnabled(true);
        heartbeatDisarm();
        // Hand the thermal level back to mi_thermald: unblock its writes and
        // reset the level so its next vote is applied from a clean state.
        write(p("temp_level", DEF_TEMP_LEVEL), 0);
        write(p("disable_thermal", DEF_DISABLE_THERMAL), 0);
    }
}