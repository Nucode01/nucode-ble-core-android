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

    /** Authoritative service snapshot of the current connection; conflated like [connectionState]. */
    public val services: StateFlow<List<GattService>>

    /**
     * Characteristics whose notifications are enabled (CCCD write acknowledged) on the current
     * connection. Empty while not connected. A desired subscription that failed to restore is
     * absent even while Ready. Differs from `SessionSnapshot.subscriptions`, which is intent.
     */
    public val activeSubscriptions: StateFlow<Set<CharacteristicRef>>

    public suspend fun connect(options: BleConnectOptions = BleConnectOptions())
    public suspend fun disconnect()

    public suspend fun read(characteristic: CharacteristicRef): ByteArray

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
