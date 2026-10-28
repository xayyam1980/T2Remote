package app.t2remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.Prefs
import app.t2remote.adb.AdbController
import app.t2remote.hid.HidController
import app.t2remote.hid.KeyMap
import app.t2remote.hid.KeyMap.Layout

/** Text routing: Bluetooth HID (default) or Wi-Fi ADB via ADBKeyBoard. */
private object TextOut {
    private val wifi get() = app.t2remote.adb.WifiHid.active
    fun type(s: String) = when {
        wifi -> AdbController.a11yInsert(s)          // any language, no layout switching needed
        Prefs.textRouteAdb -> AdbController.typeUnicode(s)
        else -> HidController.type(s)
    }
    fun backspace(n: Int) = when {
        wifi -> AdbController.a11yDelete(n)
        Prefs.textRouteAdb -> AdbController.backspace(n)
        else -> HidController.keyRepeat(KeyMap.BACKSPACE, n)
    }
    fun enter() = when {
        wifi -> AdbController.remoteEnter()
        Prefs.textRouteAdb -> AdbController.keyevent(66)
        else -> HidController.key(KeyMap.ENTER)
    }
}

@Composable
fun KeyboardScreen() {
    val st by HidController.state.collectAsState()
    var text by remember { mutableStateOf("") }
    var viaAdb by remember { mutableStateOf(Prefs.textRouteAdb) }
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current

    // While this tab is open (and Wi-Fi control is on), the projector uses the invisible T2 Keyboard,
    // so its own on-screen keyboard doesn't interfere. Leaving the tab gives it back.
    val wifiReady = st.status == HidController.Status.CONNECTED && app.t2remote.adb.WifiHid.active
    androidx.compose.runtime.DisposableEffect(wifiReady) {
        if (wifiReady) AdbController.imeBegin()
        onDispose { if (wifiReady && app.t2remote.adb.WifiHid.active) AdbController.imeEnd() }
    }

    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Live input: every change is mirrored to the projector (diff → backspaces + new chars)
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    val common = text.commonPrefixWith(new).length
                    val removed = text.length - common
                    val added = new.substring(common)
                    if (removed > 0) TextOut.backspace(removed)
                    if (added.isNotEmpty()) TextOut.type(added)
                    text = new
                },
                label = { Text("Type here — goes straight to the projector") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).focusRequester(focus),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { TextOut.enter(); text = "" }),
            )
            Text(
                "Use your phone keyboard in English or Russian (switch with the globe key). " +
                    "The app switches the projector's layout automatically.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { text = "" }) { Text("Clear field") }
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { TextOut.type(it) } }) { Text("Send clipboard") }
                OutlinedButton(onClick = { TextOut.enter(); text = "" }) { Text("Enter ⏎") }
            }

            SectionTitle("Projector layout")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = st.layout == Layout.EN, onClick = { HidController.setAssumedLayout(Layout.EN) }, label = { Text("EN active") })
                FilterChip(selected = st.layout == Layout.RU, onClick = { HidController.setAssumedLayout(Layout.RU) }, label = { Text("RU active") })
                FilledTonalButton(onClick = { HidController.switchLayout() }) { Text("Switch EN⇄RU") }
            }
            Text(
                "If letters come out in the wrong alphabet, tap the chip that matches what the projector actually uses (nothing is sent).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Keys")
            KeyRow {
                K("Esc") { HidController.key(KeyMap.ESC) }
                K("Tab") { HidController.key(KeyMap.TAB) }
                K("⌫") { HidController.key(KeyMap.BACKSPACE) }
                K("Del") { HidController.key(KeyMap.DELETE) }
            }
            KeyRow {
                K("Home") { HidController.key(KeyMap.HOME) }
                K("↑") { HidController.key(KeyMap.UP) }
                K("End") { HidController.key(KeyMap.END) }
                K("PgUp") { HidController.key(KeyMap.PAGE_UP) }
            }
            KeyRow {
                K("←") { HidController.key(KeyMap.LEFT) }
                K("↓") { HidController.key(KeyMap.DOWN) }
                K("→") { HidController.key(KeyMap.RIGHT) }
                K("PgDn") { HidController.key(KeyMap.PAGE_DOWN) }
            }
            KeyRow {
                K("Ctrl+A") { HidController.key(0x04, KeyMap.CTRL) }
                K("Ctrl+C") { HidController.key(0x06, KeyMap.CTRL) }
                K("Ctrl+V") { HidController.key(0x19, KeyMap.CTRL) }
                K("Ctrl+X") { HidController.key(0x1B, KeyMap.CTRL) }
            }

            SectionTitle("Text route")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !viaAdb, onClick = { viaAdb = false; Prefs.textRouteAdb = false }, label = { Text("Bluetooth") })
                FilterChip(selected = viaAdb, onClick = { viaAdb = true; Prefs.textRouteAdb = true }, label = { Text("Wi-Fi ADB (ADBKeyBoard)") })
            }
            Text(
                "Use Wi-Fi ADB only if the projector has no Russian physical layout. It needs ADB connected (Config tab) " +
                    "and the ADBKeyBoard input method installed and selected on the projector.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)

@Composable
private fun RowScope.K(label: String, onClick: () -> Unit) =
    FilledTonalButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, fontSize = 13.sp, maxLines = 1) }
