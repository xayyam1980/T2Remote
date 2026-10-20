package app.t2remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.hid.Consumer
import app.t2remote.hid.HidController
import app.t2remote.hid.KeyMap

@Composable
fun RemoteScreen() {
    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RB(Icons.Filled.PowerSettingsNew, "Power") { HidController.consumer(Consumer.POWER) }
                RB(Icons.Filled.Search, "Search") { HidController.consumer(Consumer.SEARCH) }
                RB(Icons.Filled.Menu, "Menu") { HidController.consumer(Consumer.MENU) }
            }

            // D-pad (arrow keys + Enter = OK)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Pad(Icons.Filled.KeyboardArrowUp) { HidController.key(KeyMap.UP) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pad(Icons.Filled.KeyboardArrowLeft) { HidController.key(KeyMap.LEFT) }
                    Box(Modifier.padding(10.dp)) {
                        FilledIconButton(onClick = { HidController.key(KeyMap.ENTER) }, Modifier.size(88.dp), shape = CircleShape) {
                            Text("OK", fontSize = 20.sp)
                        }
                    }
                    Pad(Icons.Filled.KeyboardArrowRight) { HidController.key(KeyMap.RIGHT) }
                }
                Pad(Icons.Filled.KeyboardArrowDown) { HidController.key(KeyMap.DOWN) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RB(Icons.AutoMirrored.Filled.ArrowBack, "Back") { HidController.consumer(Consumer.BACK) }
                RB(Icons.Filled.Home, "Home") { HidController.consumer(Consumer.HOME) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RB(Icons.AutoMirrored.Filled.VolumeDown, "Vol −") { HidController.consumer(Consumer.VOL_DOWN) }
                RB(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { HidController.consumer(Consumer.MUTE) }
                RB(Icons.AutoMirrored.Filled.VolumeUp, "Vol +") { HidController.consumer(Consumer.VOL_UP) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RB(Icons.Filled.SkipPrevious, "Prev") { HidController.consumer(Consumer.PREV) }
                RB(Icons.Filled.PlayArrow, "Play/Pause") { HidController.consumer(Consumer.PLAY_PAUSE) }
                RB(Icons.Filled.SkipNext, "Next") { HidController.consumer(Consumer.NEXT) }
            }
        }
    }
}

@Composable
private fun Pad(icon: ImageVector, onClick: () -> Unit) =
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
    }

@Composable
private fun RB(icon: ImageVector, label: String, onClick: () -> Unit) =
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(60.dp)) { Icon(icon, contentDescription = label) }
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
