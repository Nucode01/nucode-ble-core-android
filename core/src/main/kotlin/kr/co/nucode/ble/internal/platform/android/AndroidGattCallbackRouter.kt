package kr.co.nucode.ble.internal.platform.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import kr.co.nucode.ble.internal.gatt.toIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity

internal fun characteristicChangedEvent(
    deviceId: PeripheralId,
    source: GattCallbackSource,
    characteristic: GattCharacteristicSnapshot?,
    value: ByteArray,
): GattDriverEvent.CharacteristicChanged = GattDriverEvent.CharacteristicChanged(
    deviceId,
    source,
    characteristic,
    value.copyOf(),
)

internal fun characteristicReadEvent(
    deviceId: PeripheralId,
    source: GattCallbackSource,
    characteristic: GattCharacteristicSnapshot?,
    value: ByteArray,
    status: Int,
): GattDriverEvent.CharacteristicRead = GattDriverEvent.CharacteristicRead(
    deviceId,
    source,
    characteristic,
    value.copyOf(),
    status,
)

internal fun characteristicWriteEvent(
    deviceId: PeripheralId,
    source: GattCallbackSource,
    status: Int,
    characteristic: GattCharacteristicSnapshot?,
): GattDriverEvent.CharacteristicWrite = GattDriverEvent.CharacteristicWrite(
    deviceId,
    source,
    status,
    characteristic?.toIdentity(),
)

internal fun descriptorWriteEvent(
    deviceId: PeripheralId,
    source: GattCallbackSource,
    status: Int,
    characteristic: GattCharacteristicSnapshot?,
    descriptorUuid: java.util.UUID,
): GattDriverEvent.DescriptorWrite = GattDriverEvent.DescriptorWrite(
    deviceId,
    source,
    status,
    characteristic?.toIdentity()?.let { GattDescriptorIdentity(it, descriptorUuid) },
)

/** Converts Android callbacks to driver facts and serializes delivery onto the core scope. */
internal class AndroidGattCallbackRouter(
    private val scope: CoroutineScope,
) {
    private var eventSink: (GattDriverEvent) -> Unit = {}

    internal fun setEventSink(sink: (GattDriverEvent) -> Unit) {
        eventSink = sink
    }

    /** Shared scope hop used by every callback and kept internal for deterministic tests. */
    internal fun forward(event: GattDriverEvent) {
        scope.launch { eventSink(event) }
    }

    internal val callback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            forward(GattDriverEvent.ConnectionStateChanged(deviceIdOf(gatt), sourceOf(gatt), status, newState))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            forward(
                GattDriverEvent.ServicesDiscovered(
                    deviceId = deviceIdOf(gatt),
                    source = sourceOf(gatt),
                    status = status,
                    services = snapshotServices(gatt.services),
                ),
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            forward(GattDriverEvent.MtuChanged(deviceIdOf(gatt), sourceOf(gatt), mtu, status))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                forward(
                    characteristicChangedEvent(
                        deviceIdOf(gatt), sourceOf(gatt), snapshotCharacteristic(gatt, characteristic),
                        characteristic.value ?: ByteArray(0),
                    ),
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            forward(
                characteristicChangedEvent(
                    deviceIdOf(gatt), sourceOf(gatt), snapshotCharacteristic(gatt, characteristic), value,
                ),
            )
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                forward(
                    characteristicReadEvent(
                        deviceIdOf(gatt), sourceOf(gatt), snapshotCharacteristic(gatt, characteristic),
                        characteristic.value ?: ByteArray(0), status,
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
                characteristicReadEvent(
                    deviceIdOf(gatt), sourceOf(gatt), snapshotCharacteristic(gatt, characteristic), value, status,
                ),
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            forward(
                characteristicWriteEvent(
                    deviceIdOf(gatt), sourceOf(gatt), status,
                    snapshotCharacteristic(gatt, characteristic),
                ),
            )
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            forward(
                descriptorWriteEvent(
                    deviceIdOf(gatt), sourceOf(gatt), status,
                    snapshotCharacteristic(gatt, descriptor.characteristic), descriptor.uuid,
                ),
            )
        }
    }

    private fun deviceIdOf(gatt: BluetoothGatt): PeripheralId = PeripheralId(gatt.device.address)

    private fun sourceOf(gatt: BluetoothGatt): GattCallbackSource =
        GattCallbackSource { currentGatt -> currentGatt.bluetoothGatt === gatt }

    private fun snapshotServices(services: List<BluetoothGattService>): List<GattServiceSnapshot> {
        val ordinals = mutableMapOf<java.util.UUID, Int>()
        return services.map { service ->
            val instanceIndex = ordinals.getOrDefault(service.uuid, 0)
            ordinals[service.uuid] = instanceIndex + 1
            snapshotService(service, instanceIndex)
        }
    }

    private fun snapshotService(service: BluetoothGattService, serviceInstanceIndex: Int): GattServiceSnapshot =
        GattServiceSnapshot(
            serviceUuid = service.uuid,
            characteristics = service.characteristics.map { characteristic ->
                snapshotCharacteristic(service, characteristic, serviceInstanceIndex)
            },
            instanceIndex = serviceInstanceIndex,
        )

    private fun snapshotCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): GattCharacteristicSnapshot? {
        val service = characteristic.service ?: return null
        val serviceInstanceIndex = identityIndexOrNull(gatt.services.filter { it.uuid == service.uuid }, service)
            ?: return null
        val characteristicInstanceIndex = identityIndexOrNull(
            service.characteristics.filter { it.uuid == characteristic.uuid }, characteristic,
        ) ?: return null
        return snapshotCharacteristic(service, characteristic, serviceInstanceIndex, characteristicInstanceIndex)
    }

    private fun snapshotCharacteristic(
        service: BluetoothGattService,
        characteristic: BluetoothGattCharacteristic,
        serviceInstanceIndex: Int,
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
            platformCharacteristic = AndroidCharacteristic(characteristic),
            serviceInstanceIndex = serviceInstanceIndex,
        )
    }
}

/** Only an object still present in the platform collection owns an instance ordinal. */
internal fun <T : Any> identityIndexOrNull(instances: List<T>, target: T): Int? =
    instances.indexOfFirst { it === target }.takeIf { it >= 0 }
