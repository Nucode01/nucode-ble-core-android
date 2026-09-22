package kr.co.nucode.ble.internal

import java.security.MessageDigest
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticSensitivity
import kr.co.nucode.ble.DiagnosticValue

/** Projects a transport payload into the small allowlist accepted by diagnostic records. */
internal class DiagnosticPolicy {
    fun payloadValues(
        options: DiagnosticOptions,
        direction: PayloadDirection,
        characteristic: CharacteristicRef,
        payload: ByteArray,
    ): Map<String, DiagnosticValue> {
        val snapshot = payload.copyOf()
        val values = linkedMapOf<String, DiagnosticValue>(
            "payload_direction" to DiagnosticValue.Text(direction.wireValue),
            "payload_length" to DiagnosticValue.Number(snapshot.size.toLong()),
        )
        when (options.sensitivityFor(characteristic)) {
            DiagnosticSensitivity.Sensitive -> values["payload"] = DiagnosticValue.Redacted
            DiagnosticSensitivity.Safe -> {
                if (options.rawPayloadsEnabled && options.maxRawPayloadBytes > 0) {
                    val captured = snapshot.copyOf(minOf(snapshot.size, options.maxRawPayloadBytes))
                    values["payload"] = DiagnosticValue.Payload(
                        bytes = captured,
                        originalLength = snapshot.size,
                        truncated = captured.size != snapshot.size,
                    )
                } else {
                    values["payload_digest"] = digest(snapshot)
                }
            }
            DiagnosticSensitivity.Unknown -> values["payload_digest"] = digest(snapshot)
        }
        return values
    }

    private fun digest(payload: ByteArray): DiagnosticValue.Text {
        // Do not retain or emit the payload. Limiting digest input keeps callback work bounded.
        val prefix = payload.copyOf(minOf(payload.size, MAX_DIGEST_INPUT_BYTES))
        val hex = MessageDigest.getInstance("SHA-256").digest(prefix).joinToString("") { "%02x".format(it) }
        return DiagnosticValue.Text(hex)
    }

    private companion object {
        const val MAX_DIGEST_INPUT_BYTES = 1_024
    }
}

internal enum class PayloadDirection(internal val wireValue: String) {
    Tx("tx"),
    Rx("rx"),
}
