package kr.co.nucode.ble.internal.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.GattDriver
import kr.co.nucode.ble.internal.GattDriverEvent

/** Production [GattDriver]. Android callback conversion is delegated to [AndroidGattCallbackRouter]. */
@SuppressLint("MissingPermission")
internal class AndroidGattDriver(
    private val context: Context?,
    private val adapter: () -> BluetoothAdapter?,
    private val scope: CoroutineScope,
) : GattDriver {
    private val callbackRouter = AndroidGattCallbackRouter(scope)

    override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
        callbackRouter.setEventSink(sink)
    }

    override fun connect(deviceId: DeviceId, autoConnect: Boolean): BluetoothGatt? {
        val failureContext = FailureContext()
        val platformContext = context ?: throw BleException(
            FailureMapper.fromAdapterState(AdapterState.Unsupported).copy(context = failureContext),
        )
        val adapterInstance = adapter() ?: throw BleException(
            FailureMapper.fromAdapterState(AdapterState.PoweredOff).copy(context = failureContext),
        )
        val device = try {
            adapterInstance.getRemoteDevice(deviceId.value)
        } catch (e: SecurityException) {
            throw BleException(
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Connect,
                    operation = AndroidApiOperation.ConnectGatt,
                    context = failureContext,
                ),
            )
        } catch (e: IllegalArgumentException) {
            throw BleException(
                BleFailure(
                    category = FailureCategory("validation"),
                    code = FailureCode("UNKNOWN_DEVICE"),
                    phase = FailurePhase.Validation,
                    retryability = Retryability.Never,
                    context = failureContext,
                ),
            )
        }
        return try {
            device.connectGatt(platformContext, autoConnect, callbackRouter.callback)
        } catch (e: SecurityException) {
            throw BleException(
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Connect,
                    operation = AndroidApiOperation.ConnectGatt,
                    context = failureContext,
                ),
            )
        } ?: throw BleException(
            FailureMapper.gattStartRejected(
                phase = FailurePhase.Connect,
                operation = AndroidApiOperation.ConnectGatt,
                context = failureContext,
            ),
        )
    }

    override fun disconnect(deviceId: DeviceId, gatt: BluetoothGatt?) {
        gatt?.disconnect()
    }

    override fun close(deviceId: DeviceId, gatt: BluetoothGatt?) {
        gatt?.close()
    }

    override fun discoverServices(deviceId: DeviceId, gatt: BluetoothGatt?): Boolean =
        startOrPermissionDenied(
            phase = FailurePhase.DiscoverServices,
            operation = AndroidApiOperation.DiscoverServices,
        ) { gatt?.discoverServices() ?: false }

    override fun requestMtu(deviceId: DeviceId, gatt: BluetoothGatt?, mtu: Int): Boolean =
        startOrPermissionDenied(
            phase = FailurePhase.Configure,
            operation = AndroidApiOperation.RequestMtu,
        ) { gatt?.requestMtu(mtu) ?: false }

    override fun setCharacteristicNotification(
        deviceId: DeviceId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean = startOrPermissionDenied(
        phase = FailurePhase.Operation,
        operation = AndroidApiOperation.SetCharacteristicNotification,
    ) { gatt?.setCharacteristicNotification(characteristic, enabled) ?: false }

    override fun writeDescriptor(
        deviceId: DeviceId,
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        val currentGatt = gatt ?: return false
        return startOrPermissionDenied(
            phase = FailurePhase.Operation,
            operation = AndroidApiOperation.WriteDescriptor,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    currentGatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    override fun readCharacteristic(
        deviceId: DeviceId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean = startOrPermissionDenied(
        phase = FailurePhase.Operation,
        operation = AndroidApiOperation.ReadCharacteristic,
    ) { gatt?.readCharacteristic(characteristic) ?: false }

    override fun writeCharacteristic(
        deviceId: DeviceId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        val currentGatt = gatt ?: return false
        return startOrPermissionDenied(
            phase = FailurePhase.Operation,
            operation = AndroidApiOperation.WriteCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = value
                    currentGatt.writeCharacteristic(characteristic)
                }
            }
        }
    }

    private inline fun startOrPermissionDenied(
        phase: FailurePhase,
        operation: AndroidApiOperation,
        start: () -> Boolean,
    ): Boolean = try {
        start()
    } catch (_: SecurityException) {
        throw BleException(FailureMapper.permissionDenied(phase, operation))
    }

}
