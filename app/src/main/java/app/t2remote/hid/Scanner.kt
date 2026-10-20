package app.t2remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Phone-side discovery + pairing. Android TV's "Add accessory" screen hides phones (it filters
 * for keyboards/remotes), so we pair from the phone instead, then connect the HID profile.
 */
@SuppressLint("MissingPermission")
object Scanner {
    data class Found(val device: BluetoothDevice, val name: String, val isTv: Boolean, val rssi: Int)

    data class State(
        val scanning: Boolean = false,
        val found: List<Found> = emptyList(),
        val pairing: String? = null,
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var adapter: BluetoothAdapter? = null
    private var registered = false
    private var pendingConnect: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.device() ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val name = d.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: return
                    val major = d.bluetoothClass?.majorDeviceClass
                    val isTv = major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                        listOf("wanbo", "t2", "projector", "tv").any { name.lowercase().contains(it) }
                    _state.update { s ->
                        val rest = s.found.filterNot { it.device.address == d.address }
                        s.copy(found = (rest + Found(d, name, isTv, rssi))
                            .sortedWith(compareByDescending<Found> { it.isTv }.thenByDescending { it.rssi }))
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _state.update { it.copy(scanning = true, message = "Searching…") }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _state.update {
                    it.copy(scanning = false, message = if (it.found.isEmpty())
                        "Nothing found. On the projector open Settings → Remotes & Accessories → Add accessory (makes it visible), then search again."
                    else "Tap your projector to pair.")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.device() ?: return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDING -> _state.update { it.copy(pairing = d.address, message = "Pairing… confirm the code on both screens") }
                        BluetoothDevice.BOND_BONDED -> {
                            _state.update { it.copy(pairing = null, message = "Paired with ${d.name ?: d.address}. Connecting…") }
                            if (pendingConnect == d.address) { pendingConnect = null; HidController.connect(d) }
                        }
                        BluetoothDevice.BOND_NONE -> if (_state.value.pairing == d.address)
                            _state.update { it.copy(pairing = null, message = "Pairing failed or cancelled. Try again.") }
                    }
                }
            }
        }
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun ensure(ctx: Context): BluetoothAdapter? {
        if (adapter == null) adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(ctx.applicationContext, receiver, f, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        return adapter
    }

    fun start(ctx: Context) {
        val a = ensure(ctx) ?: return
        if (a.isDiscovering) a.cancelDiscovery()
        _state.update { State(scanning = true, message = "Searching…") }
        if (!a.startDiscovery()) _state.update {
            it.copy(scanning = false, message = "Couldn't start search. Allow Nearby devices / Location permission and turn on Location (Android 11 and older).")
        }
    }

    fun stop() { runCatching { adapter?.cancelDiscovery() } }

    /** Pair (if needed) and then connect as keyboard/mouse. */
    fun pairAndConnect(ctx: Context, d: BluetoothDevice) {
        ensure(ctx)
        stop()
        if (d.bondState == BluetoothDevice.BOND_BONDED) { HidController.connect(d); return }
        pendingConnect = d.address
        if (!d.createBond()) _state.update { it.copy(message = "Pairing request refused by the phone.") }
    }
}
