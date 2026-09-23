package kr.co.nucode.ble.internal.diagnostics

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticValue

/**
 * Isolates optional application logging from BLE callbacks. Admission is always a bounded
 * [ArrayBlockingQueue.offer], and the worker is never joined during shutdown.
 */
internal class DiagnosticDispatcher(
    logger: BleLogger?,
    capacity: Int,
    threadName: String = "nucode-ble-diagnostics",
    private val limiter: DiagnosticRateLimiter = DiagnosticRateLimiter(),
) : AutoCloseable {
    private val accepting = AtomicBoolean(true)
    private val admissionLock = Any()
    private val sink: BleLogger? = logger
    private val queue = sink?.let { ArrayBlockingQueue<Envelope>(capacity) }
    private val dropped = AtomicLong()
    private val sinkFailures = AtomicLong()
    private val worker: Thread? = queue?.let { records ->
        Thread({ drain(records, checkNotNull(sink)) }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    /** Avoids producing logger-only values when diagnostics are disabled or closing. */
    val isAccepting: Boolean
        get() = accepting.get() && queue != null

    /** Returns false when logging is disabled, closed, or the bounded queue is full. */
    fun dispatch(record: DiagnosticRecord): Boolean = synchronized(admissionLock) {
        val records = queue ?: return false
        if (!accepting.get()) return false
        val admission = limiter.admit(record)
        if (!admission.allowed) return true
        if (!records.offer(Envelope(record))) {
            limiter.restoreSuppressed(record, admission.recoveredSuppressed)
            dropped.incrementSaturated()
            return false
        }
        if (admission.recoveredSuppressed > 0) {
            if (!offerSummary(records, record, "suppressed", admission.recoveredSuppressed)) {
                limiter.restoreSuppressed(record, admission.recoveredSuppressed)
            }
        }
        val lost = dropped.get()
        if (lost > 0 && offerSummary(records, record, "dropped", lost)) dropped.set(0)
        true
    }

    /** Stops future admission and requests worker termination without flushing or joining. */
    override fun close() {
        synchronized(admissionLock) {
            if (accepting.compareAndSet(true, false)) worker?.interrupt()
        }
    }

    private fun offerSummary(
        records: ArrayBlockingQueue<Envelope>,
        cause: DiagnosticRecord,
        kind: String,
        count: Long,
    ): Boolean = records.offer(Envelope(summary(cause, kind, count), summary = true))

    private fun drain(records: ArrayBlockingQueue<Envelope>, logger: BleLogger) {
        while (accepting.get()) {
            val envelope = try {
                records.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            try {
                logger.log(envelope.record)
            } catch (_: Throwable) {
                // Diagnostics are non-authoritative. A hostile logger cannot escape its worker.
                sinkFailures.incrementSaturated()
                continue
            }
            if (!envelope.summary) {
                val failures = sinkFailures.getAndSet(0)
                if (failures > 0) {
                    try {
                        logger.log(summary(envelope.record, "sink_failure", failures))
                    } catch (_: Throwable) {
                        // Retain both the unreported failures and the failed recovery summary.
                        sinkFailures.addSaturated(failures.saturatedIncrement())
                    }
                }
            }
        }
    }

    private fun summary(cause: DiagnosticRecord, kind: String, count: Long): DiagnosticRecord =
        DiagnosticRecord(
            severity = cause.severity,
            failure = cause.failure,
            values = mapOf(
                "diagnostic_summary" to DiagnosticValue.Text(kind),
                "diagnostic_count" to DiagnosticValue.Number(count),
            ),
        )

    private fun AtomicLong.incrementSaturated() {
        addSaturated(1)
    }

    private fun AtomicLong.addSaturated(amount: Long) {
        while (true) {
            val current = get()
            val updated = if (Long.MAX_VALUE - current < amount) Long.MAX_VALUE else current + amount
            if (current == updated || compareAndSet(current, updated)) return
        }
    }

    private data class Envelope(val record: DiagnosticRecord, val summary: Boolean = false)

    private companion object {
        const val POLL_MILLIS = 100L
    }
}
