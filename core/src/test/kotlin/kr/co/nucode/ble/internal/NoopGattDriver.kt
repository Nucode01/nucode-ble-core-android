package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattDriverEvent

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

    override fun connect(deviceId: PeripheralId, autoConnect: Boolean): BluetoothGatt? = null

    override fun connect(
        deviceId: PeripheralId,
        autoConnect: Boolean,
        connectionGeneration: Long,
    ): BluetoothGatt? = connect(deviceId, autoConnect)

    override fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

    override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

    override fun discoverServices(deviceId: PeripheralId, gatt: BluetoothGatt?): Boolean = true

    override fun requestMtu(deviceId: PeripheralId, gatt: BluetoothGatt?, mtu: Int): Boolean = true

    override fun setCharacteristicNotification(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean = true

    override fun writeDescriptor(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = true

    override fun writeCharacteristic(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = true
}
