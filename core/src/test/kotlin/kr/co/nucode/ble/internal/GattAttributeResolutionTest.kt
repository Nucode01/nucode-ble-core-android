package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ServiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GattAttributeResolutionTest {
    @Test
    fun `common characteristic reference retains both duplicate ordinals in legacy identity`() {
        val serviceUuid = UUID.randomUUID()
        val characteristicUuid = UUID.randomUUID()
        val reference = CharacteristicRef(
            service = ServiceRef(BleUuid(serviceUuid), instanceIndex = 2),
            uuid = BleUuid(characteristicUuid),
            instanceIndex = 3,
        )

        val identity = GattCharacteristicIdentity(
            serviceUuid = serviceUuid,
            serviceInstanceIndex = reference.service.instanceIndex,
            characteristicUuid = characteristicUuid,
            characteristicInstanceIndex = reference.instanceIndex,
        )

        assertEquals(2, identity.serviceInstanceIndex)
        assertEquals(3, identity.characteristicInstanceIndex)
        assertEquals(2, reference.service.instanceIndex)
        assertEquals(3, reference.instanceIndex)
    }

    @Test
    fun `same UUID attributes in distinct duplicate services have distinct resolution identities`() {
        val serviceUuid = UUID.randomUUID()
        val characteristicUuid = UUID.randomUUID()
        val descriptorUuid = UUID.randomUUID()
        val first = GattCharacteristicIdentity(serviceUuid, 0, characteristicUuid, 0)
        val second = GattCharacteristicIdentity(serviceUuid, 1, characteristicUuid, 0)

        assertNotEquals(first, second)
        assertNotEquals(GattDescriptorIdentity(first, descriptorUuid), GattDescriptorIdentity(second, descriptorUuid))
    }
}
