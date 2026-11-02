package com.callrec.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val APP_COLORS = mapOf(
    "Phone" to Color(0xFF2E7D32),
    "WhatsApp" to Color(0xFF25D366),
    "WhatsApp Business" to Color(0xFF128C7E),
    "Instagram" to Color(0xFFC13584),
    "Facebook" to Color(0xFF1877F2),
    "Messenger" to Color(0xFF0084FF),
    Store.MEMOS to Color(0xFFE65100),
)

private fun appName(app: String) = if (app == Store.MEMOS) "Voice" else app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    recordings: List<Store.Recording>,
    status: Store.Status,
    onChanged: () -> Unit,
    onNeverRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var openGroup by remember { mutableStateOf<Store.Group?>(null) }
    var playing by remember { mutableStateOf<Store.Recording?>(null) }
    var renaming by remember { mutableStateOf<Store.Recording?>(null) }
    var confirmDelete by remember { mutableStateOf<Store.Recording?>(null) }

    val active by RecorderService.active.collectAsState()
    LaunchedEffect(Unit) {
        // Collect (rather than key an effect on the value) so clearing it doesn't cancel the snackbar.
        RecorderService.finished.filterNotNull().collect { file ->
            RecorderService.finished.value = null
            onChanged()
            val result = snackbar.showSnackbar("Recording saved", actionLabel = "Rename", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                renaming = Store.memo(file)
            }
        }
    }
    // The file being recorded right now isn't playable yet.
    val visible = recordings.filter { it.file != active?.file }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) RecorderService.start(ctx)
    }

    BackHandler(enabled = openGroup != null) { openGroup = null }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (active == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    },
                    icon = { Icon(Icons.Default.Mic, null) },
                    text = { Text("Record") },
                )
            }
        },
        bottomBar = { active?.let { RecordingBar(it) { RecorderService.stop(ctx) } } },
    ) { inner ->
        val group = openGroup
        if (group == null) {
            GroupList(visible, status, onOpen = { openGroup = it }, onPlay = { playing = it }, Modifier.padding(inner))
        } else {
            GroupDetail(
                group,
                visible.filter { it.group == group },
                onBack = { openGroup = null },
                onPlay = { playing = it },
                onRename = { renaming = it },
                onDelete = { confirmDelete = it },
                onNeverRecord = { onNeverRecord(group.title) },
                modifier = Modifier.padding(inner),
            )
        }
    }

    playing?.let { rec ->
        PlayerSheet(
            rec,
            onDismiss = { playing = null },
            onRename = { renaming = rec },
            onDelete = { confirmDelete = rec },
        )
    }

    renaming?.let { rec ->
        RenameDialog(rec, onDismiss = { renaming = null }) { renamed ->
            renaming = null
            if (playing?.file == rec.file) playing = null
            onChanged()
            scope.launch { snackbar.showSnackbar("Renamed to \"${renamed.customName}\"") }
        }
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("${recTitle(ctx, rec)}\nThis can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (playing?.file == rec.file) playing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Store.delete(rec) }
                        onChanged()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// ---- group list ----

private data class GroupSummary(val group: Store.Group, val count: Int, val latest: Long)

@Composable
private fun GroupList(
    recordings: List<Store.Recording>,
    status: Store.Status,
    onOpen: (Store.Group) -> Unit,
    onPlay: (Store.Recording) -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val apps = recordings.map { it.app }.distinct().sortedBy { if (it == Store.MEMOS) "~" else it }
    val inApp = recordings.filter { appFilter == null || it.app == appFilter }
    val q = query.trim()
    val groups = inApp.groupBy { it.group }
        .filter { (g, _) -> q.isEmpty() || g.title.contains(q, ignoreCase = true) }
        .map { (g, recs) -> GroupSummary(g, recs.size, recs.maxOf { it.time }) }
        .sortedByDescending { it.latest }
    // Renamed recordings are findable by their name too.
    val namedMatches = if (q.isEmpty()) emptyList() else inApp.filter { it.customName?.contains(q, ignoreCase = true) == true }

    Column(modifier.fillMaxSize()) {
        StatusBanner(status)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search contacts or recording names") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (apps.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                item { FilterChip(appFilter == null, { appFilter = null }, { Text("All") }) }
                items(apps) { app ->
                    FilterChip(appFilter == app, { appFilter = if (appFilter == app) null else app }, { Text(appName(app)) })
                }
            }
        }

        if (groups.isEmpty() && namedMatches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (recordings.isEmpty()) "No recordings yet" else "Nothing matches",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (namedMatches.isNotEmpty()) {
                item { SectionLabel("Named recordings") }
                items(namedMatches, key = { "n-" + it.file.path }) { rec ->
                    ListItem(
                        modifier = Modifier.clickable { onPlay(rec) },
                        leadingContent = { AppBadge(rec.app) },
                        headlineContent = { Text(rec.customName!!, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${rec.group.title} · ${whenLabel(ctx, rec.time)}") },
                    )
                }
                if (groups.isNotEmpty()) item { SectionLabel("Contacts") }
            }
            items(groups, key = { "g-" + it.group }) { s ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(s.group) },
                    leadingContent = { AppBadge(s.group.app) },
                    headlineContent = { Text(s.group.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val noun = if (s.group.app == Store.MEMOS) "recording" else "call"
                        Text("${appName(s.group.app)} · ${s.count} $noun${if (s.count == 1) "" else "s"} · ${whenLabel(ctx, s.latest)}")
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            }
        }
    }
}

// ---- one contact's recordings ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetail(
    group: Store.Group,
    recs: List<Store.Recording>,
    onBack: () -> Unit,
    onPlay: (Store.Recording) -> Unit,
    onRename: (Store.Recording) -> Unit,
    onDelete: (Store.Recording) -> Unit,
    onNeverRecord: () -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Column {
                    Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(appName(group.app), style = MaterialTheme.typography.bodySmall)
                }
            },
            actions = {
                if (group.contact != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Never record ${group.title}") },
                                leadingIcon = { Icon(Icons.Default.Block, null) },
                                onClick = { menu = false; onNeverRecord() },
                            )
                        }
                    }
                }
            },
        )
        if (recs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings left", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            recs.groupBy { dayLabel(ctx, it.time) }.forEach { (day, dayRecs) ->
                item(key = "h-$day") { SectionLabel(day) }
                items(dayRecs, key = { it.file.path }) { rec ->
                    RecordingRow(rec, onClick = { onPlay(rec) }, onRename = { onRename(rec) }, onDelete = { onDelete(rec) })
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(rec: Store.Recording, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val duration by produceState(0L, rec.file) { value = withContext(Dispatchers.IO) { Store.durationMs(rec.file) } }
    var menu by remember { mutableStateOf(false) }
    val time = Store.formatTime(rec.time)
    val length = if (duration > 0) Store.formatDuration(duration) else ""
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        },
        headlineContent = { Text(rec.customName ?: time, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOfNotNull(if (rec.customName != null) time else null, length.ifEmpty { null }).joinToString(" · ")) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { menu = false; share(ctx, rec) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

// ---- shared pieces ----

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatusBanner(status: Store.Status) {
    val (text, color) = when {
        !status.running -> "Call recorder not running. Connect USB and run ./callrec.sh start" to
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
private fun RecordingBar(active: RecorderService.Active, onStop: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while (true) {
            elapsed = System.currentTimeMillis() - active.startedAt
            delay(250)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(Color.Red, CircleShape))
            Spacer(Modifier.size(12.dp))
            Text("Recording  ${Store.formatDuration(elapsed)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = onStop, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Stop, "Stop", Modifier.size(32.dp)) }
        }
    }
}

@Composable
fun AppBadge(app: String) {
    Box(
        Modifier.size(40.dp).background(APP_COLORS[app] ?: Color.Gray, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (app == Store.MEMOS) {
            Icon(Icons.Default.Mic, null, tint = Color.White)
        } else {
            Text(app.take(1), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RenameDialog(rec: Store.Recording, onDismiss: () -> Unit, onRenamed: (Store.Recording) -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = rec.file.nameWithoutExtension
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        scope.launch {
            val renamed = withContext(Dispatchers.IO) { Store.rename(rec, text.text) }
            if (renamed != null) onRenamed(renamed)
            else error = if (Store.cleanName(text.text) == null) "Enter a name" else "A recording with this name already exists here"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "Saved as \"${Store.cleanName(text.text) ?: ""}.m4a\"") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::submit) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(rec: Store.Recording, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(rec) {
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(rec.file.path)
            p.prepare()
            p.start()
        }.fold(onSuccess = { p }, onFailure = { p.release(); null })
    }
    if (player == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Can't play this recording") },
            text = { Text("${rec.file.name} is damaged or unfinished.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            dismissButton = { TextButton(onClick = { onDismiss(); onDelete() }) { Text("Delete") } },
        )
        return
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
                    Text(recTitle(ctx, rec), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${appName(rec.app)} · ${rec.group.title} · " + DateUtils.formatDateTime(
                            ctx, rec.time,
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
                IconButton(onClick = onRename) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
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
                IconButton(onClick = { share(ctx, rec) }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

/** Custom name if the user gave one, else the date and time it was recorded. */
private fun recTitle(ctx: Context, rec: Store.Recording): String =
    rec.customName ?: DateUtils.formatDateTime(ctx, rec.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)

private fun dayLabel(ctx: Context, t: Long): String = when {
    DateUtils.isToday(t) -> "Today"
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
}

private fun whenLabel(ctx: Context, t: Long): String =
    if (DateUtils.isToday(t)) Store.formatTime(t) else "${dayLabel(ctx, t)}, ${Store.formatTime(t)}"

private fun share(ctx: Context, rec: Store.Recording) {
    val uri = FileProvider.getUriForFile(ctx, "com.callrec.app.files", rec.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share recording"))
}
