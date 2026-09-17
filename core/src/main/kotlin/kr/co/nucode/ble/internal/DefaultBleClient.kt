package kr.co.nucode.ble.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.ClientSnapshot
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult

internal class DefaultBleClient internal constructor(
    private val runtime: BleRuntime,
) : BleClient {
    override val adapterState = runtime.adapterState
    override val scanResults: Flow<ScanResult> = runtime.scanObservations.map { observation ->
        ScanResult(observation.peripheralId, observation.rssi, observation.advertisement)
    }
    override val events: Flow<BleClientEvent> = runtime.officialEvents

    override suspend fun startScan(request: ScanRequest) {
        runtime.checkOpen()
        runtime.startScan(request.filter.toLegacy(), request.options.toLegacy())
    }

    override suspend fun stopScan() {
        runtime.stopScan()
    }

    override fun session(peripheralId: PeripheralId): DefaultBleSession {
        runtime.checkOpen()
        return DefaultBleSession(runtime, peripheralId)
    }

    override suspend fun connect(peripheralId: PeripheralId, options: BleConnectOptions): DefaultBleSession {
        runtime.checkOpen()
        return session(peripheralId).also { it.connect(options) }
    }

    override suspend fun snapshot(): ClientSnapshot {
        return runtime.officialSnapshot()
    }

    override fun close() = runtime.close()
}

internal fun CharacteristicRef.toLegacy() = kr.co.nucode.ble.CharRef(
    serviceUuid = service.uuid.value,
    charUuid = uuid.value,
    instanceIndex = instanceIndex,
    serviceInstanceIndex = service.instanceIndex,
)

internal fun kr.co.nucode.ble.BleScanFilter.toLegacy() = kr.co.nucode.ble.ScanFilter(
    serviceUuids = serviceUuids.map { it.value }, namePrefix = namePrefix,
    manufacturerData = manufacturerData.map { kr.co.nucode.ble.ManufacturerDataFilter(it.companyId, it.data, it.mask) },
    serviceData = serviceData.map { kr.co.nucode.ble.ServiceDataFilter(it.serviceUuid.value, it.data, it.mask) },
)

internal fun kr.co.nucode.ble.BleScanOptions.toLegacy() = kr.co.nucode.ble.ScanOptions(
    when (mode) {
        kr.co.nucode.ble.BleScanMode.LowPower -> kr.co.nucode.ble.ScanMode.LowPower
        kr.co.nucode.ble.BleScanMode.Balanced -> kr.co.nucode.ble.ScanMode.Balanced
        kr.co.nucode.ble.BleScanMode.LowLatency -> kr.co.nucode.ble.ScanMode.LowLatency
    },
)
