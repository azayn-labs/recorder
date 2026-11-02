package com.callrec.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val APP_COLORS = mapOf(
    "Phone" to Color(0xFF2E7D32),
    "WhatsApp" to Color(0xFF25D366),
    "WhatsApp Business" to Color(0xFF128C7E),
    "Instagram" to Color(0xFFC13584),
    "Facebook" to Color(0xFF1877F2),
    "Messenger" to Color(0xFF0084FF),
)

@Composable
fun RecordingsScreen(
    recordings: List<Store.Recording>,
    status: Store.Status,
    onDeleted: (Store.Recording) -> Unit,
    onNeverRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<Store.Recording?>(null) }
    var confirmDelete by remember { mutableStateOf<Store.Recording?>(null) }

    val apps = recordings.map { it.app }.distinct().sorted()
    val shown = recordings.filter {
        (appFilter == null || it.app == appFilter) &&
            (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true))
    }

    Column(modifier.fillMaxSize()) {
        StatusBanner(status)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by name or number") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (apps.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                item { FilterChip(appFilter == null, { appFilter = null }, { Text("All") }) }
                items(apps) { app ->
                    FilterChip(appFilter == app, { appFilter = if (appFilter == app) null else app }, { Text(app) })
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (recordings.isEmpty()) "No recordings yet" else "Nothing matches",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            shown.groupBy { dayLabel(ctx, it.time) }.forEach { (day, recs) ->
                item(key = "h-$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(recs, key = { it.file.path }) { rec ->
                    RecordingRow(
                        rec,
                        onClick = { playing = rec },
                        onShare = { share(ctx, rec) },
                        onNeverRecord = { onNeverRecord(rec.title) },
                        onDelete = { confirmDelete = rec },
                    )
                }
            }
        }
    }

    playing?.let { rec ->
        PlayerSheet(
            rec,
            onDismiss = { playing = null },
            onShare = { share(ctx, rec) },
            onDelete = { confirmDelete = rec },
        )
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("${rec.title} · ${rec.app}\nThis can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (playing == rec) playing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Store.delete(rec) }
                        onDeleted(rec)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusBanner(status: Store.Status) {
    val (text, color) = when {
        !status.running -> "Recorder not running. Connect USB and run ./callrec.sh start" to
            MaterialTheme.colorScheme.errorContainer
        status.state == "recording" -> "● Recording ${status.app ?: "call"}${status.peer?.let { " · $it" } ?: ""}" to
            MaterialTheme.colorScheme.tertiaryContainer
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun RecordingRow(
    rec: Store.Recording,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onNeverRecord: () -> Unit,
    onDelete: () -> Unit,
) {
    val duration by produceState(0L, rec.file) { value = withContext(Dispatchers.IO) { Store.durationMs(rec.file) } }
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { AppBadge(rec.app) },
        headlineContent = { Text(rec.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${rec.app} · ${Store.formatTime(rec.time)}" + if (duration > 0) " · ${Store.formatDuration(duration)}" else "")
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { menu = false; onShare() },
                    )
                    if (rec.peer != null) {
                        DropdownMenuItem(
                            text = { Text("Never record ${rec.title}") }, leadingIcon = { Icon(Icons.Default.Block, null) },
                            onClick = { menu = false; onNeverRecord() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

@Composable
fun AppBadge(app: String) {
    Box(
        Modifier.size(40.dp).background(APP_COLORS[app] ?: Color.Gray, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(app.take(1), color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(rec: Store.Recording, onDismiss: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val player = remember(rec) {
        MediaPlayer().apply {
            setDataSource(rec.file.path)
            prepare()
            start()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val duration = player.duration.coerceAtLeast(1)
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) position = player.currentPosition.toFloat()
            isPlaying = player.isPlaying
            delay(200)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBadge(rec.app)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${rec.app} · " + DateUtils.formatDateTime(
                            LocalContext.current, rec.time,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Slider(
                value = position.coerceIn(0f, duration.toFloat()),
                valueRange = 0f..duration.toFloat(),
                onValueChange = { dragging = true; position = it },
                onValueChangeFinished = { player.seekTo(position.toInt()); dragging = false },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Store.formatDuration(position.toLong()), style = MaterialTheme.typography.labelMedium)
                Text(Store.formatDuration(duration.toLong()), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) {
                    Icon(Icons.Default.Replay10, "Back 10 seconds")
                }
                FilledIconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.start(); isPlaying = player.isPlaying },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(36.dp))
                }
                IconButton(onClick = { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration)) }) {
                    Icon(Icons.Default.Forward10, "Forward 10 seconds")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

private fun dayLabel(ctx: Context, t: Long): String = when {
    DateUtils.isToday(t) -> "Today"
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
}

private fun share(ctx: Context, rec: Store.Recording) {
    val uri = FileProvider.getUriForFile(ctx, "com.callrec.app.files", rec.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share recording"))
}
