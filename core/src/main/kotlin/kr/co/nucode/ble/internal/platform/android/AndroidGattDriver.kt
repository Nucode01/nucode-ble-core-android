package kr.co.nucode.ble.internal.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.TargetResolution
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor

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

    override fun connect(deviceId: PeripheralId, autoConnect: Boolean): GattHandle? {
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
        val gatt = try {
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
        return AndroidGattHandle(gatt)
    }

    override fun disconnect(deviceId: PeripheralId, gatt: GattHandle?) {
        gatt.bluetoothGatt?.disconnect()
    }

    override fun close(deviceId: PeripheralId, gatt: GattHandle?) {
        gatt.bluetoothGatt?.close()
    }

    override fun discoverServices(deviceId: PeripheralId, gatt: GattHandle?): Boolean =
        startOrPermissionDenied(
            phase = FailurePhase.DiscoverServices,
            operation = AndroidApiOperation.DiscoverServices,
        ) { gatt.bluetoothGatt?.discoverServices() ?: false }

    override fun requestMtu(deviceId: PeripheralId, gatt: GattHandle?, mtu: Int): Boolean =
        startOrPermissionDenied(
            phase = FailurePhase.Configure,
            operation = AndroidApiOperation.RequestMtu,
        ) { gatt.bluetoothGatt?.requestMtu(mtu) ?: false }

    override fun setCharacteristicNotification(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        enabled: Boolean,
    ): Boolean = startOrPermissionDenied(
        phase = FailurePhase.Operation,
        operation = AndroidApiOperation.SetCharacteristicNotification,
    ) { gatt.bluetoothGatt?.setCharacteristicNotification(characteristic.bluetoothCharacteristic, enabled) ?: false }

    override fun writeDescriptor(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        descriptor: PlatformDescriptor,
        value: ByteArray,
    ): Boolean {
        val currentGatt = gatt.bluetoothGatt ?: return false
        val descriptor = descriptor.bluetoothDescriptor
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
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
    ): Boolean = startOrPermissionDenied(
        phase = FailurePhase.Operation,
        operation = AndroidApiOperation.ReadCharacteristic,
    ) { gatt.bluetoothGatt?.readCharacteristic(characteristic.bluetoothCharacteristic) ?: false }

    override fun writeCharacteristic(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        val currentGatt = gatt.bluetoothGatt ?: return false
        val characteristic = characteristic.bluetoothCharacteristic
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

    override fun resolveTarget(
        deviceId: PeripheralId,
        gatt: GattHandle?,
        charRef: CharacteristicRef,
        characteristic: PlatformCharacteristic,
    ): TargetResolution {
        val currentGatt = gatt.bluetoothGatt ?: return TargetResolution.NotConnected
        val platformCharacteristic = characteristic.bluetoothCharacteristic
        val matchingServices = currentGatt.services.filter { it.uuid == charRef.service.uuid.value }
        val service = matchingServices.getOrNull(charRef.service.instanceIndex)
            ?: return TargetResolution.NotFound
        val matchingCharacteristics = service.characteristics.filter { it.uuid == charRef.uuid.value }
        if (matchingCharacteristics.getOrNull(charRef.instanceIndex) !== platformCharacteristic) {
            return TargetResolution.NotFound
        }
        return TargetResolution.Resolved(
            propertyMask = platformCharacteristic.properties,
            cccdDescriptor = platformCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.let(::AndroidDescriptor),
        )
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

    private companion object {
        /** CCCD(00002902-...) 표준 UUID. BLE 스펙 고정값 — 기기마다 다르지 않다. */
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
