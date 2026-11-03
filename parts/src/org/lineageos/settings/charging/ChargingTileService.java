/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charging;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ChargingTileService extends TileService {
    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        boolean on = ChargingConfig.MODE_EXTREME.equals(ChargingConfig.mode(this));
        String mode = on ? ChargingConfig.MODE_OFF : ChargingConfig.MODE_EXTREME;
        ChargingConfig.setMode(this, mode);

        if (on) {
            stopService(new Intent(this, ChargingService.class));
            ChargingController.restoreStock();
        } else {
            startForegroundService(new Intent(this, ChargingService.class));
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = ChargingConfig.MODE_EXTREME.equals(ChargingConfig.mode(this));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.charging_tile_title));
        tile.updateTile();
    }
}