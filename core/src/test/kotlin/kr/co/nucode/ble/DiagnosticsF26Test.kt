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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic acceptance gate for F26 diagnostics hardening. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsF26Test {
    @Test
    fun `one unknown platform failure preserves its typed tuple across event and logger projections`() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        val delivered = CountDownLatch(1)
        val core = BleRuntime(
            config = BleConfig(
                logger = BleLogger { record ->
                    synchronized(records) { records += record }
                    delivered.countDown()
                },
            ),
            adapter = { null },
        )
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        val failure = unknownFailure()

        core.emit(BleEvent.Diagnostic(Severity.Error, failure))

        assertTrue("logger did not receive diagnostic", delivered.await(2, TimeUnit.SECONDS))
        runCurrent()
        assertEquals(listOf(BleEvent.Diagnostic(Severity.Error, failure)), events)
        val record = synchronized(records) { records.single() }
        assertEquals(DiagnosticSeverity.Error, record.severity)
        assertEquals(DiagnosticFailure(failure), record.failure)
        assertEquals(failure.context, record.failure.context)
        assertEquals(failure.platformDetail, record.failure.platformDetail)
        assertTrue(record.values.isEmpty())
        assertFalse(record.toString().contains("private exception message"))
        core.close()
    }

    @Test
    fun `logger absent preserves compatibility diagnostic without evaluating unsafe values`() = runTest {
        val core = BleRuntime(config = BleConfig(logger = null), adapter = { null })
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        val failure = unknownFailure()
        var valuesEvaluated = false

        core.emit(DiagnosticObservation(Severity.Warning, failure, valuesFactory = { _: DiagnosticOptions ->
            valuesEvaluated = true
            mapOf("payload" to DiagnosticValue.Text("raw-address-secret"))
        }))

        runCurrent()
        assertFalse(valuesEvaluated)
        assertEquals(listOf(BleEvent.Diagnostic(Severity.Warning, failure)), events)
        assertFalse(events.single().toString().contains("raw-address-secret"))
        core.close()
    }

    private fun unknownFailure() = BleFailure(
        category = FailureCategory("vendor"),
        code = FailureCode("UNKNOWN_CALLBACK_STATUS"),
        phase = FailurePhase.Operation,
        retryability = Retryability.Unknown,
        context = FailureContext(
            sessionId = "opaque-session",
            connectionGeneration = 9,
            operationId = "opaque-operation",
            operationKind = OperationKind.WriteWithResponse,
        ),
        platformDetail = PlatformDetail.Android(
            domain = "vendor.gatt.callback",
            statusCode = 731,
            apiOperation = "vendorWrite",
        ),
        message = "private exception message",
    )
}
