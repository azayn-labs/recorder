package com.callrec.app

import android.media.MediaMetadataRetriever
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Files shared with the recorder daemon (see daemon/src/com/callrec/Main.java):
 *   CallRecorder/<App>/<yyyy-MM-dd_HH-mm-ss>[_<peer>].m4a   recordings
 *   CallRecorder/.config.json                               settings, written here
 *   CallRecorder/.status.json                               daemon heartbeat
 */
object Store {
    val root = File(Environment.getExternalStorageDirectory(), "CallRecorder")

    /** Config keys for per-app switches, in display order. Must match CallInfo.label() in the daemon. */
    val APPS = listOf("Phone", "WhatsApp", "WhatsApp Business", "Instagram", "Facebook", "Messenger", "Other")

    // ---- recordings ----

    data class Recording(val file: File, val app: String, val time: Long, val peer: String?) {
        val title: String get() = peer ?: "Unknown"
    }

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun recordings(): List<Recording> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.flatMap { dir ->
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a") } ?: emptyArray()).map { parse(it, dir.name) }
        }.sortedByDescending { it.time }
    }

    private fun parse(file: File, app: String): Recording {
        val base = file.name.removeSuffix(".m4a")
        val time = runCatching { stampFormat.parse(base.take(19))!!.time }.getOrElse { file.lastModified() }
        val peer = base.drop(20).ifBlank { null }
        return Recording(file, app, time, peer)
    }

    private val durations = ConcurrentHashMap<String, Long>()

    /** Duration in ms (blocking, cached). */
    fun durationMs(file: File): Long = durations.getOrPut(file.path) {
        runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(file.path)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            }
        }.getOrNull() ?: 0L
    }

    fun delete(rec: Recording) {
        rec.file.delete()
        durations.remove(rec.file.path)
    }

    // ---- config ----

    data class Blocked(val name: String, val numbers: List<String>)

    data class Config(
        val enabled: Boolean = true,
        val apps: Map<String, Boolean> = emptyMap(),
        val never: List<Blocked> = emptyList(),
    ) {
        fun appEnabled(app: String) = apps[app] ?: true
    }

    private val configFile get() = File(root, ".config.json")

    fun loadConfig(): Config {
        val o = runCatching { JSONObject(configFile.readText()) }.getOrNull() ?: return Config()
        val apps = o.optJSONObject("apps")
        val never = o.optJSONArray("never") ?: JSONArray()
        return Config(
            enabled = o.optBoolean("enabled", true),
            apps = apps?.keys()?.asSequence()?.associateWith { apps.getBoolean(it) } ?: emptyMap(),
            never = (0 until never.length()).map { i ->
                val e = never.getJSONObject(i)
                val nums = e.optJSONArray("numbers") ?: JSONArray()
                Blocked(e.optString("name"), (0 until nums.length()).map { nums.getString(it) })
            },
        )
    }

    fun saveConfig(c: Config) {
        val o = JSONObject()
            .put("enabled", c.enabled)
            .put("apps", JSONObject(c.apps))
            .put("never", JSONArray(c.never.map { b ->
                JSONObject().put("name", b.name).put("numbers", JSONArray(b.numbers))
            }))
        root.mkdirs()
        val tmp = File(root, ".config.json.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(configFile)
    }

    // ---- daemon status ----

    data class Status(
        val running: Boolean,
        val state: String = "idle",
        val app: String? = null,
        val peer: String? = null,
        val since: Long = 0,
        val reason: String? = null,
    )

    fun status(): Status {
        val o = runCatching { JSONObject(File(root, ".status.json").readText()) }.getOrNull()
            ?: return Status(running = false)
        val fresh = System.currentTimeMillis() - o.optLong("heartbeat") < 10_000
        if (!fresh) return Status(running = false)
        return Status(
            running = true,
            state = o.optString("state", "idle"),
            app = o.optString("app").ifEmpty { null },
            peer = o.optString("peer").ifEmpty { null },
            since = o.optLong("since"),
            reason = o.optString("reason").ifEmpty { null },
        )
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun formatTime(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
}
