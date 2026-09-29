package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.port.GattDriverEvent
import kr.co.nucode.ble.internal.port.TargetResolution
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor
import kr.co.nucode.ble.CharacteristicRef

/**
 * 테스트 전용 무동작 [GattDriver] 기반 클래스.
 * `connect`는 null, Boolean 반환 메서드는 true, Unit 메서드는 아무것도 하지 않는다. 예외로
 * `readCharacteristic`은 override 하지 않아 인터페이스 기본값(false)을 그대로 쓴다.
 * [setEventSink]로 받은 sink를 보관하고 [emit]으로 이벤트를 흘려보낸다.
 * 가짜 driver는 이 클래스를 상속해 기본값과 다른 메서드만 override 한다.
 */
internal open class NoopGattDriver : GattDriver {
    private var sink: (GattDriverEvent) -> Unit = {}

    override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
        this.sink = sink
    }

    /** 보관한 sink로 [event]를 전달한다. */
    fun emit(event: GattDriverEvent) {
        sink(event)
    }

    override fun connect(deviceId: PeripheralId, autoConnect: Boolean): GattHandle? = null

    override fun connect(
        deviceId: PeripheralId,
        autoConnect: Boolean,
        connectionGeneration: Long,
    ): GattHandle? = connect(deviceId, autoConnect)

    override fun disconnect(deviceId: PeripheralId, gatt: GattHandle?) = Unit

    override fun close(deviceId: PeripheralId, gatt: GattHandle?) = Unit

    override fun discoverServices(deviceId: PeripheralId, gatt: GattHandle?): Boolean = true

    override fun requestMtu(deviceId: PeripheralId, gatt: GattHandle?, mtu: Int): Boolean = true

    override fun setCharacteristicNotification(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        enabled: Boolean,
    ): Boolean = true

    override fun writeDescriptor(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        descriptor: PlatformDescriptor,
        value: ByteArray,
    ): Boolean = true

    override fun writeCharacteristic(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = true

    /** 테스트 driver 는 handle 을 돌려주지 않는다(connect 는 null). handle 이 없으면 production 과 같이 NotConnected. */
    override fun resolveTarget(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        charRef: CharacteristicRef,
        characteristic: PlatformCharacteristic,
    ): TargetResolution = if (gatt == null) TargetResolution.NotConnected else TargetResolution.NotFound
}
