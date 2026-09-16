package kr.co.nucode.ble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.co.nucode.ble.internal.DiagnosticDispatcher
import kr.co.nucode.ble.internal.DiagnosticReporter

class DiagnosticPayloadTest {
    @Test
    fun `no logger skips payload factory while compatibility diagnostics remain safe`() {
        var captured = false
        val compatibility = mutableListOf<BleEvent.Diagnostic>()
        val reporter = DiagnosticReporter(
            emitCompatibility = compatibility::add,
            dispatcher = DiagnosticDispatcher(logger = null, capacity = 1),
            options = DiagnosticOptions(),
        )
        reporter.report(Severity.Info, failure()) {
            captured = true
            mapOf("payload" to DiagnosticValue.Text("secret address name exception"))
        }
        assertFalse(captured)
        assertEquals(1, compatibility.size)
        assertFalse(compatibility.single().toString().contains("secret address name exception"))
    }

    @Test
    fun `logger receives immutable bounded Safe payload projection`() {
        val delivered = CountDownLatch(1)
        var record: DiagnosticRecord? = null
        val source = byteArrayOf(7, 8, 9)
        val reporter = DiagnosticReporter(
            emitCompatibility = {},
            dispatcher = DiagnosticDispatcher(BleLogger { record = it; delivered.countDown() }, capacity = 1),
            options = DiagnosticOptions(),
        )
        reporter.report(Severity.Info, failure()) {
            source[0] = 1
            mapOf("payload" to DiagnosticValue.Payload(source, 3, false))
        }
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        source[1] = 2
        val payload = record!!.values.getValue("payload") as DiagnosticValue.Payload
        assertArrayEquals(byteArrayOf(1, 8, 9), payload.bytes)
        assertFalse(record.toString().contains("exception sentinel"))
    }

    private fun failure() = BleFailure(
        category = FailureCategory("diagnostic"),
        code = FailureCode("GATT_PAYLOAD_OBSERVED"),
        phase = FailurePhase.Operation,
        retryability = Retryability.Never,
        context = FailureContext(sessionId = "opaque-session", operationId = "opaque-operation"),
        message = "exception sentinel",
    )
}
