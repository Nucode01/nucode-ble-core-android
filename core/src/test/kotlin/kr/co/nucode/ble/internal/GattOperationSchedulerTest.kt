package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.gatt.GattDescriptorIdentity
import kr.co.nucode.ble.internal.gatt.GattOperation
import kr.co.nucode.ble.internal.gatt.GattOperationKind
import kr.co.nucode.ble.internal.gatt.GattOperationScheduler
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationSchedulerTest {
    @Test
    fun `FIFO head 하나만 pending이 되고 완료 뒤 다음 항목이 시작된다`() {
        val scheduler = GattOperationScheduler()
        val generation = scheduler.beginNextGeneration()
        val characteristic = characteristicIdentity()
        val descriptor = GattDescriptorIdentity(characteristic, UUID.randomUUID())
        val first = scheduler.enqueue(GattOperation.Read(generation, characteristic))
        val second = scheduler.enqueue(
            GattOperation.WriteWithResponse(generation, characteristic, byteArrayOf(1)),
        )
        val third = scheduler.enqueue(GattOperation.Subscribe(generation, descriptor))

        assertSame(first, scheduler.startNext())
        assertNull(scheduler.startNext())
        assertEquals(2, scheduler.queuedCount)

        assertSame(
            first,
            scheduler.takePendingIfMatches(generation, GattOperationKind.Read, characteristic),
        )
        assertSame(second, scheduler.startNext())
        assertSame(
            second,
            scheduler.takePendingIfMatches(
                generation,
                GattOperationKind.WriteWithResponse,
                characteristic,
            ),
        )
        assertSame(third, scheduler.startNext())
        assertTrue(first.sequence < second.sequence)
        assertTrue(second.sequence < third.sequence)
    }

    @Test
    fun `wrong generation kind target callback은 pending을 완료하지 않는다`() {
        val scheduler = GattOperationScheduler()
        val generation = scheduler.beginNextGeneration()
        val target = characteristicIdentity()
        val otherTarget = target.copy(characteristicInstanceIndex = target.characteristicInstanceIndex + 1)
        val pending = scheduler.enqueue(GattOperation.Read(generation, target))
        scheduler.startNext()

        assertNull(
            scheduler.takePendingIfMatches(generation - 1, GattOperationKind.Read, target),
        )
        assertSame(pending, scheduler.pending)
        assertFalse(pending.completion.isCompleted)

        assertNull(
            scheduler.takePendingIfMatches(
                generation,
                GattOperationKind.WriteWithResponse,
                target,
            ),
        )
        assertSame(pending, scheduler.pending)
        assertFalse(pending.completion.isCompleted)

        assertNull(
            scheduler.takePendingIfMatches(generation, GattOperationKind.Read, otherTarget),
        )
        assertSame(pending, scheduler.pending)
        assertFalse(pending.completion.isCompleted)

        assertSame(
            pending,
            scheduler.takePendingIfMatches(generation, GattOperationKind.Read, target),
        )
        assertNull(scheduler.pending)
    }

    @Test
    fun `poison은 pending과 queue를 회수하고 late callback을 거부한다`() {
        val scheduler = GattOperationScheduler()
        val generation = scheduler.beginNextGeneration()
        val target = characteristicIdentity()
        val pending = scheduler.enqueue(GattOperation.Read(generation, target))
        val queued = scheduler.enqueue(
            GattOperation.WriteWithoutResponse(generation, target, byteArrayOf(2)),
        )
        scheduler.startNext()

        val drained = scheduler.poisonCurrentGeneration()

        assertTrue(scheduler.isPoisoned)
        assertSame(pending, drained.pending)
        assertEquals(listOf(queued), drained.queued)
        assertNull(scheduler.pending)
        assertEquals(0, scheduler.queuedCount)
        assertNull(
            scheduler.takePendingIfMatches(generation, GattOperationKind.Read, target),
        )

        val nextGeneration = scheduler.beginNextGeneration()
        assertEquals(generation + 1, nextGeneration)
        assertFalse(scheduler.isPoisoned)
    }

    @Test
    fun `queued cancellation은 해당 항목만 제거하고 FIFO 순서를 보존한다`() {
        val scheduler = GattOperationScheduler()
        val generation = scheduler.beginNextGeneration()
        val target = characteristicIdentity()
        val first = scheduler.enqueue(GattOperation.Read(generation, target))
        val cancelled = scheduler.enqueue(GattOperation.Read(generation, target))
        val third = scheduler.enqueue(GattOperation.Read(generation, target))

        assertTrue(scheduler.removeQueued(cancelled))
        assertFalse(scheduler.removeQueued(cancelled))
        assertSame(first, scheduler.startNext())
        scheduler.takePendingIfMatches(generation, GattOperationKind.Read, target)
        assertSame(third, scheduler.startNext())
    }

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
        val first = Session(PeripheralId("A"))
        val second = Session(PeripheralId("B"))

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
