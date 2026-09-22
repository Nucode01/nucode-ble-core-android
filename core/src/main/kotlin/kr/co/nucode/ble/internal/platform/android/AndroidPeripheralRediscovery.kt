package kr.co.nucode.ble.internal.platform.android

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.internal.PeripheralRediscovery
import kr.co.nucode.ble.internal.RediscoveryOutcome

/**
 * Address-filtered, bounded scan that repopulates the platform LE cache before connectGatt.
 * After a Bluetooth restart the stack forgets the address type it learned from a prior
 * advertisement, so `getRemoteDevice(address)` connects as PUBLIC and never reaches a
 * random-static peripheral. One address-filtered scan observation restores that cache entry.
 * This runs its own [BluetoothLeScanner] client independent of the app-wide [AndroidScanner]
 * worker, since an app may hold multiple concurrent scan clients.
 */
@SuppressLint("MissingPermission")
internal class AndroidPeripheralRediscovery(
    private val scanner: () -> BluetoothLeScanner?,
) : PeripheralRediscovery {
    override suspend fun rediscover(deviceId: DeviceId, timeoutMillis: Long): RediscoveryOutcome {
        val leScanner = scanner() ?: return RediscoveryOutcome.Unavailable(
            FailureMapper.scanStartFailed().copy(code = FailureCode("SCANNER_UNAVAILABLE")),
        )

        // 잘못된 형식의 주소면 setDeviceAddress 가 그 자리에서 IllegalArgumentException 을 던진다
        // (review HIGH 2) — typed failure 로 바꿔 폴백 직접 연결이 UNKNOWN_DEVICE 로 이어지게 한다.
        val (filter, settings) = try {
            ScanFilter.Builder().setDeviceAddress(deviceId.value).build() to
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        } catch (_: IllegalArgumentException) {
            return RediscoveryOutcome.Unavailable(FailureMapper.scanStartFailed())
        }
        val deferred = CompletableDeferred<RediscoveryOutcome>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address.equals(deviceId.value, ignoreCase = true)) {
                    deferred.complete(RediscoveryOutcome.Found)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.any { it.device.address.equals(deviceId.value, ignoreCase = true) }) {
                    deferred.complete(RediscoveryOutcome.Found)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                deferred.complete(RediscoveryOutcome.Unavailable(FailureMapper.scanRuntimeFailed(errorCode)))
            }
        }

        val startFailure = try {
            leScanner.startScan(listOf(filter), settings, callback)
            null
        } catch (_: SecurityException) {
            return RediscoveryOutcome.Unavailable(
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Scan,
                    operation = AndroidApiOperation.StartScan,
                ),
            )
        } catch (_: Exception) {
            RediscoveryOutcome.Unavailable(FailureMapper.scanStartFailed())
        }
        if (startFailure != null) return startFailure

        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() } ?: RediscoveryOutcome.NotFound
        } finally {
            try {
                leScanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission revocation is reported at the next command boundary; local ownership still ends.
            }
        }
    }
}
