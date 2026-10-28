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
import androidx.compose.runtime.collectAsState
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
    var mode by remember { mutableStateOf(Prefs.touchMode) }
    val fg by app.t2remote.adb.AdbController.foreground.collectAsState()
    val autoDpad = fg in DPAD_APPS
    val dpad = when (mode) { "dpad" -> true; "mouse" -> false; else -> autoDpad }
    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "Auto", "mouse" to "Mouse", "dpad" to "D-pad").forEach { (k, label) ->
                androidx.compose.material3.FilterChip(
                    selected = mode == k,
                    onClick = { mode = k; Prefs.touchMode = k },
                    label = { Text(if (k == "auto") "Auto · " + (if (autoDpad) "D-pad" else "Mouse") else label, fontSize = 12.sp) },
                )
            }
        }
        Touchpad(Modifier.weight(1f).fillMaxWidth().padding(12.dp), dpad)
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
        // Arrow pad + OK for screens that ignore mouse clicks (e.g. the Google TV home menu)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val k = app.t2remote.hid.KeyMap
            FilledTonalButton(onClick = { HidController.key(k.LEFT) }, Modifier.weight(1f).height(52.dp)) { Text("←", fontSize = 20.sp) }
            FilledTonalButton(onClick = { HidController.key(k.UP) }, Modifier.weight(1f).height(52.dp)) { Text("↑", fontSize = 20.sp) }
            Button(onClick = { HidController.key(k.ENTER) }, Modifier.weight(1.2f).height(52.dp)) { Text("OK") }
            FilledTonalButton(onClick = { HidController.key(k.DOWN) }, Modifier.weight(1f).height(52.dp)) { Text("↓", fontSize = 20.sp) }
            FilledTonalButton(onClick = { HidController.key(k.RIGHT) }, Modifier.weight(1f).height(52.dp)) { Text("→", fontSize = 20.sp) }
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
/** TV apps that ignore mouse clicks — the touchpad becomes a swipe D-pad there. */
val DPAD_APPS = setOf(
    "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic", "com.google.android.youtube.tvkids",
    "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.hbo.hbonow",
    "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "app.t2remote.home",
    "com.google.android.katniss",
)

@Composable
fun Touchpad(modifier: Modifier, dpad: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (dpad) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(dpad) {
                if (dpad) { dpadGestures(); return@pointerInput }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.uptimeMillis
                    val origin = down.position
                    var end = start
                    var maxFingers = 1
                    // A tap stays a tap until the finger leaves this radius; until then the pointer doesn't move,
                    // so the click lands exactly where the pointer was.
                    val slop = viewConfiguration.touchSlop * 1.5f
                    var moved = false
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
                            val c = pressed[0]
                            if (!moved && (c.position - origin).getDistance() > slop) moved = true
                            if (moved) {
                                val d = c.positionChange()
                                // Mild acceleration: faster swipes move proportionally further
                                val boost = 1f + (abs(d.x) + abs(d.y)) / 60f
                                accX += d.x * speed * boost / 2f
                                accY += d.y * speed * boost / 2f
                                val ix = accX.toInt(); val iy = accY.toInt()
                                if (ix != 0 || iy != 0) {
                                    HidController.mouseMove(ix, iy)
                                    accX -= ix; accY -= iy
                                }
                            }
                        } else if (pressed.size >= 2) {
                            val dy = pressed.map { it.positionChange().y }.average().toFloat()
                            if (!moved && abs(pressed[0].position.y - origin.y) > slop) moved = true
                            if (moved) {
                                accWheel += dy / wheelDiv * invert
                                val w = accWheel.toInt()
                                if (w != 0) { HidController.wheel(w); accWheel -= w }
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    }

                    if (!moved && end - start < 400) {
                        HidController.click(if (maxFingers >= 2) 2 else 1)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (dpad) "D-pad\nswipe — move highlight · tap — OK\n2-finger tap — Back"
            else "Touchpad\n1 finger — move · tap — click\n2 fingers — scroll · tap — right click",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}


/** Swipe = arrow keys (repeats while you keep moving), tap = OK, two-finger tap = Back. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dpadGestures() {
    val step = 56.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.uptimeMillis
        var end = start
        var fingers = 1
        var accX = 0f; var accY = 0f
        var moved = false
        val slop = viewConfiguration.touchSlop * 1.5f
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            ev.changes.firstOrNull()?.let { end = it.uptimeMillis }
            if (pressed.isEmpty()) break
            fingers = max(fingers, pressed.size)
            if (pressed.size == 1 && fingers == 1) {
                val d = pressed[0].positionChange()
                accX += d.x; accY += d.y
                if (!moved && (pressed[0].position - down.position).getDistance() > slop) moved = true
                if (moved) {
                    while (abs(accX) >= step || abs(accY) >= step) {
                        if (abs(accX) >= abs(accY)) {
                            HidController.key(if (accX > 0) app.t2remote.hid.KeyMap.RIGHT else app.t2remote.hid.KeyMap.LEFT)
                            accX -= if (accX > 0) step else -step
                        } else {
                            HidController.key(if (accY > 0) app.t2remote.hid.KeyMap.DOWN else app.t2remote.hid.KeyMap.UP)
                            accY -= if (accY > 0) step else -step
                        }
                    }
                }
            }
            ev.changes.forEach { it.consume() }
        }
        if (!moved && end - start < 400) {
            if (fingers >= 2) HidController.consumer(Consumer.BACK)
            else HidController.key(app.t2remote.hid.KeyMap.ENTER)
        }
    }
}
