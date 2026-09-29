package kr.co.nucode.ble

import kr.co.nucode.ble.internal.port.GattCallbackSource
import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattDriverEvent
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.TestStageAuthority
import kr.co.nucode.ble.internal.model.BleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 페이로드 관측 진단은 logger 로만 가고 공개 events 에는 투영되지 않는다(plan M task 1). */
class PayloadObservationRoutingTest {
    private val observed = FailureCode("GATT_PAYLOAD_OBSERVED")
    private val serviceUuid = UUID.randomUUID()
    private val charUuid = UUID.randomUUID()
    private val ch = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))

    private fun collectEvents(client: BleClient, sink: MutableList<BleClientEvent>): Job =
        CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { synchronized(sink) { sink += it } }
        }

    private fun collectNotifications(session: BleSession, sink: MutableList<Int>): Job =
        CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            session.notifications(ch).collect { sink += it[0].toInt() }
        }

    private fun NoopGattDriver.notify(id: PeripheralId, value: Byte) = emit(
        GattDriverEvent.CharacteristicChanged(
            id,
            GattCallbackSource { true },
            GattCharacteristicSnapshot(serviceUuid, charUuid, instanceIndex = 0, propertyMask = 0),
            byteArrayOf(value),
        ),
    )

    private fun List<BleClientEvent>.observations() =
        filterIsInstance<OfficialBleClientEvent.Diagnostic>().filter { it.failure.code == observed }

    @Test(timeout = 5_000)
    fun `engine payload observation does not reach public events without logger`() = runBlocking {
        val driver = NoopGattDriver()
        val core = BleRuntime(config = BleConfig(), gattDriver = driver)
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:4A")
        val session = client.session(id)
        // 작업 콜백은 살아 있는 연결 generation 에만 귀속된다(CallbackAttribution).
        requireNotNull(core.registry.get(id)).connectionCoordinator
            .beginConnection(TestStageAuthority, CoroutineScope(Dispatchers.Unconfined + Job()), linkTimeoutMillis = 60_000) {}
        val events = mutableListOf<BleClientEvent>()
        val received = mutableListOf<Int>()
        val eventsJob = collectEvents(client, events)
        val notifyJob = collectNotifications(session, received)

        driver.notify(id, 7)

        notifyJob.cancel()
        eventsJob.cancel()
        client.close()
        assertEquals(listOf(7), received)
        assertEquals(emptyList<OfficialBleClientEvent.Diagnostic>(), synchronized(events) { events.observations() })
    }

    @Test(timeout = 5_000)
    fun `engine payload observation reaches configured logger`() = runBlocking {
        val latch = CountDownLatch(1)
        val logged = mutableListOf<DiagnosticRecord>()
        val logger = BleLogger { record ->
            synchronized(logged) { logged += record }
            if (record.failure.code == observed) latch.countDown()
        }
        val driver = NoopGattDriver()
        val core = BleRuntime(config = BleConfig(logger = logger), gattDriver = driver)
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:4B")
        client.session(id)
        // 작업 콜백은 살아 있는 연결 generation 에만 귀속된다(CallbackAttribution).
        requireNotNull(core.registry.get(id)).connectionCoordinator
            .beginConnection(TestStageAuthority, CoroutineScope(Dispatchers.Unconfined + Job()), linkTimeoutMillis = 60_000) {}

        driver.notify(id, 9)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(synchronized(logged) { logged.any { it.failure.code.value == "GATT_PAYLOAD_OBSERVED" } })
        client.close()
        // 값 정책도 observe 경로로 적용된다 — 기본 옵션(Unknown sensitivity)은 원문 대신 digest.
        val values = synchronized(logged) { logged.first { it.failure.code == observed } }.values
        assertEquals(DiagnosticValue.Text("rx"), values["payload_direction"])
        assertEquals(DiagnosticValue.Number(1L), values["payload_length"])
        assertTrue("digest expected: ${values.keys}", "payload_digest" in values)
        assertTrue("raw payload must not be captured by default: ${values.keys}", "payload" !in values)
    }

    @Test(timeout = 5_000)
    fun `non observation diagnostic still reaches public events`() = runBlocking {
        val core = BleRuntime(config = BleConfig(), gattDriver = NoopGattDriver())
        val client = DefaultBleClient(core)
        val events = mutableListOf<BleClientEvent>()
        val eventsJob = collectEvents(client, events)
        val failure = BleFailure(
            category = FailureCategory("vendor"),
            code = FailureCode("UNKNOWN_VENDOR_STATUS"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Unknown,
            context = FailureContext(),
            platformDetail = PlatformDetail.Android(domain = "vendor.domain", statusCode = 731),
        )

        core.emit(BleEvent.Diagnostic(Severity.Error, failure))

        eventsJob.cancel()
        client.close()
        val diagnostics = synchronized(events) { events.filterIsInstance<OfficialBleClientEvent.Diagnostic>() }
        assertEquals(listOf(failure), diagnostics.map { it.failure })
    }
}
