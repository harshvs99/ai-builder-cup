package org.relay.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.relay.app.ble.BlePermissions

/**
 * Permissions asked one at a time, when first needed (design spec, Navigation): Bluetooth + notifications when
 * Home first shows, location at the first Send, microphone at the first mic tap.
 *
 * Get one in a composable with [rememberPermissions], then e.g. `permissions.ensureMicrophone { granted -> ... }`.
 * Each `ensure*` calls `onResult(true)` straight away when already granted, otherwise shows the system prompt and
 * calls `onResult` with the outcome. Read [tick] (or any `has*`/`isBluetoothOn` after reading it) in composition to
 * recompose when permission state may have changed (it also bumps on every resume, e.g. back from system settings).
 */
class Permissions internal constructor(
    private val ctx: Context,
    private val launchPermissions: (Array<String>) -> Unit,
    private val launchEnableBluetooth: () -> Unit,
) {
    /** Bumped whenever permission or Bluetooth state may have changed. */
    var tick: Int by mutableIntStateOf(0)
        private set

    private enum class Kind { BLUETOOTH, LOCATION, MICROPHONE }

    private var pendingKind: Kind? = null
    private var pendingResult: ((Boolean) -> Unit)? = null

    // ---- state
    fun hasBluetooth(): Boolean = BlePermissions.canRunMesh(ctx)
    fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
    fun hasLocation(): Boolean = BlePermissions.hasLocation(ctx)
    fun hasMicrophone(): Boolean = BlePermissions.hasRecordAudio(ctx)
    fun isBluetoothOn(): Boolean = BlePermissions.isBluetoothOn(ctx)

    // ---- ensure*
    /** Bluetooth (scan, advertise, connect) plus notifications, then asks to switch Bluetooth on if it is off. */
    fun ensureBluetooth(onResult: (Boolean) -> Unit = {}) {
        val missing = bluetoothPermissions().filter { !granted(it) }
        if (missing.isNotEmpty()) {
            begin(Kind.BLUETOOTH, onResult)
            launchPermissions(missing.toTypedArray())
        } else {
            afterBluetoothGranted(onResult)
        }
    }

    /** Precise location (geotags the NEED; also scanning on Android 11 and older). */
    fun ensureLocation(onResult: (Boolean) -> Unit = {}) {
        if (hasLocation()) return onResult(true)
        begin(Kind.LOCATION, onResult)
        launchPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun ensureMicrophone(onResult: (Boolean) -> Unit = {}) {
        if (hasMicrophone()) return onResult(true)
        begin(Kind.MICROPHONE, onResult)
        launchPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    /** Ask to switch Bluetooth on (needs the connect permission; asks for the Bluetooth permissions first). */
    fun enableBluetooth(onResult: (Boolean) -> Unit = {}) {
        if (!hasBluetooth()) return ensureBluetooth(onResult)
        if (isBluetoothOn()) return onResult(true)
        begin(Kind.BLUETOOTH, onResult)
        launchEnableBluetooth()
    }

    /** System settings page of this app (for permissions the user denied for good). */
    fun openAppSettings() {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    // ---- plumbing, called by [rememberPermissions]
    internal fun refresh() { tick++ }

    internal fun onPermissionsResult() {
        tick++
        val kind = pendingKind
        when (kind) {
            Kind.BLUETOOTH -> {
                if (hasBluetooth()) afterBluetoothGranted(pendingResult ?: {}, clearPendingFirst = true) else finish(false)
            }
            Kind.LOCATION -> finish(hasLocation())
            Kind.MICROPHONE -> finish(hasMicrophone())
            null -> {}
        }
    }

    internal fun onBluetoothEnableResult() {
        tick++
        if (hasBluetooth()) MeshRepository.startService(ctx) // the service also waits for Bluetooth by itself
        finish(hasBluetooth() && isBluetoothOn())
    }

    private fun afterBluetoothGranted(onResult: (Boolean) -> Unit, clearPendingFirst: Boolean = false) {
        if (clearPendingFirst) { pendingKind = null; pendingResult = null }
        if (BlePermissions.hasConnect(ctx) && !isBluetoothOn()) {
            begin(Kind.BLUETOOTH, onResult)
            launchEnableBluetooth()
        } else {
            MeshRepository.startService(ctx)
            tick++
            onResult(true)
        }
    }

    private fun begin(kind: Kind, onResult: (Boolean) -> Unit) {
        pendingKind = kind
        pendingResult = onResult
    }

    private fun finish(ok: Boolean) {
        val cb = pendingResult
        pendingKind = null
        pendingResult = null
        cb?.invoke(ok)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun bluetoothPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and older: BLE scanning needs location.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** A [Permissions] bound to this composition's activity-result launchers. */
@Composable
fun rememberPermissions(): Permissions {
    val ctx = LocalContext.current
    val holder = remember { arrayOfNulls<Permissions>(1) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        holder[0]?.onPermissionsResult()
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        holder[0]?.onBluetoothEnableResult()
    }
    val p = remember {
        Permissions(
            ctx,
            launchPermissions = { permLauncher.launch(it) },
            launchEnableBluetooth = { btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        ).also { holder[0] = it }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { p.refresh() }
    return p
}
