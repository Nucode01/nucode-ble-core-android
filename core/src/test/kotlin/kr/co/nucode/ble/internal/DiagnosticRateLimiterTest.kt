package kr.co.nucode.ble.internal

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DiagnosticFailure
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticSeverity
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.Retryability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRateLimiterTest {
    @Test
    fun `suppresses duplicates and returns exact count at window edge`() {
        var now = 0L
        val limiter = DiagnosticRateLimiter(windowMillis = 10, maxKeys = 2, clockMillis = { now })

        assertTrue(limiter.admit(record("ONE")).allowed)
        assertFalse(limiter.admit(record("ONE")).allowed)
        assertFalse(limiter.admit(record("ONE")).allowed)
        now = 10
        val recovered = limiter.admit(record("ONE"))
        assertTrue(recovered.allowed)
        assertEquals(2, recovered.recoveredSuppressed)
    }

    @Test
    fun `key flood remains bounded and evicts least recently used key`() {
        val limiter = DiagnosticRateLimiter(windowMillis = 10, maxKeys = 2, clockMillis = { 0L })
        limiter.admit(record("ONE"))
        limiter.admit(record("TWO"))
        limiter.admit(record("THREE"))

        assertEquals(2, limiter.trackedKeyCount())
        assertTrue(limiter.admit(record("ONE")).allowed)
    }

    @Test
    fun `same failure in distinct correlation contexts is admitted as a separate event`() {
        val limiter = DiagnosticRateLimiter(windowMillis = 10, maxKeys = 8, clockMillis = { 0L })
        val first = FailureContext(
            sessionId = "session-a",
            connectionGeneration = 1,
            operationId = "operation-a",
            operationKind = OperationKind.Read,
        )

        assertTrue(limiter.admit(record("ONE", first)).allowed)
        assertFalse(limiter.admit(record("ONE", first)).allowed)
        assertTrue(limiter.admit(record("ONE", first.copy(sessionId = "session-b"))).allowed)
        assertTrue(limiter.admit(record("ONE", first.copy(connectionGeneration = 2))).allowed)
        assertTrue(limiter.admit(record("ONE", first.copy(operationId = "operation-b"))).allowed)
        assertTrue(limiter.admit(record("ONE", first.copy(operationKind = OperationKind.WriteWithResponse))).allowed)
    }

    @Test
    fun `failed suppression summary can restore its exact count`() {
        var now = 0L
        val limiter = DiagnosticRateLimiter(windowMillis = 10, maxKeys = 1, clockMillis = { now })
        val record = record("ONE")
        limiter.admit(record)
        repeat(3) { assertFalse(limiter.admit(record).allowed) }
        now = 10
        val recovered = limiter.admit(record)
        assertEquals(3, recovered.recoveredSuppressed)

        limiter.restoreSuppressed(record, recovered.recoveredSuppressed)
        now = 20
        assertEquals(3, limiter.admit(record).recoveredSuppressed)
    }

    @Test
    fun `suppression counter saturates`() {
        var now = 0L
        val limiter = DiagnosticRateLimiter(windowMillis = Long.MAX_VALUE, maxKeys = 1, clockMillis = { now })
        limiter.admit(record("ONE"))
        repeat(3) { limiter.admit(record("ONE")) }
        now = Long.MAX_VALUE
        assertEquals(3, limiter.admit(record("ONE")).recoveredSuppressed)
    }

    @Test
    fun `saturating counter never wraps`() {
        assertEquals(Long.MAX_VALUE, Long.MAX_VALUE.saturatedIncrement())
    }

    private fun record(code: String, context: FailureContext = FailureContext()) = DiagnosticRecord(
        DiagnosticSeverity.Warning,
        DiagnosticFailure(
            BleFailure(
                FailureCategory("test"),
                FailureCode(code),
                FailurePhase.Operation,
                Retryability.Never,
                context = context,
            ),
        ),
    )
}
