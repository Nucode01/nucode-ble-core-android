package kr.co.nucode.ble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
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
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        val failure = failure()

        core.emit(BleEvent.Diagnostic(Severity.Warning, failure))

        assertTrue(loggerLatch.await(2, TimeUnit.SECONDS))
        runCurrent()
        val event = events.filterIsInstance<BleEvent.Diagnostic>().single()
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
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        val failure = failure()

        var observedOptions: DiagnosticOptions? = null
        core.diagnosticIngress.report(Severity.Error, failure) { options ->
            observedOptions = options
            mapOf("payload-state" to DiagnosticValue.Redacted)
        }

        assertTrue(loggerLatch.await(2, TimeUnit.SECONDS))
        runCurrent()
        assertEquals(listOf(BleEvent.Diagnostic(Severity.Error, failure)), events)
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
        val core = newCore(BleLogger { entered.countDown(); release.await() })
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()

        core.emit(BleEvent.Diagnostic(Severity.Error, failure()))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        runCurrent()
        assertEquals(1, events.filterIsInstance<BleEvent.Diagnostic>().size)
        val started = System.nanoTime()
        core.close()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("close waited for logger: $elapsedMillis ms", elapsedMillis < 200)
        release.countDown()
    }

    @Test
    fun `without logger compatibility diagnostics remain complete`() = runTest {
        val core = newCore(logger = null)
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        val failure = failure()

        var valuesEvaluated = false
        core.diagnosticIngress.report(Severity.Info, failure) { _: DiagnosticOptions ->
            valuesEvaluated = true
            mapOf("unexpected" to DiagnosticValue.Text("capture"))
        }
        runCurrent()

        assertEquals(listOf(BleEvent.Diagnostic(Severity.Info, failure)), events)
        assertTrue(!valuesEvaluated)
        core.close()
    }

    private fun kotlinx.coroutines.test.TestScope.newCore(
        logger: BleLogger?,
        diagnosticOptions: DiagnosticOptions = DiagnosticOptions(),
    ): BleRuntime = BleRuntime(
        // close() is synchronous; use the runtime's real single-thread dispatcher here rather
        // than a test dispatcher which requires this test thread to advance its scheduler.
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
