package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import org.junit.Test

/**
 * 4단계 스캔 콜백 식별이 줄여 가는 기준선 — (시나리오 | 변형 | 불변식) 위반 목록.
 * 새 위반이 생기면 red, 고친 위반은 여기서 빼야 green 이다.
 */
internal val KNOWN_SCAN_INVARIANT_VIOLATIONS: Set<String> = setOf(
    // 원인: Scanner.handleFailure 가 활성 여부를 보지 않고 Idle(실패)로 덮어쓴다 — 어댑터 off 뒤 온 onScanFailed 가 첫 종료 원인을 바꾼다.
    // 원인: 어댑터 off 홉보다 먼저 처리된 onScanFailed 가 Idle(SCAN_RUNTIME_FAILED) 로 끝내고, 뒤이은 refresh 는 이미 Idle 인 스캔을 다시 보지 않는다.
    "adapter-off-failure-before-refresh | Arrive | I5c",
    "adapter-off-while-scanning | Arrive | I5c",
    "adapter-off-while-scanning | Late | I5c",
    // 원인(M1): 코어로 가는 실패 홉에 요청 식별이 없어 A 의 늦은 실패가 B 스캔을 끝낸다.
    "restart-then-stale-failure | Arrive | I5b",
    "restart-then-stale-failure | Duplicate | I5b",
    "restart-then-stale-failure | Late | I5b",
)

/**
 * plan platform-harness v1 task 3 — 스캔 시나리오 × 콜백 변형에서 불변식 I5 를 모은다.
 * I5a: `scanState is Scanning` ⇔ `snapshot().isScanning`(매 단계).
 * I5b: 다른(이전) 요청의 실패·거절이 현재 스캔(상태와 플랫폼 스캔)을 바꾸지 않는다.
 * I5c: 어댑터 off 로 멈춘 스캔은 Idle(SCAN_STOPPED_BY_ADAPTER) — 첫 종료 원인이 이긴다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformScanScenarioTest {

    private val requestA = ScanRequest(BleScanFilter(namePrefix = "A"))
    private val requestB = ScanRequest(BleScanFilter(namePrefix = "B"))

    private val variants: List<Pair<String, CallbackVariant>> = listOf(
        "Arrive" to CallbackVariant.Arrive(),
        "Late" to CallbackVariant.Late(delayMillis = 500),
        "Duplicate" to CallbackVariant.Duplicate(),
    )

    /** 한 시나리오 실행 중 위반을 모으는 도우미. */
    private class Run(val scope: TestScope, val h: Harness, val scenario: String, val variant: String) {
        val violations = mutableSetOf<String>()

        fun violate(invariant: String) {
            violations += "$scenario | $variant | $invariant"
        }

        /** 공개 startScan. 던진 예외를 돌려준다. */
        fun start(request: ScanRequest): Throwable? {
            val job = scope.async { runCatching { h.client.startScan(request) }.exceptionOrNull() }
            step()
            return job.getCompleted()
        }

        fun stop() {
            scope.async { h.client.stopScan() }
            step()
        }

        /** 현재 시점까지 실행하고 I5a 를 본다. */
        fun step() {
            scope.runCurrent()
            checkI5a()
        }

        fun settle() {
            scope.advanceUntilIdle()
            step()
        }

        private fun checkI5a() {
            val snapshot = scope.async { h.client.snapshot() }
            scope.runCurrent()
            val scanning = h.client.scanState.value is ScanState.Scanning
            if (scanning != snapshot.getCompleted().isScanning) violate("I5a")
        }

        fun scanningWith(request: ScanRequest): Boolean =
            (h.client.scanState.value as? ScanState.Scanning)?.request == request && h.core.scanner.isScanning

        fun idleCode(): String? = (h.client.scanState.value as? ScanState.Idle)?.lastFailure?.code?.value
    }

    private fun TestScope.scenario(name: String, variant: String, body: Run.() -> Unit): Set<String> {
        val run = Run(this, harness(), name, variant)
        run.body()
        run.settle()
        run.h.close()
        return run.violations
    }

    private fun TestScope.collectAll(): Set<String> {
        val all = mutableSetOf<String>()

        all += scenario("start-stop", "-") {
            start(requestA)
            stop()
            if (idleCode() != null || h.core.scanner.isScanning) violate("I5c")
        }

        for ((label, variant) in variants) {
            all += scenario("start-runtime-failure", label) {
                start(requestA)
                h.fake.scanFailed(errorCode = 2, variant = variant)
                settle()
                if (idleCode() != "SCAN_RUNTIME_FAILED") violate("I5c")
            }
        }

        // M1: A 의 콜백이 정지·재시작 뒤 늦게 실패를 낸다.
        for ((label, variant) in variants) {
            all += scenario("restart-then-stale-failure", label) {
                start(requestA)
                val startA = h.fake.scanStarts.last()
                stop()
                start(requestB)
                startA.fail(errorCode = 2, variant = variant)
                settle()
                if (!scanningWith(requestB)) violate("I5b")
            }
        }

        val adapterVariants = listOf("Missing" to CallbackVariant.Missing) + variants.take(2)
        for ((label, variant) in adapterVariants) {
            all += scenario("adapter-off-while-scanning", label) {
                h.fake.onScanFailedAfterAdapterOff = variant
                start(requestA)
                h.fake.setAdapter(AdapterState.PoweredOff)
                step()
                if (idleCode() != "SCAN_STOPPED_BY_ADAPTER") violate("I5c")
                settle()
                if (idleCode() != "SCAN_STOPPED_BY_ADAPTER") violate("I5c")
            }
        }

        // 어댑터 off 홉(refreshAdapterState)보다 onScanFailed 가 먼저 큐에 들어간 순서.
        all += scenario("adapter-off-failure-before-refresh", "Arrive") {
            h.fake.onScanFailedAfterAdapterOff = CallbackVariant.Arrive()
            start(requestA)
            h.fake.setAdapter(AdapterState.PoweredOff, scanFailureBeforeRefresh = true)
            step()
            if (idleCode() != "SCAN_STOPPED_BY_ADAPTER") violate("I5c")
            settle()
            if (idleCode() != "SCAN_STOPPED_BY_ADAPTER") violate("I5c")
        }

        all += scenario("start-same-request-twice", "-") {
            start(requestA)
            val error = start(requestA)
            if (error != null || !scanningWith(requestA) || h.fake.scanStarts.size != 1) violate("I5b")
        }

        all += scenario("start-different-request-while-scanning", "-") {
            start(requestA)
            val error = start(requestB)
            val code = (error as? BleException)?.failure?.code?.value
            if (code != "SCAN_ALREADY_RUNNING" || !scanningWith(requestA) || h.fake.scanStarts.size != 1) {
                violate("I5b")
            }
        }

        return all
    }

    @Test
    fun `스캔 시나리오 위반 목록이 기준선과 정확히 같다`() = runTest {
        assertViolationsPinned(KNOWN_SCAN_INVARIANT_VIOLATIONS, collectAll())
    }
}
