package org.relay.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime-permission helpers. Every BLE call in this app is preceded by one of these checks. */
object BlePermissions {
    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun hasConnect(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScan(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(ctx, Manifest.permission.BLUETOOTH_SCAN)
        else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasAdvertise(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE)

    fun hasLocation(ctx: Context): Boolean = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasRecordAudio(ctx: Context): Boolean = granted(ctx, Manifest.permission.RECORD_AUDIO)

    /** True when the mesh transport can run (BLE permissions only; location/mic/notifications are optional). */
    fun canRunMesh(ctx: Context): Boolean = hasConnect(ctx) && hasScan(ctx) && hasAdvertise(ctx)

    /** Runtime permissions to request on first run. */
    fun requiredRuntimePermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        list += Manifest.permission.ACCESS_FINE_LOCATION
        list += Manifest.permission.ACCESS_COARSE_LOCATION // Android 12+: FINE must be requested together with COARSE
        list += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    /** Permissions still to request. COARSE only counts while FINE is missing (a user may grant approximate only). */
    fun missing(ctx: Context): List<String> =
        requiredRuntimePermissions().filter { p ->
            !granted(ctx, p) && (p != Manifest.permission.ACCESS_COARSE_LOCATION || !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
        }

    fun missingShort(ctx: Context): List<String> = missing(ctx).map { it.substringAfterLast('.') }

    fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Reading the adapter state needs no permission. */
    fun isBluetoothOn(ctx: Context): Boolean = try { adapter(ctx)?.isEnabled == true } catch (_: SecurityException) { false }

    fun hasBle(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}
