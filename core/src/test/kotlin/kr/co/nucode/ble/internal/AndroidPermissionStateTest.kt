package kr.co.nucode.ble.internal

import android.Manifest
import android.os.Build
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.internal.platform.android.AndroidPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidPermissionStateTest {
    @Test
    fun `S plus missing declaration cannot be requested`() {
        val result = AndroidPermissionState.evaluate(
            sdkInt = Build.VERSION_CODES.S,
            targetSdkInt = Build.VERSION_CODES.S,
            declared = setOf(Manifest.permission.BLUETOOTH_SCAN),
            isGranted = { true },
        )
        assertEquals(AdapterState.Unauthorized(canRequest = false), result)
    }

    @Test
    fun `S plus declared but denied is requestable`() {
        val result = AndroidPermissionState.evaluate(
            sdkInt = Build.VERSION_CODES.S,
            targetSdkInt = Build.VERSION_CODES.S,
            declared = setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            isGranted = { false },
        )
        assertEquals(AdapterState.Unauthorized(canRequest = true), result)
    }

    @Test
    fun `pre Q accepts either declared granted location permission`() {
        val result = AndroidPermissionState.evaluate(
            sdkInt = Build.VERSION_CODES.P,
            declared = setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            isGranted = { it == Manifest.permission.ACCESS_COARSE_LOCATION },
        )
        assertNull(result)
    }
}
