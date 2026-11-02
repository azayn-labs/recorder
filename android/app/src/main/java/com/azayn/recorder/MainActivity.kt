package com.azayn.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UiPrefs.load(this)
        RecorderControl.load(this)
        setContent {
            AzaynTheme { AuroraBackground { RecorderApp() } }
        }
    }
}

@Composable
private fun RecorderApp() {
    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = Environment.isExternalStorageManager() }
    val recorderPrefs by RecorderControl.prefs.collectAsState()
    // First run: set up the on-phone start (it also grants storage access). People who start the
    // recorder from a computer instead can skip it.
    var setupOpen by rememberSaveable { mutableStateOf(!recorderPrefs.paired && !Store.status().running) }
    LaunchedEffect(Unit) { if (Store.status().running && !recorderPrefs.paired) setupOpen = false }
    if (setupOpen || (!hasAccess && !recorderPrefs.paired)) {
        SetupScreen(
            onDone = { setupOpen = false; hasAccess = Environment.isExternalStorageManager() },
            onSkip = { setupOpen = false },
        )
        return
    }
    if (!hasAccess) {
        AccessScreen()
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Shared, polled state: daemon status, recordings on disk, config.
    var status by remember { mutableStateOf(Store.Status(running = false)) }
    var statusLoaded by remember { mutableStateOf(false) }
    var recordings by remember { mutableStateOf(emptyList<Store.Recording>()) }
    var config by remember { mutableStateOf(Store.Config()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadKey++ }
    LaunchedEffect(reloadKey) {
        config = withContext(Dispatchers.IO) { Store.loadConfig() }
        while (true) {
            val (s, r) = withContext(Dispatchers.IO) { Store.status() to Store.recordings() }
            status = s
            statusLoaded = true
            if (r != recordings) recordings = r
            delay(2000)
        }
    }

    // Voice recorder: the ball in the middle of the bar starts it from either tab and stops it.
    val ctx = LocalContext.current

    // Backstop for the boot/update triggers (Android can skip those): opening the app starts the
    // recorder if it's off, or restarts it if it's still running an older build. Once per session,
    // and never in the middle of a call.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status, recorderPrefs) {
        if (!statusLoaded || autoStarted || !recorderPrefs.paired || !recorderPrefs.autoStart || RecorderControl.busy.value != null) return@LaunchedEffect
        val outdated = status.running && status.version != com.azayn.recorder.daemon.Main.VERSION && status.state == "idle"
        if (!status.running || outdated) {
            autoStarted = true
            SetupService.startRecorder(ctx)
        }
    }
    val active by RecorderService.active.collectAsState()
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        val a = active ?: return@LaunchedEffect
        while (true) {
            elapsed = System.currentTimeMillis() - a.startedAt
            delay(250)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) RecorderService.start(ctx)
    }

    fun updateConfig(c: Store.Config) {
        config = c
        Store.saveConfig(c)
    }

    // Multi-select on the Recordings tab; while anything is selected the centre button deletes.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            val recording = active != null
            val selecting = selected.isNotEmpty() && tab == 0
            // Content scrolls behind the bar; a soft fade keeps it from clashing with the bar's labels.
            Box {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.45f to az.background.copy(alpha = 0.7f), 1f to az.background.copy(alpha = 0.95f)),
                    ),
                )
                NotchedNavBar(
                    tabs = listOf(Icons.Outlined.GraphicEq to "Recordings", Icons.Outlined.Settings to "Settings"),
                    selected = tab,
                    onSelect = { tab = it; selected = emptySet() },
                    centerIcon = when {
                        selecting -> Icons.Default.Delete
                        recording -> Icons.Default.Stop
                        else -> Icons.Default.Mic
                    },
                    centerLabel = when {
                        selecting -> "Delete ${selected.size}"
                        recording -> Store.formatDuration(elapsed)
                        else -> "Record"
                    },
                    centerLabelColor = if (selecting || recording) az.error else az.textTertiary,
                    centerDescription = when {
                        selecting -> "Delete ${selected.size} selected"
                        recording -> "Stop recording"
                        else -> "Record a voice note"
                    },
                    centerColors = if (selecting || recording) listOf(Color(0xFFFF6B7D), Color(0xFFE11D48)) else listOf(Color(0xFF2F9BFF), Color(0xFF4F46E5)),
                    centerScale = if (recording && !selecting) 1.18f else 1f,
                    centerGlow = if (recording && !selecting) Color(0xFFFF4D6A) else null,
                    onCenter = {
                        when {
                            selecting -> confirmDelete = true
                            recording -> RecorderService.stop(ctx)
                            else -> {
                                tab = 0 // the saved recording (and its Rename prompt) shows on Recordings
                                micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                            }
                        }
                    },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        // Only the top inset is applied: lists draw behind the bottom bar and pad their own end by
        // its height, so the last item can scroll clear of it instead of being cut off.
        val mod = Modifier.padding(top = padding.calculateTopPadding())
        val bottomInset = padding.calculateBottomPadding()
        when (tab) {
            0 -> RecordingsScreen(
                recordings = recordings,
                status = status,
                onChanged = { reloadKey++ },
                onSetup = { setupOpen = true },
                onNeverRecord = { name ->
                    if (config.never.none { it.name.equals(name, ignoreCase = true) }) {
                        updateConfig(config.copy(never = config.never + Store.Blocked(name, emptyList())))
                    }
                },
                selected = selected,
                onSelect = { selected = it },
                bottomInset = bottomInset,
                modifier = mod,
            )
            else -> SettingsScreen(status = status, config = config, onChange = ::updateConfig, onSetup = { setupOpen = true }, bottomInset = bottomInset, modifier = mod)
        }
    }

    if (confirmDelete) {
        val doomed = recordings.filter { it.file.path in selected }
        DeleteSheet(doomed, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            scope.launch {
                withContext(Dispatchers.IO) { doomed.forEach(Store::delete) }
                selected = emptySet()
                reloadKey++
            }
        }
    }
}

/** Action sheet confirming a multi-delete: what goes, from whom, and that it can't be undone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteSheet(recs: List<Store.Recording>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val n = recs.size
    val who = recs.map { it.group.title }.distinct()
    val from = when (who.size) {
        0 -> ""
        1 -> "from ${who[0]}"
        2 -> "from ${who[0]} and ${who[1]}"
        else -> "from ${who[0]}, ${who[1]} and ${who.size - 2} more"
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(Icons.Default.Delete, tint = az.error, fill = az.errorBg, size = 48)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "Delete $n recording${if (n == 1) "" else "s"}?",
                        style = MaterialTheme.typography.titleLarge, color = az.textPrimary,
                    )
                    Text(
                        listOf(from, "This can't be undone.").filter { it.isNotEmpty() }.joinToString(". ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium, color = az.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF6B7D), Color(0xFFE11D48))))
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Text("Delete $n recording${if (n == 1) "" else "s"}", color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun AccessScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconWell(Icons.Default.FolderOpen, size = 64)
        Text("Storage access needed", style = MaterialTheme.typography.headlineSmall, color = az.textPrimary)
        Text(
            "Recordings are saved in the CallRecorder folder by the background recorder. " +
                "Allow \"All files access\" so this app can play, share and delete them.",
            style = MaterialTheme.typography.bodyMedium,
            color = az.textSecondary,
            textAlign = TextAlign.Center,
        )
        GradientButton("Grant access", null, onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            )
        })
    }
}
