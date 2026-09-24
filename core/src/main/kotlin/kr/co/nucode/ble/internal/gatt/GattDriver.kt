package kr.co.nucode.ble.internal.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.session.Session

/**
 * Android GATT command/callback boundary.
 *
 * [GattBridge] owns lifecycle interpretation and [Session] updates. Implementations only start
 * platform commands and forward callback facts. A test driver may return `null` from [connect]
 * after a successful synthetic start; the production driver either returns a real handle or
 * throws.
 */
internal interface GattDriver {
    fun setEventSink(sink: (GattDriverEvent) -> Unit)

    fun connect(deviceId: PeripheralId, autoConnect: Boolean): BluetoothGatt?

    /**
     * Coordinator-aware 연결 시작. 기존/production driver는 같은 Android 호출을 유지하고,
     * fake driver는 callback에 귀속할 generation을 캡처할 수 있다.
     */
    fun connect(
        deviceId: PeripheralId,
        autoConnect: Boolean,
        connectionGeneration: Long,
    ): BluetoothGatt? = connect(deviceId, autoConnect)

    fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?)

    fun close(deviceId: PeripheralId, gatt: BluetoothGatt?)

    fun discoverServices(deviceId: PeripheralId, gatt: BluetoothGatt?): Boolean

    fun requestMtu(deviceId: PeripheralId, gatt: BluetoothGatt?, mtu: Int): Boolean

    fun setCharacteristicNotification(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean

    fun writeDescriptor(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean

    fun readCharacteristic(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean = false

    fun writeCharacteristic(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean
}

/** Callback origin guard. Production matches `BluetoothGatt` identity; fakes choose current/stale. */
internal fun interface GattCallbackSource {
    fun matches(currentGatt: BluetoothGatt?): Boolean
}

/** Raw service snapshot. Mapping to public models remains in [GattBridge]. */
internal data class GattServiceSnapshot(
    val serviceUuid: UUID,
    val characteristics: List<GattCharacteristicSnapshot>,
    val instanceIndex: Int = 0,
)

/** Raw characteristic snapshot. The platform handle is absent in JVM fakes. */
internal data class GattCharacteristicSnapshot(
    val serviceUuid: UUID,
    val charUuid: UUID,
    val instanceIndex: Int,
    val propertyMask: Int,
    val platformCharacteristic: BluetoothGattCharacteristic? = null,
    val serviceInstanceIndex: Int = 0,
)

internal fun GattCharacteristicSnapshot.toIdentity(): GattCharacteristicIdentity =
    GattCharacteristicIdentity(
        serviceUuid = serviceUuid,
        serviceInstanceIndex = serviceInstanceIndex,
        characteristicUuid = charUuid,
        characteristicInstanceIndex = instanceIndex,
    )

/** Android callback facts forwarded by [GattDriver]; no lifecycle policy lives here. */
internal sealed interface GattDriverEvent {
    val deviceId: PeripheralId
    val source: GattCallbackSource

    data class ConnectionStateChanged(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val status: Int,
        val newState: Int,
        /** Android가 주지 않는 값. fake/queued callback은 시작 시 캡처한 generation을 싣는다. */
        val connectionGeneration: Long? = null,
    ) : GattDriverEvent

    data class ServicesDiscovered(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val status: Int,
        val services: List<GattServiceSnapshot>,
        /** null인 production callback은 current handle identity로 current generation에 귀속한다. */
        val connectionGeneration: Long? = null,
    ) : GattDriverEvent

    data class MtuChanged(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val mtu: Int,
        val status: Int,
        /** null인 production callback은 current handle identity로 current generation에 귀속한다. */
        val connectionGeneration: Long? = null,
    ) : GattDriverEvent

    data class CharacteristicChanged(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val characteristic: GattCharacteristicSnapshot?,
        val value: ByteArray,
    ) : GattDriverEvent

    data class CharacteristicRead(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val characteristic: GattCharacteristicSnapshot?,
        val value: ByteArray,
        val status: Int,
    ) : GattDriverEvent

    data class CharacteristicWrite(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val status: Int,
        val attributeIdentity: GattCharacteristicIdentity? = null,
    ) : GattDriverEvent

    data class DescriptorWrite(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val status: Int,
        val attributeIdentity: GattDescriptorIdentity? = null,
    ) : GattDriverEvent
}
