package org.relay.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.relay.app.MeshRepository
import org.relay.app.ble.BlePermissions

/** Permission / Bluetooth setup actions shared by the Home banner and the Settings tab. */
class SetupActions(
    val requestAll: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openAppSettings: () -> Unit,
    /** Bumped whenever permission state may have changed, to refresh derived UI. */
    val tick: Int,
)

@Composable
fun RelayRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var promptedThisRun by rememberSaveable { mutableIntStateOf(0) }

    fun startIfReady() {
        if (BlePermissions.canRunMesh(ctx)) MeshRepository.startService(ctx)
    }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        startIfReady() // the service waits for Bluetooth if the user declined
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (BlePermissions.canRunMesh(ctx) && !BlePermissions.isBluetoothOn(ctx) && BlePermissions.hasConnect(ctx)) {
            btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startIfReady()
        }
    }

    fun requestAll() {
        val missing = BlePermissions.missing(ctx)
        when {
            missing.isNotEmpty() -> permLauncher.launch(missing.toTypedArray())
            !BlePermissions.isBluetoothOn(ctx) && BlePermissions.hasConnect(ctx) ->
                btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> startIfReady()
        }
    }

    val actions = SetupActions(
        requestAll = ::requestAll,
        enableBluetooth = {
            if (BlePermissions.hasConnect(ctx)) btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else requestAll()
        },
        openAppSettings = { openAppSettings(ctx) },
        tick = tick,
    )

    // First run: ask for permissions, then Bluetooth, then start the service.
    LaunchedEffect(Unit) {
        if (promptedThisRun == 0) {
            promptedThisRun = 1
            requestAll()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        startIfReady()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("🏠" to "Home", "📡" to "Relay", "⚙️" to "Settings").forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(actions)
                1 -> RelayScreen()
                else -> SettingsScreen(actions)
            }
        }
    }
}

private fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}
