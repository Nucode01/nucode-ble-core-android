package kr.co.nucode.ble.internal

import java.util.LinkedHashMap
import kr.co.nucode.ble.DiagnosticRecord

/**
 * Keeps logger-only observations bounded. Compatibility events have already been emitted before
 * this boundary, so suppressing a record can never change command or state outcomes.
 */
internal class DiagnosticRateLimiter(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = LinkedHashMap<Key, Entry>(maxKeys, 0.75f, true)

    init {
        require(windowMillis > 0) { "windowMillis must be positive" }
        require(maxKeys > 0) { "maxKeys must be positive" }
    }

    @Synchronized
    fun admit(record: DiagnosticRecord): Admission {
        val now = clockMillis()
        val key = Key(record)
        val entry = entries[key]
        if (entry == null) {
            if (entries.size == maxKeys) entries.entries.iterator().apply { next(); remove() }
            entries[key] = Entry(now)
            return Admission(allowed = true)
        }

        // A backwards wall-clock jump starts a fresh window rather than retaining suppression.
        if (now < entry.windowStartedAt || now - entry.windowStartedAt >= windowMillis) {
            val suppressed = entry.suppressed
            entry.windowStartedAt = now
            entry.suppressed = 0
            return Admission(allowed = true, recoveredSuppressed = suppressed)
        }

        entry.suppressed = entry.suppressed.saturatedIncrement()
        return Admission(allowed = false)
    }

    @Synchronized
    internal fun trackedKeyCount(): Int = entries.size

    internal data class Admission(
        val allowed: Boolean,
        val recoveredSuppressed: Long = 0,
    )

    private data class Key(val severity: Any, val category: Any, val code: Any, val phase: Any) {
        constructor(record: DiagnosticRecord) : this(
            record.severity,
            record.failure.category,
            record.failure.code,
            record.failure.phase,
        )
    }

    private data class Entry(var windowStartedAt: Long, var suppressed: Long = 0)

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_000L
        const val DEFAULT_MAX_KEYS = 128
    }
}

internal fun Long.saturatedIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1
