package kr.co.nucode.ble

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsContractTest {
    @Test
    fun `diagnostic defaults keep raw capture disabled`() {
        val options = DiagnosticOptions()

        assertFalse(options.rawPayloadsEnabled)
        assertEquals(DiagnosticOptions.DEFAULT_MAX_RAW_PAYLOAD_BYTES, options.maxRawPayloadBytes)
    }

    @Test
    fun `Sensitive exact characteristic wins over Safe`() {
        val characteristic = characteristic()
        val options = DiagnosticOptions(
            rawPayloadsEnabled = true,
            safeCharacteristics = setOf(characteristic),
            sensitiveCharacteristics = setOf(characteristic),
        )

        assertEquals(DiagnosticSensitivity.Sensitive, options.sensitivityFor(characteristic))
        assertEquals(DiagnosticSensitivity.Unknown, options.sensitivityFor(characteristic()))
    }

    @Test
    fun `options and record snapshots cannot be mutated through input or getter`() {
        val characteristic = characteristic()
        val source = linkedSetOf(characteristic)
        val options = DiagnosticOptions(safeCharacteristics = source)
        source.clear()
        assertEquals(DiagnosticSensitivity.Safe, options.sensitivityFor(characteristic))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (options.safeCharacteristics as MutableSet<CharacteristicRef>).clear()
        }

        val payloadSource = byteArrayOf(1, 2)
        val payload = DiagnosticValue.Payload(payloadSource, originalLength = 2, truncated = false)
        payloadSource[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), payload.bytes)
        val exposed = payload.bytes
        exposed[0] = 8
        assertArrayEquals(byteArrayOf(1, 2), payload.bytes)

        val record = DiagnosticRecord(DiagnosticSeverity.Error, diagnosticFailure(), mapOf("status" to DiagnosticValue.Number(1)))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (record.values as MutableMap<String, DiagnosticValue>)["other"] = DiagnosticValue.Flag(true)
        }
    }

    @Test
    fun `record strips BleFailure message while retaining unknown platform detail exactly`() {
        val detail = PlatformDetail.Android(domain = "vendor.future", statusCode = 987, symbolicName = "NEW_STATUS")
        val failure = BleFailure(
            category = FailureCategory("transport"),
            code = FailureCode("VENDOR_NEW"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Unknown,
            platformDetail = detail,
            message = "secret exception message",
        )

        val record = DiagnosticRecord(DiagnosticSeverity.Error, DiagnosticFailure(failure))

        assertEquals(detail, record.failure.platformDetail)
        assertFalse(record.toString().contains("secret exception message"))
    }

    @Test
    fun `diagnostic bounds reject invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { DiagnosticOptions(maxRawPayloadBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticOptions(queueCapacity = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticValue.Text("x".repeat(DiagnosticValue.MAX_TEXT_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticValue.Payload(byteArrayOf(1), originalLength = 2, truncated = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticRecord(
                DiagnosticSeverity.Info,
                diagnosticFailure(),
                (1..(DiagnosticRecord.MAX_VALUE_COUNT + 1)).associate { "key$it" to DiagnosticValue.Number(it.toLong()) },
            )
        }
    }

    private fun characteristic(): CharacteristicRef = CharacteristicRef(
        service = ServiceRef(BleUuid(UUID.randomUUID())),
        uuid = BleUuid(UUID.randomUUID()),
    )

    private fun diagnosticFailure(): DiagnosticFailure = DiagnosticFailure(
        category = FailureCategory("transport"),
        code = FailureCode("FAILED"),
        phase = FailurePhase.Operation,
        retryability = Retryability.Unknown,
        context = FailureContext(sessionId = "opaque-session"),
        platformDetail = null,
        writeProgress = null,
    )
}
