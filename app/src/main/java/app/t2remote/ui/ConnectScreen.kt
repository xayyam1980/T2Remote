package app.t2remote.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.hid.HidController
import app.t2remote.hid.Scanner
import app.t2remote.hid.AudioGuard
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import app.t2remote.hid.HidController.Status

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(onMakeDiscoverable: () -> Unit, onEnableBluetooth: () -> Unit) {
    val st by HidController.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var forgotMsg by remember { mutableStateOf<String?>(null) }
    val devices = remember(refresh, st.status) { HidController.bondedDevices() }
    val scan by Scanner.state.collectAsState()
    val ctx = LocalContext.current
    val audioHosts by AudioGuard.audioHosts.collectAsState()
    LaunchedEffect(Unit) { while (true) { AudioGuard.refresh(); HidController.adoptConnected(); delay(2000) } }
    val speakerDevice = devices.firstOrNull { it.address in audioHosts && it.address != st.host?.address }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Card {
            Text("Status: ${st.status.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (st.message.isNotBlank()) Text(st.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.status == Status.OFF || st.status == Status.NO_BLUETOOTH)
                    Button(onClick = onEnableBluetooth) { Text("Start Bluetooth") }
                if (st.status != Status.CONNECTED)
                    OutlinedButton(onClick = { HidController.restart(ctx) }) { Text("Restart") }
                if (st.status == Status.CONNECTED || st.status == Status.CONNECTING)
                    OutlinedButton(onClick = { HidController.disconnect() }) {
                        Text(if (st.status == Status.CONNECTING) "Cancel" else "Disconnect")
                    }
            }
        }

        if (speakerDevice != null) {
            SectionTitle("Projector is connected as a speaker")
            Card {
                Text("“${speakerDevice.name ?: speakerDevice.address}” is playing your phone's audio, so it ignores the keyboard/mouse.")
                Text("Open Bluetooth settings → tap the gear next to it → turn off Media audio (and Call audio). Come back and tap Connect.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open Bluetooth settings") }
                    OutlinedButton(onClick = { Thread { AudioGuard.blockAudio(speakerDevice) }.start() }) { Text("Try auto-off") }
                }
            }
        }

        SectionTitle("Pair from the projector (recommended)")
        Card {
            Text("The projector accepts a keyboard/mouse only if it does the pairing itself.")
            Text("1. If the projector is already paired: tap Forget below, and on the projector open Settings → Remotes & Accessories → your phone → Unpair.")
            Text("2. Tap Make phone visible (5 min) and keep this app open.")
            Button(onClick = onMakeDiscoverable) {
                Text("Make phone visible")
            }
            Text("3. On the projector: Settings → Remotes & Accessories → Add accessory. Pick “${HidController.localName()}” and confirm the code.")
            Text("The status above turns Connected on its own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Alternative: search from the phone")
        Card {
            Text("Use only if the projector doesn't list your phone. On the projector keep Add accessory open, then tap Search and Pair.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { refresh++; Scanner.start(ctx) },
                    enabled = !scan.scanning && st.status != Status.OFF && st.status != Status.NO_BLUETOOTH,
                ) { Text(if (scan.scanning) "Searching…" else "Search") }
                if (scan.scanning) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    OutlinedButton(onClick = { Scanner.stop() }) { Text("Stop") }
                }
            }
            if (scan.message.isNotBlank()) Text(scan.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            scan.found.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = if (f.isTv) FontWeight.Bold else FontWeight.Normal)
                        Text(f.device.address + if (f.isTv) " · likely projector" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { Scanner.pairAndConnect(ctx, f.device) },
                        enabled = scan.pairing == null && st.status != Status.NO_BLUETOOTH,
                    ) { Text(if (scan.pairing == f.device.address) "Pairing…" else "Pair") }
                }
            }
        }

        SectionTitle("Already paired devices")
        Card {
            if (devices.isEmpty()) Text("No paired devices yet.")
            devices.forEach { d ->
                val isHost = st.host?.address == d.address
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: "Unknown", fontWeight = FontWeight.SemiBold)
                        Text(d.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isHost) Text("Connected", color = MaterialTheme.colorScheme.primary)
                    else {
                        OutlinedButton(
                            onClick = { HidController.connect(d) },
                            enabled = st.status != Status.NO_BLUETOOTH,
                        ) { Text("Connect") }
                        TextButton(onClick = {
                            val ok = HidController.forget(d)
                            refresh++
                            forgotMsg = if (ok) "Forgot ${d.name ?: d.address}. Also remove the phone on the projector " +
                                "(Settings → Remotes & Accessories → your phone → Unpair), keep Add accessory open, then Search and Pair again."
                            else "Couldn't forget automatically. Unpair it in the phone's Settings → Bluetooth, and on the projector."
                        }) { Text("Forget") }
                    }
                }
            }
            forgotMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
            OutlinedButton(onClick = { refresh++; HidController.adoptConnected() }) { Text("Refresh list") }
        }

        SectionTitle("Connection log")
        Card {
            val log by HidController.log.collectAsState()
            if (log.isEmpty()) Text("No events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            OutlinedButton(onClick = {
                clip.setText(androidx.compose.ui.text.AnnotatedString(log.joinToString("\n") + "\nphone=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " Android " + android.os.Build.VERSION.RELEASE))
            }) { Text("Copy log") }
            log.takeLast(25).forEach {
                Text(it, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
