package com.azayn.recorder

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun SettingsScreen(
    status: Store.Status,
    config: Store.Config,
    onChange: (Store.Config) -> Unit,
    onSetup: () -> Unit,
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val recorder by RecorderControl.prefs.collectAsState()
    val busy by RecorderControl.busy.collectAsState()
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPicker = true
    }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    fun add(b: Store.Blocked) {
        if (config.never.none { it.name.equals(b.name, true) && it.numbers == b.numbers }) {
            onChange(config.copy(never = config.never + b))
        }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 24.dp),
    ) {
        item { ScreenTitle("Settings", inset = 4.dp) }
        item { StatusCard(status) }

        item { SectionLabel("Recorder") }
        item {
            Panel {
                if (recorder.paired) {
                    PanelRow(
                        if (status.running) "Restart the recorder" else "Start the recorder now",
                        hint = busy ?: "Uses Wireless debugging on this phone; no computer needed",
                        icon = Icons.Default.PlayCircleOutline,
                        enabled = busy == null,
                        onClick = { SetupService.startRecorder(ctx) },
                    ) { Chevron() }
                    PanelDivider()
                    PanelRow(
                        "Start after the phone restarts",
                        hint = "When you're on Wi-Fi",
                        icon = Icons.Default.RestartAlt,
                        onClick = { RecorderControl.update(ctx, recorder.copy(autoStart = !recorder.autoStart)) },
                    ) { Switch(recorder.autoStart, { RecorderControl.update(ctx, recorder.copy(autoStart = it)) }) }
                    PanelDivider()
                    PanelRow(
                        "Turn off Wireless debugging afterwards",
                        hint = "The recorder keeps running; safer on shared Wi-Fi",
                        icon = Icons.Default.WifiOff,
                        onClick = { RecorderControl.update(ctx, recorder.copy(wirelessOffAfter = !recorder.wirelessOffAfter)) },
                    ) { Switch(recorder.wirelessOffAfter, { RecorderControl.update(ctx, recorder.copy(wirelessOffAfter = it)) }) }
                    PanelDivider()
                }
                PanelRow(
                    if (recorder.paired) "Pair again" else "Set up on this phone",
                    hint = if (recorder.paired) "If the phone stops trusting the app" else "Start the recorder without a computer",
                    icon = Icons.Default.Link,
                    onClick = {
                        if (recorder.paired) RecorderControl.update(ctx, recorder.copy(paired = false))
                        onSetup()
                    },
                ) { Chevron() }
            }
        }

        item { SectionLabel("Recording") }
        item {
            Panel {
                PanelRow(
                    "Record calls",
                    hint = if (config.enabled) "Calls are recorded automatically" else "Paused: no calls are recorded",
                    icon = Icons.Default.Mic,
                    onClick = { onChange(config.copy(enabled = !config.enabled)) },
                ) { Switch(config.enabled, { onChange(config.copy(enabled = it)) }) }
                Store.APPS.forEach { app ->
                    PanelDivider()
                    val on = config.enabled && config.appEnabled(app)
                    PanelRow(
                        if (app == "Other") "Other calling apps" else app,
                        leading = { AppBadge(app, size = 26) },
                        enabled = config.enabled,
                        onClick = { onChange(config.copy(apps = config.apps + (app to !config.appEnabled(app)))) },
                    ) {
                        Switch(on, { onChange(config.copy(apps = config.apps + (app to it))) }, enabled = config.enabled)
                    }
                }
            }
        }

        item { SectionLabel("Audio format") }
        item {
            Panel {
                Store.Format.entries.forEachIndexed { i, f ->
                    if (i > 0) PanelDivider()
                    PanelRow(
                        f.label + if (f == Store.Format.M4A) " (default)" else "",
                        hint = f.detail,
                        icon = Icons.Default.AudioFile,
                        onClick = { onChange(config.copy(format = f)) },
                    ) { RadioButton(selected = config.format == f, onClick = { onChange(config.copy(format = f)) }) }
                }
            }
        }
        item { Caption("Applies to new recordings, calls and voice. Existing recordings keep their format.") }

        item { SectionLabel("Never record") }
        item {
            Panel {
                config.never.forEach { b ->
                    PanelRow(
                        b.name.ifBlank { b.numbers.firstOrNull() ?: "?" },
                        hint = if (b.numbers.isNotEmpty() && b.name.isNotBlank()) b.numbers.joinToString(", ") else null,
                        icon = Icons.Default.Block,
                    ) {
                        IconButton(onClick = { onChange(config.copy(never = config.never - b)) }) {
                            Icon(Icons.Default.Close, "Remove", tint = az.textTertiary)
                        }
                    }
                    PanelDivider()
                }
                PanelRow(
                    "Add from contacts", hint = "All of a contact's numbers", icon = Icons.Default.Contacts,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                            PackageManager.PERMISSION_GRANTED
                        ) showPicker = true else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    },
                ) { Chevron() }
                PanelDivider()
                PanelRow(
                    "Add a name or number", hint = "e.g. an Instagram username", icon = Icons.Default.Edit,
                    onClick = { showManual = true },
                ) { Chevron() }
            }
        }
        item {
            Caption(
                "Phone numbers match regular calls. Names match how WhatsApp, Instagram or Facebook shows the " +
                    "person on its call screen. Outgoing phone calls only reveal the number after hang-up, so those " +
                    "are recorded and then deleted right away.",
            )
        }

        item { SectionLabel("Appearance") }
        item {
            Panel {
                PanelRow("Theme", hint = "System follows your phone's light or dark setting", icon = Icons.Outlined.Palette)
                SegmentedTabs(
                    ThemePref.entries, UiPrefs.theme, { it.label }, { UiPrefs.setTheme(ctx, it) },
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }

        item { SectionLabel("About") }
        item {
            Panel {
                PanelRow("Version", hint = "Free version from GitHub", icon = Icons.Outlined.Info) {
                    Text(version, style = MaterialTheme.typography.bodyMedium, color = az.primary)
                }
                PanelDivider()
                PanelRow("Recordings folder", hint = Store.root.path, icon = Icons.Outlined.Folder)
                PanelDivider()
                PanelRow(
                    "Source code", hint = "github.com/azayn-labs/recorder", icon = Icons.Default.Code,
                    onClick = { openLink(ctx, Links.REPO) },
                ) { Chevron() }
                PanelDivider()
                PanelRow(
                    "License", hint = "Apache 2.0 · provided as is, without warranty", icon = Icons.Outlined.Gavel,
                    onClick = { openLink(ctx, Links.LICENSE) },
                ) { Chevron() }
                PanelDivider()
                PanelRow(
                    "Support Azayn Recorder", hint = "ko-fi.com/azayn", icon = Icons.Outlined.FavoriteBorder,
                    onClick = { openLink(ctx, Links.KOFI) },
                ) { Chevron() }
            }
        }
        item { Caption("You are responsible for following the call-recording laws where you and the other person are.") }
    }

    if (showPicker) ContactPicker(onDismiss = { showPicker = false }, onPick = { showPicker = false; add(it) })
    if (showManual) ManualEntry(onDismiss = { showManual = false }, onAdd = { showManual = false; add(it) })
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = az.textTertiary,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
    )
}

