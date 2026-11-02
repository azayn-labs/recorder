package com.azayn.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Opens Developer options scrolled to (and highlighting) the Wireless debugging row. */
fun openWirelessDebugging(ctx: Context) {
    val args = Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") }
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .putExtra(":settings:show_fragment_args", args)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * First run without a computer: walk through Developer options, Wi-Fi and Wireless debugging, pair
 * once, and start the recorder. Each step ticks itself off as the phone's settings change.
 */
@Composable
fun SetupScreen(onDone: () -> Unit, onSkip: (() -> Unit)?) {
    val ctx = LocalContext.current
    var devOn by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var wireless by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val prefs by RecorderControl.prefs.collectAsState()
    val pairing by SetupService.pairing.collectAsState()
    val port by SetupService.pairingPort.collectAsState()
    val message by SetupService.message.collectAsState()
    val busy by RecorderControl.busy.collectAsState()
    // The pairing code is typed into a notification while Settings is open, so notifications must be
    // allowed; finding Wireless debugging on the network needs local network access (Android 17+).
    val setupPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SetupService.startPairing(ctx)
        openWirelessDebugging(ctx)
    }

    LaunchedEffect(Unit) {
        while (true) {
            devOn = RecorderControl.developerOptionsOn(ctx)
            wifi = RecorderControl.onWifi(ctx)
            wireless = RecorderControl.wirelessDebuggingOn(ctx)
            running = withContext(Dispatchers.IO) { runCatching { Store.status().running }.getOrDefault(false) }
            delay(1000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item { ScreenTitle("Set up Azayn Recorder", inset = 4.dp) }
        item {
            Text(
                "Android doesn't let apps record calls. Azayn Recorder works around this the way developer tools do: " +
                    "it pairs once with your phone's own Wireless debugging, then starts its recorder through it. " +
                    "No computer and no root needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = az.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        item { SectionLabel("Steps") }
        item {
            Panel {
                Step(1, "Turn on Developer options", "Settings › About phone › Software information › tap Build number 7 times", devOn) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PanelDivider()
                Step(2, "Connect to Wi-Fi", "Wireless debugging only works on Wi-Fi. The recorder keeps running when you leave it.", wifi) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PanelDivider()
                Step(3, "Turn on Wireless debugging", "Developer options › Wireless debugging › On, then Allow", wireless || prefs.paired) {
                    openWirelessDebugging(ctx)
                }
                PanelDivider()
                Step(4, "Pair with a code", "Tap \"Pair device with pairing code\" and type the 6 digits here or in the notification", prefs.paired, null)
                PanelDivider()
                Step(5, "Start the recorder", "Starts automatically after pairing, and again after each restart", running, null)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
        item {
            when {
                running -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("All set. Calls are now recorded automatically.", style = MaterialTheme.typography.titleSmall, color = az.textPrimary)
                    Spacer(Modifier.height(12.dp))
                    GradientButton("Continue", null, onClick = onDone)
                }
                busy != null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(busy!!, style = MaterialTheme.typography.bodyMedium, color = az.textPrimary)
                }
                prefs.paired -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    GradientButton("Start the recorder", null, onClick = { SetupService.startRecorder(ctx) })
                }
                pairing -> Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (port == null) "Waiting for the pairing dialog… In Wireless debugging, tap \"Pair device with pairing code\"."
                            else "Pairing dialog found. Type the 6-digit code it shows.",
                            style = MaterialTheme.typography.bodyMedium, color = az.textPrimary,
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Pairing code") },
                            singleLine = true,
                            enabled = port != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = az.border),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GradientButton("Pair", null, onClick = { if (code.length == 6) SetupService.submitCode(ctx, code) })
                            TextButton(onClick = { openWirelessDebugging(ctx) }) { Text("Open Wireless debugging") }
                        }
                        Text(
                            "Tip: on the Fold, open Settings and this app side by side, or use the code field in the notification.",
                            style = MaterialTheme.typography.bodySmall, color = az.textTertiary,
                        )
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    GradientButton("Start pairing", null, onClick = {
                        setupPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_LOCAL_NETWORK))
                    })
                    if (!wireless) {
                        Spacer(Modifier.height(8.dp))
                        Text("Finish steps 1 to 3 first.", style = MaterialTheme.typography.bodySmall, color = az.textTertiary)
                    }
                }
            }
        }
        if (message != null) {
            item {
                Text(message!!, style = MaterialTheme.typography.bodyMedium, color = az.error, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
        }

        item { SectionLabel("Good to know") }
        item {
            Text(
                "The pairing gives this app the same access a computer with USB debugging has. It's used only to start " +
                    "the recorder and is never shared. You can turn Wireless debugging off any time; the recorder keeps " +
                    "running until the phone restarts, then the app starts it again when you're on Wi-Fi.\n\n" +
                    "Recording calls can be illegal without everyone's consent. You are responsible for using it lawfully.",
                style = MaterialTheme.typography.bodySmall,
                color = az.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (onSkip != null) {
            item {
                TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) { Text("Skip, I start it from a computer") }
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, hint: String, done: Boolean, onOpen: (() -> Unit)?) {
    PanelRow(
        "$n. $title",
        hint = hint,
        leading = {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (done) az.primary else az.textTertiary, modifier = Modifier.size(24.dp),
            )
        },
        onClick = if (!done) onOpen else null,
    ) { if (!done && onOpen != null) TextButton(onClick = onOpen) { Text("Open") } }
}
