package kr.co.nucode.ble.internal.port

import java.util.UUID
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor

/**
 * Android GATT command/callback boundary.
 *
 * `GattBridge` owns lifecycle interpretation and `Session` updates. Implementations only start
 * platform commands and forward callback facts. A test driver may return `null` from [connect]
 * after a successful synthetic start; the production driver either returns a real handle or
 * throws.
 */
internal interface GattDriver {
    fun setEventSink(sink: (GattDriverEvent) -> Unit)

    fun connect(deviceId: PeripheralId, autoConnect: Boolean): GattHandle?

    /**
     * Coordinator-aware 연결 시작. 기존/production driver는 같은 Android 호출을 유지하고,
     * fake driver는 callback에 귀속할 generation을 캡처할 수 있다.
     */
    fun connect(
        deviceId: PeripheralId,
        autoConnect: Boolean,
        connectionGeneration: Long,
    ): GattHandle? = connect(deviceId, autoConnect)

    fun disconnect(deviceId: PeripheralId, gatt: GattHandle?)

    fun close(deviceId: PeripheralId, gatt: GattHandle?)

    fun discoverServices(deviceId: PeripheralId, gatt: GattHandle?): Boolean

    fun requestMtu(deviceId: PeripheralId, gatt: GattHandle?, mtu: Int): Boolean

    fun setCharacteristicNotification(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        enabled: Boolean,
    ): Boolean

    fun writeDescriptor(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        descriptor: PlatformDescriptor,
        value: ByteArray,
    ): Boolean

    fun readCharacteristic(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
    ): Boolean = false

    fun readDescriptor(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        descriptor: PlatformDescriptor,
    ): Boolean = false

    fun writeCharacteristic(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean

    /**
     * [characteristic] 이 [gatt] 의 현재 서비스 그래프에서 [charRef] 자리(서비스·characteristic UUID와
     * instance index)에 있는 바로 그 객체인지 판정하고, 맞으면 property mask 와 CCCD descriptor 를 돌려준다.
     */
    fun resolveTarget(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        charRef: CharacteristicRef,
        characteristic: PlatformCharacteristic,
    ): TargetResolution
}

/** driver 가 판정한 operation target 해석 결과. */
internal sealed interface TargetResolution {
    /** 현재 handle 의 서비스 그래프에서 같은 객체를 찾았다. [descriptors] 는 그 characteristic 의 descriptor(탐색 순서)다. */
    data class Resolved(
        val propertyMask: Int,
        val cccdDescriptor: PlatformDescriptor?,
        val descriptors: List<PlatformDescriptorEntry> = emptyList(),
    ) : TargetResolution

    /** 해석할 handle 이 없다. */
    data object NotConnected : TargetResolution

    /** handle 의 서비스 그래프에 해당 characteristic 이 (같은 객체로) 없다. */
    data object NotFound : TargetResolution
}

/** Callback origin guard. Production matches platform handle identity; fakes choose current/stale. */
internal fun interface GattCallbackSource {
    fun matches(currentGatt: GattHandle?): Boolean
}

/** Raw service snapshot. Mapping to public models remains in `GattBridge`. */
internal data class GattServiceSnapshot(
    val serviceUuid: UUID,
    val characteristics: List<GattCharacteristicSnapshot>,
    val instanceIndex: Int = 0,
)

/** Raw characteristic snapshot. The platform handle is absent in JVM fakes. [descriptorUuids] keep discovery order. */
internal data class GattCharacteristicSnapshot(
    val serviceUuid: UUID,
    val charUuid: UUID,
    val instanceIndex: Int,
    val propertyMask: Int,
    val platformCharacteristic: PlatformCharacteristic? = null,
    val serviceInstanceIndex: Int = 0,
    val descriptorUuids: List<UUID> = emptyList(),
)

/** 해석된 characteristic 아래 descriptor 하나 — 시작 때 (uuid, 순번)으로 고르는 플랫폼 객체. */
internal class PlatformDescriptorEntry(val uuid: UUID, val descriptor: PlatformDescriptor)

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

    class DescriptorRead(
        override val deviceId: PeripheralId,
        override val source: GattCallbackSource,
        val status: Int,
        val attributeIdentity: GattDescriptorIdentity?,
        val value: ByteArray,
    ) : GattDriverEvent
}