@Composable
private fun StatusCard(status: Store.Status) {
    data class S(val icon: ImageVector, val tint: Color, val fill: Color, val title: String, val detail: String)
    val s = when {
        !status.running -> S(
            Icons.Default.ErrorOutline, az.error, az.errorBg, "Recorder not running",
            "Calls aren't being recorded. Start it below.",
        )
        status.state == "recording" -> S(
            Icons.Default.FiberManualRecord, az.error, az.errorBg, "Recording now",
            listOfNotNull(status.app, status.peer).joinToString(" · ") +
                if (status.since > 0) " · since ${Store.formatTime(status.since)}" else "",
        )
        status.state == "stopped" -> S(
            Icons.Default.StopCircle, az.primary, az.primaryTint, "Recording stopped",
            "Stopped from the notification. It's saved when the call ends.",
        )
        status.state == "skipping" -> S(
            Icons.Default.Block, az.primary, az.primaryTint, "Not recording this call", status.reason ?: "",
        )
        else -> S(Icons.Default.CheckCircle, az.primary, az.primaryTint, "Recorder running", "Waiting for calls")
    }
    Panel {
        PanelRow(s.title, hint = s.detail, leading = { IconWell(s.icon, tint = s.tint, fill = s.fill) })
    }
}

private fun loadContacts(ctx: Context): List<Store.Blocked> {
    val byId = linkedMapOf<Long, Pair<String, MutableList<String>>>()
    ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        null, null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE",
    )?.use { c ->
        while (c.moveToNext()) {
            val entry = byId.getOrPut(c.getLong(0)) { (c.getString(1) ?: "") to mutableListOf() }
            val num = c.getString(2) ?: continue
            if (entry.second.none { it.filter(Char::isDigit) == num.filter(Char::isDigit) }) entry.second += num
        }
    }
    return byId.values.map { Store.Blocked(it.first, it.second) }
}

@Composable
private fun ContactPicker(onDismiss: () -> Unit, onPick: (Store.Blocked) -> Unit) {
    val ctx = LocalContext.current
    val contacts by produceState<List<Store.Blocked>?>(null) { value = withContext(Dispatchers.IO) { loadContacts(ctx) } }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Never record…") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search contacts") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val q = query.trim()
                val shown = contacts.orEmpty().filter {
                    q.isEmpty() || it.name.contains(q, true) || it.numbers.any { n -> n.contains(q) }
                }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(shown) { c ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(c) },
                            headlineContent = { Text(c.name) },
                            supportingContent = { Text(c.numbers.joinToString(", ")) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManualEntry(onDismiss: () -> Unit, onAdd: (Store.Blocked) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Never record") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name or username") },
                    supportingText = { Text("As shown on the WhatsApp / Instagram / Facebook call screen") },
                )
                OutlinedTextField(value = number, onValueChange = { number = it }, singleLine = true, label = { Text("Phone number") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || number.isNotBlank(),
                onClick = { onAdd(Store.Blocked(name.trim(), listOfNotNull(number.trim().ifBlank { null }))) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
