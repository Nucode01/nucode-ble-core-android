package kr.co.nucode.ble

import kr.co.nucode.ble.internal.model.PlatformScanSpec
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.harness.RecordingScanPort
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.TestClock
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.core.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.internal.scan.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan scan-owner 목표 3·4 — 재발견이 Scanner 의 소비자로 사용자 스캔과 한 플랫폼 스캔을 나눠 쓰는 합류 규칙.
 * seam: [RecordingScanPort](플랫폼 문) — 결과·실패는 Scanner 가 시작마다 만든 콜백으로만 싣는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanRediscoveryJoinTest {

    private val port = RecordingScanPort()
    private val events = mutableListOf<BleEvent>()
    private val delivered = mutableListOf<ScanObservation>()

    private fun CoroutineScope.newScanner() = Scanner(
        emit = events::add,
        scope = this,
        emitObservation = delivered::add,
        port = port,
        clock = TestClock,
    )

    private val target = PeripheralId("AA:BB:CC:DD:EE:01")
    private val other = PeripheralId("AA:BB:CC:DD:EE:02")
    private val service = BleUuid(UUID.randomUUID())
    private val filtered = ScanRequest(BleScanFilter(serviceUuids = listOf(service)), BleScanOptions(BleScanMode.LowPower))
    private val scanAll = ScanRequest(BleScanFilter(), BleScanOptions())

    private fun observation(id: PeripheralId, withService: Boolean = false) = ScanObservation(
        id,
        -50,
        AdvertisementData(null, if (withService) setOf(service) else emptySet(), emptyMap(), emptyMap(), null, null),
    )

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("scan"),
        code = FailureCode(code),
        phase = FailurePhase.Scan,
        retryability = Retryability.Unknown,
    )

    private fun TestScope.rediscover(scanner: Scanner, id: PeripheralId = target, timeout: Long = 1_000): Deferred<RediscoveryOutcome> =
        async { scanner.rediscover(id, timeout) }.also { runCurrent() }

    /** review MED-3 — 한 디스패처 턴의 재발견 등록 N 개(예: 어댑터 복귀로 autoReconnect 세션 N 개 재연결)는 플랫폼 시작 1회로 합친다. */
    @Test
    fun `같은 턴의 재발견 등록 여러 개는 합집합으로 한 번만 시작한다`() = runTest {
        val scanner = newScanner()
        val third = PeripheralId("AA:BB:CC:DD:EE:03")
        val outcomes = listOf(target, other, third).map { id -> async { scanner.rediscover(id, 1_000) } }
        runCurrent()
        assertEquals(PlatformScanSpec(null, setOf(target, other, third), BleScanMode.LowLatency), port.starts.single().spec)
        port.starts.single().onResult(observation(other))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcomes[1].await())
        assertEquals("남은 재발견이 있으면 재시작하지 않는다", 1, port.starts.size)
        outcomes.forEach { it.cancel() }
        runCurrent()
    }

    @Test
    fun `사용자 스캔 없음 - 주소만 LowLatency 로 시작하고 Found 뒤 정지한다`() = runTest {
        val scanner = newScanner()
        val outcome = rediscover(scanner)
        assertEquals(PlatformScanSpec(null, setOf(target), BleScanMode.LowLatency), port.starts.single().spec)
        port.starts.single().onResult(observation(PeripheralId("aa:bb:cc:dd:ee:01"))) // 대소문자 무시
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        assertEquals(1, port.stops)
        assertFalse(port.running)
        assertEquals(ScanState.Idle(null), scanner.scanState.value)
        assertTrue("재발견 결과가 사용자에게 갔다", delivered.isEmpty())
    }

    @Test
    fun `사용자 스캔 없음 - 시간 초과면 NotFound 뒤 정지한다`() = runTest {
        val scanner = newScanner()
        val outcome = rediscover(scanner, timeout = 500)
        port.starts.single().onResult(observation(other))
        advanceTimeBy(501)
        runCurrent()
        assertEquals(RediscoveryOutcome.NotFound, outcome.await())
        assertEquals(1, port.stops)
        assertFalse(port.running)
    }

    @Test
    fun `사용자 전체 스캔 - LowLatency 로 한 번 재시작해 합류하고 라우팅으로 Found`() = runTest {
        val scanner = newScanner()
        scanner.startScan(scanAll)
        val outcome = rediscover(scanner)
        // 재발견은 LowLatency 가 필요하다 — Balanced 전체 스캔은 모드로 덮지 못해 한 번 재시작한다.
        assertEquals(2, port.starts.size)
        assertEquals(PlatformScanSpec(scanAll.filter, emptySet(), BleScanMode.LowLatency), port.starts.last().spec)
        port.starts.last().onResult(observation(target))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        assertEquals(1, port.stops)
        assertTrue(port.running)
        assertEquals(ScanState.Scanning(scanAll), scanner.scanState.value)
        assertEquals(1, delivered.size) // 전체 스캔 사용자는 그 결과도 받는다
    }

    @Test
    fun `사용자 필터 스캔 - 합집합으로 한 번 재시작하고 사용자 결과는 계속 필터된다`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        val states = mutableListOf<ScanState>()
        val watch = async { scanner.scanState.collect { states += it } }
        runCurrent()
        val outcome = rediscover(scanner)
        assertEquals(2, port.starts.size)
        assertEquals(1, port.stops)
        assertEquals(PlatformScanSpec(filtered.filter, setOf(target), BleScanMode.LowLatency), port.starts.last().spec)
        // 주소로만 들어온 비대상 광고는 scanResults 로 새지 않는다.
        port.starts.last().onResult(observation(target))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        assertTrue("주소 결과가 사용자에게 갔다", delivered.isEmpty())
        port.starts.last().onResult(observation(other, withService = true))
        runCurrent()
        assertEquals(1, delivered.size)
        // 재발견이 끝나도 재시작하지 않는다.
        assertEquals(2, port.starts.size)
        assertEquals(1, port.stops)
        assertTrue(port.running)
        watch.cancel()
        assertEquals("재시작 사이 Idle 이 보였다: $states", listOf<ScanState>(ScanState.Scanning(filtered)), states)
    }

    @Test
    fun `재발견 중 사용자 스캔 시작 - 합집합으로 재시작한다`() = runTest {
        val scanner = newScanner()
        val outcome = rediscover(scanner)
        scanner.startScan(filtered)
        assertEquals(2, port.starts.size)
        assertEquals(PlatformScanSpec(filtered.filter, setOf(target), BleScanMode.LowLatency), port.starts.last().spec)
        assertEquals(ScanState.Scanning(filtered), scanner.scanState.value)
        port.starts.last().onResult(observation(target))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        assertTrue(port.running)
    }

    @Test
    fun `재발견 중 사용자 전체 스캔 시작 - 전체로 재시작한다`() = runTest {
        val scanner = newScanner()
        rediscover(scanner)
        scanner.startScan(scanAll)
        assertEquals(PlatformScanSpec(scanAll.filter, emptySet(), BleScanMode.LowLatency), port.starts.last().spec)
    }

    @Test
    fun `재발견 중 사용자 정지 - 플랫폼 스캔은 유지되고 재발견이 끝나면 정지한다`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        val outcome = rediscover(scanner)
        val stopsBefore = port.stops
        scanner.stopScan()
        assertEquals(ScanState.Idle(null), scanner.scanState.value)
        assertEquals(stopsBefore, port.stops)
        assertTrue(port.running)
        port.starts.last().onResult(observation(other, withService = true))
        runCurrent()
        assertTrue("정지한 사용자에게 결과가 갔다", delivered.isEmpty())
        port.starts.last().onResult(observation(target))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        assertEquals(stopsBefore + 1, port.stops)
        assertFalse(port.running)
        assertEquals(2, port.starts.size)
    }

    @Test
    fun `재시작 실패 - 사용자 Idle(lastFailure) 와 재발견 Unavailable`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        port.onStart = { failure("SCAN_START_FAILED") }
        val outcome = rediscover(scanner)
        val expected = failure("SCAN_START_FAILED")
        assertEquals(RediscoveryOutcome.Unavailable(expected), outcome.await())
        assertEquals(ScanState.Idle(expected), scanner.scanState.value)
        assertFalse(port.running)
        assertTrue(events.any { it is BleEvent.Diagnostic && it.severity == Severity.Error && it.failure == expected })
    }

    @Test
    fun `재발견 중 사용자 시작 실패 - 사용자는 던지고 재발견은 Unavailable`() = runTest {
        val scanner = newScanner()
        val outcome = rediscover(scanner)
        port.onStart = { failure("SCAN_START_FAILED") }
        assertEquals("SCAN_START_FAILED", scanner.startScan(filtered)?.code?.value)
        runCurrent()
        assertEquals(RediscoveryOutcome.Unavailable(failure("SCAN_START_FAILED")), outcome.await())
        assertEquals(ScanState.Idle(null), scanner.scanState.value)
        assertFalse(port.running)
    }

    @Test
    fun `합류 중 onScanFailed - 두 소비자가 같은 실패로 끝난다`() = runTest {
        val scanner = newScanner()
        scanner.startScan(scanAll)
        val outcome = rediscover(scanner)
        val runtime = failure("SCAN_RUNTIME_FAILED")
        port.starts.last().onFailure(runtime)
        runCurrent()
        assertEquals(RediscoveryOutcome.Unavailable(runtime), outcome.await())
        assertEquals(ScanState.Idle(runtime), scanner.scanState.value)
        assertFalse(port.running)
    }

    @Test
    fun `어댑터 꺼짐 - 두 소비자가 끝난다`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        val outcome = rediscover(scanner)
        scanner.stopForAdapter(AdapterState.PoweredOff)
        runCurrent()
        val adapterOff = (outcome.await() as RediscoveryOutcome.Unavailable).failure
        assertEquals("SCAN_STOPPED_BY_ADAPTER", adapterOff.code.value)
        assertEquals(ScanState.Idle(adapterOff), scanner.scanState.value)
        assertFalse(port.running)
    }

    @Test
    fun `어댑터 꺼짐 - 런타임 sweep 이 재발견만 돌던 스캔도 끝낸다`() = runTest {
        var adapter: AdapterState = AdapterState.Ready
        val core = BleRuntime(
            config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
            adapterState = { adapter },
            scanPort = port,
        )
        val outcome = rediscover(core.scanner)
        assertTrue(port.running)
        adapter = AdapterState.PoweredOff
        core.refreshAdapterState()
        runCurrent()
        val result = outcome.await()
        assertEquals("SCAN_STOPPED_BY_ADAPTER", (result as RediscoveryOutcome.Unavailable).failure.code.value)
        assertFalse(port.running)
    }

    @Test
    fun `재발견 코루틴 취소 - 소비자가 빠지고 마지막이면 플랫폼을 멈춘다`() = runTest {
        val scanner = newScanner()
        val first = rediscover(scanner, target)
        val second = rediscover(scanner, other)
        assertEquals(PlatformScanSpec(null, setOf(target, other), BleScanMode.LowLatency), port.starts.last().spec)
        first.cancel()
        runCurrent()
        assertTrue("다른 재발견이 남았는데 멈췄다", port.running)
        second.cancel()
        runCurrent()
        assertFalse(port.running)
        // 해제된 소비자에게는 늦은 결과가 가지 않는다(상태 없음) — 다음 재발견은 새로 시작한다.
        val third = rediscover(scanner, target)
        assertEquals(PlatformScanSpec(null, setOf(target), BleScanMode.LowLatency), port.starts.last().spec)
        third.cancel()
    }

    @Test
    fun `재시작 뒤 이전 generation 의 결과와 실패는 버린다`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        val stale = port.starts.single()
        val outcome = rediscover(scanner)
        stale.onResult(observation(target))
        stale.onResult(observation(other, withService = true))
        stale.onFailure(failure("SCAN_RUNTIME_FAILED"))
        runCurrent()
        assertFalse(outcome.isCompleted)
        assertTrue(delivered.isEmpty())
        assertEquals(ScanState.Scanning(filtered), scanner.scanState.value)
        assertTrue(port.running)
        val warning = events.filterIsInstance<BleEvent.Diagnostic>().single { it.failure.code.value == "SCAN_RUNTIME_FAILED" }
        assertEquals(Severity.Warning, warning.severity)
        outcome.cancel()
    }

    @Test
    fun `잘못된 주소 재발견 - 돌던 사용자 필터 스캔을 건드리지 않고 Unavailable`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        val outcome = rediscover(scanner, PeripheralId("not-a-mac"))
        assertEquals(RediscoveryOutcome.Unavailable(FailureMapper.scanStartFailed()), outcome.await())
        assertEquals("SCAN_START_FAILED", (outcome.await() as RediscoveryOutcome.Unavailable).failure.code.value)
        assertEquals(1, port.starts.size)
        assertEquals(0, port.stops)
        assertTrue(port.running)
        assertEquals(ScanState.Scanning(filtered), scanner.scanState.value)
        port.starts.single().onResult(observation(other, withService = true))
        runCurrent()
        assertEquals(1, delivered.size)
    }

    @Test
    fun `소문자 주소 재발견 - 플랫폼 필터엔 대문자 정규형으로 싣고 Found`() = runTest {
        val scanner = newScanner()
        val outcome = rediscover(scanner, PeripheralId("aa:bb:cc:dd:ee:01"))
        assertEquals(PlatformScanSpec(null, setOf(target), BleScanMode.LowLatency), port.starts.single().spec)
        port.starts.single().onResult(observation(target))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        assertFalse(port.running)
    }

    @Test
    fun `사용자 LowPower 전체 스캔 중 재발견 - LowLatency 로 한 번 재시작하고 사용자는 Idle 을 보지 않는다`() = runTest {
        val scanner = newScanner()
        val lowPowerAll = ScanRequest(BleScanFilter(), BleScanOptions(BleScanMode.LowPower))
        scanner.startScan(lowPowerAll)
        val states = mutableListOf<ScanState>()
        val watch = async { scanner.scanState.collect { states += it } }
        runCurrent()
        val outcome = rediscover(scanner)
        assertEquals(2, port.starts.size)
        assertEquals(1, port.stops)
        assertEquals(PlatformScanSpec(lowPowerAll.filter, emptySet(), BleScanMode.LowLatency), port.starts.last().spec)
        port.starts.last().onResult(observation(target))
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, outcome.await())
        // 재발견이 끝나도 재시작하지 않는다(높은 모드는 사용자 정지까지 유지).
        assertEquals(2, port.starts.size)
        assertTrue(port.running)
        watch.cancel()
        assertEquals("재시작 사이 Idle 이 보였다: $states", listOf<ScanState>(ScanState.Scanning(lowPowerAll)), states)
    }

    @Test
    fun `재발견 중 사용자 정지 - 남은 플랫폼 스캔은 이미 LowLatency 다`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        val outcome = rediscover(scanner)
        scanner.stopScan()
        assertTrue(port.running)
        assertEquals(BleScanMode.LowLatency, port.starts.last().spec.mode)
        outcome.cancel()
    }

    @Test
    fun `사용자 시작에서 포트가 던지면 - 사용자 상태가 고착되지 않는다`() = runTest {
        val scanner = newScanner()
        port.onStart = { throw IllegalStateException("boom") }
        assertTrue(runCatching { scanner.startScan(filtered) }.exceptionOrNull() is IllegalStateException)
        assertEquals(ScanState.Idle(null), scanner.scanState.value)
        port.onStart = { null }
        // 다른 요청도 시작할 수 있다(SCAN_ALREADY_RUNNING 이 아니다).
        assertEquals(null, scanner.startScan(scanAll))
        assertEquals(ScanState.Scanning(scanAll), scanner.scanState.value)
        scanner.stopScan()
        assertEquals(ScanState.Idle(null), scanner.scanState.value)
        assertFalse(port.running)
    }

    @Test
    fun `재발견에서 포트가 던지면 - Unavailable 이고 소비자가 빠진다`() = runTest {
        val scanner = newScanner()
        port.onStart = { throw IllegalStateException("boom") }
        val outcome = rediscover(scanner)
        assertEquals(RediscoveryOutcome.Unavailable(FailureMapper.scanStartFailed()), outcome.await())
        // 소비자가 빠졌다 — 다음 재발견의 spec 에 이전 주소가 남지 않는다.
        port.onStart = { null }
        val next = rediscover(scanner, other)
        assertEquals(PlatformScanSpec(null, setOf(other), BleScanMode.LowLatency), port.starts.last().spec)
        next.cancel()
    }

    @Test
    fun `사용자 필터 스캔 중 재발견 재시작에서 포트가 던지면 - 사용자도 같은 실패로 Idle`() = runTest {
        val scanner = newScanner()
        scanner.startScan(filtered)
        port.onStart = { throw IllegalStateException("boom") }
        val outcome = rediscover(scanner)
        val failure = FailureMapper.scanStartFailed()
        assertEquals(RediscoveryOutcome.Unavailable(failure), outcome.await())
        // 도는 플랫폼 스캔이 없는데 Scanning 으로 남지 않는다.
        assertEquals(ScanState.Idle(failure), scanner.scanState.value)
    }
}
