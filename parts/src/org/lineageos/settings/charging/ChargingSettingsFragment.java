/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charging;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;

import org.lineageos.settings.R;

public class ChargingSettingsFragment extends PreferenceFragment {
    private static final long REFRESH_MS = 2000;

    private Preference mStatus;
    private ListPreference mMode;
    private ListPreference mLimit;
    private ListPreference mCoolTemp;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.charging_prefs);
        final Context ctx = getActivity();

        mStatus = findPreference("chg_status");
        mMode = findPreference("chg_mode");
        mLimit = findPreference("chg_limit_w");
        mCoolTemp = findPreference("chg_cool_temp");

        buildLadder();

        mMode.setValue(ChargingConfig.mode(ctx));
        mLimit.setValue(String.valueOf(ChargingConfig.limitW(ctx)));
        mCoolTemp.setValue(String.valueOf(ChargingConfig.coolTargetTemp(ctx)));

        mMode.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                String mode = (String) value;
                applyMode(mode);
                updateVisibility(mode);
                if (ChargingConfig.MODE_EXTREME.equals(mode)) {
                    showInfo(getString(R.string.charging_extreme_title),
                            getString(R.string.charging_extreme_message));
                } else if (ChargingConfig.MODE_LIMIT.equals(mode)) {
                    showInfo(getString(R.string.charging_limit_title_warning),
                            getString(R.string.charging_limit_message));
                }
                return true;
            }
        });

        mLimit.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                int w = Integer.parseInt((String) value);
                ChargingConfig.prefs(ctx).edit()
                        .putInt(ChargingConfig.KEY_LIMIT, w).apply();
                if (w >= 16000) {
                    showInfo(getString(R.string.charging_limit_title_warning),
                            getString(R.string.charging_high_message));
                }
                if (ChargingConfig.MODE_LIMIT.equals(ChargingConfig.mode(ctx))) {
                    applyMode(ChargingConfig.MODE_LIMIT);
                }
                return true;
            }
        });

        mCoolTemp.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                int t = Integer.parseInt((String) value);
                ChargingConfig.prefs(ctx).edit()
                        .putInt(ChargingConfig.KEY_COOL_TEMP, t).apply();
                return true;
            }
        });

        if (!ChargingConfig.supported()) {
            mMode.setEnabled(false);
            mLimit.setEnabled(false);
            mCoolTemp.setEnabled(false);
            mStatus.setSummary("Not supported on this device");
        }

        updateVisibility(ChargingConfig.mode(ctx));
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefresh);
    }

    private void buildLadder() {
        int[] ladder = ChargingConfig.ladderW();
        CharSequence[] entries = new CharSequence[ladder.length];
        CharSequence[] values = new CharSequence[ladder.length];
        for (int i = 0; i < ladder.length; i++) {
            values[i] = String.valueOf(ladder[i]);
            if (ladder[i] >= 27500) {
                entries[i] = "Max (27 W)";
            } else {
                entries[i] = (ladder[i] / 1000) + " W";
            }
        }
        mLimit.setEntries(entries);
        mLimit.setEntryValues(values);
    }

    private void updateVisibility(String mode) {
        boolean limit = ChargingConfig.MODE_LIMIT.equals(mode);
        boolean cool = ChargingConfig.MODE_COOL.equals(mode);
        mLimit.setVisible(limit);
        mCoolTemp.setVisible(cool);
    }

    private void applyMode(String mode) {
        final Context ctx = getActivity();
        ChargingConfig.setMode(ctx, mode);
        Intent intent = new Intent(ctx, ChargingService.class);
        if (ChargingConfig.MODE_OFF.equals(mode)) {
            ctx.stopService(intent);
            ChargingController.restoreStock();
        } else {
            ctx.startForegroundService(intent);
        }
    }

    private void refreshStatus() {
        if (mStatus == null) return;
        if (!ChargingController.isCharging()) {
            mStatus.setSummary(getString(R.string.charging_status_unknown));
            return;
        }
        int soc = ChargingController.soc();
        int batt = ChargingController.battTemp();
        int cp = ChargingController.cpDieTemp();
        double in = ChargingController.inputWatt();
        double out = ChargingController.batteryWatt();
        mStatus.setSummary(String.format(java.util.Locale.US,
                "%s \u00b7 %d%% \u00b7 %.1f W in / %.1f W batt \u00b7 %.1f \u00b0C batt \u00b7 CP %.1f \u00b0C",
                ChargingConfig.modeLabel(ChargingConfig.mode(getActivity())),
                soc, in, out, batt / 10.0, cp / 10.0));
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}