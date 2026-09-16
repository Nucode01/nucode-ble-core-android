package kr.co.nucode.ble.internal.platform.android

import android.Manifest
import android.os.Build
import kr.co.nucode.ble.AdapterState

/** Android-only permission matrix. It deliberately reports facts and never requests a permission. */
internal object AndroidPermissionState {
    fun evaluate(
        sdkInt: Int,
        targetSdkInt: Int = sdkInt,
        declared: Set<String>,
        isGranted: (String) -> Boolean,
    ): AdapterState.Unauthorized? = when {
        sdkInt >= Build.VERSION_CODES.S && targetSdkInt >= Build.VERSION_CODES.S -> {
            val required = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            when {
                required.any { it !in declared } -> AdapterState.Unauthorized(canRequest = false)
                required.all(isGranted) -> null
                else -> AdapterState.Unauthorized(canRequest = true)
            }
        }

        sdkInt >= Build.VERSION_CODES.Q -> legacyFineLocation(declared, isGranted)
        else -> legacyAnyLocation(declared, isGranted)
    }

    private fun legacyFineLocation(
        declared: Set<String>,
        isGranted: (String) -> Boolean,
    ): AdapterState.Unauthorized? {
        val required = listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        return when {
            required.any { it !in declared } -> AdapterState.Unauthorized(canRequest = false)
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> null
            else -> AdapterState.Unauthorized(canRequest = true)
        }
    }

    private fun legacyAnyLocation(
        declared: Set<String>,
        isGranted: (String) -> Boolean,
    ): AdapterState.Unauthorized? {
        val bluetooth = listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val locations = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            bluetooth.any { it !in declared } || locations.none { it in declared } ->
                AdapterState.Unauthorized(canRequest = false)
            locations.any { it in declared && isGranted(it) } -> null
            else -> AdapterState.Unauthorized(canRequest = true)
        }
    }
}
