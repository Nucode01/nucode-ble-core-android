package kr.co.nucode.ble.internal

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DiagnosticFailure
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticSeverity
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
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

    private fun record(code: String) = DiagnosticRecord(
        DiagnosticSeverity.Warning,
        DiagnosticFailure(BleFailure(FailureCategory("test"), FailureCode(code), FailurePhase.Operation, Retryability.Never)),
    )
}
