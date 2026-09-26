package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.internal.platform.android.isSameCharacteristicAtRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacteristicRefSelectionTest {
    private data class FakeChar(val uuid: UUID, val tag: String = "")
    private class FakeService(val uuid: UUID, val chars: List<FakeChar>)

    private val serviceUuid = UUID.randomUUID()
    private val charUuid = UUID.randomUUID()

    private fun ref(serviceIndex: Int = 0, charIndex: Int = 0) = CharacteristicRef(
        service = ServiceRef(BleUuid(serviceUuid), instanceIndex = serviceIndex),
        uuid = BleUuid(charUuid),
        instanceIndex = charIndex,
    )

    private fun select(services: List<FakeService>, ref: CharacteristicRef, expected: FakeChar) =
        isSameCharacteristicAtRef(
            services = services,
            serviceUuid = { it.uuid },
            characteristics = { it.chars },
            characteristicUuid = { it.uuid },
            charRef = ref,
            expected = expected,
        )

    @Test
    fun `위치에 같은 객체가 있으면 일치`() {
        val target = FakeChar(charUuid)
        assertTrue(select(listOf(FakeService(serviceUuid, listOf(target))), ref(), target))
    }

    @Test
    fun `equals 가 같아도 다른 객체면 불일치`() {
        val inGatt = FakeChar(charUuid)
        val other = FakeChar(charUuid)
        assertFalse(select(listOf(FakeService(serviceUuid, listOf(inGatt))), ref(), other))
    }

    @Test
    fun `서비스 instanceIndex 가 범위 밖이면 불일치`() {
        val target = FakeChar(charUuid)
        assertFalse(select(listOf(FakeService(serviceUuid, listOf(target))), ref(serviceIndex = 1), target))
    }

    @Test
    fun `characteristic instanceIndex 가 범위 밖이면 불일치`() {
        val target = FakeChar(charUuid)
        assertFalse(select(listOf(FakeService(serviceUuid, listOf(target))), ref(charIndex = 1), target))
    }

    @Test
    fun `중복 서비스 UUID 의 두 번째 인스턴스에서 선택한다`() {
        val first = FakeChar(charUuid, "a")
        val second = FakeChar(charUuid, "b")
        val services = listOf(
            FakeService(serviceUuid, listOf(first)),
            FakeService(UUID.randomUUID(), listOf(FakeChar(charUuid))),
            FakeService(serviceUuid, listOf(second)),
        )
        assertTrue(select(services, ref(serviceIndex = 1), second))
        assertFalse(select(services, ref(serviceIndex = 1), first))
        assertTrue(select(services, ref(serviceIndex = 0), first))
    }

    @Test
    fun `같은 서비스 안에서 characteristic UUID 로 거른 뒤 instanceIndex 로 선택한다`() {
        val otherUuid = FakeChar(UUID.randomUUID(), "other")
        val target = FakeChar(charUuid, "target")
        val services = listOf(FakeService(serviceUuid, listOf(otherUuid, target)))
        assertTrue(select(services, ref(charIndex = 0), target))
        assertFalse(select(services, ref(charIndex = 0), otherUuid))
    }
}
