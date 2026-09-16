package kr.co.nucode.ble

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Platform-neutral central-role entry point. Construction is supplied by a platform boundary. */
public interface BleClient : Closeable {
    public val adapterState: StateFlow<AdapterState>
    public val scanResults: Flow<ScanResult>
    public val events: Flow<BleClientEvent>

    public suspend fun startScan(request: ScanRequest = ScanRequest())
    public suspend fun stopScan()

    public fun session(peripheralId: PeripheralId): BleSession

    public suspend fun connect(
        peripheralId: PeripheralId,
        options: BleConnectOptions = BleConnectOptions(),
    ): BleSession

    public suspend fun snapshot(): ClientSnapshot
    override fun close()
}
