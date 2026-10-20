package app.t2remote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

private val Scheme = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    secondary = Color(0xFFFFB86B),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1E252C),
)

private enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("Connect", Icons.Filled.Bluetooth),
    MOUSE("Mouse", Icons.Filled.Mouse),
    KEYBOARD("Keyboard", Icons.Filled.Keyboard),
    REMOTE("Remote", Icons.Filled.SettingsRemote),
    SETTINGS("Config", Icons.Filled.Settings),
}

@Composable
fun AppRoot(onMakeDiscoverable: () -> Unit, onEnableBluetooth: () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (Tab.entries[tab]) {
                        Tab.CONNECT -> ConnectScreen(onMakeDiscoverable, onEnableBluetooth)
                        Tab.MOUSE -> MouseScreen()
                        Tab.KEYBOARD -> KeyboardScreen()
                        Tab.REMOTE -> RemoteScreen()
                        Tab.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }
}
