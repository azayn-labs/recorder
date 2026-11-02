package com.callrec.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Microphone recorder for voice recordings, running as a foreground service so it survives screen-off. */
class RecorderService : Service() {

    /** Recording in progress (file and start time), or null. */
    data class Active(val file: File, val startedAt: Long)

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "recorder"

        private val _active = MutableStateFlow<Active?>(null)
        val active: StateFlow<Active?> = _active

        /** The last finished recording, so the UI can offer to rename it. */
        val finished = MutableStateFlow<File?>(null)

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (recorder == null) begin()
            ACTION_STOP -> end()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val file = Store.newMemoFile()
        try {
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file)
                prepare()
                start()
            }
            _active.value = Active(file, System.currentTimeMillis())
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            file.delete()
            stopSelf()
        }
    }

    private fun end() {
        val rec = recorder
        val file = _active.value?.file
        recorder = null
        _active.value = null
        if (rec != null) {
            // stop() throws if nothing was captured yet (stopped immediately); the file is useless then.
            val ok = runCatching { rec.stop() }.isSuccess
            rec.release()
            if (ok) finished.value = file else file?.delete()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) end()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recorder", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording…")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
