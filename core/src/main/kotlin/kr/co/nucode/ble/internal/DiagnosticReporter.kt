package kr.co.nucode.ble.internal

import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DiagnosticFailure
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticSeverity
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.Severity

/** Internal-only ingress for logger observations; it is deliberately not a [BleEvent] subtype. */
internal fun interface DiagnosticIngress {
    fun report(
        severity: Severity,
        failure: BleFailure,
        values: (DiagnosticOptions) -> Map<String, DiagnosticValue>,
    )
}

/** Projects one typed failure to the legacy event stream and optional isolated logger. */
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
