package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.internal.scan.ScanObservation
import org.junit.Test

/**
 * 4단계 스캔 콜백 식별이 줄여 가는 기준선 — (시나리오 | 변형 | 불변식) 위반 목록.
 * 새 위반이 생기면 red, 고친 위반은 여기서 빼야 green 이다.
 */
internal val KNOWN_SCAN_INVARIANT_VIOLATIONS: Set<String> = emptySet()

/**
 * plan platform-harness v1 task 3 — 스캔 시나리오 × 콜백 변형에서 불변식 I5 를 모은다.
 * I5a: `scanState is Scanning` ⇔ `snapshot().isScanning`(매 단계).
 * I5b: 다른(이전) 요청의 실패·거절이 현재 스캔(상태와 플랫폼 스캔)을 바꾸지 않는다.
 * I5c: 스캔 종료 이유는 먼저 확정된 것이 유지된다(first-wins) — 어댑터 off 로 먼저 멈추면
 *   Idle(SCAN_STOPPED_BY_ADAPTER), onScanFailed 가 먼저 처리되면 Idle(SCAN_RUNTIME_FAILED).
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

        // 같은 요청 재시작: A → 정지 → A. 첫 A 의 늦은 실패가 두 번째 A 를 멈추면 안 된다(요청 동등성으로는 못 가름).
        for ((label, variant) in variants) {
            all += scenario("same-request-restart-then-stale-failure", label) {
                start(requestA)
                val firstA = h.fake.scanStarts.last()
                stop()
                start(requestA)
                firstA.fail(errorCode = 2, variant = variant)
                settle()
                if (!scanningWith(requestA) || h.fake.scanStarts.size != 2) violate("I5b")
            }
        }

        // 재시작 뒤 이전 스캔의 늦은 결과 — 새 요청의 필터에 맞아도 내보내지 않는다.
        all += scenario("restart-then-stale-result", "-") {
            val results = mutableListOf<ScanObservation>()
            scope.backgroundScope.launch { h.core.scanObservations.toList(results) }
            step()
            start(requestA)
            val startA = h.fake.scanStarts.last()
            stop()
            start(requestB)
            startA.result(
                ScanObservation(
                    PeripheralId("AA:BB:CC:DD:EE:51"),
                    -50,
                    AdvertisementData("B-device", emptySet(), emptyMap(), emptyMap(), null, null),
                ),
            )
            settle()
            if (results.isNotEmpty() || !scanningWith(requestB)) violate("I5b")
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

        // 어댑터 off 홉(refreshAdapterState)보다 onScanFailed 가 먼저 큐에 들어간 순서 —
        // 런타임 실패가 먼저 확정되므로 SCAN_RUNTIME_FAILED 가 유지되는 것이 맞다(결정 1).
        all += scenario("adapter-off-failure-before-refresh", "Arrive") {
            h.fake.onScanFailedAfterAdapterOff = CallbackVariant.Arrive()
            start(requestA)
            h.fake.setAdapter(AdapterState.PoweredOff, scanFailureBeforeRefresh = true)
            step()
            if (idleCode() != "SCAN_RUNTIME_FAILED") violate("I5c")
            settle()
            if (idleCode() != "SCAN_RUNTIME_FAILED") violate("I5c")
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
