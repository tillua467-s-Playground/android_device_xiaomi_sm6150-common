/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charging;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;

public class ChargingService extends Service {
    private static final String TAG = "ChargingService";

    private static final String CHANNEL_ID = "charging_modes";
    private static final int NOTIF_ID = 0x43;
    private static final long TICK_MS = 2000;
    private static final double CP_THRESHOLD_W = 16.0;
    private static final double CP_CEILING_UA = 2_600_000;
    private static final double CTM_CEILING_UA = 2_800_000;
    private static final int HISTORY = 30;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Double> mHistory = new ArrayDeque<>();

    private String mMode = ChargingConfig.MODE_OFF;
    private boolean mRunning;
    private boolean mPaused;
    private boolean mUseCp = true;
    private boolean mChargerLimitedNotified;
    private boolean mLimitNotified;
    private double mPowerCap = 14.0;
    private long mLastCoolAdjust;
    private double mPeakW;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            tick();
            if (mRunning) mHandler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mMode = ChargingConfig.mode(this);
        if (ChargingConfig.MODE_OFF.equals(mMode)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification(
                ChargingConfig.modeLabel(mMode) + " \u2014 starting", ""));

        if (!mRunning) {
            mRunning = true;
            mHistory.clear();
            mChargerLimitedNotified = false;
            mLimitNotified = false;
            mPaused = false;
            mUseCp = true;
            mPowerCap = 14.0;
            mPeakW = 0;
            mHandler.post(mTick);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        mHandler.removeCallbacks(mTick);
        ChargingController.restoreStock();
        ChargingConfig.prefs(this).edit()
                .putFloat(ChargingConfig.KEY_PEAK_W, (float) mPeakW)
                .apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void tick() {
        if (!ChargingController.isCharging()) {
            stopAndRestore();
            return;
        }

        int soc = ChargingController.soc();
        int batt = ChargingController.battTemp();
        int cpDie = ChargingController.cpDieTemp();
        double watts = ChargingController.inputWatt();

        if (watts > mPeakW) mPeakW = watts;

        // Hard safety net, independent of the selected mode.
        if (batt > ChargingConfig.safetyMaxBatt()
                || cpDie > ChargingConfig.safetyMaxCpDie()) {
            Log.w(TAG, "safety stop batt=" + batt + " cp=" + cpDie);
            ChargingController.restoreStock();
            mMode = ChargingConfig.MODE_OFF;
            ChargingConfig.setMode(this, mMode);
            stopAndRestore();
            return;
        }

        double target;
        switch (mMode) {
            case ChargingConfig.MODE_EXTREME:
                if (soc > ChargingConfig.extremeMaxSoc()) {
                    Log.i(TAG, "extreme finished at soc " + soc);
                    mMode = ChargingConfig.MODE_OFF;
                    ChargingConfig.setMode(this, mMode);
                    stopAndRestore();
                    return;
                }
                if (mPaused) {
                    if (batt <= ChargingConfig.extremeResumeTemp() && cpDie <= 680) {
                        mPaused = false;
                        Log.i(TAG, "extreme resumed");
                    }
                } else if (batt > ChargingConfig.extremeStopTemp() || cpDie > 750) {
                    mPaused = true;
                    Log.i(TAG, "extreme paused batt=" + batt + " cp=" + cpDie);
                    ChargingController.restoreStock();
                }
                if (mPaused) {
                    updateNotification("Paused \u2014 too hot",
                            batteryLine(soc, batt, cpDie, watts));
                    return;
                }
                target = 24.0;
                break;

            case ChargingConfig.MODE_COOL:
                target = coolTarget(batt, cpDie);
                break;

            case ChargingConfig.MODE_LIMIT:
            default:
                target = ChargingConfig.limitW(this) / 1000.0;
                if (batt > ChargingConfig.extremeStopTemp() || cpDie > 780) {
                    Log.w(TAG, "limit stopped batt=" + batt + " cp=" + cpDie);
                    mMode = ChargingConfig.MODE_OFF;
                    ChargingConfig.setMode(this, mMode);
                    stopAndRestore();
                    return;
                }
                break;
        }

        apply(target, watts);
        ChargingController.heartbeat();

        updateNotification(ChargingConfig.modeLabel(mMode)
                        + " \u2014 " + String.format("%.1f W", watts),
                batteryLine(soc, batt, cpDie, watts));

        checkChargerLimit(target, watts, soc);
    }

    private double coolTarget(int batt, int cpDie) {
        int want = ChargingConfig.coolTargetTemp(this);
        long now = System.currentTimeMillis();
        if (now - mLastCoolAdjust >= 10000) {
            mLastCoolAdjust = now;
            if (batt > want || cpDie > 700) {
                mPowerCap -= 1.0;
            } else if (batt <= want - 10) {
                mPowerCap += 0.5;
            }
            mPowerCap = Math.max(6.0, Math.min(20.0, mPowerCap));
        }
        return mPowerCap;
    }

    private void apply(double target, double watts) {
        ChargingController.ensureModeActive();

        boolean wantCp = target >= CP_THRESHOLD_W;
        if (wantCp != mUseCp) {
            mUseCp = wantCp;
            mHistory.clear();
            if (wantCp) {
                ChargingController.setCpEnabled(true);
                ChargingController.write(ChargingConfig.path("ctm",
                        "/sys/class/power_supply/usb/ctm_current_max"), -1);
            } else {
                ChargingController.clearOverride();
                ChargingController.setCpEnabled(false);
            }
        }

        if (mUseCp) {
            int cur = ChargingController.cpIlim();
            int next;
            if (cur <= 0) {
                next = 2_000_000;
            } else {
                next = cur + (int) ((target - watts) * 120_000);
            }
            ChargingController.setOverride(next);
        } else {
            int cur = ChargingController.ctm();
            int next;
            if (cur <= 0) {
                next = 1_500_000;
            } else {
                next = cur + (int) ((target - watts) * 100_000);
            }
            ChargingController.setCtm(next);
        }
    }

    private void checkChargerLimit(double target, double watts, int soc) {
        mHistory.addLast(watts);
        while (mHistory.size() > HISTORY) mHistory.removeFirst();

        if (mChargerLimitedNotified || mHistory.size() < HISTORY / 2) return;
        if (target <= 0) return;

        double sum = 0;
        for (double v : mHistory) sum += v;
        double avg = sum / mHistory.size();

        boolean atCeiling;
        if (mUseCp) {
            atCeiling = ChargingController.cpIlim() >= CP_CEILING_UA;
        } else {
            atCeiling = ChargingController.ctm() >= CTM_CEILING_UA;
        }

        if (avg < 0.9 * target && atCeiling) {
            mChargerLimitedNotified = true;
            updateNotification(
                    String.format("Charging at ~%.0f W", avg),
                    String.format("This %s charger can't supply the %.0f W you selected.",
                            ChargingController.chargerType(), target));
        }
    }

    private String batteryLine(int soc, int batt, int cpDie, double watts) {
        return String.format("%d%%  %.1f W in  %.1f W batt  %.1f C  CP %.1f C",
                soc, watts, ChargingController.batteryWatt(),
                batt / 10.0, cpDie / 10.0);
    }

    private void stopAndRestore() {
        if (!mRunning) return;
        mRunning = false;
        mHandler.removeCallbacks(mTick);
        ChargingController.restoreStock();
        ChargingConfig.prefs(this).edit()
                .putFloat(ChargingConfig.KEY_PEAK_W, (float) mPeakW)
                .apply();
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Charging modes", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Active charging mode and live power");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, ChargingActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(title)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (text != null && !text.isEmpty()) b.setContentText(text);
        return b.build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }
}