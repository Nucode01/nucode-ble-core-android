package kr.co.nucode.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBleCoreCompatibilityTest {
    @Test
    fun `legacy BleCore remains an explicitly deprecated compatibility facade`() {
        val source = java.io.File("src/main/kotlin/kr/co/nucode/ble/BleCore.kt").readText()
        assertTrue(source.contains("@Deprecated(\"Use AndroidBleClientFactory.create() and BleClient\")"))
        assertTrue(source.contains("public val events: Flow<BleEvent> get() = runtime.events"))
        assertTrue(source.contains("public suspend fun connect(deviceId: DeviceId, options: ConnectOptions"))
        assertTrue(source.contains("public fun close(): Unit = runtime.close()"))
    }

    @Test
    fun `raw advertisement is confined to the deprecated compatibility event`() {
        val eventSource = java.io.File("src/main/kotlin/kr/co/nucode/ble/BleEvent.kt").readText()
        val publicSource = listOf("PublicModels.kt", "BleClient.kt", "BleSession.kt")
            .joinToString("\n") { java.io.File("src/main/kotlin/kr/co/nucode/ble/$it").readText() }

        assertTrue(eventSource.contains("public data class ScanResult(") && eventSource.contains("val advData: ByteArray"))
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
}
