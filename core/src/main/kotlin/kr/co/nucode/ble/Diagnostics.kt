package kr.co.nucode.ble

import java.util.Collections

/** Severity assigned to a diagnostic observation. */
public enum class DiagnosticSeverity {
    Info,
    Warning,
    Error,
}

/**
 * A bounded, typed diagnostic value. This deliberately has no arbitrary object or throwable, or
 * platform value variant: diagnostics must not become an exception-message escape hatch.
 */
public sealed interface DiagnosticValue {
    /** A short allowlisted textual value. */
    public data class Text(val value: String) : DiagnosticValue {
        init {
            require(value.length <= MAX_TEXT_LENGTH) { "diagnostic text exceeds $MAX_TEXT_LENGTH characters" }
        }
    }

    /** A scalar count, status, or duration. */
    public data class Number(val value: Long) : DiagnosticValue

    /** A scalar flag. */
    public data class Flag(val value: Boolean) : DiagnosticValue

    /** Explicit indication that a value was intentionally withheld. */
    public data object Redacted : DiagnosticValue

    /**
     * Bounded raw bytes admitted only by the internal diagnostic policy. The input and exposed
     * value are copied so an application cannot mutate a record after it has been accepted.
     */
    public class Payload(
        bytes: ByteArray,
        public val originalLength: Int,
        public val truncated: Boolean,
    ) : DiagnosticValue {
        private val bytesCopy: ByteArray = bytes.copyOf()

        public val bytes: ByteArray
            get() = bytesCopy.copyOf()

        init {
            require(bytes.size <= MAX_PAYLOAD_BYTES) { "diagnostic payload exceeds $MAX_PAYLOAD_BYTES bytes" }
            require(originalLength >= bytes.size) { "originalLength must not be less than captured bytes" }
            require(truncated == (originalLength != bytes.size)) {
                "truncated must match whether the payload was shortened"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Payload &&
                bytesCopy.contentEquals(other.bytesCopy) &&
                originalLength == other.originalLength &&
                truncated == other.truncated

        override fun hashCode(): Int {
            var result = bytesCopy.contentHashCode()
            result = 31 * result + originalLength
            result = 31 * result + truncated.hashCode()
            return result
        }

        override fun toString(): String =
            "Payload(bytes=<${bytesCopy.size} bytes>, originalLength=$originalLength, truncated=$truncated)"
    }

    public companion object {
        public const val MAX_TEXT_LENGTH: Int = 512
        public const val MAX_PAYLOAD_BYTES: Int = 1_024
    }
}

/**
 * Immutable public projection of a failure for a diagnostic sink.
 *
 * [BleFailure.message] is intentionally excluded. Platform detail remains typed so unknown
 * platform domains and codes survive diagnostic projection without accepting framework objects.
 */
public data class DiagnosticFailure(
    val category: FailureCategory,
    val code: FailureCode,
    val phase: FailurePhase,
    val retryability: Retryability,
    val context: FailureContext,
    val platformDetail: PlatformDetail?,
    val writeProgress: WriteProgress?,
) {
    public constructor(failure: BleFailure) : this(
        category = failure.category,
        code = failure.code,
        phase = failure.phase,
        retryability = failure.retryability,
        context = failure.context,
        platformDetail = failure.platformDetail,
        writeProgress = failure.writeProgress,
    )
}

/**
 * Immutable bounded value delivered to [BleLogger]. Peripheral identity is deliberately absent;
 * correlation is limited to the opaque values carried by [failure].
 */
public class DiagnosticRecord(
    public val severity: DiagnosticSeverity,
    public val failure: DiagnosticFailure,
    values: Map<String, DiagnosticValue> = emptyMap(),
) {
    private val valuesCopy: Map<String, DiagnosticValue>

    public val values: Map<String, DiagnosticValue>
        get() = valuesCopy

    init {
        require(values.size <= MAX_VALUE_COUNT) { "diagnostic record has more than $MAX_VALUE_COUNT values" }
        values.forEach { (key, _) ->
            require(key.isNotBlank() && key.length <= MAX_VALUE_KEY_LENGTH) {
                "diagnostic value key must be non-blank and at most $MAX_VALUE_KEY_LENGTH characters"
            }
        }
        valuesCopy = Collections.unmodifiableMap(LinkedHashMap(values))
    }

    override fun equals(other: Any?): Boolean =
        other is DiagnosticRecord && severity == other.severity && failure == other.failure && valuesCopy == other.valuesCopy

    override fun hashCode(): Int {
        var result = severity.hashCode()
        result = 31 * result + failure.hashCode()
        result = 31 * result + valuesCopy.hashCode()
        return result
    }

    override fun toString(): String = "DiagnosticRecord(severity=$severity, failure=$failure, values=$valuesCopy)"

    public companion object {
        public const val MAX_VALUE_COUNT: Int = 32
        public const val MAX_VALUE_KEY_LENGTH: Int = 64
    }
}

/** A sink for non-authoritative diagnostic observations. */
public fun interface BleLogger {
    public fun log(record: DiagnosticRecord)
}

/** Classification for an exact [CharacteristicRef] in diagnostics payload policy. */
public enum class DiagnosticSensitivity {
    Unknown,
    Safe,
    Sensitive,
}

/**
 * Immutable diagnostics configuration. Raw capture is disabled by default. A payload may be
 * emitted only when [rawPayloadsEnabled] is true and its exact characteristic resolves to Safe;
 * an entry in [sensitiveCharacteristics] always wins over [safeCharacteristics].
 */
public class DiagnosticOptions(
    public val rawPayloadsEnabled: Boolean = false,
    public val maxRawPayloadBytes: Int = DEFAULT_MAX_RAW_PAYLOAD_BYTES,
    safeCharacteristics: Set<CharacteristicRef> = emptySet(),
    sensitiveCharacteristics: Set<CharacteristicRef> = emptySet(),
    public val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val safeCharacteristicsCopy = Collections.unmodifiableSet(LinkedHashSet(safeCharacteristics))
    private val sensitiveCharacteristicsCopy = Collections.unmodifiableSet(LinkedHashSet(sensitiveCharacteristics))

    public val safeCharacteristics: Set<CharacteristicRef>
        get() = safeCharacteristicsCopy

    public val sensitiveCharacteristics: Set<CharacteristicRef>
        get() = sensitiveCharacteristicsCopy

    init {
        require(maxRawPayloadBytes in 1..DiagnosticValue.MAX_PAYLOAD_BYTES) {
            "maxRawPayloadBytes must be within 1..${DiagnosticValue.MAX_PAYLOAD_BYTES}"
        }
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY) {
            "queueCapacity must be within 1..$MAX_QUEUE_CAPACITY"
        }
    }

    /** Sensitive wins when callers accidentally classify an exact reference both ways. */
    public fun sensitivityFor(characteristic: CharacteristicRef): DiagnosticSensitivity = when {
        characteristic in sensitiveCharacteristicsCopy -> DiagnosticSensitivity.Sensitive
        characteristic in safeCharacteristicsCopy -> DiagnosticSensitivity.Safe
        else -> DiagnosticSensitivity.Unknown
    }

    override fun equals(other: Any?): Boolean =
        other is DiagnosticOptions &&
            rawPayloadsEnabled == other.rawPayloadsEnabled &&
            maxRawPayloadBytes == other.maxRawPayloadBytes &&
            safeCharacteristicsCopy == other.safeCharacteristicsCopy &&
            sensitiveCharacteristicsCopy == other.sensitiveCharacteristicsCopy &&
            queueCapacity == other.queueCapacity

    override fun hashCode(): Int {
        var result = rawPayloadsEnabled.hashCode()
        result = 31 * result + maxRawPayloadBytes
        result = 31 * result + safeCharacteristicsCopy.hashCode()
        result = 31 * result + sensitiveCharacteristicsCopy.hashCode()
        result = 31 * result + queueCapacity
        return result
    }

    public companion object {
        public const val DEFAULT_MAX_RAW_PAYLOAD_BYTES: Int = 256
        public const val DEFAULT_QUEUE_CAPACITY: Int = 64
        public const val MAX_QUEUE_CAPACITY: Int = 1_024
    }
}
