package com.azayn.recorder

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
    Store.MEMOS to Color(0xFF4F46E5),
)

private fun appName(app: String) = if (app == Store.MEMOS) "Voice" else app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    recordings: List<Store.Recording>,
    status: Store.Status,
    onChanged: () -> Unit,
    onSetup: () -> Unit,
    onNeverRecord: (String) -> Unit,
    /** Multi-select: paths of the selected recordings. Long-press starts it; the app's centre button deletes. */
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
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


    BackHandler(enabled = openGroup != null) { openGroup = null }
    // Registered later, so Back leaves selection before it leaves a contact's page.
    BackHandler(enabled = selected.isNotEmpty()) { onSelect(emptySet()) }
    // Recordings that disappeared (deleted elsewhere, renamed) drop out of the selection.
    LaunchedEffect(visible) {
        val paths = visible.mapTo(HashSet()) { it.file.path }
        if (selected.any { it !in paths }) onSelect(selected.filterTo(HashSet()) { it in paths })
    }
    val sel = Selection(selected, onSelect)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        val group = openGroup
        if (group == null) {
            GroupList(visible, status, onSetup, sel, onOpen = { openGroup = it }, onPlay = { playing = it }, Modifier.padding(inner))
        } else {
            GroupDetail(
                group,
                visible.filter { it.group == group },
                sel,
                onBack = { openGroup = null; onSelect(emptySet()) },
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
    onSetup: () -> Unit,
    sel: Selection,
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

    // Everything this view shows, for "Select all".
    val shownPaths = (namedMatches + groups.flatMap { g -> inApp.filter { it.group == g.group } }).map { it.file.path }.toSet()

    Column(modifier.fillMaxSize()) {
        if (sel.active) SelectionHeader(sel, shownPaths) else ScreenTitle("Recordings")
        StatusBanner(status, onSetup)
        SearchField(query, { query = it })
        if (apps.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                item { PillChip("All", appFilter == null) { appFilter = null } }
                items(apps) { app -> PillChip(appName(app), appFilter == app) { appFilter = if (appFilter == app) null else app } }
            }
        }

        if (groups.isEmpty() && namedMatches.isEmpty()) {
            EmptyState(if (recordings.isEmpty()) "No recordings yet" else "Nothing matches",
                if (recordings.isEmpty()) "Calls are saved here automatically. Tap Record for a voice note." else null)
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            if (namedMatches.isNotEmpty()) {
                item { SectionLabel("Named recordings") }
                item {
                    Panel {
                        namedMatches.forEachIndexed { i, rec ->
                            if (i > 0) PanelDivider()
                            val path = setOf(rec.file.path)
                            PanelRow(
                                rec.customName!!,
                                hint = "${rec.group.title} · ${whenLabel(ctx, rec.time)}",
                                leading = { if (sel.active) SelectionMark(sel.all(path)) else AppBadge(rec.app) },
                                selected = sel.all(path),
                                onClick = { if (sel.active) sel.toggle(path) else onPlay(rec) },
                                onLongClick = { sel.add(path) },
                            )
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) {
                item { SectionLabel(if (namedMatches.isNotEmpty()) "Contacts" else "Contacts and apps") }
                item {
                    Panel {
                        groups.forEachIndexed { i, s ->
                            if (i > 0) PanelDivider()
                            val noun = if (s.group.app == Store.MEMOS) "recording" else "call"
                            // A contact row selects all of that contact's recordings (in the current filter).
                            val paths = inApp.filter { it.group == s.group }.mapTo(HashSet()) { it.file.path }
                            PanelRow(
                                s.group.title,
                                hint = "${appName(s.group.app)} · ${s.count} $noun${if (s.count == 1) "" else "s"} · ${whenLabel(ctx, s.latest)}",
                                leading = { if (sel.active) SelectionMark(sel.all(paths)) else AppBadge(s.group.app) },
                                selected = sel.all(paths),
                                onClick = { if (sel.active) sel.toggle(paths) else onOpen(s.group) },
                                onLongClick = { sel.add(paths) },
                            ) { if (!sel.active) Chevron() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search contacts or recording names", color = az.textTertiary) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = az.textTertiary) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = az.surface,
            unfocusedContainerColor = az.surface,
            unfocusedBorderColor = az.border,
            focusedBorderColor = az.primary,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyState(title: String, detail: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconWell(Icons.Default.GraphicEq, size = 56)
        Text(title, style = MaterialTheme.typography.titleMedium, color = az.textPrimary)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = az.textSecondary, textAlign = TextAlign.Center)
    }
}

// ---- one contact's recordings ----

@Composable
private fun GroupDetail(
    group: Store.Group,
    recs: List<Store.Recording>,
    sel: Selection,
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
        if (sel.active) SelectionHeader(sel, recs.mapTo(HashSet()) { it.file.path }) else ScreenTitle(
            group.title,
            subtitle = appName(group.app),
            navigation = { HeaderButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
        ) {
            if (group.contact != null) {
                Box {
                    HeaderButton(Icons.Default.MoreVert, "More") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Never record ${group.title}") },
                            leadingIcon = { Icon(Icons.Default.Block, null) },
                            onClick = { menu = false; onNeverRecord() },
                        )
                    }
                }
            }
        }
        if (recs.isEmpty()) {
            EmptyState("No recordings left", null)
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            recs.groupBy { dayLabel(ctx, it.time) }.forEach { (day, dayRecs) ->
                item(key = "h-$day") { SectionLabel(day) }
                item(key = "p-$day") {
                    Panel {
                        dayRecs.forEachIndexed { i, rec ->
                            if (i > 0) PanelDivider()
                            val path = setOf(rec.file.path)
                            RecordingRow(
                                rec,
                                selecting = sel.active,
                                selected = sel.all(path),
                                onClick = { if (sel.active) sel.toggle(path) else onPlay(rec) },
                                onLongClick = { sel.add(path) },
                                onRename = { onRename(rec) },
                                onDelete = { onDelete(rec) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    rec: Store.Recording,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val duration by produceState(0L, rec.file) { value = withContext(Dispatchers.IO) { Store.durationMs(rec.file) } }
    var menu by remember { mutableStateOf(false) }
    val time = Store.formatTime(rec.time)
    val length = if (duration > 0) Store.formatDuration(duration) else ""
    PanelRow(
        rec.customName ?: time,
        hint = listOfNotNull(if (rec.customName != null) time else null, length.ifEmpty { null }).joinToString(" · "),
        leading = { if (selecting) SelectionMark(selected) else IconWell(Icons.Default.PlayArrow) },
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        if (!selecting) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = az.textTertiary) }
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
    }
}

// ---- multi-select ----

/** Selected recording paths, with helpers for rows that stand for one recording or several (a contact). */
class Selection(val paths: Set<String>, private val set: (Set<String>) -> Unit) {
    val active get() = paths.isNotEmpty()
    fun all(of: Set<String>) = of.isNotEmpty() && paths.containsAll(of)
    fun add(of: Set<String>) = set(paths + of)
    fun toggle(of: Set<String>) = set(if (all(of)) paths - of else paths + of)
    fun clear() = set(emptySet())
}

/** Replaces the screen title while selecting: close, count, select all. */
@Composable
private fun SelectionHeader(sel: Selection, shown: Set<String>) {
    val n = sel.paths.size
    ScreenTitle(
        "$n selected",
        subtitle = "Tap to select more, then Delete",
        navigation = { HeaderButton(Icons.Default.Close, "Cancel selection") { sel.clear() } },
    ) {
        TextButton(onClick = { if (sel.all(shown)) sel.clear() else sel.add(shown) }) {
            Text(if (sel.all(shown)) "Select none" else "Select all")
        }
    }
}

// ---- shared pieces ----

@Composable
private fun StatusBanner(status: Store.Status, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs by RecorderControl.prefs.collectAsState()
    val busy by RecorderControl.busy.collectAsState()
    val error by SetupService.message.collectAsState()
    when {
        status.running && status.state == "recording" -> Panel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fill = az.errorBg) {
            PanelRow(
                "Recording ${status.app ?: "call"}${status.peer?.let { " · $it" } ?: ""}",
                leading = { IconWell(Icons.Default.FiberManualRecord, tint = az.error, fill = az.surface, size = 32) },
                titleColor = az.error,
            )
        }
        status.running && status.version != com.azayn.recorder.daemon.Main.VERSION && prefs.paired ->
            Panel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                PanelRow(
                    "Recorder update ready", hint = busy ?: "Restart it to use this version",
                    leading = { IconWell(Icons.Default.Refresh, size = 32) },
                ) { if (busy == null) TextButton(onClick = { SetupService.startRecorder(ctx) }) { Text("Restart") } }
            }
        !status.running -> Panel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fill = az.errorBg) {
            PanelRow(
                if (prefs.paired) "Recorder is off" else "Recorder not set up",
                hint = busy ?: error ?: if (prefs.paired) "Calls aren't being recorded" else "Pair once with Wireless debugging",
                leading = { IconWell(Icons.Default.ErrorOutline, tint = az.error, fill = az.surface, size = 32) },
                titleColor = az.error,
            ) {
                if (busy == null) {
                    TextButton(onClick = { if (prefs.paired) SetupService.startRecorder(ctx) else onSetup() }) {
                        Text(if (prefs.paired) "Start" else "Set up")
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
fun AppBadge(app: String, size: Int = 40) {
    Box(
        Modifier.size(size.dp).background(APP_COLORS[app] ?: Color(0xFF6B7085), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (app == Store.MEMOS) {
            Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
        } else {
            Text(
                app.take(1), color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (size * 0.42).sp),
            )
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
                supportingText = { Text(error ?: "Saved as \"${Store.cleanName(text.text) ?: ""}.${rec.file.extension}\"") },
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
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = az.error) }
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
                Box(
                    Modifier.size(64.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(AzaynGradient)
                        .clickable { if (player.isPlaying) player.pause() else player.start(); isPlaying = player.isPlaying },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration)) }) {
                    Icon(Icons.Default.Forward10, "Forward 10 seconds")
                }
                IconButton(onClick = { share(ctx, rec) }) { Icon(Icons.Default.Share, "Share") }
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
    val uri = FileProvider.getUriForFile(ctx, "com.azayn.recorder.files", rec.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(Store.Format.ofFile(rec.file).mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share recording"))
}
