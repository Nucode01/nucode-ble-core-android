package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.model.PlatformDescriptor

/** driver 가 판정한 operation target 해석 결과. */
internal sealed interface TargetResolution {
    /** 현재 handle 의 서비스 그래프에서 같은 객체를 찾았다. */
    data class Resolved(val propertyMask: Int, val cccdDescriptor: PlatformDescriptor?) : TargetResolution

    /** 해석할 handle 이 없다. */
    data object NotConnected : TargetResolution

    /** handle 의 서비스 그래프에 해당 characteristic 이 (같은 객체로) 없다. */
    data object NotFound : TargetResolution
}

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
