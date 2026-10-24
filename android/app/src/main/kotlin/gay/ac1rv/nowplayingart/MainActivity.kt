package gay.ac1rv.nowplayingart

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val pebbleApps = PebbleAppSetup.installedPebblePhoneApps(this)
        findViewById<TextView>(R.id.pebbleAppText).text = when {
            pebbleApps.isEmpty() -> getString(R.string.pebble_app_missing)
            else -> getString(R.string.pebble_app_found, pebbleApps.joinToString(", "))
        }
        val enabled = isNotificationListenerEnabled()
        findViewById<TextView>(R.id.statusText).text = if (enabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        if (enabled) {
            NotificationManagerCompat.from(this).cancel(1)
            MediaRefresh.pushNow(this)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrEmpty()) return false
        val names = flat.split(":")
        val mine = ComponentName(this, MediaNotificationListener::class.java)
        return names.any { name ->
            val cn = ComponentName.unflattenFromString(name)
            cn != null && cn == mine
        }
    }
}
