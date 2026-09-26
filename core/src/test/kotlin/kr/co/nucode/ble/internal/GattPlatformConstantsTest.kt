package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import kr.co.nucode.ble.internal.gatt.GattProperties
import kr.co.nucode.ble.internal.gatt.GattStatus
import kr.co.nucode.ble.internal.gatt.GattWriteTypes
import kr.co.nucode.ble.internal.gatt.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 코어의 플랫폼 중립 상수가 Android 상수와 같은 값인지 고정한다. */
class GattPlatformConstantsTest {
    @Test
    fun `core GATT constants equal Android values`() {
        assertEquals(BluetoothGatt.GATT_SUCCESS, GattStatus.SUCCESS)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, LinkState.DISCONNECTED)
        assertEquals(BluetoothProfile.STATE_CONNECTING, LinkState.CONNECTING)
        assertEquals(BluetoothProfile.STATE_CONNECTED, LinkState.CONNECTED)
        assertEquals(BluetoothProfile.STATE_DISCONNECTING, LinkState.DISCONNECTING)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, GattWriteTypes.NO_RESPONSE)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, GattWriteTypes.DEFAULT)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_SIGNED, GattWriteTypes.SIGNED)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_READ, GattProperties.READ)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, GattProperties.WRITE_NO_RESPONSE)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE, GattProperties.WRITE)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_NOTIFY, GattProperties.NOTIFY)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_INDICATE, GattProperties.INDICATE)
    }
}
