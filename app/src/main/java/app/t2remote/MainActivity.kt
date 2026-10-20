package app.t2remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.t2remote.hid.HidController
import app.t2remote.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val btPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startBluetooth() }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startBluetooth() }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppRoot(
                onMakeDiscoverable = ::makeDiscoverable,
                onEnableBluetooth = ::ensureBluetooth,
            )
        }
        ensureBluetooth()
    }

    private fun hasPermissions() = btPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBluetooth() {
        if (!hasPermissions()) permissionLauncher.launch(btPermissions) else startBluetooth()
    }

    @Suppress("MissingPermission")
    private fun startBluetooth() {
        if (!hasPermissions()) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return HidController.init(this)
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        HidController.init(this)
    }

    private fun makeDiscoverable() {
        if (!hasPermissions()) { ensureBluetooth(); return }
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) HidController.shutdown()
    }
}
