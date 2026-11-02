package com.azayn.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts the call recorder daemon without a computer: over Wireless debugging the app talks to the
 * phone's own adbd (see [Adb]) and launches the daemon as the shell user, straight from this APK.
 */
object RecorderControl {
    private const val PREFS = "recorder"
    private const val ADB_WIFI = "adb_wifi_enabled" // Settings.Global key behind "Wireless debugging"

    data class Prefs(val paired: Boolean, val autoStart: Boolean, val wirelessOffAfter: Boolean)

    private val _prefs = MutableStateFlow(Prefs(paired = false, autoStart = true, wirelessOffAfter = true))
    val prefs: StateFlow<Prefs> = _prefs

    /** Progress of a start or pairing, for the UI. Null when idle. */
    val busy = MutableStateFlow<String?>(null)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _prefs.value = Prefs(p.getBoolean("paired", false), p.getBoolean("autoStart", true), p.getBoolean("wirelessOffAfter", true))
    }

    fun update(ctx: Context, prefs: Prefs) {
        _prefs.value = prefs
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("paired", prefs.paired)
            .putBoolean("autoStart", prefs.autoStart)
            .putBoolean("wirelessOffAfter", prefs.wirelessOffAfter)
            .apply()
    }

    fun developerOptionsOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun wirelessDebuggingOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, ADB_WIFI, 0) == 1

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Granted to us over ADB during setup; lets us switch Wireless debugging on and off ourselves. */
    private fun canToggleWireless(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun setWireless(ctx: Context, on: Boolean) =
        runCatching { Settings.Global.putInt(ctx.contentResolver, ADB_WIFI, if (on) 1 else 0) }.isSuccess

    /**
     * Starts (or restarts) the daemon. Blocking; call off the main thread. Returns null on success,
     * else a message for the user.
     */
    fun start(ctx: Context): String? = try {
        busy.value = "Starting the recorder…"
        doStart(ctx).also { android.util.Log.i("AzaynRecorder", "start: ${it ?: "ok"}") }
    } finally {
        busy.value = null
    }

    private fun doStart(ctx: Context): String? {
        if (!prefs.value.paired) return "Set up the recorder first: pair with Wireless debugging."
        if (!onWifi(ctx)) return "Connect to Wi-Fi. Wireless debugging only works on Wi-Fi."
        var turnedOn = false
        if (!wirelessDebuggingOn(ctx)) {
            if (!canToggleWireless(ctx) || !setWireless(ctx, true)) return "Turn on Wireless debugging in Developer options."
            turnedOn = true
            Thread.sleep(2500) // adbd needs a moment to start listening
        }
        busy.value = "Connecting…"
        try {
            if (!Adb.connect(ctx)) return "Couldn't reach Wireless debugging. Is it on?"
            busy.value = "Starting the recorder…"
            Adb.shell(ctx, grantCommand(ctx))
            Adb.shell(ctx, launchCommand(ctx))
        } catch (e: AdbPairingRequiredException) {
            update(ctx, prefs.value.copy(paired = false))
            return "This phone no longer trusts the app. Pair again."
        } catch (e: Exception) {
            return "Couldn't start the recorder: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            Adb.disconnect(ctx)
        }
        val running = waitForHeartbeat()
        if (prefs.value.wirelessOffAfter && (turnedOn || canToggleWireless(ctx))) setWireless(ctx, false)
        return if (running) null else "The recorder didn't start. Try again."
    }

    /** One-time grants the setup needs; harmless to repeat. */
    private fun grantCommand(ctx: Context): String {
        val pkg = ctx.packageName
        return listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "appops set $pkg MANAGE_EXTERNAL_STORAGE allow",
            "pm grant $pkg android.permission.READ_CONTACTS",
            "pm grant $pkg android.permission.RECORD_AUDIO",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm grant $pkg android.permission.ACCESS_LOCAL_NETWORK",
        ).joinToString("; ") + "; true"
    }

    /**
     * Stops a running daemon (it finalizes any recording on SIGTERM), then starts this APK's version,
     * detached from adbd so it outlives the connection and Wireless debugging being turned off.
     */
    private fun launchCommand(ctx: Context): String {
        val apk = ctx.applicationInfo.sourceDir
        val pidFile = "/data/local/tmp/callrec.pid"
        return "p=\$(cat $pidFile 2>/dev/null); " +
            "if [ -n \"\$p\" ] && grep -q com.azayn.recorder.daemon /proc/\$p/cmdline 2>/dev/null; then kill \$p; sleep 2; fi; " +
            "mkdir -p ${Store.root.path}; " +
            "CLASSPATH=$apk nohup setsid app_process / com.azayn.recorder.daemon.Main ${Store.root.path} " +
            ">/data/local/tmp/callrec.log 2>&1 </dev/null & echo started"
    }

    private fun waitForHeartbeat(): Boolean {
        val since = System.currentTimeMillis()
        repeat(20) {
            val s = Store.status()
            if (s.running && s.heartbeat >= since - 1000) return true
            Thread.sleep(500)
        }
        return false
    }
}
