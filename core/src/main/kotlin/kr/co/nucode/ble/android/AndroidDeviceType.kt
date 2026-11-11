package kr.co.nucode.ble.android

import android.bluetooth.BluetoothDevice
import kr.co.nucode.ble.ScanResult

/** Android only: the device type Android reports for a scanned device (`BluetoothDevice.getType()`). */
public enum class AndroidDeviceType {
    Classic,
    Le,
    Dual,
    Unknown,
}

/**
 * Android only: the device type of this scan result, read once when the result arrived.
 *
 * - The value comes from Android's cache of the device, so the same device can be [AndroidDeviceType.Unknown]
 *   at first and a known type later.
 * - [AndroidDeviceType.Unknown] when the platform could not say, when the app lacks `BLUETOOTH_CONNECT` on
 *   Android 12+, or when the result was not built from an Android scan (public constructor, iOS).
 * - Equality of [ScanResult] includes it.
 */
public val ScanResult.androidDeviceType: AndroidDeviceType
    get() = when (advertisement.platformDeviceType) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> AndroidDeviceType.Classic
        BluetoothDevice.DEVICE_TYPE_LE -> AndroidDeviceType.Le
        BluetoothDevice.DEVICE_TYPE_DUAL -> AndroidDeviceType.Dual
        else -> AndroidDeviceType.Unknown
    }
