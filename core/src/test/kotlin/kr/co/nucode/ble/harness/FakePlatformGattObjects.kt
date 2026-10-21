package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor
import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattServiceSnapshot
import kr.co.nucode.ble.internal.port.PlatformDescriptorEntry

/** 테스트용 불투명 characteristic. equals 는 재정의하지 않는다 — 동일성은 참조 기준이다. */
internal class FakePlatformCharacteristic(val uuid: UUID, val properties: Int = 0) : PlatformCharacteristic

/** 테스트용 불투명 descriptor. equals 는 재정의하지 않는다 — 동일성은 참조 기준이다. */
internal class FakePlatformDescriptor(val uuid: UUID) : PlatformDescriptor

/**
 * [FakeAndroidPlatform] 의 descriptor 표 — 만든 가짜 descriptor 객체 → identity. JVM 스텁 객체는 uuid·순번을 못 돌려주므로
 * 만든 객체를 기억해 콜백 identity 를 채운다(production router 가 객체에서 읽는 자리).
 */
internal class FakeDescriptorTable {
    private val identities = mutableMapOf<PlatformDescriptor, GattDescriptorIdentity>()

    fun identityOf(descriptor: PlatformDescriptor): GattDescriptorIdentity? = identities[descriptor]

    fun register(identity: GattDescriptorIdentity): PlatformDescriptor =
        FakePlatformDescriptor(identity.descriptorUuid).also { identities[it] = identity }

    /** 탐색 스냅샷에서 [ref] 의 descriptor 를 탐색 순서대로 가짜 객체로 만든다(스냅샷에 없으면 빈 목록). */
    fun entries(ref: CharacteristicRef, services: List<GattServiceSnapshot>): List<PlatformDescriptorEntry> {
        val snapshot = services.filter { it.serviceUuid == ref.service.uuid.value }.getOrNull(ref.service.instanceIndex)
            ?.characteristics?.filter { it.charUuid == ref.uuid.value }?.getOrNull(ref.instanceIndex)
            ?: return emptyList()
        val ordinals = mutableMapOf<UUID, Int>()
        return snapshot.descriptorUuids.map { uuid ->
            val index = ordinals.getOrDefault(uuid, 0)
            ordinals[uuid] = index + 1
            PlatformDescriptorEntry(uuid, register(GattDescriptorIdentity(GattCharacteristicIdentity.from(ref), uuid, index)))
        }
    }
}

/** GAP 서비스(0x1800) + Device Name(0x2A00, read) 스냅샷 — 실기기에서 항상 보이는 최소 토폴로지. */
internal fun gapServiceSnapshot(): GattServiceSnapshot {
    val gap = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    return GattServiceSnapshot(
        serviceUuid = gap,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                serviceUuid = gap,
                charUuid = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                instanceIndex = 0,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
            ),
        ),
    )
}
