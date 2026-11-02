package com.azayn.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** "Recording now" notifications, shown as Android Live Updates (status bar chip / Samsung Now Bar). */
object Notifications {
    private const val CHANNEL = "recording"
    const val ID_CALL = 2
    const val ID_VOICE = 1

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("recorder") // voice recorder's channel from before it shared this one
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording in progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** Ongoing notification with a running timer since [since] and the given buttons. */
    fun ongoing(ctx: Context, title: String, text: String?, since: Long, actions: List<Notification.Action>): Notification {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(title)
            .apply { if (!text.isNullOrBlank()) setContentText(text) }
            .setWhen(since)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setRequestPromotedOngoing(true)
            .setContentIntent(open)
            .apply { actions.forEach(::addAction) }
            .build()
    }

    fun action(ctx: Context, title: String, intent: PendingIntent) =
        Notification.Action.Builder(null, title, intent).build()
}

/**
 * Receives recording state from the call recorder daemon (adb shell uid). Exported but guarded by
 * android.permission.DUMP in the manifest, so only shell/system can send it.
 */
class CallNotifier : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (intent.getStringExtra("state") != "recording") {
            nm.cancel(Notifications.ID_CALL)
            return
        }
        val app = intent.getStringExtra("app").orEmpty().ifEmpty { "call" }
        val peer = intent.getStringExtra("peer")
        val since = intent.getLongExtra("since", System.currentTimeMillis())
        val n = Notifications.ongoing(
            ctx,
            title = if (app == "Phone") "Recording phone call" else "Recording $app call",
            text = peer,
            since = since,
            actions = listOf(
                Notifications.action(ctx, "Stop", CallControl.pending(ctx, "stop")),
                Notifications.action(ctx, "Discard", CallControl.pending(ctx, "discard")),
            ),
        )
        nm.notify(Notifications.ID_CALL, n)
    }
}

/** Notification buttons for a call recording: hands the command to the daemon via <root>/.control. */
class CallControl : BroadcastReceiver() {
    companion object {
        fun pending(ctx: Context, command: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, command.hashCode(),
            Intent(ctx, CallControl::class.java).setAction(command),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val command = intent.action ?: return
        runCatching {
            // Write-then-rename: a file created in place isn't seen by the daemon (it keeps polling a cached miss).
            val tmp = File(Store.root, ".control.tmp")
            tmp.writeText(command)
            tmp.renameTo(File(Store.root, ".control"))
        }
        // The daemon confirms by broadcasting "idle"; hide the buttons' notification right away anyway.
        ctx.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_CALL)
    }
}
