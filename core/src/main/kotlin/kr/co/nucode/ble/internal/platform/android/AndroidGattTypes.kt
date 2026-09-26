package kr.co.nucode.ble.internal.platform.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor

// 동일성 규칙: wrapper 는 equals 를 재정의하지 않는다. 동일성이 필요한 비교(콜백 출처, 서비스 그래프 안의
// characteristic)는 전부 감싼 Android 객체를 꺼내 `===` 로 한다.

/** [BluetoothGatt] 를 감싼 불투명 handle. */
internal class AndroidGattHandle(val gatt: BluetoothGatt) : GattHandle

/** [BluetoothGattCharacteristic] 를 감싼 불투명 characteristic. */
internal class AndroidCharacteristic(val characteristic: BluetoothGattCharacteristic) : PlatformCharacteristic

/** [BluetoothGattDescriptor] 를 감싼 불투명 descriptor. */
internal class AndroidDescriptor(val descriptor: BluetoothGattDescriptor) : PlatformDescriptor

internal val GattHandle?.bluetoothGatt: BluetoothGatt?
    get() = (this as? AndroidGattHandle)?.gatt

internal val PlatformCharacteristic.bluetoothCharacteristic: BluetoothGattCharacteristic
    get() = (this as AndroidCharacteristic).characteristic

internal val PlatformDescriptor.bluetoothDescriptor: BluetoothGattDescriptor
    get() = (this as AndroidDescriptor).descriptor
