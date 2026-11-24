package com.azayn.recorder

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlin.concurrent.thread

/**
 * Keeps the recorder running. It dies whenever adbd stops (see [RecorderControl.start]), and Android
 * stops adbd when Wi-Fi drops. So when Wi-Fi comes back we start it again, with two triggers:
 * - a network callback that fires [WifiReceiver] as soon as a Wi-Fi network is available, and
 * - a periodic job as a backstop, in case Android drops that callback.
 * Both end in [RecorderJob], which does nothing if the recorder is already running. A job and not a
 * foreground service, since Android doesn't let a background broadcast start a foreground service.
 */
object RecorderWatch {
    private const val JOB_NOW = 1001
    private const val JOB_PERIODIC = 1002

    /** Idempotent; call when the app starts and after a reboot. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val pi = PendingIntent.getBroadcast(
                app, 0, Intent(app, WifiReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifi, pi)
        }.onFailure { android.util.Log.w("AzaynRecorder", "watch: network callback", it) }

        val js = app.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(
                JobInfo.Builder(JOB_PERIODIC, ComponentName(app, RecorderJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    /** Checks soon, once there's an unmetered (normally Wi-Fi) network. */
    fun checkSoon(ctx: Context) {
        val app = ctx.applicationContext
        app.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB_NOW, ComponentName(app, RecorderJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build(),
        )
    }
}

/** A Wi-Fi network became available (from [RecorderWatch]'s network callback). */
class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        RecorderWatch.checkSoon(ctx)
    }
}

/** Starts the recorder if it's off, we're on Wi-Fi, and the user wants it started automatically. */
class RecorderJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                RecorderControl.load(this)
                val p = RecorderControl.prefs.value
                if (p.paired && p.autoStart && RecorderControl.busy.value == null &&
                    !Store.status().running && RecorderControl.onWifi(this)
                ) {
                    android.util.Log.i("AzaynRecorder", "watch: recorder is off, starting it")
                    RecorderControl.start(this)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
