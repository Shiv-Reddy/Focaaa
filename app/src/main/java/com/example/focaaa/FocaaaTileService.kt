package com.example.focaaa

import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.example.focaaa.chat.ChatSessionNotification

class FocaaaTileService : TileService() {
    private val gesturePreferences by lazy { getSharedPreferences("focaaa_tile_gesture", MODE_PRIVATE) }

    override fun onStartListening() {
        super.onStartListening()
        qsTile.label = getString(R.string.quick_settings_tile_label)
        qsTile.contentDescription = getString(R.string.quick_settings_tile_label)
        qsTile.icon = Icon.createWithResource(this, R.drawable.ic_quick_settings)
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!registerTileTap()) return

        val notificationManager = NotificationManagerCompat.from(this)
        if (!notificationManager.areNotificationsEnabled()) {
            Toast.makeText(this, "Enable Focaaa notifications to use notification-panel chat.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = getSystemService(android.app.NotificationManager::class.java)
                ?.getNotificationChannel(ChatSessionNotification.CHANNEL_ID)
            if (channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                Toast.makeText(this, "Enable the Focaaa notification-panel chat channel in Android Settings.", Toast.LENGTH_LONG).show()
                return
            }
        }
        ChatSessionNotification.activate(applicationContext)
    }

    private fun registerTileTap(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previousTap = gesturePreferences.getLong(KEY_LAST_TAP, 0L)
        val previousCount = gesturePreferences.getInt(KEY_TAP_COUNT, 0)
        val elapsed = now - previousTap
        val count = if (previousTap > 0 && elapsed in 0L..TRIPLE_TAP_WINDOW_MS) previousCount + 1 else 1
        if (count >= REQUIRED_TAPS) {
            gesturePreferences.edit().remove(KEY_LAST_TAP).remove(KEY_TAP_COUNT).apply()
            return true
        }
        gesturePreferences.edit().putLong(KEY_LAST_TAP, now).putInt(KEY_TAP_COUNT, count).apply()
        return false
    }

    companion object {
        private const val REQUIRED_TAPS = 3
        private const val TRIPLE_TAP_WINDOW_MS = 3_000L
        private const val KEY_LAST_TAP = "last_tap_elapsed"
        private const val KEY_TAP_COUNT = "tap_count"
    }
}
