package kr.co.nucode.ble.internal

import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DiagnosticFailure
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticSeverity
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.Severity

/**
 * Internal diagnostic ingress (deliberately not a [BleEvent] subtype). [report] is for event-like
 * diagnostics and is projected to the public event stream first; [observe] is logger-only.
 */
internal interface DiagnosticIngress {
    fun report(
        severity: Severity,
        failure: BleFailure,
        values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
    )

    /** Logger-only observation (e.g. per-payload): never projected to the public event stream. */
    fun observe(
        severity: Severity,
        failure: BleFailure,
        values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
    )

    /** Drops everything — default for bridges built without a runtime (tests, standalone seams). */
    object None : DiagnosticIngress {
        override fun report(
            severity: Severity,
            failure: BleFailure,
            values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
        ) = Unit

        override fun observe(
            severity: Severity,
            failure: BleFailure,
            values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
        ) = Unit
    }
}

/**
 * [report] projects one typed failure to the event stream, then to the optional isolated logger;
 * [observe] goes to the logger only.
 */
internal class DiagnosticReporter(
    private val emitCompatibility: (BleEvent.Diagnostic) -> Unit,
    private val dispatcher: DiagnosticDispatcher,
    private val options: DiagnosticOptions,
) : DiagnosticIngress {
    override fun report(
        severity: Severity,
        failure: BleFailure,
        values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
    ) {
        // Keep this projection first: rate limiting/logger outcomes cannot affect public truth.
        emitCompatibility(BleEvent.Diagnostic(severity, failure))
        dispatchToLogger(severity, failure, values)
    }

    /** 관측 진단 — 공개 이벤트 버퍼를 채우지 않도록 logger 로만 보낸다. */
    override fun observe(
        severity: Severity,
        failure: BleFailure,
        values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
    ) = dispatchToLogger(severity, failure, values)

    private fun dispatchToLogger(
        severity: Severity,
        failure: BleFailure,
        values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
    ) {
        if (!dispatcher.isAccepting) return
        val record = try {
            DiagnosticRecord(
                severity = severity.toDiagnosticSeverity(),
                failure = DiagnosticFailure(failure),
                values = values(options),
            )
        } catch (_: Throwable) {
            // A logger-only payload policy must not alter compatibility/lifecycle delivery.
            DiagnosticRecord(severity.toDiagnosticSeverity(), DiagnosticFailure(failure))
        }
        dispatcher.dispatch(record)
    }

    fun report(severity: Severity, failure: BleFailure) = report(severity, failure) { emptyMap() }

    private fun Severity.toDiagnosticSeverity(): DiagnosticSeverity = when (this) {
        Severity.Info -> DiagnosticSeverity.Info
        Severity.Warning -> DiagnosticSeverity.Warning
        Severity.Error -> DiagnosticSeverity.Error
    }
}
