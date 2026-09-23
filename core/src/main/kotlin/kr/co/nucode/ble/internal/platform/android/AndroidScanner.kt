package kr.co.nucode.ble.internal.platform.android

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
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleServiceDataFilter
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.internal.ScanObservation

/** Android implementation of scan lifetime. Policy/predicate state stays in internal.Scanner. */
internal class AndroidScanner(private val scanner: () -> BluetoothLeScanner?) {
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(
        filter: BleScanFilter,
        options: BleScanOptions,
        onObservation: (ScanObservation) -> Unit,
        onFailure: (BleFailure) -> Unit,
    ): BleFailure? {
        val leScanner = scanner() ?: return FailureMapper.scanStartFailed().copy(
            code = kr.co.nucode.ble.FailureCode("SCANNER_UNAVAILABLE"),
        )
        val platformCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val facts = AndroidAdvertisementMapper.facts(result)
                onObservation(
                    ScanObservation(
                        peripheralId = PeripheralId(result.device.address),
                        rssi = result.rssi,
                        advertisement = AndroidAdvertisementMapper.map(facts),
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                onFailure(FailureMapper.scanRuntimeFailed(errorCode))
            }
        }
        return try {
            leScanner.startScan(toPlatformFilters(filter).ifEmpty { null }, toPlatformSettings(options), platformCallback)
            callback = platformCallback
            null
        } catch (_: SecurityException) {
            FailureMapper.permissionDenied(
                phase = kr.co.nucode.ble.FailurePhase.Scan,
                operation = AndroidApiOperation.StartScan,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val active = callback ?: return
        try {
            scanner()?.stopScan(active)
        } catch (_: SecurityException) {
            // Permission revocation is reported at the next command boundary; local ownership still ends.
        } finally {
            callback = null
        }
    }

    internal fun toPlatformFilters(filter: BleScanFilter): List<ScanFilter> = buildList {
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

    private fun toPlatformSettings(options: BleScanOptions): ScanSettings = ScanSettings.Builder().setScanMode(
        when (options.mode) {
            BleScanMode.LowPower -> ScanSettings.SCAN_MODE_LOW_POWER
            BleScanMode.Balanced -> ScanSettings.SCAN_MODE_BALANCED
            BleScanMode.LowLatency -> ScanSettings.SCAN_MODE_LOW_LATENCY
        },
    ).build()
}
