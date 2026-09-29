package kr.co.nucode.ble.internal.gatt

/** 러너 소스(ContractScenarioTest)가 옛 위치 `internal.gatt` 에서 import 하므로 포트로 옮긴 두 스냅샷 이름을 별칭으로 남긴다. */
internal typealias GattServiceSnapshot = kr.co.nucode.ble.internal.port.GattServiceSnapshot

internal typealias GattCharacteristicSnapshot = kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot

/** GATT status 값. Android `BluetoothGatt.GATT_*` 와 같은 값이다. */
internal object GattStatus {
    const val SUCCESS = 0
}

/** 링크 상태 값. Android `BluetoothProfile.STATE_*` 와 같은 값이다. */
internal object LinkState {
    const val DISCONNECTED = 0
    const val CONNECTING = 1
    const val CONNECTED = 2
    const val DISCONNECTING = 3
}

/** characteristic write 타입 값. Android `BluetoothGattCharacteristic.WRITE_TYPE_*` 와 같은 값이다. */
internal object GattWriteTypes {
    const val NO_RESPONSE = 1
    const val DEFAULT = 2
    const val SIGNED = 4
}

/** characteristic property 비트. Android `BluetoothGattCharacteristic.PROPERTY_*` 와 같은 값이다. */
internal object GattProperties {
    const val READ = 0x02
    const val WRITE_NO_RESPONSE = 0x04
    const val WRITE = 0x08
    const val NOTIFY = 0x10
    const val INDICATE = 0x20
}
