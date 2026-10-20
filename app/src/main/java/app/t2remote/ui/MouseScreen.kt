package app.t2remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.Prefs
import app.t2remote.hid.Consumer
import app.t2remote.hid.HidController
import kotlin.math.abs
import kotlin.math.max

@Composable
fun MouseScreen() {
    var dragLock by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Touchpad(Modifier.weight(1f).fillMaxWidth().padding(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { HidController.click(1) }, Modifier.weight(1f).height(56.dp)) { Text("Left") }
            Button(
                onClick = { dragLock = !dragLock; HidController.setButtonHeld(1, dragLock) },
                Modifier.width(88.dp).height(56.dp),
                colors = if (dragLock) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                else ButtonDefaults.filledTonalButtonColors(),
            ) { Text(if (dragLock) "Drop" else "Drag", fontSize = 13.sp) }
            FilledTonalButton(onClick = { HidController.click(2) }, Modifier.weight(1f).height(56.dp)) { Text("Right") }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { HidController.consumer(Consumer.BACK) }, Modifier.weight(1f)) { Text("Back") }
            FilledTonalButton(onClick = { HidController.consumer(Consumer.HOME) }, Modifier.weight(1f)) { Text("Home") }
            FilledTonalButton(onClick = { HidController.consumer(Consumer.VOL_DOWN) }, Modifier.weight(1f)) { Text("Vol −") }
            FilledTonalButton(onClick = { HidController.consumer(Consumer.VOL_UP) }, Modifier.weight(1f)) { Text("Vol +") }
        }
    }
}

/**
 * One finger: move pointer. Two fingers: scroll. Tap: left click. Two-finger tap: right click (Back on Android).
 */
@Composable
fun Touchpad(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.uptimeMillis
                    var end = start
                    var maxFingers = 1
                    var travel = 0f
                    var accX = 0f; var accY = 0f; var accWheel = 0f
                    val speed = Prefs.pointerSpeed
                    val wheelDiv = 36f / Prefs.scrollSpeed
                    val invert = if (Prefs.invertScroll) -1f else 1f

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        ev.changes.firstOrNull()?.let { end = it.uptimeMillis }
                        if (pressed.isEmpty()) break
                        maxFingers = max(maxFingers, pressed.size)

                        if (pressed.size == 1 && maxFingers == 1) {
                            val d = pressed[0].positionChange()
                            travel += abs(d.x) + abs(d.y)
                            // Mild acceleration: faster swipes move proportionally further
                            val boost = 1f + (abs(d.x) + abs(d.y)) / 60f
                            accX += d.x * speed * boost / 2f
                            accY += d.y * speed * boost / 2f
                            val ix = accX.toInt(); val iy = accY.toInt()
                            if (ix != 0 || iy != 0) {
                                HidController.mouseMove(ix, iy)
                                accX -= ix; accY -= iy
                            }
                        } else if (pressed.size >= 2) {
                            val dy = pressed.map { it.positionChange().y }.average().toFloat()
                            travel += abs(dy)
                            accWheel += dy / wheelDiv * invert
                            val w = accWheel.toInt()
                            if (w != 0) { HidController.wheel(w); accWheel -= w }
                        }
                        ev.changes.forEach { it.consume() }
                    }

                    if (travel < 24f && end - start < 280) {
                        HidController.click(if (maxFingers >= 2) 2 else 1)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Touchpad\n1 finger — move · tap — click\n2 fingers — scroll · tap — right click",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
