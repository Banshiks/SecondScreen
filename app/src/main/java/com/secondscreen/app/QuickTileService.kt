package com.secondscreen.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun isHostRunning(): Boolean {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (service.service.className == HostService::class.java.name) return true
        }
        return false
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (isHostRunning()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "SecondScreen"
            tile.contentDescription = "Остановить трансляцию"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "SecondScreen"
            tile.contentDescription = "Открыть SecondScreen"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isHostRunning()) {
            stopService(Intent(this, HostService::class.java))
            updateTile()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
