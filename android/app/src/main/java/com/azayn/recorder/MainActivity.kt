package com.azayn.recorder

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
        setContent {
            AzaynTheme { AuroraBackground { App() } }
        }
    }
}

@Composable
private fun App() {
    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = Environment.isExternalStorageManager() }
    if (!hasAccess) {
        AccessScreen()
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Shared, polled state: daemon status, recordings on disk, config.
    var status by remember { mutableStateOf(Store.Status(running = false)) }
    var recordings by remember { mutableStateOf(emptyList<Store.Recording>()) }
    var config by remember { mutableStateOf(Store.Config()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadKey++ }
    LaunchedEffect(reloadKey) {
        config = withContext(Dispatchers.IO) { Store.loadConfig() }
        while (true) {
            val (s, r) = withContext(Dispatchers.IO) { Store.status() to Store.recordings() }
            status = s
            if (r != recordings) recordings = r
            delay(2000)
        }
    }

    // Voice recorder: the ball in the middle of the bar starts it from either tab and stops it.
    val ctx = LocalContext.current
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

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            val recording = active != null
            NotchedNavBar(
                tabs = listOf(Icons.Outlined.GraphicEq to "Recordings", Icons.Outlined.Settings to "Settings"),
                selected = tab,
                onSelect = { tab = it },
                centerIcon = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                centerLabel = if (recording) Store.formatDuration(elapsed) else "Record",
                centerLabelColor = if (recording) az.error else az.textTertiary,
                centerDescription = if (recording) "Stop recording" else "Record a voice note",
                onCenter = {
                    if (recording) {
                        RecorderService.stop(ctx)
                    } else {
                        tab = 0 // the saved recording (and its Rename prompt) shows on Recordings
                        micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (tab) {
            0 -> RecordingsScreen(
                recordings = recordings,
                status = status,
                onChanged = { reloadKey++ },
                onNeverRecord = { name ->
                    if (config.never.none { it.name.equals(name, ignoreCase = true) }) {
                        updateConfig(config.copy(never = config.never + Store.Blocked(name, emptyList())))
                    }
                },
                modifier = mod,
            )
            else -> SettingsScreen(status = status, config = config, onChange = ::updateConfig, modifier = mod)
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
