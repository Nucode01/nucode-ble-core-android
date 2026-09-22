package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPolicyTest {
    private val policy = DiagnosticPolicy()
    private val ref = charRef(UUID.randomUUID(), UUID.randomUUID(), instanceIndex = 1, serviceInstanceIndex = 2)
    private val exact = ref

    @Test
    fun `raw payload requires opt in and exact Safe characteristic`() {
        val bytes = byteArrayOf(1, 2, 3)
        val disabled = policy.payloadValues(DiagnosticOptions(safeCharacteristics = setOf(exact)), PayloadDirection.Tx, ref, bytes)
        assertFalse(disabled.containsKey("payload"))
        assertTrue(disabled["payload_digest"] is DiagnosticValue.Text)

        val wrongInstance = exact.copy(instanceIndex = 0)
        val unknown = policy.payloadValues(
            DiagnosticOptions(rawPayloadsEnabled = true, safeCharacteristics = setOf(wrongInstance)),
            PayloadDirection.Tx,
            ref,
            bytes,
        )
        assertFalse(unknown.containsKey("payload"))
        assertTrue(unknown["payload_digest"] is DiagnosticValue.Text)
    }

    @Test
    fun `Safe payload is bounded and defensively copied`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val values = policy.payloadValues(
            DiagnosticOptions(rawPayloadsEnabled = true, maxRawPayloadBytes = 2, safeCharacteristics = setOf(exact)),
            PayloadDirection.Rx,
            ref,
            bytes,
        )
        bytes[0] = 9
        val payload = values.getValue("payload") as DiagnosticValue.Payload
        assertArrayEquals(byteArrayOf(1, 2), payload.bytes)
        assertEquals(4, payload.originalLength)
        assertTrue(payload.truncated)
        assertEquals("rx", (values.getValue("payload_direction") as DiagnosticValue.Text).value)
    }

    @Test
    fun `Sensitive always redacts even when also Safe`() {
        val values = policy.payloadValues(
            DiagnosticOptions(
                rawPayloadsEnabled = true,
                safeCharacteristics = setOf(exact),
                sensitiveCharacteristics = setOf(exact),
            ),
            PayloadDirection.Rx,
            ref,
            "secret-address-device-name".encodeToByteArray(),
        )
        assertEquals(DiagnosticValue.Redacted, values["payload"])
        assertFalse(values.values.any { it.toString().contains("secret-address-device-name") })
    }
}
