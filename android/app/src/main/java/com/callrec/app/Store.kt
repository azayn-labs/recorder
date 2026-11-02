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

    /** Folder (next to the app folders) for recordings made with the in-app microphone recorder. */
    const val MEMOS = "Recordings"
    val memoDir get() = File(root, MEMOS)

    /**
     * A call is CallRecorder/<app>/<contact>/<name>.m4a, a voice recording is CallRecorder/Recordings/<name>.m4a.
     * <name> is YYYY-MM-DD-HH-MM-SS until the user renames it.
     */
    data class Recording(val file: File, val app: String, val contact: String?, val time: Long) {
        val isMemo get() = app == MEMOS
        /** User-given name, or null if the file still has its timestamp name. */
        val customName: String? get() = file.nameWithoutExtension.takeUnless { STAMP.matches(it) }
        val group get() = Group(app, contact)
    }

    /** Recordings are listed per (app, contact); voice recordings form one group with contact == null. */
    data class Group(val app: String, val contact: String?) {
        val title get() = contact ?: "Voice recordings"
    }

    private val STAMP = Regex("""\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}(-\d+)?""")
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    fun newMemoFile(): File {
        memoDir.mkdirs()
        return File(memoDir, stampFormat.format(Date()) + ".m4a")
    }

    fun recordings(): List<Recording> {
        val apps = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return apps.flatMap { appDir ->
            if (appDir.name == MEMOS) {
                audioFiles(appDir).map { recording(it, MEMOS, null) }
            } else {
                (appDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()).flatMap { contactDir ->
                    audioFiles(contactDir).map { recording(it, appDir.name, contactDir.name) }
                }
            }
        }.sortedByDescending { it.time }
    }

    private fun audioFiles(dir: File) = dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a") }.orEmpty().toList()

    fun memo(file: File) = recording(file, MEMOS, null)

    private fun recording(file: File, app: String, contact: String?): Recording {
        val name = file.nameWithoutExtension
        val time = if (STAMP.matches(name)) {
            runCatching { stampFormat.parse(name.take(19))!!.time }.getOrNull()
        } else null
        // Renamed files carry their start time as mtime (see rename()).
        return Recording(file, app, contact, time ?: file.lastModified())
    }

    /** Characters that can't be in a file name. Returns null if the name is unusable. */
    fun cleanName(name: String): String? =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "").trim().trimStart('.').take(120).ifEmpty { null }

    /** Renames a recording in place. Returns the renamed recording, or null if the name is taken or invalid. */
    fun rename(rec: Recording, newName: String): Recording? {
        val clean = cleanName(newName) ?: return null
        val dest = File(rec.file.parentFile, "$clean.m4a")
        if (dest == rec.file) return rec
        if (dest.exists() || !rec.file.renameTo(dest)) return null
        // Once the timestamp is gone from the name, the file's mtime is the recording's date; make it the start time.
        dest.setLastModified(rec.time)
        durations.remove(rec.file.path)
        return rec.copy(file = dest)
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
        // Drop the contact folder once its last recording is gone.
        val dir = rec.file.parentFile
        if (!rec.isMemo && dir != null && dir.list().isNullOrEmpty()) dir.delete()
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
