package kr.co.nucode.ble.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.DiagnosticFailure
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticSeverity
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.BleFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDispatcherTest {
    @Test
    fun `logger throw does not stop later records`() {
        val calls = AtomicInteger()
        val delivered = CountDownLatch(1)
        val dispatcher = DiagnosticDispatcher(
            logger = BleLogger {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("logger failure")
                delivered.countDown()
            },
            capacity = 2,
        )

        assertTrue(dispatcher.dispatch(record("ONE")))
        assertTrue(dispatcher.dispatch(record("TWO")))
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        dispatcher.close()
    }

    @Test
    fun `throw loop is recovered with one structured sink failure summary`() {
        val calls = AtomicInteger()
        val recovered = CountDownLatch(2)
        val records = mutableListOf<DiagnosticRecord>()
        val dispatcher = DiagnosticDispatcher(
            logger = BleLogger { record ->
                if (calls.incrementAndGet() <= 3) throw IllegalStateException("logger failure")
                synchronized(records) { records += record }
                recovered.countDown()
            },
            capacity = 8,
        )

        assertTrue(dispatcher.dispatch(record("ONE")))
        assertTrue(dispatcher.dispatch(record("TWO")))
        assertTrue(dispatcher.dispatch(record("THREE")))
        assertTrue(dispatcher.dispatch(record("FOUR")))
        assertTrue(recovered.await(2, TimeUnit.SECONDS))
        val summary = synchronized(records) { records.single { it.values["diagnostic_summary"] != null } }
        assertEquals("sink_failure", (summary.values["diagnostic_summary"] as kr.co.nucode.ble.DiagnosticValue.Text).value)
        assertEquals(3L, (summary.values["diagnostic_count"] as kr.co.nucode.ble.DiagnosticValue.Number).value)
        dispatcher.close()
    }

    @Test
    fun `full queue and close never block producer`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = DiagnosticDispatcher(
            logger = BleLogger {
                entered.countDown()
                release.await()
            },
            capacity = 1,
        )

        assertTrue(dispatcher.dispatch(record("BLOCKING")))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(dispatcher.dispatch(record("QUEUED")))
        val started = System.nanoTime()
        assertFalse(dispatcher.dispatch(record("DROPPED")))
        dispatcher.close()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("producer/close waited for logger: $elapsedMillis ms", elapsedMillis < 200)
        release.countDown()
    }

    @Test
    fun `absent logger has no admission work`() {
        val dispatcher = DiagnosticDispatcher(logger = null, capacity = 1)
        assertFalse(dispatcher.dispatch(record("NO_LOGGER")))
        dispatcher.close()
    }

    @Test
    fun `recovery summaries are structured and never recursively summarized`() {
        val delivered = CountDownLatch(3)
        val records = mutableListOf<DiagnosticRecord>()
        val dispatcher = DiagnosticDispatcher(
            logger = BleLogger { record -> synchronized(records) { records += record }; delivered.countDown() },
            capacity = 4,
            limiter = DiagnosticRateLimiter(windowMillis = 10, maxKeys = 2, clockMillis = { clock }),
        )

        clock = 0
        assertTrue(dispatcher.dispatch(record("ONE")))
        assertTrue(dispatcher.dispatch(record("ONE")))
        clock = 10
        assertTrue(dispatcher.dispatch(record("ONE")))
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, synchronized(records) { records.count { it.values["diagnostic_summary"] != null } })
        assertEquals("suppressed", (synchronized(records) { records.first { it.values["diagnostic_summary"] != null } }
            .values["diagnostic_summary"] as kr.co.nucode.ble.DiagnosticValue.Text).value)
        dispatcher.close()
    }

    @Test
    fun `close rejects stale producer storm without blocking`() {
        val dispatcher = DiagnosticDispatcher(logger = BleLogger {}, capacity = 1)
        dispatcher.close()
        repeat(1_000) { assertFalse(dispatcher.dispatch(record("STALE"))) }
    }

    @Test
    fun `queue overflow is reported on the next admitted record`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(3)
        val delivered = CountDownLatch(5)
        val records = mutableListOf<DiagnosticRecord>()
        val dispatcher = DiagnosticDispatcher(
            logger = BleLogger { record ->
                synchronized(records) { records += record }
                entered.countDown()
                if (record.failure.code.value == "BLOCKING") release.await()
                drained.countDown()
                delivered.countDown()
            },
            capacity = 2,
        )

        assertTrue(dispatcher.dispatch(record("BLOCKING")))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(dispatcher.dispatch(record("QUEUED_ONE")))
        assertTrue(dispatcher.dispatch(record("QUEUED_TWO")))
        assertFalse(dispatcher.dispatch(record("DROPPED")))
        release.countDown()
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertTrue(dispatcher.dispatch(record("RECOVERY")))
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        val summary = synchronized(records) {
            records.single { (it.values["diagnostic_summary"] as? kr.co.nucode.ble.DiagnosticValue.Text)?.value == "dropped" }
        }
        assertEquals(1L, (summary.values["diagnostic_count"] as kr.co.nucode.ble.DiagnosticValue.Number).value)
        dispatcher.close()
    }

    private fun record(code: String) = DiagnosticRecord(
        severity = DiagnosticSeverity.Error,
        failure = DiagnosticFailure(
            BleFailure(
                category = FailureCategory("test"),
                code = FailureCode(code),
                phase = FailurePhase.Operation,
                retryability = Retryability.Never,
            ),
        ),
    )

    private companion object {
        var clock: Long = 0
    }
}
