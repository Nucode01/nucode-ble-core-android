package kr.co.nucode.ble.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.Chunking
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.WriteReceipt
import kr.co.nucode.ble.WriteResult
import kr.co.nucode.ble.WriteType
import kr.co.nucode.ble.BleEvent

/** Stateless per-peripheral view; Session remains the sole mutable state owner. */
internal class DefaultBleSession(
    private val runtime: BleRuntime,
    override val peripheralId: PeripheralId,
) : BleSession {
    private val deviceId get() = DeviceId(peripheralId.value)
    // The view retains one Session reference, never a registry/map or a job.
    private val backing = runtime.sessionForView(deviceId)

    override val connectionState = MappedStateFlow(backing.connectionState) { it }
    override val services = MappedStateFlow(backing.services) { it }

    override suspend fun connect(options: BleConnectOptions) {
        runtime.checkOpen()
        runtime.connectOfficial(deviceId, options)
    }

    override suspend fun disconnect() {
        runtime.disconnect(deviceId)
    }

    override suspend fun read(characteristic: CharacteristicRef): ByteArray {
        runtime.checkOpen()
        return runtime.read(deviceId, characteristic.toLegacy())
    }

    override suspend fun write(
        characteristic: CharacteristicRef,
        value: ByteArray,
        options: BleWriteOptions,
    ): WriteReceipt {
        runtime.checkOpen()
        val result = runtime.write(
            deviceId, characteristic.toLegacy(), value.copyOf(),
            if (options.mode == BleWriteMode.WithResponse) WriteType.WithResponse else WriteType.WithoutResponse,
            when (options.chunking) {
                is kr.co.nucode.ble.BleChunking.Disabled -> Chunking.None
                is kr.co.nucode.ble.BleChunking.Automatic -> Chunking.Auto
                is kr.co.nucode.ble.BleChunking.Fixed -> Chunking.Fixed(options.chunking.maxBytes)
            },
        )
        return when (result) {
            is WriteResult.Completed -> WriteReceipt(
                bytesCompleted = value.size,
                chunksCompleted = result.chunksCompleted,
                delivery = if (options.mode == BleWriteMode.WithResponse) {
                    kr.co.nucode.ble.DeliverySemantics.RemoteAcknowledged
                } else {
                    kr.co.nucode.ble.DeliverySemantics.SubmittedToPlatform
                },
            )
            WriteResult.Ok -> error("legacy write success must not reach the official facade")
            is WriteResult.Failed -> throw kr.co.nucode.ble.BleException(result.failure)
            is WriteResult.TooLarge -> throw IllegalArgumentException("write exceeds ${result.max} bytes")
        }
    }

    override suspend fun subscribe(characteristic: CharacteristicRef) {
        runtime.checkOpen()
        runtime.subscribe(deviceId, characteristic.toLegacy())
    }

    override fun notifications(characteristic: CharacteristicRef): Flow<ByteArray> {
        val legacy = characteristic.toLegacy()
        return runtime.events.filter { it is BleEvent.CharValue && it.deviceId == deviceId && it.charRef == legacy }
            .map { (it as BleEvent.CharValue).bytes.copyOf() }
    }

    override suspend fun unsubscribe(characteristic: CharacteristicRef) {
        runtime.checkOpen()
        runtime.unsubscribe(deviceId, characteristic.toLegacy())
    }
}
