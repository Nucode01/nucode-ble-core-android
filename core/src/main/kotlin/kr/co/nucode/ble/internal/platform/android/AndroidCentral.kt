package kr.co.nucode.ble.internal.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kr.co.nucode.ble.AdapterState

/** Owns Android Context/adapter/manifest facts; it has no permission UI responsibility. */
internal class AndroidCentral(private val context: Context) {
    fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun adapterState(): AdapterState {
        val adapter = adapter() ?: return AdapterState.Unsupported
        AndroidPermissionState.evaluate(
            sdkInt = Build.VERSION.SDK_INT,
            targetSdkInt = context.applicationInfo.targetSdkVersion,
            declared = declaredPermissions(),
            isGranted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
        )?.let { return it }
        return if (adapter.isEnabled) AdapterState.Ready else AdapterState.PoweredOff
    }

    private fun declaredPermissions(): Set<String> = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
    } catch (_: Exception) {
        emptySet()
    }
}
