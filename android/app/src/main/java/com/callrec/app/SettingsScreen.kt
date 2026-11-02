package com.callrec.app

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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    status: Store.Status,
    config: Store.Config,
    onChange: (Store.Config) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPicker = true
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item { StatusCard(status) }

        item { SectionHeader("Recording") }
        item {
            SwitchRow("Record calls", if (config.enabled) "On" else "Paused: no calls are recorded", config.enabled) {
                onChange(config.copy(enabled = it))
            }
        }
        items(Store.APPS) { app ->
            SwitchRow(
                if (app == "Other") "Other calling apps" else app,
                null,
                config.enabled && config.appEnabled(app),
                enabled = config.enabled,
                leading = { AppBadge(app) },
            ) { onChange(config.copy(apps = config.apps + (app to it))) }
        }

        item { SectionHeader("Never record") }
        item {
            Text(
                "Calls with these people are not recorded. Phone numbers match regular calls. Names match how " +
                    "WhatsApp, Instagram or Facebook shows the person in its call screen. For outgoing phone calls " +
                    "the number is only known after hang-up, so those are recorded and then deleted right away.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(config.never, key = { it.name + it.numbers }) { b ->
            ListItem(
                headlineContent = { Text(b.name.ifBlank { b.numbers.firstOrNull() ?: "?" }) },
                supportingContent = if (b.numbers.isNotEmpty() && b.name.isNotBlank()) {
                    { Text(b.numbers.joinToString(", ")) }
                } else null,
                trailingContent = {
                    IconButton(onClick = { onChange(config.copy(never = config.never - b)) }) {
                        Icon(Icons.Default.Close, "Remove")
                    }
                },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED
                    ) showPicker = true else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                }) {
                    Icon(Icons.Default.Contacts, null)
                    Text("  From contacts")
                }
                OutlinedButton(onClick = { showManual = true }) {
                    Icon(Icons.Default.Edit, null)
                    Text("  Name / number")
                }
            }
        }
        item { SectionHeader("Storage") }
        item {
            Text(
                "Recordings: ${Store.root.path}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    fun add(b: Store.Blocked) {
        if (config.never.none { it.name.equals(b.name, true) && it.numbers == b.numbers }) {
            onChange(config.copy(never = config.never + b))
        }
    }

    if (showPicker) ContactPicker(onDismiss = { showPicker = false }, onPick = { showPicker = false; add(it) })
    if (showManual) ManualEntry(onDismiss = { showManual = false }, onAdd = { showManual = false; add(it) })
}

@Composable
private fun StatusCard(status: Store.Status) {
    val (title, detail, color) = when {
        !status.running -> Triple(
            "Recorder not running",
            "It stops when the phone restarts. Connect USB and run ./callrec.sh start on your computer.",
            MaterialTheme.colorScheme.errorContainer,
        )
        status.state == "recording" -> Triple(
            "Recording now",
            listOfNotNull(status.app, status.peer).joinToString(" · ") +
                if (status.since > 0) " · since ${Store.formatTime(status.since)}" else "",
            MaterialTheme.colorScheme.tertiaryContainer,
        )
        status.state == "stopped" -> Triple(
            "Recording stopped",
            "Stopped from the notification. It's saved when the call ends.",
            MaterialTheme.colorScheme.secondaryContainer,
        )
        status.state == "skipping" -> Triple(
            "Not recording this call",
            status.reason ?: "",
            MaterialTheme.colorScheme.secondaryContainer,
        )
        else -> Triple("Recorder running", "Waiting for calls", MaterialTheme.colorScheme.primaryContainer)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onToggle(!checked) },
        leadingContent = leading,
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled) },
    )
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
