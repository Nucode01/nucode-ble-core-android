package kr.co.nucode.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Thin public view of one peripheral session. */
public interface BleSession {
    public val peripheralId: PeripheralId
    public val connectionState: StateFlow<ConnectionState>
    public val services: StateFlow<List<GattService>>

    public suspend fun connect(options: BleConnectOptions = BleConnectOptions())
    public suspend fun disconnect()

    public suspend fun read(characteristic: CharacteristicRef): ByteArray

    public suspend fun write(
        characteristic: CharacteristicRef,
        value: ByteArray,
        options: BleWriteOptions = BleWriteOptions(),
    ): WriteReceipt

    public suspend fun subscribe(characteristic: CharacteristicRef)
    public fun notifications(characteristic: CharacteristicRef): Flow<ByteArray>
    public suspend fun unsubscribe(characteristic: CharacteristicRef)
}
