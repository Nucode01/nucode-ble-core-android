package kr.co.nucode.ble.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.ClientSnapshot
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.normalized
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult

internal class DefaultBleClient internal constructor(
    private val runtime: BleRuntime,
) : BleClient {
    override val adapterState = runtime.adapterState
    override val scanResults: Flow<ScanResult> = runtime.scanObservations.map { observation ->
        ScanResult(observation.peripheralId, observation.rssi, observation.advertisement)
    }
    override val scanState = runtime.scanState
    override val events: Flow<BleClientEvent> = runtime.officialEvents

    override suspend fun startScan(request: ScanRequest) {
        runtime.checkOpen()
        runtime.startScan(request.filter, request.options)
    }

    override suspend fun stopScan() {
        runtime.stopScan()
    }

    override fun session(peripheralId: PeripheralId): DefaultBleSession {
        runtime.checkOpen()
        return DefaultBleSession(runtime, peripheralId.normalized())
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
