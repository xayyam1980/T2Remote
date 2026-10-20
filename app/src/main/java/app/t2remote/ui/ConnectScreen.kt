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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.hid.HidController
import app.t2remote.hid.HidController.Status

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(onMakeDiscoverable: () -> Unit, onEnableBluetooth: () -> Unit) {
    val st by HidController.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val devices = remember(refresh, st.status) { HidController.bondedDevices() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Card {
            Text("Status: ${st.status.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (st.message.isNotBlank()) Text(st.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.status == Status.OFF || st.status == Status.NO_BLUETOOTH)
                    Button(onClick = onEnableBluetooth) { Text("Start Bluetooth") }
                if (st.status == Status.CONNECTED)
                    OutlinedButton(onClick = { HidController.disconnect() }) { Text("Disconnect") }
            }
        }

        SectionTitle("First time: pair from the projector")
        Card {
            Text("1. Tap the button below — the phone becomes visible for 2 minutes.")
            Button(onClick = onMakeDiscoverable, enabled = st.status == Status.READY || st.status == Status.CONNECTED) {
                Text("Make phone visible")
            }
            Text("2. On the projector: Settings → Remotes & Accessories → Add accessory (Pair accessory).")
            Text("3. Select “${android.os.Build.MODEL}” and confirm the pairing code on both screens.")
            Text("After that, the app reconnects automatically on launch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    else OutlinedButton(
                        onClick = { HidController.connect(d) },
                        enabled = st.status == Status.READY,
                    ) { Text("Connect") }
                }
            }
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh list") }
        }
    }
}
