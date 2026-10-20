package app.t2remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.Prefs
import app.t2remote.adb.AdbActions
import app.t2remote.adb.AdbController
import app.t2remote.hid.KeyMap

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val adb by AdbController.state.collectAsState()

    var pointer by remember { mutableFloatStateOf(Prefs.pointerSpeed) }
    var scroll by remember { mutableFloatStateOf(Prefs.scrollSpeed) }
    var keyDelay by remember { mutableFloatStateOf(Prefs.keyDelayMs.toFloat()) }
    var invert by remember { mutableStateOf(Prefs.invertScroll) }
    var auto by remember { mutableStateOf(Prefs.autoReconnect) }
    var combo by remember { mutableStateOf(Prefs.layoutSwitch) }
    var host by remember { mutableStateOf(Prefs.adbHost) }
    var port by remember { mutableStateOf(Prefs.adbPort.toString()) }
    var shellCmd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AdbActions.Action?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        SectionTitle("Mouse")
        Card {
            LabeledSlider("Pointer speed", pointer, 0.5f..4f, "%.1f×") { pointer = it; Prefs.pointerSpeed = it }
            LabeledSlider("Scroll speed", scroll, 0.3f..3f, "%.1f×") { scroll = it; Prefs.scrollSpeed = it }
            SwitchRow("Invert scroll direction", invert) { invert = it; Prefs.invertScroll = it }
        }

        SectionTitle("Keyboard")
        Card {
            Text("Projector shortcut that switches EN ⇄ RU layout:")
            KeyMap.SwitchCombo.entries.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = combo == c, onClick = { combo = c; Prefs.layoutSwitch = c })
                    Text(c.label)
                }
            }
            LabeledSlider("Delay between keys", keyDelay, 4f..60f, "%.0f ms") {
                keyDelay = it; Prefs.keyDelayMs = it.toInt()
            }
            Text("Raise the delay if characters get lost while typing.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Connection")
        Card {
            SwitchRow("Reconnect to last projector on launch", auto) { auto = it; Prefs.autoReconnect = it }
        }

        SectionTitle("Projector configuration (Wi-Fi ADB, optional)")
        Card {
            Text(
                "Enable on projector: Settings → Device Preferences → About → tap Build 7× → Developer options → " +
                    "turn on USB/Network debugging. Find the IP in Settings → Network.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("Projector IP") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = host.isNotBlank() && !adb.busy,
                    onClick = {
                        Prefs.adbHost = host; Prefs.adbPort = port.toIntOrNull() ?: 5555
                        AdbController.connect(ctx, host, Prefs.adbPort)
                    },
                ) { Text(if (adb.connected) "Reconnect" else "Connect") }
                if (adb.connected) OutlinedButton(onClick = { AdbController.disconnect() }) { Text("Disconnect") }
            }

            if (adb.connected) {
                Text("Open on projector", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdbActions.settings.forEach { a ->
                        FilledTonalButton(onClick = { AdbController.shell(a.cmd) }) { Text(a.label, fontSize = 12.sp) }
                    }
                }
                Text("System", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdbActions.system.forEach { a ->
                        FilledTonalButton(onClick = { if (a.confirm) confirm = a else AdbController.shell(a.cmd) }) {
                            Text(a.label, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(
                    shellCmd, { shellCmd = it }, label = { Text("Shell command") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(enabled = shellCmd.isNotBlank(), onClick = { AdbController.shell(shellCmd) }) { Text("Run") }
            }
            if (adb.output.isNotBlank()) {
                Text(adb.output, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${a.label}?") },
            text = { Text("Run “${a.cmd}” on the projector?") },
            confirmButton = { TextButton(onClick = { AdbController.shell(a.cmd); confirm = null }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Text(fmt.format(value), color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
