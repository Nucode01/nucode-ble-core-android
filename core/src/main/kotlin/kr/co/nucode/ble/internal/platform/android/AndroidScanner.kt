package kr.co.nucode.ble.internal.platform.android

import kr.co.nucode.ble.internal.port.PlatformScanPort
import kr.co.nucode.ble.internal.model.PlatformScanSpec
import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleServiceDataFilter
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.model.ScanObservation

/**
 * Android implementation of scan lifetime — 앱의 유일한 `BluetoothLeScanner.startScan/stopScan` 호출처
 * (plan scan-owner 불변식 ①). Policy/predicate state stays in internal.scan.Scanner.
 */
internal class AndroidScanner(central: AndroidCentral?) : PlatformScanPort {
    /** [central] 에서 매 호출 시 스캐너를 다시 얻는다(BT off → on 뒤 낡은 참조 금지). */
    private val scanner: () -> BluetoothLeScanner? = { central?.adapter()?.bluetoothLeScanner }

    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(
        spec: PlatformScanSpec,
        onResult: (ScanObservation) -> Unit,
        onFailure: (BleFailure) -> Unit,
    ): BleFailure? {
        val leScanner = scanner() ?: return FailureMapper.scanStartFailed().copy(
            code = kr.co.nucode.ble.FailureCode("SCANNER_UNAVAILABLE"),
        )
        // 잘못된 형식의 주소면 setDeviceAddress 가 그 자리에서 IllegalArgumentException 을 던진다
        // (review HIGH 2) — typed failure 로 바꿔 재발견 폴백 직접 연결이 UNKNOWN_DEVICE 로 이어지게 한다.
        val filters = try {
            platformFilters(spec)
        } catch (_: IllegalArgumentException) {
            return FailureMapper.scanStartFailed()
        }
        val platformCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onResult(observation(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onResult(observation(it)) }
            }

            override fun onScanFailed(errorCode: Int) {
                onFailure(FailureMapper.scanRuntimeFailed(errorCode))
            }
        }
        return try {
            leScanner.startScan(filters, toPlatformSettings(spec.mode), platformCallback)
            callback = platformCallback
            null
        } catch (_: SecurityException) {
            FailureMapper.permissionDenied(
                phase = kr.co.nucode.ble.FailurePhase.Scan,
                operation = AndroidApiOperation.StartScan,
            )
        } catch (_: Exception) {
            // 그 밖의 플랫폼 예외(IllegalStateException 등)도 typed 시작 실패로 — Scanner 상태가 고착되지 않게 한다.
            FailureMapper.scanStartFailed()
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        val active = callback ?: return
        try {
            scanner()?.stopScan(active)
        } catch (_: Exception) {
            // Permission revocation(SecurityException)·어댑터 꺼짐 등 어떤 예외든 삼킨다 — 권한은 다음 명령 경계에서
            // 보고되고, local ownership still ends.
        } finally {
            callback = null
        }
    }

    private fun observation(result: ScanResult): ScanObservation {
        val facts = AndroidAdvertisementMapper.facts(result)
        return ScanObservation(
            peripheralId = PeripheralId(result.device.address),
            rssi = result.rssi,
            advertisement = AndroidAdvertisementMapper.map(facts),
        )
    }

    /**
     * 플랫폼 필터 목록(OR) = 사용자 필터들 + 주소마다 setDeviceAddress 필터 하나. null = 전체 스캔 —
     * 사용자 필터가 플랫폼 수준에서 비어 있으면(전체 요청) 주소도 이미 덮으므로 주소 필터를 붙이지 않는다.
     */
    private fun platformFilters(spec: PlatformScanSpec): List<ScanFilter>? {
        val user = spec.userFilter?.let(::toPlatformFilters)
        if (user != null && user.isEmpty()) return null
        val filters = user.orEmpty() + spec.addresses.map { ScanFilter.Builder().setDeviceAddress(it.value).build() }
        return filters.ifEmpty { null }
    }

    internal companion object {
        fun toPlatformFilters(filter: BleScanFilter): List<ScanFilter> = buildList {
            filter.serviceUuids.forEach { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it.value)).build()) }
            filter.manufacturerData.forEach { add(manufacturerFilter(it)) }
            filter.serviceData.forEach { add(serviceDataFilter(it)) }
        }

        private fun manufacturerFilter(filter: BleManufacturerDataFilter): ScanFilter = ScanFilter.Builder().apply {
            when {
                filter.data == null -> setManufacturerData(filter.companyId, null)
                filter.mask == null -> setManufacturerData(filter.companyId, filter.data)
                else -> setManufacturerData(filter.companyId, filter.data, filter.mask)
            }
        }.build()

        private fun serviceDataFilter(filter: BleServiceDataFilter): ScanFilter = ScanFilter.Builder().apply {
            val uuid = ParcelUuid(filter.serviceUuid.value)
            when {
                filter.data == null -> setServiceData(uuid, null)
                filter.mask == null -> setServiceData(uuid, filter.data)
                else -> setServiceData(uuid, filter.data, filter.mask)
            }
        }.build()
    }

    private fun toPlatformSettings(mode: BleScanMode): ScanSettings = ScanSettings.Builder().setScanMode(
        when (mode) {
            BleScanMode.LowPower -> ScanSettings.SCAN_MODE_LOW_POWER
            BleScanMode.Balanced -> ScanSettings.SCAN_MODE_BALANCED
            BleScanMode.LowLatency -> ScanSettings.SCAN_MODE_LOW_LATENCY
        },
    ).build()
}
