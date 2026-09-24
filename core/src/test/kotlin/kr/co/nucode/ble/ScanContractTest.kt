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
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.event.BleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `failure-delivery-fixes/v1` task 2 — startScan 동기 실패의 throw, scanState 전이,
 * stopScan 의 취소 내성을 공개 경계(BleRuntime)에서 고정한다.
 * seam: 테스트 생성자의 어댑터 probe 와 internal `core.scanner.startOverride`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanContractTest {

    private class ProbeHolder(var state: AdapterState)

    private fun TestScope.newCore(probe: ProbeHolder = ProbeHolder(AdapterState.Ready)): BleRuntime =
        BleRuntime(
            config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
            adapter = { null },
            adapterState = { probe.state },
        ).also { it.scanner.startOverride = { _, _ -> null } }

    private val first = BleScanFilter(namePrefix = "A")
    private val second = BleScanFilter(namePrefix = "B")

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("scan"),
        code = FailureCode(code),
        phase = FailurePhase.Scan,
        retryability = Retryability.Unknown,
    )

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
        core.scanner.startOverride = { _, _ -> failure("SCAN_START_FAILED") }
        val thrown = runCatching { core.startScan(first) }.exceptionOrNull()
        assertEquals("SCAN_START_FAILED", (thrown as? BleException)?.failure?.code?.value)
        assertEquals(ScanState.Idle(null), core.scanState.value)
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
        core.startScan(first)
        core.scanner.handleFailure(failure("SCAN_RUNTIME_FAILED"))
        val state = core.scanState.value as ScanState.Idle
        assertEquals("SCAN_RUNTIME_FAILED", state.lastFailure?.code?.value)
        assertFalse(core.officialSnapshot().isScanning)
    }

    @Test
    fun `f 스캔 중 어댑터가 꺼지면 Idle(SCAN_STOPPED_BY_ADAPTER)`() = runTest {
        val probe = ProbeHolder(AdapterState.Ready)
        val core = newCore(probe)
        core.startScan(first)
        probe.state = AdapterState.PoweredOff
        core.refreshAdapterState()
        runCurrent()
        val state = core.scanState.value as ScanState.Idle
        assertEquals("SCAN_STOPPED_BY_ADAPTER", state.lastFailure?.code?.value)
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
}
