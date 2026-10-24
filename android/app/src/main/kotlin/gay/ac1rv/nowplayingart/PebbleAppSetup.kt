package gay.ac1rv.nowplayingart

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PebbleAppSetup {
    private const val TAG = "PebbleAppSetup"

    /** Official Core Pebble app (Play Store: Core Devices / Pebble). */
    val CORE_PEBBLE_PACKAGES = listOf(
        "coredevices.coreapp",
        "io.rebble.android",
        "com.getpebble.android.basalt",
        "com.getpebble.android.app",
    )

    fun bootstrap(context: Context) {
        ClassicPebbleBridge.ensureWatchReceiver(context) {
            MediaRefresh.pushNow(context, force = true)
        }
        CoroutineScope(Dispatchers.Main).launch {
            preferCorePebbleApp(context)
        }
    }

    fun installedPebblePhoneApps(context: Context): List<String> {
        val pm = context.packageManager
        return CORE_PEBBLE_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private suspend fun preferCorePebbleApp(context: Context) {
        val installed = installedPebblePhoneApps(context)
        Log.i(TAG, "Pebble phone apps installed: $installed")
        if (installed.isEmpty()) return
        val picker = DefaultPebbleAndroidAppPicker.getInstance(context.applicationContext)
        val preferred = installed.firstOrNull { it == "coredevices.coreapp" } ?: installed.first()
        try {
            picker.selectApp(preferred)
            Log.i(TAG, "PebbleKit2 using phone app: $preferred")
        } catch (e: Exception) {
            Log.w(TAG, "selectApp($preferred)", e)
        }
    }
}
