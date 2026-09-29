package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.model.WriteResult
import kotlinx.coroutines.flow.Flow
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.WriteReceipt

/** Stateless per-peripheral view; Session remains the sole mutable state owner. */
internal class DefaultBleSession(
    private val runtime: BleRuntime,
    override val peripheralId: PeripheralId,
) : BleSession {
    // The view retains one Session reference, never a registry/map or a job.
    private val backing = runtime.sessionForView(peripheralId)

    override val connectionState = MappedStateFlow(backing.connectionState) { it }
    override val services = MappedStateFlow(backing.services) { it }
    override val activeSubscriptions = backing.subscriptionState.activeFlow

    override suspend fun connect(options: BleConnectOptions) {
        runtime.checkOpen()
        runtime.connectOfficial(peripheralId, options)
    }

    override suspend fun disconnect() {
        runtime.disconnect(peripheralId)
    }

    override suspend fun read(characteristic: CharacteristicRef): ByteArray {
        runtime.checkOpen()
        return runtime.read(peripheralId, characteristic)
    }

    override suspend fun write(
        characteristic: CharacteristicRef,
        value: ByteArray,
        options: BleWriteOptions,
    ): WriteReceipt {
        runtime.checkOpen()
        val result = runtime.write(
            peripheralId, characteristic, value.copyOf(),
            if (options.mode == BleWriteMode.WithResponse) WriteType.WithResponse else WriteType.WithoutResponse,
            options.chunking,
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
            is WriteResult.Failed -> throw kr.co.nucode.ble.BleException(result.failure)
            is WriteResult.TooLarge -> throw kr.co.nucode.ble.BleException(
                GattFailureFactory.payloadTooLarge(
                    backing,
                    if (options.mode == BleWriteMode.WithResponse) OperationKind.WriteWithResponse else OperationKind.WriteWithoutResponse,
                ),
            )
        }
    }

    override suspend fun subscribe(characteristic: CharacteristicRef) {
        runtime.checkOpen()
        runtime.subscribe(peripheralId, characteristic)
    }

    override fun notifications(characteristic: CharacteristicRef): Flow<ByteArray> {
        return runtime.notifications(peripheralId, characteristic)
    }

    override suspend fun unsubscribe(characteristic: CharacteristicRef) {
        runtime.checkOpen()
        runtime.unsubscribe(peripheralId, characteristic)
    }
}
