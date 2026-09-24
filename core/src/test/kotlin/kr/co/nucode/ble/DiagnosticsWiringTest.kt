package kr.co.nucode.ble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.event.BleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsWiringTest {
    @Test
    fun `one failure projects unchanged to compatibility event and isolated logger`() = runTest {
        val logged = mutableListOf<DiagnosticRecord>()
        val loggerLatch = CountDownLatch(1)
        val core = newCore(BleLogger { record -> synchronized(logged) { logged += record }; loggerLatch.countDown() })
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()
        val failure = failure()

        core.emit(BleEvent.Diagnostic(Severity.Warning, failure))

        assertTrue(loggerLatch.await(2, TimeUnit.SECONDS))
        runCurrent()
        val event = events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().single()
        assertEquals(failure, event.failure)
        val record = synchronized(logged) { logged.single() }
        assertEquals(DiagnosticSeverity.Warning, record.severity)
        assertEquals(DiagnosticFailure(failure), record.failure)
        core.close()
    }

    @Test
    fun `internal observation lazily supplies logger values while compatibility remains payload free`() = runTest {
        val logged = mutableListOf<DiagnosticRecord>()
        val loggerLatch = CountDownLatch(1)
        val configuredOptions = DiagnosticOptions(rawPayloadsEnabled = true, maxRawPayloadBytes = 17)
        val core = newCore(
            logger = BleLogger { record -> synchronized(logged) { logged += record }; loggerLatch.countDown() },
            diagnosticOptions = configuredOptions,
        )
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()
        val failure = failure()

        var observedOptions: DiagnosticOptions? = null
        core.diagnosticIngress.report(Severity.Error, failure) { options ->
            observedOptions = options
            mapOf("payload-state" to DiagnosticValue.Redacted)
        }

        assertTrue(loggerLatch.await(2, TimeUnit.SECONDS))
        runCurrent()
        assertEquals(listOf(OfficialBleClientEvent.Diagnostic(Severity.Error, failure)), events)
        assertEquals(
            mapOf("payload-state" to DiagnosticValue.Redacted),
            synchronized(logged) { logged.single().values },
        )
        assertEquals(configuredOptions, observedOptions)
        core.close()
    }

    @Test
    fun `blocked logger does not block compatibility event or close`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val released = AtomicBoolean(false)
        // plan `runtime-no-runblocking/v3` review MEDIUM Fix 3 — interrupt 로 풀리는 latch.await()
        // 는 이 테스트의 목적(정리가 blocked logger 를 join 하지 않는다는 계약)을 증명하지
        // 못한다: `DiagnosticDispatcher.close()`가 `interrupt()` 만 부르든 `interrupt(); join()`
        // 으로 회귀하든, interruptible latch 는 어느 쪽이든 즉시 풀려 두 구현을 구분 못 한다.
        // 그래서 이 로거는 interrupt 를 삼키고 명시적 `released` 신호 전까지 계속 블록한다 —
        // `close()`가 join 으로 회귀하면 그 join 이 이 무한 블록을 기다리게 돼 아래
        // `withTimeout(200)` 이 진짜로 실패한다.
        val core = newCore(BleLogger {
            entered.countDown()
            while (!released.get()) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // 계속 블록한다 — 명시적 release 전까지는 interrupt 에 풀리지 않는다.
                }
            }
        })
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()

        core.emit(BleEvent.Diagnostic(Severity.Error, failure()))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        runCurrent()
        assertEquals(1, events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().size)
        val started = System.nanoTime()
        core.close()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("close waited for logger: $elapsedMillis ms", elapsedMillis < 200)
        // close() 자체가 즉시 반환하는 건 이제(plan `runtime-no-runblocking` task 2(B)) 늘
        // 참이라 위 단언만으로는 공허하다(정리를 스케줄만 하고 실제로 안 돌아도 통과한다) —
        // 정리가 blocked logger 를 실제로 기다리지 않고 끝난다는 것 자체를 증명하려면, 아직
        // release 하지 않은 상태에서 정리 완료(`awaitClosed()`)까지 200ms 안에 끝나는지 봐야
        // 한다(plan v2 review MEDIUM Fix 3). `diagnosticDispatcher.close()` 는 워커 스레드를
        // join 하지 않고 `interrupt()` 만 요청하므로(DiagnosticDispatcher.close 참고) 정리가
        // 이 blocked logger 를 기다릴 이유가 없다.
        runBlocking { withTimeout(200) { core.awaitClosed() } }
        released.set(true)
        release.countDown()
    }

    @Test
    fun `without logger compatibility diagnostics remain complete`() = runTest {
        val core = newCore(logger = null)
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()
        val failure = failure()

        var valuesEvaluated = false
        core.diagnosticIngress.report(Severity.Info, failure) { _: DiagnosticOptions ->
            valuesEvaluated = true
            mapOf("unexpected" to DiagnosticValue.Text("capture"))
        }
        runCurrent()

        assertEquals(listOf(OfficialBleClientEvent.Diagnostic(Severity.Info, failure)), events)
        assertTrue(!valuesEvaluated)
        core.close()
    }

    private fun kotlinx.coroutines.test.TestScope.newCore(
        logger: BleLogger?,
        diagnosticOptions: DiagnosticOptions = DiagnosticOptions(),
    ): BleRuntime = BleRuntime(
        // close() no longer blocks (plan `runtime-no-runblocking` task 2(B)): the runtime's real
        // single-thread dispatcher is fine here without wiring a `StandardTestDispatcher`.
        // "blocked logger does not block compatibility event or close" DOES assert a post-close
        // effect (`awaitClosed()` completing within 200ms while the logger is still blocked) —
        // it calls `awaitClosed()` on a real `runBlocking` before releasing the logger latch.
        config = BleConfig(logger = logger, diagnosticOptions = diagnosticOptions),
        adapter = { null },
    )

    private fun failure() = BleFailure(
        category = FailureCategory("vendor"),
        code = FailureCode("UNKNOWN_VENDOR_STATUS"),
        phase = FailurePhase.Operation,
        retryability = Retryability.Unknown,
        context = FailureContext(sessionId = "session-opaque", connectionGeneration = 7, operationId = "operation-opaque"),
        platformDetail = PlatformDetail.Android(domain = "vendor.domain", statusCode = 731, symbolicName = "VENDOR_731"),
        message = "must not reach diagnostics",
    )
}
