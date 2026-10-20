package app.t2remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.t2remote.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * Turns the phone into a Bluetooth HID keyboard + mouse (Android 9+ BluetoothHidDevice API).
 * Permissions are checked by MainActivity before init().
 */
@SuppressLint("MissingPermission")
object HidController {
    private const val TAG = "T2Hid"

    enum class Status { NO_BLUETOOTH, OFF, REGISTERING, READY, CONNECTING, CONNECTED }

    data class State(
        val status: Status = Status.OFF,
        val host: BluetoothDevice? = null,
        val layout: KeyMap.Layout = KeyMap.Layout.EN,
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Recent connection events, newest last — shown on the Connect tab for troubleshooting. */
    val log: StateFlow<List<String>> = _log
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    fun log(msg: String) {
        Log.d(TAG, msg)
        _log.update { (it + "${timeFmt.format(java.util.Date())}  $msg").takeLast(40) }
    }
    private var connectedAt = 0L

    /** This phone's Bluetooth name, as the projector will list it. */
    fun localName(): String = runCatching { adapter?.name }.getOrNull() ?: android.os.Build.MODEL

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var proxyRequested = false
    private val callbackExec = Executors.newSingleThreadExecutor()
    private val typingExec = Executors.newSingleThreadExecutor()
    private var mouseButtons = 0

    val isConnected get() = _state.value.status == Status.CONNECTED

    fun init(ctx: Context) {
        if (proxyRequested) return
        val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (a == null) { _state.update { it.copy(status = Status.NO_BLUETOOTH, message = "Bluetooth not available") }; return }
        adapter = a
        if (!a.isEnabled) { _state.update { it.copy(status = Status.OFF, message = "Turn Bluetooth on") }; return }
        AudioGuard.init(ctx)
        Scanner.attach(ctx)
        proxyRequested = a.getProfileProxy(ctx.applicationContext, profileListener, BluetoothProfile.HID_DEVICE)
        if (!proxyRequested) _state.update { it.copy(message = "This phone does not support HID device mode") }
    }

    fun shutdown() {
        hid?.let {
            runCatching { _state.value.host?.let { h -> it.disconnect(h) } }
            runCatching { it.unregisterApp() }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        }
        hid = null; proxyRequested = false
        _state.update { State(layout = it.layout) }
    }

    /** Re-registers the keyboard/mouse service from scratch (recovers from stuck states). */
    fun restart(ctx: Context) {
        log("restart")
        main.removeCallbacks(watchdog)
        target = null
        shutdown()
        main.postDelayed({ init(ctx) }, 800)
    }

    fun bondedDevices(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedBy { it.name ?: it.address }

    private val main = Handler(Looper.getMainLooper())
    private var target: BluetoothDevice? = null
    private var attempt = 0
    private var waited = 0
    private const val MAX_ATTEMPTS = 3
    private const val ATTEMPT_SECONDS = 8

    fun connect(device: BluetoothDevice) {
        val h = hid ?: run {
            log("connect requested but keyboard service not registered")
            _state.update { it.copy(message = "Keyboard/mouse service not running — tap Restart, then Connect") }
            return
        }
        main.removeCallbacks(watchdog)
        target = device; attempt = 1; waited = 0
        log("connect → ${device.name ?: device.address}")
        _state.update { it.copy(status = Status.CONNECTING, message = "Connecting to ${device.name ?: device.address}…") }
        if (AudioGuard.isAudioConnected(device)) {
            // Projector is using the phone as an audio source — drop that first.
            _state.update { it.copy(message = "Projector is connected as a speaker — switching audio off…") }
            Thread {
                val off = AudioGuard.blockAudio(device)
                main.post {
                    if (!off) _state.update { it.copy(message = "Projector is still connected as a speaker. Turn off Media audio for it (button below), then Connect.") }
                    hid?.connect(device)
                    main.postDelayed(watchdog, 1000)
                }
            }.start()
            return
        }
        log("hid.connect returned ${h.connect(device)}")
        main.postDelayed(watchdog, 1000)
    }

    /**
     * Polls the real connection state once a second: some phones/projectors connect without
     * delivering the callback. Retries a stalled attempt, then gives up with a clear hint.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val h = hid ?: return
            val dev = target ?: return
            if (adoptConnected()) return
            if (_state.value.status != Status.CONNECTING) return
            waited++
            if (waited < ATTEMPT_SECONDS) { main.postDelayed(this, 1000); return }
            if (attempt < MAX_ATTEMPTS) {
                attempt++; waited = 0
                log("no answer, retry $attempt")
                _state.update { it.copy(message = "No answer from projector — retry $attempt of $MAX_ATTEMPTS…") }
                runCatching { h.disconnect(dev) }
                main.postDelayed({ hid?.connect(dev) }, 1200)
                main.postDelayed(this, 2200)
                return
            }
            runCatching { h.disconnect(dev) }
            target = null
            _state.update {
                it.copy(status = Status.READY, host = null,
                    message = if (AudioGuard.isAudioConnected(dev))
                        "Projector is connected as a speaker, so it ignores the keyboard/mouse. Turn off Media audio for it (button below), then Connect."
                    else "Projector paired but didn't open the keyboard/mouse channel. Tap Forget, unpair on the projector, then Search and Pair again.")
            }
        }
    }

    /** If the stack reports a connected host we didn't hear about, take it. */
    fun adoptConnected(): Boolean {
        val h = hid ?: return false
        val dev = runCatching { h.connectedDevices.firstOrNull() }.getOrNull()
            ?: target?.takeIf { runCatching { h.getConnectionState(it) }.getOrNull() == BluetoothProfile.STATE_CONNECTED }
            ?: return false
        onConnected(dev)
        return true
    }

    private fun onConnected(device: BluetoothDevice) {
        main.removeCallbacks(watchdog)
        if (_state.value.status != Status.CONNECTED) { connectedAt = System.currentTimeMillis(); log("CONNECTED to ${device.name ?: device.address}") }
        target = null
        Prefs.lastHost = device.address
        _state.update { it.copy(status = Status.CONNECTED, host = device, message = "Connected to ${device.name ?: device.address}") }
    }

    /** Removes the bond (hidden API, works on most phones) so pairing can start clean. */
    fun forget(device: BluetoothDevice): Boolean {
        runCatching { hid?.disconnect(device) }
        if (Prefs.lastHost == device.address) Prefs.lastHost = null
        return runCatching { device.javaClass.getMethod("removeBond").invoke(device) as Boolean }.getOrDefault(false)
    }

    fun disconnect() {
        main.removeCallbacks(watchdog)
        val dev = _state.value.host ?: target
        target = null
        dev?.let { runCatching { hid?.disconnect(it) } }
        _state.update { it.copy(status = if (hid != null) Status.READY else Status.OFF, host = null, message = "Disconnected") }
    }

    // ------------------------------------------------------------------ registration

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null; proxyRequested = false
            _state.update { it.copy(status = Status.OFF, host = null, message = "HID service stopped") }
        }
    }

