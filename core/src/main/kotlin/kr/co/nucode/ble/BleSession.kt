package kr.co.nucode.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Thin public view of one peripheral session. */
public interface BleSession {
    public val peripheralId: PeripheralId
    /**
     * Authoritative connection state. Conflated: a slow collector may skip intermediate states
     * but always observes the latest one; nothing is lost to buffer overflow. Prefer this over
     * [BleClient.events] whenever the current state matters.
     */
    public val connectionState: StateFlow<ConnectionState>

    /**
     * Services from the most recent successful discovery; conflated like [connectionState].
     * Not cleared on disconnect: it keeps the last discovered list until the next discovery replaces it,
     * so check that [connectionState] is `Ready` before using it to address operations.
     */
    public val services: StateFlow<List<GattService>>

    /**
     * Characteristics whose notifications are enabled (CCCD write acknowledged) on the current
     * connection. Empty while not connected. A desired subscription that failed to restore is
     * absent even while Ready. Differs from `SessionSnapshot.subscriptions`, which is intent.
     */
    public val activeSubscriptions: StateFlow<Set<CharacteristicRef>>

    public suspend fun connect(options: BleConnectOptions = BleConnectOptions())
    public suspend fun disconnect()

    /**
     * GATT operations (read, write, subscribe, unsubscribe): once accepted, cancelling the caller detaches only
     * the caller. The operation still runs to completion (a queued one still starts, remaining write chunks are
     * still sent), the caller gets only `CancellationException`, and the session is not torn down. Only the
     * internal `operationTimeoutMillis` deadline, counted from the platform start, fails the session
     * (`OPERATION_TIMEOUT`). A cancelled subscribe that completes still becomes an active subscription.
     */
    public suspend fun read(characteristic: CharacteristicRef): ByteArray

    /**
     * Writes [value]. If chunking is disabled and [value] exceeds one chunk, or a fixed chunk size
     * exceeds what one write can carry, throws `BleException` with code `PAYLOAD_TOO_LARGE`
     * (retryability Never) and sends nothing.
     */
    public suspend fun write(
        characteristic: CharacteristicRef,
        value: ByteArray,
        options: BleWriteOptions = BleWriteOptions(),
    ): WriteReceipt

    public suspend fun subscribe(characteristic: CharacteristicRef)
    /**
     * Hot stream of notify/indicate values for [characteristic]. Each collector has its own
     * buffer of [BleConfig.eventBufferCapacity] values; if a collector falls further behind, the
     * newest values are dropped for that collector only. Other collectors, other sessions and
     * unrelated events cannot cause loss.
     */
    public fun notifications(characteristic: CharacteristicRef): Flow<ByteArray>
    public suspend fun unsubscribe(characteristic: CharacteristicRef)
}
