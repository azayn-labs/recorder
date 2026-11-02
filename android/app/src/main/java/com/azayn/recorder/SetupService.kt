package com.azayn.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

/**
 * Short-lived foreground service for the two things that need ADB: pairing with Wireless debugging
 * (watching for the pairing service and taking the 6-digit code, in-app or from a notification reply)
 * and starting the recorder daemon (from the app, after a reboot, or after an app update).
 */
class SetupService : Service() {
    companion object {
        private const val ACTION_PAIR = "pair"
        private const val ACTION_CODE = "code"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        const val ACTION_BOOT = "boot"
        private const val EXTRA_CODE = "code"
        private const val CHANNEL = "setup"
        private const val ID = 3
        private const val PAIR_TIMEOUT_MS = 5 * 60_000L

        /** Pairing service found (Wireless debugging's "Pair with code" dialog is open). */
        val pairingPort = MutableStateFlow<Pair<String, Int>?>(null)
        val pairing = MutableStateFlow(false)
        /** Last outcome to show in the app: null = nothing to report. */
        val message = MutableStateFlow<String?>(null)

        fun startPairing(ctx: Context) = send(ctx, ACTION_PAIR)
        fun submitCode(ctx: Context, code: String) = send(ctx, ACTION_CODE) { putExtra(EXTRA_CODE, code) }
        fun startRecorder(ctx: Context) = send(ctx, ACTION_START)
        fun cancel(ctx: Context) = send(ctx, ACTION_CANCEL)

        private fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            ctx.startForegroundService(Intent(ctx, SetupService::class.java).setAction(action).apply(extras))
        }
    }

    private var mdns: AdbMdns? = null
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RecorderControl.load(this)
        foreground(note("Azayn Recorder", "Setting up…"))
        when (intent?.action) {
            ACTION_PAIR -> listenForPairing()
            ACTION_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                    ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_CODE)?.toString()
                if (code.isNullOrBlank()) listenForPairing() else pair(code)
            }
            ACTION_START -> startRecorder(boot = false)
            ACTION_BOOT -> startRecorder(boot = true)
            ACTION_CANCEL -> finish()
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun listenForPairing() {
        pairing.value = true
        message.value = null
        nm().notify(ID, note("Pair with Wireless debugging", "In Wireless debugging, tap \"Pair device with pairing code\"."))
        if (mdns == null) {
            mdns = Adb.discoverPairing(this) { host, port ->
                pairingPort.value = host to port
                nm().notify(ID, codeNote())
            }
        }
        thread(isDaemon = true) {
            Thread.sleep(PAIR_TIMEOUT_MS)
            if (pairing.value && !working) finish()
        }
    }

    private fun pair(code: String) {
        val target = pairingPort.value
        if (target == null) {
            message.value = "Open \"Pair device with pairing code\" in Wireless debugging first."
            listenForPairing()
            return
        }
        if (working) return
        working = true
        nm().notify(ID, note("Pairing…", null))
        thread {
            val ok = runCatching { Adb.pair(this, target.first, target.second, code) }
            working = false
            if (ok.getOrDefault(false)) {
                RecorderControl.update(this, RecorderControl.prefs.value.copy(paired = true))
                stopPairingDiscovery()
                startRecorder(boot = false)
            } else {
                message.value = "Pairing failed. Check the code and try again (it changes each time the dialog opens)."
                nm().notify(ID, codeNote(retry = true))
            }
        }
    }

    private fun startRecorder(boot: Boolean) {
        if (working) return
        working = true
        nm().notify(ID, note("Starting the recorder…", null))
        thread {
            if (boot) waitForWifi()
            val error = RecorderControl.start(this)
            working = false
            message.value = error
            if (error != null) {
                // Leave a tappable notice; the app shows the same message on its status card.
                nm().notify(ID + 1, note("Recorder not started", error, ongoing = false))
            }
            finish()
        }
    }

    /** After a reboot Wi-Fi can take a while; Wireless debugging needs it. */
    private fun waitForWifi() {
        repeat(60) {
            if (RecorderControl.onWifi(this)) return
            Thread.sleep(2000)
        }
    }

    private fun stopPairingDiscovery() {
        mdns?.stop()
        mdns = null
        pairing.value = false
        pairingPort.value = null
    }

    private fun finish() {
        stopPairingDiscovery()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopPairingDiscovery()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun foreground(n: Notification) =
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

    private fun note(title: String, text: String?, ongoing: Boolean = true): Notification {
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "Recorder setup", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)) }
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    /** "Enter pairing code" with an inline reply field, so the code can be typed without leaving Settings. */
    private fun codeNote(retry: Boolean = false): Notification {
        val reply = PendingIntent.getForegroundService(
            this, 1, Intent(this, SetupService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(null, "Enter code", reply)
            .addRemoteInput(RemoteInput.Builder(EXTRA_CODE).setLabel("6-digit pairing code").build())
            .build()
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, SetupService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(if (retry) "Wrong code? Try again" else "Pairing code")
            .setContentText("Type the 6-digit code shown in Wireless debugging.")
            .setOngoing(true)
            .setOnlyAlertOnce(!retry)
            .addAction(action)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }
}

/** After a reboot or an app update the recorder isn't running (or runs old code); start it again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        android.util.Log.i("AzaynRecorder", "auto-start: ${intent.action}")
        RecorderControl.load(ctx)
        val p = RecorderControl.prefs.value
        if (p.paired && p.autoStart) {
            ctx.startForegroundService(Intent(ctx, SetupService::class.java).setAction(SetupService.ACTION_BOOT))
        }
    }
}
