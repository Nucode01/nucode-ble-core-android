package kr.co.nucode.ble

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Platform-neutral central-role entry point. Construction is supplied by a platform boundary. */
public interface BleClient : Closeable {
    public val adapterState: StateFlow<AdapterState>

    /**
     * Hot, buffered stream of scan results. Only values emitted while collecting are seen. The
     * buffer of [BleConfig.eventBufferCapacity] values is shared by all collectors: while any
     * collector is that far behind, newly emitted values are dropped for every collector.
     */
    public val scanResults: Flow<ScanResult>

    /**
     * Hot, buffered stream of change notices: connection transitions, discovered services,
     * subscription restore failures and diagnostics. It is **lossy under backpressure** — the
     * buffer of [BleConfig.eventBufferCapacity] events is shared by all collectors, and while any
     * collector is that far behind, newly emitted events of every kind are dropped for every
     * collector. Do not reconstruct state from this stream: the
     * authoritative, never-lost values are [BleSession.connectionState] and
     * [BleSession.services] (conflated `StateFlow`s that always hold the latest value).
     */
    public val events: Flow<BleClientEvent>

    public suspend fun startScan(request: ScanRequest = ScanRequest())
    public suspend fun stopScan()

    public fun session(peripheralId: PeripheralId): BleSession

    public suspend fun connect(
        peripheralId: PeripheralId,
        options: BleConnectOptions = BleConnectOptions(),
    ): BleSession

    public suspend fun snapshot(): ClientSnapshot

    /**
     * Idempotent — a second call is a no-op. Schedules cleanup on the core dispatcher and
     * returns immediately without waiting for it; [snapshot] awaits that cleanup's completion
     * before returning, so callers that need a post-close guarantee should go through
     * [snapshot] rather than assuming [close] itself blocked. Any command issued after [close]
     * has run fails with `CLIENT_CLOSED`. Because [close] can return before the underlying
     * platform connection handles are released, those handles may still be open at the moment
     * this call returns — if the process exits immediately afterward, the platform reclaims
     * them on process death.
     */
    override fun close()
}
