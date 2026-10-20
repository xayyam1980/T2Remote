package app.t2remote.adb

import android.content.Context
import android.util.Base64
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Optional configuration channel: ADB over Wi-Fi (projector: Developer options → Network/ADB debugging, port 5555).
 * Used for opening system settings pages, launching apps, sleep/reboot, and Unicode text via ADBKeyBoard.
 */
object AdbController {
    data class State(
        val connected: Boolean = false,
        val busy: Boolean = false,
        val target: String = "",
        val output: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var dadb: Dadb? = null

    fun connect(ctx: Context, host: String, port: Int) = scope.launch {
        lock.withLock {
            _state.update { it.copy(busy = true, output = "Connecting to $host:$port…\nAccept the 'Allow debugging' prompt on the projector.") }
            runCatching { dadb?.close() }
            dadb = null
            try {
                val priv = File(ctx.filesDir, "adbkey")
                val pub = File(ctx.filesDir, "adbkey.pub")
                if (!priv.exists()) AdbKeyPair.generate(priv, pub)
                val d = Dadb.create(host, port, AdbKeyPair.read(priv, pub))
                val model = d.shell("getprop ro.product.model").output.trim()
                dadb = d
                _state.update { State(connected = true, target = "$host:$port", output = "Connected: $model") }
            } catch (e: Throwable) {
                _state.update { State(output = "Failed: ${e.message ?: e.javaClass.simpleName}\n" +
                        "Check IP, that ADB/network debugging is ON, and accept the prompt, then retry.") }
            }
        }
    }

    fun disconnect() = scope.launch {
        lock.withLock {
            runCatching { dadb?.close() }
            dadb = null
            _state.update { State(output = "Disconnected") }
        }
    }

    /** Runs a shell command on the projector and shows the output. */
    fun shell(cmd: String, quiet: Boolean = false) = scope.launch {
        lock.withLock {
            val d = dadb ?: run { _state.update { it.copy(output = "Not connected (Wi-Fi ADB)") }; return@withLock }
            if (!quiet) _state.update { it.copy(busy = true) }
            try {
                val r = d.shell(cmd)
                if (!quiet) _state.update { it.copy(busy = false, output = "$ $cmd\n${r.output.ifBlank { "(exit ${r.exitCode})" }}".take(6000)) }
            } catch (e: Throwable) {
                runCatching { d.close() }
                dadb = null
                _state.update { State(output = "Connection lost: ${e.message}") }
            }
        }
    }

    fun keyevent(code: Int) { shell("input keyevent $code", quiet = true) }

    /** Unicode text via ADBKeyBoard IME (com.android.adbkeyboard) — works for Russian without layout switching. */
    fun typeUnicode(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        shell("am broadcast -a ADB_INPUT_B64 --es msg $b64", quiet = true)
    }

    fun backspace(times: Int) {
        if (times <= 0) return
        shell("input keyevent " + List(times) { "67" }.joinToString(" "), quiet = true)
    }

    val isConnected get() = _state.value.connected
}

/** Ready-made configuration commands for Android TV 11. */
object AdbActions {
    data class Action(val label: String, val cmd: String, val confirm: Boolean = false)

    val settings = listOf(
        Action("All settings", "am start -a android.settings.SETTINGS"),
        Action("Wi-Fi", "am start -a android.settings.WIFI_SETTINGS"),
        Action("Bluetooth", "am start -a android.settings.BLUETOOTH_SETTINGS"),
        Action("Display", "am start -a android.settings.DISPLAY_SETTINGS"),
        Action("Sound", "am start -a android.settings.SOUND_SETTINGS"),
        Action("Keyboard layouts", "am start -a android.settings.HARD_KEYBOARD_SETTINGS"),
        Action("Input methods", "am start -a android.settings.INPUT_METHOD_SETTINGS"),
        Action("Language", "am start -a android.settings.LOCALE_SETTINGS"),
        Action("Date & time", "am start -a android.settings.DATE_SETTINGS"),
        Action("Apps", "am start -a android.settings.APPLICATION_SETTINGS"),
        Action("Developer options", "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
        Action("Accessibility", "am start -a android.settings.ACCESSIBILITY_SETTINGS"),
    )

    val system = listOf(
        Action("Sleep", "input keyevent 223"),
        Action("Wake", "input keyevent 224"),
        Action("Screenshot → /sdcard", "screencap -p /sdcard/t2remote.png && echo saved /sdcard/t2remote.png"),
        Action("Device info", "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id; wm size"),
        Action("Third-party apps", "pm list packages -3"),
        Action("Current input method", "settings get secure default_input_method"),
        Action("Enable ADBKeyBoard", "ime enable com.android.adbkeyboard/.AdbIME && ime set com.android.adbkeyboard/.AdbIME"),
        Action("Reboot", "reboot", confirm = true),
    )
}
