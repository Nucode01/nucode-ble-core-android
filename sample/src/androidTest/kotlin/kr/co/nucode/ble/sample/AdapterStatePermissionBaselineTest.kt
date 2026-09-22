package kr.co.nucode.ble.sample

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.android.AndroidBleClientFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adapter-state baseline: runtime permissions revoked before process start.
 * Run only after `pm revoke` — no GrantPermissionRule here on purpose. Records to logcat (BleBaseline).
 */
@RunWith(AndroidJUnit4::class)
class AdapterStatePermissionBaselineTest {

    @Test(timeout = 30_000)
    fun revokedRuntimePermissionYieldsUnauthorizedAndScanIsDenied() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareBle") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scanGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        assumeTrue("run after: adb shell pm revoke <pkg> BLUETOOTH_SCAN/CONNECT", !scanGranted)

        val client = AndroidBleClientFactory.create(context)
        try {
            val initial = client.adapterState.value
            Log.i(TAG, "STEP initial adapterState=$initial")
            val scanOutcome = try {
                client.startScan(ScanRequest()); "no-throw"
            } catch (e: BleException) {
                "BleException ${e.failure.code.value}/${e.failure.phase}/${e.failure.retryability}"
            }
            Log.i(TAG, "STEP startScan -> $scanOutcome")
            assertTrue("expected Unauthorized(canRequest=true), got $initial", initial == AdapterState.Unauthorized(canRequest = true))
            assertTrue("expected PERMISSION_DENIED, got $scanOutcome", scanOutcome.contains("PERMISSION_DENIED"))
        } finally {
            client.close()
        }
    }

    private companion object { const val TAG = "BleBaseline" }
}
