package kr.co.nucode.ble.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

/** 현재 platform collection에 실제로 남아 있는 동일 객체만 instance index를 갖는다. */
internal fun <T : Any> identityIndexOrNull(instances: List<T>, target: T): Int? =
    instances.indexOfFirst { it === target }.takeIf { it >= 0 }

/** Production [GattDriver]. Android callbacks are serialized onto the existing core scope. */
@SuppressLint("MissingPermission")
internal class AndroidGattDriver(
    private val context: Context?,
    private val adapter: () -> BluetoothAdapter?,
    private val scope: CoroutineScope,
) : GattDriver {
    private var eventSink: (GattDriverEvent) -> Unit = {}

    override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
        eventSink = sink
    }

    /** Shared by every platform callback, and kept internal so the scope hop is unit-testable. */
    internal fun forward(event: GattDriverEvent) {
        scope.launch { eventSink(event) }
    }

    private val callback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            forward(
                GattDriverEvent.ConnectionStateChanged(
                    deviceId = deviceIdOf(gatt),
                    source = sourceOf(gatt),
                    status = status,
                    newState = newState,
                ),
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                eventSink(
                    GattDriverEvent.ServicesDiscovered(
                        deviceId = deviceIdOf(gatt),
                        source = sourceOf(gatt),
                        status = status,
                        services = snapshotServices(gatt.services),
                    ),
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            forward(GattDriverEvent.MtuChanged(deviceIdOf(gatt), sourceOf(gatt), mtu, status))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: ByteArray(0)
                scope.launch {
                    eventSink(
                        GattDriverEvent.CharacteristicChanged(
                            deviceIdOf(gatt),
                            sourceOf(gatt),
                            snapshotCharacteristic(gatt, characteristic),
                            value,
                        ),
                    )
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            scope.launch {
                eventSink(
                    GattDriverEvent.CharacteristicChanged(
                        deviceIdOf(gatt),
                        sourceOf(gatt),
                        snapshotCharacteristic(gatt, characteristic),
                        value,
                    ),
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                forward(
                    GattDriverEvent.CharacteristicRead(
                        deviceId = deviceIdOf(gatt),
                        source = sourceOf(gatt),
                        characteristic = snapshotCharacteristic(gatt, characteristic),
                        value = characteristic.value ?: ByteArray(0),
                        status = status,
                    ),
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            forward(
                GattDriverEvent.CharacteristicRead(
                    deviceId = deviceIdOf(gatt),
                    source = sourceOf(gatt),
                    characteristic = snapshotCharacteristic(gatt, characteristic),
                    value = value,
                    status = status,
                ),
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            forward(
                GattDriverEvent.CharacteristicWrite(
                    deviceId = deviceIdOf(gatt),
                    source = sourceOf(gatt),
                    status = status,
                    attributeIdentity = snapshotCharacteristic(gatt, characteristic)?.toIdentity(),
                ),
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val characteristicIdentity =
                snapshotCharacteristic(gatt, descriptor.characteristic)?.toIdentity()
            forward(
                GattDriverEvent.DescriptorWrite(
                    deviceId = deviceIdOf(gatt),
                    source = sourceOf(gatt),
                    status = status,
                    attributeIdentity = characteristicIdentity?.let {
                        GattDescriptorIdentity(it, descriptor.uuid)
                    },
                ),
            )
        }
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
            device.connectGatt(platformContext, autoConnect, callback)
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

    private fun deviceIdOf(gatt: BluetoothGatt): DeviceId = DeviceId(gatt.device.address)

    private fun sourceOf(gatt: BluetoothGatt): GattCallbackSource =
        GattCallbackSource { currentGatt -> currentGatt === gatt }

    private fun snapshotServices(services: List<BluetoothGattService>): List<GattServiceSnapshot> {
        val ordinals = mutableMapOf<java.util.UUID, Int>()
        return services.map { service ->
            val instanceIndex = ordinals.getOrDefault(service.uuid, 0)
            ordinals[service.uuid] = instanceIndex + 1
            snapshotService(service, instanceIndex)
        }
    }

    private fun snapshotService(
        service: BluetoothGattService,
        serviceInstanceIndex: Int,
    ): GattServiceSnapshot =
        GattServiceSnapshot(
            serviceUuid = service.uuid,
            characteristics = service.characteristics.map { characteristic ->
                snapshotCharacteristic(service, characteristic, serviceInstanceIndex)
            },
            instanceIndex = serviceInstanceIndex,
        )

    private fun snapshotCharacteristic(
        characteristic: BluetoothGattCharacteristic,
    ): GattCharacteristicSnapshot? {
        val service = characteristic.service ?: return null
        return snapshotCharacteristic(service, characteristic)
    }

    private fun snapshotCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): GattCharacteristicSnapshot? {
        val service = characteristic.service ?: return null
        val serviceInstanceIndex = identityIndexOrNull(
            gatt.services.filter { it.uuid == service.uuid },
            service,
        ) ?: return null
        val characteristicInstanceIndex = identityIndexOrNull(
            service.characteristics.filter { it.uuid == characteristic.uuid },
            characteristic,
        ) ?: return null
        return snapshotCharacteristic(
            service = service,
            characteristic = characteristic,
            serviceInstanceIndex = serviceInstanceIndex,
            characteristicInstanceIndex = characteristicInstanceIndex,
        )
    }

    private fun snapshotCharacteristic(
        service: BluetoothGattService,
        characteristic: BluetoothGattCharacteristic,
        serviceInstanceIndex: Int = 0,
        characteristicInstanceIndex: Int? = null,
    ): GattCharacteristicSnapshot {
        val instanceIndex = characteristicInstanceIndex ?: service.characteristics
            .filter { it.uuid == characteristic.uuid }
            .indexOfFirst { it === characteristic }
            .coerceAtLeast(0)
        return GattCharacteristicSnapshot(
            serviceUuid = service.uuid,
            charUuid = characteristic.uuid,
            instanceIndex = instanceIndex,
            propertyMask = characteristic.properties,
            platformCharacteristic = characteristic,
            serviceInstanceIndex = serviceInstanceIndex,
        )
    }
}
