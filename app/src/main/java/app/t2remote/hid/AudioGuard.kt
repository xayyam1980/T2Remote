package app.t2remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The WANBO projector also acts as a Bluetooth speaker. If the phone streams audio to it (A2DP),
 * the projector treats the phone as an audio source and won't open the keyboard/mouse channel.
 * This detects that and tries to drop the audio link; public APIs can only detect it, so the
 * disconnect uses hidden methods that work on some phones. Otherwise the UI sends the user to
 * Bluetooth settings to switch off "Media audio".
 */
@SuppressLint("MissingPermission")
object AudioGuard {
    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    private val _audioHosts = MutableStateFlow<Set<String>>(emptySet())
    /** Addresses currently connected to the phone as an audio sink. */
    val audioHosts: StateFlow<Set<String>> = _audioHosts

    fun init(ctx: Context) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dp = proxy as BluetoothA2dp
                    BluetoothProfile.HEADSET -> headset = proxy as BluetoothHeadset
                }
                refresh()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) a2dp = null else headset = null
            }
        }
        adapter.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.HEADSET)
    }

    fun refresh() {
        val set = buildSet {
            runCatching { a2dp?.connectedDevices?.forEach { add(it.address) } }
            runCatching { headset?.connectedDevices?.forEach { add(it.address) } }
        }
        _audioHosts.value = set
    }

    fun isAudioConnected(d: BluetoothDevice): Boolean { refresh(); return d.address in _audioHosts.value }

    /** Best effort: stop audio to this device and stop it reconnecting. True if audio is now off. */
    fun blockAudio(d: BluetoothDevice): Boolean {
        for (proxy in listOfNotNull<BluetoothProfile>(a2dp, headset)) {
            val c = proxy.javaClass
            runCatching { c.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(proxy, d, 0) }
            runCatching { c.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(proxy, d, 0) }
            runCatching { c.getMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, d) }
        }
        Thread.sleep(600)
        return !isAudioConnected(d)
    }
}
