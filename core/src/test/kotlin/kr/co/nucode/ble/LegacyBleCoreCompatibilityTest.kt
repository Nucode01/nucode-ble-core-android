package kr.co.nucode.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBleCoreCompatibilityTest {
    @Test
    fun `legacy BleCore adapter is removed while the Android factory uses one runtime`() {
        val sourceRoot = java.io.File("src/main/kotlin")
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val factory = java.io.File(
            "src/main/kotlin/kr/co/nucode/ble/android/AndroidBleClientFactory.kt",
        ).readText()

        assertFalse(Regex("\\b(class|interface|object)\\s+BleCore\\b").containsMatchIn(sources))
        assertTrue(factory.contains("DefaultBleClient(BleRuntime(context, config))"))
    }

    @Test
    fun `raw advertisement is absent from all event and public sources`() {
        val eventSource = java.io.File("src/main/kotlin/kr/co/nucode/ble/BleEvent.kt").readText()
        val publicSource = listOf("PublicModels.kt", "BleClient.kt", "BleSession.kt")
            .joinToString("\n") { java.io.File("src/main/kotlin/kr/co/nucode/ble/$it").readText() }

        assertFalse("raw scan event must not exist", eventSource.contains("ScanResult("))
        assertFalse("raw scan event must not exist", eventSource.contains("advData"))
        assertFalse("common API must expose structured advertisement only", publicSource.contains("advData"))
        assertFalse("common API must not expose a raw scan packet", publicSource.contains("rawAdvertisement"))
    }

    @Test
    fun `Android context is confined to the factory construction boundary`() {
        val factorySource = java.io.File(
            "src/main/kotlin/kr/co/nucode/ble/android/AndroidBleClientFactory.kt",
        ).readText()
        assertTrue(factorySource.contains("public fun create(context: Context"))
        assertTrue(factorySource.contains("DefaultBleClient(BleRuntime(context, config))"))
    }

    @Test
    fun `legacy diagnostic event remains the compatibility projection of the typed logger failure`() {
        val failure = BleFailure(
            category = FailureCategory("gatt"),
            code = FailureCode("STATUS_FAILED"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Unknown,
        )

        val event = BleEvent.Diagnostic(Severity.Error, failure)
        val record = DiagnosticRecord(DiagnosticSeverity.Error, DiagnosticFailure(failure))

        assertTrue(event.failure === failure)
        assertEquals(record.failure.category, event.failure.category)
        assertEquals(record.failure.code, event.failure.code)
    }
}
