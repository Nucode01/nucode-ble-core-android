package kr.co.nucode.ble

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.TestStageAuthority
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** notification 경로가 공용 이벤트 버스와 분리돼 자기 수집자 지연으로만 유실되는지 고정한다(plan J task 1). */
class NotificationRoutingTest {
    private fun ref() = CharacteristicRef(ServiceRef(BleUuid(UUID.randomUUID())), BleUuid(UUID.randomUUID()))

    /** 수집자를 Unconfined + UNDISPATCHED 로 붙인다 — collect 진입이 동기로 일어난다. */
    private fun collect(
        session: BleSession,
        ch: CharacteristicRef,
        onValue: suspend (ByteArray) -> Unit,
    ): Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
        session.notifications(ch).collect { onValue(it) }
    }

    @Test(timeout = 5_000)
    fun `notification survives unrelated event flood while collector is busy`() = runBlocking {
        val core = BleRuntime(config = BleConfig())
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:31")
        val ch = ref()
        val session = client.session(id)
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val job = collect(session, ch) {
            received += it[0].toInt()
            if (received.size == 1) gate.await()
            if (received.size == 2) done.complete(Unit)
        }
        core.emit(BleEvent.CharValue(id, ch, byteArrayOf(1)))
        // 무관한 세션 100개의 연결 이벤트로 공개 이벤트 흐름을 채운다(registry 에 있어야 officialEvents 로 나간다).
        repeat(100) { i ->
            val other = PeripheralId("AA:BB:CC:DD:EF:%02X".format(i))
            core.registry.getOrCreate(other)
            core.emit(BleEvent.ConnStateChanged(other, ConnectionState.Connecting))
        }
        core.emit(BleEvent.CharValue(id, ch, byteArrayOf(2)))
        gate.complete(Unit)
        withTimeoutOrNull(1_000) { done.await() }
        job.cancel()
        client.close()
        assertEquals(listOf(1, 2), received)
    }

    @Test(timeout = 5_000)
    fun `slow collector of another session does not cause loss for a fast collector`() = runBlocking {
        val core = BleRuntime(config = BleConfig())
        val client = DefaultBleClient(core)
        val slowId = PeripheralId("AA:BB:CC:DD:EE:41")
        val fastId = PeripheralId("AA:BB:CC:DD:EE:42")
        val slowCh = ref()
        val fastCh = ref()
        val gate = CompletableDeferred<Unit>()
        val slow = collect(client.session(slowId), slowCh) { gate.await() }
        val fastReceived = mutableListOf<Int>()
        val fast = collect(client.session(fastId), fastCh) { fastReceived += it[0].toInt() }
        repeat(200) { i ->
            core.emit(BleEvent.CharValue(slowId, slowCh, byteArrayOf(i.toByte())))
            core.emit(BleEvent.CharValue(fastId, fastCh, byteArrayOf(i.toByte())))
        }
        gate.complete(Unit)
        slow.cancel()
        fast.cancel()
        client.close()
        assertEquals((0 until 200).map { it.toByte().toInt() }, fastReceived)
    }

    @Test(timeout = 5_000)
    fun `collector started undispatched receives an immediate emit`() = runBlocking {
        val core = BleRuntime(config = BleConfig())
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:51")
        val ch = ref()
        val received = mutableListOf<Int>()
        val job = collect(client.session(id), ch) { received += it[0].toInt() }
        core.emit(BleEvent.CharValue(id, ch, byteArrayOf(7)))
        job.cancel()
        client.close()
        assertEquals(listOf(7), received)
    }

    @Test(timeout = 5_000)
    fun `cancelling collection unregisters the collector`() = runBlocking {
        val core = BleRuntime(config = BleConfig())
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:61")
        val ch = ref()
        val a = collect(client.session(id), ch) { }
        val b = collect(client.session(id), ref()) { }
        assertEquals(2, core.notificationCollectorCount)
        a.cancel()
        b.cancel()
        assertEquals(0, core.notificationCollectorCount)
        client.close()
    }

    @Test(timeout = 5_000)
    fun `blocked collector loses only its own overflow and counts drops`() = runBlocking {
        val capacity = 4
        val core = BleRuntime(config = BleConfig(eventBufferCapacity = capacity))
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:71")
        val ch = ref()
        val session = client.session(id)
        val gate = CompletableDeferred<Unit>()
        val aReceived = mutableListOf<Int>()
        val aDone = CompletableDeferred<Unit>()
        val a = collect(session, ch) {
            aReceived += it[0].toInt()
            if (aReceived.size == 1) gate.await()
            if (aReceived.size == 1 + capacity) aDone.complete(Unit)
        }
        val bReceived = mutableListOf<Int>()
        val b = collect(session, ch) { bReceived += it[0].toInt() }
        val total = 20
        val before = core.droppedEventCount
        repeat(total) { i -> core.emit(BleEvent.CharValue(id, ch, byteArrayOf(i.toByte()))) }
        assertEquals((0 until total).toList(), bReceived)
        // A 는 첫 값 처리 중 → 채널 용량만큼만 버퍼되고 나머지(newest)는 버려진다.
        val expectedDrops = (total - 1 - capacity).toLong()
        assertEquals(expectedDrops, core.droppedEventCount - before)
        gate.complete(Unit)
        withTimeoutOrNull(1_000) { aDone.await() }
        assertEquals((0..capacity).toList(), aReceived)
        a.cancel()
        b.cancel()
        client.close()
    }

    @Test(timeout = 5_000)
    fun `mutating a received array does not affect another collector`() = runBlocking {
        val core = BleRuntime(config = BleConfig())
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:81")
        val ch = ref()
        val session = client.session(id)
        val seen = mutableListOf<ByteArray>()
        val a = collect(session, ch) { it[0] = 99; seen += it }
        val b = collect(session, ch) { seen += it }
        val source = byteArrayOf(1, 2)
        core.emit(BleEvent.CharValue(id, ch, source))
        a.cancel()
        b.cancel()
        client.close()
        assertEquals(2, seen.size)
        assertTrue(seen[0].contentEquals(byteArrayOf(99, 2)))
        assertTrue(seen[1].contentEquals(byteArrayOf(1, 2)))
        assertTrue(source.contentEquals(byteArrayOf(1, 2)))
    }

    @Test(timeout = 5_000)
    fun `notification emitted after close is not delivered`() = runBlocking {
        val core = BleRuntime(config = BleConfig())
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:91")
        val ch = ref()
        val received = mutableListOf<Int>()
        val job = collect(client.session(id), ch) { received += it[0].toInt() }
        client.close()
        core.emit(BleEvent.CharValue(id, ch, byteArrayOf(5)))
        job.cancel()
        assertEquals(emptyList<Int>(), received)
    }

    /**
     * 실제 호출 지점 형태 — driver 콜백 → GattOperationEngine(`toCharacteristicRef`) → emit → hub →
     * notifications. 비영 instance index 로 ref 변환이 소비자가 넘긴 ref 와 같은 키가 되는지 고정한다.
     */
    @Test(timeout = 5_000)
    fun `driver characteristic callback reaches session notifications through the engine`() = runBlocking {
        val driver = SinkCapturingDriver()
        val core = BleRuntime(config = BleConfig(), gattDriver = driver)
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:3A")
        val serviceUuid = UUID.randomUUID()
        val charUuid = UUID.randomUUID()
        val ch = CharacteristicRef(ServiceRef(BleUuid(serviceUuid), 1), BleUuid(charUuid), 2)
        val other = CharacteristicRef(ServiceRef(BleUuid(serviceUuid), 0), BleUuid(charUuid), 2)
        val session = client.session(id)
        // 작업 콜백은 살아 있는 연결 generation 에만 귀속된다(CallbackAttribution).
        requireNotNull(core.registry.get(id)).connectionCoordinator
            .beginConnection(TestStageAuthority, CoroutineScope(Dispatchers.Unconfined + Job()), linkTimeoutMillis = 60_000) {}
        val received = mutableListOf<Int>()
        val otherReceived = mutableListOf<Int>()
        val job = collect(session, ch) { received += it[0].toInt() }
        val otherJob = collect(session, other) { otherReceived += it[0].toInt() }

        driver.emit(
            GattDriverEvent.CharacteristicChanged(
                id,
                GattCallbackSource { true },
                GattCharacteristicSnapshot(serviceUuid, charUuid, instanceIndex = 2, propertyMask = 0, serviceInstanceIndex = 1),
                byteArrayOf(7),
            ),
        )

        job.cancel()
        otherJob.cancel()
        client.close()
        assertEquals(listOf(7), received)
        assertEquals(emptyList<Int>(), otherReceived)
    }

    private class SinkCapturingDriver : NoopGattDriver()
}
