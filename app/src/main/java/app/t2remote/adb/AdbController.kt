package app.t2remote.adb

import android.content.Context
import android.os.Build
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** ADB client identity (RSA key + self-signed cert), persisted in app storage. */
private class AdbManager(ctx: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(ctx.filesDir, "adb_private.key")
        val certFile = File(ctx.filesDir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=T2Remote")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            key = kp.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "T2Remote"
}

/**
 * Wi-Fi control/diagnostics channel over ADB.
 * Android 11+ "Wireless debugging": pair once with the 6-digit code, then connect (port changes each time —
 * leave Connect port empty to discover it automatically). Classic "ADB over network" on port 5555 also works.
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
    private var mgr: AdbManager? = null

    private fun manager(ctx: Context): AdbManager = mgr ?: AdbManager(ctx.applicationContext).also { mgr = it }

    private fun fail(prefix: String, e: Throwable) =
        _state.update { it.copy(busy = false, connected = false, output = "$prefix: ${e.message ?: e.javaClass.simpleName}") }

    /** Step 1 (once): Wireless debugging → "Pair device with pairing code". */
    fun pair(ctx: Context, host: String, port: Int, code: String) = scope.launch {
        lock.withLock {
            _state.update { it.copy(busy = true, output = "Pairing with $host:$port…") }
            try {
                val ok = manager(ctx).pair(host, port, code.trim())
                _state.update { it.copy(busy = false, output = if (ok) "Paired. Now tap Connect." else "Pairing failed — check the code and pairing port (they change each time).") }
            } catch (e: Throwable) { fail("Pairing failed", e) }
        }
    }

    /** Step 2: connect. port <= 0 → discover the Wireless-debugging port automatically. */
    fun connect(ctx: Context, host: String, port: Int) = scope.launch {
        lock.withLock {
            _state.update { it.copy(busy = true, output = "Connecting… accept “Allow debugging” on the projector if asked.") }
            try {
                val m = manager(ctx)
                runCatching { m.disconnect() }
                m.setHostAddress(host)
                val ok = if (port > 0) m.connect(host, port) else m.autoConnect(ctx.applicationContext, 10_000)
                if (!ok) throw IllegalStateException("no answer")
                val model = execLocked(m, "getprop ro.product.model; getprop ro.build.version.release").trim().replace("\n", " · Android ")
                _state.update { State(connected = true, target = if (port > 0) "$host:$port" else host, output = "Connected: $model") }
            } catch (e: Throwable) {
                fail("Connect failed", e)
                _state.update { it.copy(output = it.output + "\nIs Wireless debugging ON and paired? Leave the port empty to auto-detect it.") }
            }
        }
    }

    fun disconnect() = scope.launch {
        lock.withLock {
            runCatching { mgr?.disconnect() }
            _state.update { State(output = "Disconnected") }
        }
    }

    private fun execLocked(m: AdbManager, cmd: String): String =
        m.openStream("shell:$cmd").use { it.openInputStream().readBytes().toString(Charsets.UTF_8) }

    /** Runs a shell command on the projector and shows the output. */
    fun shell(cmd: String, quiet: Boolean = false) = scope.launch {
        lock.withLock {
            val m = mgr
            if (m == null || !_state.value.connected) { _state.update { it.copy(output = "Not connected (Wi-Fi ADB)") }; return@withLock }
            if (!quiet) _state.update { it.copy(busy = true) }
            try {
                val out = execLocked(m, cmd)
                if (!quiet) _state.update { it.copy(busy = false, output = "$ $cmd\n${out.ifBlank { "(no output)" }}".take(12000)) }
            } catch (e: Throwable) {
                fail("Connection lost", e)
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
        Action("Bluetooth HID log", "logcat -d -t 3000 | grep -iE 'hidh|bt_hh|btif_hh|bta_hh|hidhost|hid_host|uhid|l2cap.*hid|bt_l2c|security|encrypt' | tail -n 120"),
        Action("Bluetooth state", "dumpsys bluetooth_manager | grep -iE -A3 'hid|input' | head -n 150"),
        Action("Device info", "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id; wm size"),
        Action("Third-party apps", "pm list packages -3"),
        Action("Current input method", "settings get secure default_input_method"),
        Action("Enable ADBKeyBoard", "ime enable com.android.adbkeyboard/.AdbIME && ime set com.android.adbkeyboard/.AdbIME"),
        Action("Reboot", "reboot", confirm = true),
    )
}