    private fun register() {
        val h = hid ?: return
        _state.update { it.copy(status = Status.REGISTERING, message = "Registering keyboard/mouse…") }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "T2 Remote", "Keyboard and mouse", "T2Remote",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.bytes,
        )
        // Outgoing QoS as used by common working HID-device apps; some stacks reject a null QoS.
        val qos = android.bluetooth.BluetoothHidDeviceAppQosSettings(
            android.bluetooth.BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, android.bluetooth.BluetoothHidDeviceAppQosSettings.MAX,
        )
        log("registering keyboard/mouse service")
        if (!h.registerApp(sdp, null, qos, callbackExec, hidCallback)) {
            _state.update { it.copy(status = Status.OFF, message = "Registration failed — close other Bluetooth keyboard apps") }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log("keyboard/mouse service registered=$registered" + (pluggedDevice?.let { " host=${it.name ?: it.address}" } ?: ""))
            if (!registered) {
                _state.update { it.copy(status = Status.OFF, host = null, message = "Not registered") }
                return
            }
            _state.update { it.copy(status = Status.READY, message = "Ready — pair or pick the projector") }
            if (adoptConnected()) return
            val auto = pluggedDevice ?: if (Prefs.autoReconnect) {
                Prefs.lastHost?.let { addr -> bondedDevices().firstOrNull { it.address == addr } }
            } else null
            auto?.let { d -> main.post { connect(d) } }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            log("${device.name ?: device.address}: " + when (state) {
                BluetoothProfile.STATE_CONNECTED -> "connected"
                BluetoothProfile.STATE_CONNECTING -> "connecting"
                BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                else -> "disconnected"
            })
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> main.post { onConnected(device) }
                BluetoothProfile.STATE_CONNECTING ->
                    _state.update { it.copy(status = Status.CONNECTING) }
                BluetoothProfile.STATE_DISCONNECTED -> main.post {
                    // During a retry cycle the watchdog owns the state.
                    if (target == null) {
                        val quickDrop = _state.value.status == Status.CONNECTED &&
                            System.currentTimeMillis() - connectedAt < 4000
                        _state.update {
                            it.copy(status = Status.READY, host = null, message = if (quickDrop)
                                "The projector refused the keyboard/mouse connection. It only accepts devices it paired itself — follow “Pair from the projector” below."
                            else "Disconnected")
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = HidDescriptor.sizeOf(id.toInt())
            if (size > 0) hid?.replyReport(device, type, id, ByteArray(size))
            else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    // ------------------------------------------------------------------ low-level reports

    private fun send(id: Int, data: ByteArray): Boolean {
        val h = hid ?: return false
        val host = _state.value.host ?: (if (adoptConnected()) _state.value.host else null) ?: return false
        return h.sendReport(host, id, data)
    }

    private fun keyReport(mod: Int, code: Int) =
        send(HidDescriptor.ID_KEYBOARD, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))

    private fun pause() = Thread.sleep(Prefs.keyDelayMs.toLong().coerceAtLeast(4))

    /** Press + release synchronously. Call only on typingExec. */
    private fun tapNow(mod: Int, code: Int) {
        keyReport(mod, code); pause()
        keyReport(0, 0); pause()
    }

    // ------------------------------------------------------------------ keyboard API

    fun key(code: Int, mod: Int = 0) = typingExec.execute { tapNow(mod, code) }

    fun keyRepeat(code: Int, times: Int) = typingExec.execute { repeat(times) { tapNow(0, code) } }

    /** Types text, switching the projector's layout between EN and RU as needed. */
    fun type(text: String) = typingExec.execute {
        for (c in text) {
            val (layout, stroke) = KeyMap.resolve(c, _state.value.layout) ?: continue
            if (layout != _state.value.layout) switchLayoutNow(layout)
            tapNow(stroke.mod, stroke.code)
        }
    }

    /** Sends the layout-switch shortcut once and flips the tracked layout. */
    fun switchLayout() = typingExec.execute {
        val next = if (_state.value.layout == KeyMap.Layout.EN) KeyMap.Layout.RU else KeyMap.Layout.EN
        switchLayoutNow(next)
    }

    private fun switchLayoutNow(target: KeyMap.Layout) {
        val combo = Prefs.layoutSwitch
        if (combo.key == 0) { // modifier-only combo (Alt+Shift)
            keyReport(combo.mod, 0); pause(); keyReport(0, 0)
        } else {
            tapNow(combo.mod, combo.key)
        }
        Thread.sleep(120) // let the projector apply the switch
        _state.update { it.copy(layout = target) }
    }

    /** Correct the tracked layout without sending anything (when app and projector got out of sync). */
    fun setAssumedLayout(layout: KeyMap.Layout) = _state.update { it.copy(layout = layout) }

    // ------------------------------------------------------------------ mouse API

    fun mouseMove(dx: Int, dy: Int) {
        var x = dx; var y = dy
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127)
            send(HidDescriptor.ID_MOUSE, byteArrayOf(mouseButtons.toByte(), sx.toByte(), sy.toByte(), 0))
            x -= sx; y -= sy
        }
    }

    fun wheel(steps: Int) {
        var s = steps
        while (s != 0) {
            val chunk = s.coerceIn(-127, 127)
            send(HidDescriptor.ID_MOUSE, byteArrayOf(mouseButtons.toByte(), 0, 0, chunk.toByte()))
            s -= chunk
        }
    }

    fun click(button: Int = 1) = typingExec.execute {
        send(HidDescriptor.ID_MOUSE, byteArrayOf((mouseButtons or button).toByte(), 0, 0, 0)); pause()
        send(HidDescriptor.ID_MOUSE, byteArrayOf(mouseButtons.toByte(), 0, 0, 0))
    }

    /** Hold/release a button (for drag). Returns new held state. */
    fun setButtonHeld(button: Int, held: Boolean) {
        mouseButtons = if (held) mouseButtons or button else mouseButtons and button.inv()
        send(HidDescriptor.ID_MOUSE, byteArrayOf(mouseButtons.toByte(), 0, 0, 0))
    }

    // ------------------------------------------------------------------ consumer keys

    fun consumer(usage: Int) = typingExec.execute {
        send(HidDescriptor.ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte())); pause()
        send(HidDescriptor.ID_CONSUMER, byteArrayOf(0, 0))
    }
}
