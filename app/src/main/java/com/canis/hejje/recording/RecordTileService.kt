package com.canis.hejje.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.canis.hejje.HejjeApp
import com.canis.hejje.MainActivity
import com.canis.hejje.R
import com.canis.hejje.ui.formatter

/**
 * Quick Settings tile: start or stop recording a drive without opening the app, e.g. in a car
 * whose Bluetooth Hejje does not know. If Android does not let it start from there, it opens
 * Hejje, which starts the recording.
 */
class RecordTileService : TileService() {
    private val controller get() = (application as HejjeApp).container.controller

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val c = controller
        if (c.live.value != null) {
            c.manualStop()
            refresh()
            return
        }
        val canLocate = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (canLocate && c.tryManualStart()) {
            refresh()
            return
        }
        openApp(startRecording = canLocate)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val live = controller.live.value
        tile.state = if (live != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                live == null -> getString(R.string.tile_idle)
                live.hasFix -> getString(R.string.tile_recording_distance, formatter().distance(live.distanceM))
                else -> getString(R.string.tile_recording)
            }
        }
        tile.updateTile()
    }

    private fun openApp(startRecording: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_RECORDING, startRecording)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startBelow34(intent)
        }
    }

    // The PendingIntent version only exists from Android 14; older versions take the Intent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startBelow34(intent: Intent) = startActivityAndCollapse(intent)
}
