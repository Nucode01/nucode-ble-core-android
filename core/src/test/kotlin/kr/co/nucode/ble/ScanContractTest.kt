package kr.co.nucode.ble

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.harness.RecordingScanPort
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.scan.ScanObservation
import kr.co.nucode.ble.internal.event.BleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `failure-delivery-fixes/v1` task 2 — startScan 동기 실패의 throw, scanState 전이,
 * stopScan 의 취소 내성을 공개 경계(BleRuntime)에서 고정한다.
 * seam: 테스트 생성자의 어댑터 probe 와 `scanPort`(RecordingScanPort).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanContractTest {

    private class ProbeHolder(var state: AdapterState)

    /** [realPlatform] 이면 fake 대신 운영 AndroidScanner(어댑터 null) 를 쓴다. */
    private fun TestScope.newCore(
        probe: ProbeHolder = ProbeHolder(AdapterState.Ready),
        realPlatform: Boolean = false,
    ): BleRuntime =
        BleRuntime(
            config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
            adapter = { null },
            adapterState = { probe.state },
            scanPort = port.takeUnless { realPlatform },
        )

    private val port = RecordingScanPort()

    /** 성공한 시작마다 Scanner 가 만든 콜백 — 테스트는 이 람다로만 실패·결과를 싣는다(production 홉과 같은 경로). */
    private val starts: List<RecordingScanPort.Start> get() = port.starts.filter { it.failure == null }

    private val first = BleScanFilter(namePrefix = "A")
    private val second = BleScanFilter(namePrefix = "B")

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("scan"),
        code = FailureCode(code),
        phase = FailurePhase.Scan,
        retryability = Retryability.Unknown,
    )

    private fun TestScope.collectEvents(core: BleRuntime): List<BleEvent> {
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        return events
    }

    private fun List<BleEvent>.diagnosticCodes(): List<String> =
        filterIsInstance<BleEvent.Diagnostic>().map { it.failure.code.value }

    @Test
    fun `a 다른 요청으로 재시작하면 SCAN_ALREADY_RUNNING 을 던지고 상태와 Diagnostic 은 유지된다`() = runTest {
        val core = newCore()
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        core.startScan(first)
        val thrown = runCatching { core.startScan(second) }.exceptionOrNull()
        runCurrent()
        assertEquals("SCAN_ALREADY_RUNNING", (thrown as? BleException)?.failure?.code?.value)
        assertEquals(ScanState.Scanning(ScanRequest(first, BleScanOptions())), core.scanState.value)
        assertTrue(events.any { it is BleEvent.Diagnostic && it.failure.code.value == "SCAN_ALREADY_RUNNING" })
    }

    @Test
    fun `b 플랫폼 시작 실패는 그 실패를 던지고 Scanning 이 되지 않는다`() = runTest {
        val core = newCore()
        val events = collectEvents(core)
        port.onStart = { failure("SCAN_START_FAILED") }
        val thrown = runCatching { core.startScan(first) }.exceptionOrNull()
        runCurrent()
        assertEquals("SCAN_START_FAILED", (thrown as? BleException)?.failure?.code?.value)
        assertEquals(ScanState.Idle(null), core.scanState.value)
        assertTrue(events.diagnosticCodes().toString(), "SCAN_START_FAILED" in events.diagnosticCodes())
    }

    @Test
    fun `c 같은 요청 두 번은 던지지 않는다`() = runTest {
        val core = newCore()
        core.startScan(first)
        core.startScan(first)
        assertTrue(core.scanState.value is ScanState.Scanning)
    }

    @Test
    fun `d start 와 stop 에서 scanState 와 snapshot isScanning 이 일치한다`() = runTest {
        val core = newCore()
        core.startScan(first)
        assertTrue(core.scanState.value is ScanState.Scanning)
        assertTrue(core.officialSnapshot().isScanning)
        core.stopScan()
        assertEquals(ScanState.Idle(null), core.scanState.value)
        assertFalse(core.officialSnapshot().isScanning)
    }

    @Test
    fun `e 런타임 실패는 Idle(lastFailure) 로 전이한다`() = runTest {
        val core = newCore()
        val events = collectEvents(core)
        core.startScan(first)
        starts.last().onFailure(failure("SCAN_RUNTIME_FAILED"))
        runCurrent()
        val state = core.scanState.value as ScanState.Idle
        assertEquals("SCAN_RUNTIME_FAILED", state.lastFailure?.code?.value)
        assertFalse(core.officialSnapshot().isScanning)
        assertTrue(events.diagnosticCodes().toString(), "SCAN_RUNTIME_FAILED" in events.diagnosticCodes())
    }

    @Test
    fun `i 재시작 뒤 이전 스캔의 늦은 실패는 버리고 Warning Diagnostic 만 남긴다`() = runTest {
        val core = newCore()
        val events = collectEvents(core)
        core.startScan(first)
        val stale = starts.last()
        core.stopScan()
        core.startScan(second)
        stale.onFailure(failure("SCAN_RUNTIME_FAILED"))
        runCurrent()
        assertEquals(ScanState.Scanning(ScanRequest(second, BleScanOptions())), core.scanState.value)
        val diagnostic = events.filterIsInstance<BleEvent.Diagnostic>().single { it.failure.code.value == "SCAN_RUNTIME_FAILED" }
        assertEquals(Severity.Warning, diagnostic.severity)
    }

    @Test
    fun `j 정지 뒤 온 실패는 Idle(null) 을 바꾸지 않는다`() = runTest {
        val core = newCore()
        val events = collectEvents(core)
        core.startScan(first)
        val started = starts.last()
        core.stopScan()
        started.onFailure(failure("SCAN_RUNTIME_FAILED"))
        runCurrent()
        assertEquals(ScanState.Idle(null), core.scanState.value)
        assertEquals(Severity.Warning, events.filterIsInstance<BleEvent.Diagnostic>().single().severity)
    }

    @Test
    fun `k 어댑터로 멈춘 뒤 온 늦은 실패는 SCAN_STOPPED_BY_ADAPTER 를 유지한다`() = runTest {
        val probe = ProbeHolder(AdapterState.Ready)
        val core = newCore(probe)
        core.startScan(first)
        val started = starts.last()
        probe.state = AdapterState.PoweredOff
        core.refreshAdapterState()
        runCurrent()
        started.onFailure(failure("SCAN_RUNTIME_FAILED"))
        runCurrent()
        val state = core.scanState.value as ScanState.Idle
        assertEquals("SCAN_STOPPED_BY_ADAPTER", state.lastFailure?.code?.value)
    }

    @Test
    fun `l 재시작 뒤 이전 스캔의 늦은 결과는 새 필터에 맞아도 내보내지 않는다`() = runTest {
        val core = newCore()
        val results = mutableListOf<ScanObservation>()
        backgroundScope.launch { core.scanObservations.toList(results) }
        runCurrent()
        core.startScan(first)
        val stale = starts.last()
        core.stopScan()
        core.startScan(second)
        // 두 번째 요청(namePrefix = "B")에 맞는 광고 — 필터가 아니라 generation 으로 걸러져야 한다.
        val matchesSecond = observation("B-device")
        stale.onResult(matchesSecond)
        runCurrent()
        assertTrue(results.toString(), results.isEmpty())
        starts.last().onResult(matchesSecond)
        runCurrent()
        assertEquals(1, results.size)
    }

    @Test
    fun `m 실패한 시작도 generation 을 소비해 다음 성공한 시작과 공유하지 않는다`() = runTest {
        val core = newCore()
        val events = collectEvents(core)
        port.onStart = { failure("SCAN_START_FAILED") }
        runCatching { core.startScan(first) }
        val failedStart = port.starts.single()
        port.onStart = { null }
        core.startScan(first)
        // 실패한 시작의 콜백이 뒤늦게 와도 성공한 스캔을 멈추지 않는다.
        failedStart.onFailure(failure("SCAN_RUNTIME_FAILED"))
        runCurrent()
        assertEquals(ScanState.Scanning(ScanRequest(first, BleScanOptions())), core.scanState.value)
        val diagnostic = events.filterIsInstance<BleEvent.Diagnostic>().single { it.failure.code.value == "SCAN_RUNTIME_FAILED" }
        assertEquals(Severity.Warning, diagnostic.severity)
    }

    private fun observation(localName: String) = ScanObservation(
        PeripheralId("AA:BB:CC:DD:EE:41"),
        -50,
        AdvertisementData(localName, emptySet(), emptyMap(), emptyMap(), null, null),
    )

    @Test
    fun `f 스캔 중 어댑터가 꺼지면 Idle(SCAN_STOPPED_BY_ADAPTER)`() = runTest {
        val probe = ProbeHolder(AdapterState.Ready)
        val core = newCore(probe)
        val events = collectEvents(core)
        core.startScan(first)
        probe.state = AdapterState.PoweredOff
        core.refreshAdapterState()
        runCurrent()
        val state = core.scanState.value as ScanState.Idle
        assertEquals("SCAN_STOPPED_BY_ADAPTER", state.lastFailure?.code?.value)
        assertTrue(events.diagnosticCodes().toString(), "SCAN_STOPPED_BY_ADAPTER" in events.diagnosticCodes())
    }

    @Test
    fun `g 이미 취소된 코루틴에서 부른 stopScan 도 스캔을 끝낸다`() = runTest {
        val core = newCore()
        core.startScan(first)
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            core.stopScan()
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(ScanState.Idle(null), core.scanState.value)
        assertFalse(core.officialSnapshot().isScanning)
    }

    @Test
    fun `h 플랫폼 스캐너가 없으면 SCANNER_UNAVAILABLE 을 던지고 Diagnostic 을 낸다`() = runTest {
        val core = newCore(realPlatform = true)
        val events = collectEvents(core)
        val thrown = runCatching { core.startScan(first) }.exceptionOrNull()
        runCurrent()
        assertEquals("SCANNER_UNAVAILABLE", (thrown as? BleException)?.failure?.code?.value)
        assertEquals(ScanState.Idle(null), core.scanState.value)
        assertTrue(events.diagnosticCodes().toString(), "SCANNER_UNAVAILABLE" in events.diagnosticCodes())
    }

    /** AndroidScanner 의 SecurityException 변환은 JVM 에서 만들 수 없어 같은 코드를 fake 포트로 싣는다. */
    @Test
    fun `i 스캔 시작 권한 거부는 PERMISSION_DENIED 를 던지고 Diagnostic 을 낸다`() = runTest {
        val core = newCore()
        val events = collectEvents(core)
        port.onStart = { failure("PERMISSION_DENIED") }
        val thrown = runCatching { core.startScan(first) }.exceptionOrNull()
        runCurrent()
        assertEquals("PERMISSION_DENIED", (thrown as? BleException)?.failure?.code?.value)
        assertEquals(ScanState.Idle(null), core.scanState.value)
        assertTrue(events.diagnosticCodes().toString(), "PERMISSION_DENIED" in events.diagnosticCodes())
    }
}
