package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.session.GattOperationScheduler
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

/** 큐 차례 규칙은 GattQueueCoreTest 가 본다. 여기서는 셸이 드는 값(handle 의 operationId, identity, chunk)과 소유를 본다. */
class GattOperationSchedulerTest {
    @Test
    fun `admission snapshot은 generation과 sequence에 고정되고 opaque하다`() {
        val scheduler = GattOperationScheduler()
        val generation = scheduler.beginNextGeneration()
        val first = scheduler.enqueue(GattOperation.Read(generation, characteristicIdentity()))
        val second = scheduler.enqueue(GattOperation.Read(generation, characteristicIdentity()))

        assertEquals("gatt-$generation-0", first.operationId)
        assertEquals("gatt-$generation-1", second.operationId)
        assertFalse(first.operationId.contains("0000180f"))
    }

    @Test
    fun `attribute identity는 service와 characteristic instance 및 descriptor를 구분한다`() {
        val base = characteristicIdentity()

        assertFalse(base == base.copy(serviceInstanceIndex = base.serviceInstanceIndex + 1))
        assertFalse(
            base == base.copy(characteristicInstanceIndex = base.characteristicInstanceIndex + 1),
        )
        assertFalse(
            GattDescriptorIdentity(base, UUID.fromString(CCCD_UUID)) ==
                GattDescriptorIdentity(base, UUID.randomUUID()),
        )
    }

    @Test
    fun `write operation은 입력과 반환 chunk를 방어 복사한다`() {
        val source = byteArrayOf(1, 2, 3)
        val operation = GattOperation.WriteWithResponse(
            generation = 0,
            attributeIdentity = characteristicIdentity(),
            chunk = source,
        )

        source[0] = 9
        val firstCopy = operation.chunkCopy()
        firstCopy[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), operation.chunkCopy())
    }

    @Test
    fun `scheduler는 Session과 함께 소유되고 Session마다 분리된다`() {
        val first = Session(PeripheralId("A"), TestClock)
        val second = Session(PeripheralId("B"), TestClock)

        assertNotSame(first.gattOperationScheduler, second.gattOperationScheduler)
    }

    private fun characteristicIdentity(): GattCharacteristicIdentity =
        GattCharacteristicIdentity.from(
            charRef = charRef(
                serviceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
                charUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
                instanceIndex = 2,
            ),
            serviceInstanceIndex = 1,
        )

    private companion object {
        const val CCCD_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
