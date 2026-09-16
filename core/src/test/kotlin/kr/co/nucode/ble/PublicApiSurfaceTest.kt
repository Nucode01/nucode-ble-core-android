package kr.co.nucode.ble

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiSurfaceTest {
    @Test
    fun `official connect overloads use only neutral options`() {
        val clientConnect = BleClient::class.java.methods.single { method ->
            method.name.startsWith("connect") &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[1] == BleConnectOptions::class.java
        }
        val sessionConnect = BleSession::class.java.methods.single { method ->
            method.name == "connect" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == BleConnectOptions::class.java
        }
        assertEquals(
            BleConnectOptions::class.java,
            clientConnect.parameterTypes[1],
        )
        assertEquals(
            BleConnectOptions::class.java,
            sessionConnect.parameterTypes[0],
        )
    }

    @Test
    fun `official source files do not expose Android or legacy identifiers`() {
        val sources = listOf("PublicModels.kt", "BleClient.kt", "BleSession.kt").map { name ->
            java.io.File("src/main/kotlin/kr/co/nucode/ble/$name").readText()
        }.joinToString("\n")

        listOf("android\\.", "BluetoothGatt", "DeviceId", "CharRef", "(?<!Ble)\\bConnectOptions\\b", "(?<!Ble)\\bReconnectPolicy\\b").forEach {
            forbidden -> assertFalse(
                "forbidden public API identifier: $forbidden",
                Regex(forbidden).containsMatchIn(sources),
            )
        }
        assertTrue(sources.contains("BleConnectOptions"))
        assertTrue(sources.contains("BleReconnectPolicy"))
    }

    @Test
    fun `public flow surface stays platform neutral`() {
        listOf(BleClient::class.java, BleSession::class.java).forEach { surface ->
            surface.methods.filter { it.declaringClass == surface }.forEach { method ->
                (method.genericParameterTypes.toList() + method.genericReturnType).forEach(::assertCommonType)
            }
        }
    }

    @Test
    fun `common surface uses the intentional Ble option names while legacy names remain compatibility only`() {
        assertTrue(BleConnectOptions::class.java.name.startsWith("kr.co.nucode.ble."))
        assertTrue(BleReconnectPolicy::class.java.name.startsWith("kr.co.nucode.ble."))

        listOf(BleClient::class.java, BleSession::class.java).forEach { surface ->
            surface.methods.filter { it.declaringClass == surface }.forEach { method ->
                (method.genericParameterTypes.toList() + method.genericReturnType).forEach { type ->
                    assertFalse(type.typeName.contains("ConnectOptions") && !type.typeName.contains("BleConnectOptions"))
                    assertFalse(type.typeName.contains("ReconnectPolicy") && !type.typeName.contains("BleReconnectPolicy"))
                }
            }
        }
    }

    @Test
    fun `core manifest delegates permission component and foreground service ownership to applications`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText().lowercase()
        listOf("<uses-permission", "<activity", "<service", "foreground_service").forEach { forbidden ->
            assertFalse("core manifest must not declare $forbidden", manifest.contains(forbidden))
        }
    }

    private fun assertCommonType(type: Type) {
        val name = type.typeName
        listOf("android.", "BluetoothGatt", "DeviceId", "CharRef", "ConnectOptions", "ReconnectPolicy").forEach { forbidden ->
            val allowed = (forbidden == "ConnectOptions" && name.contains("BleConnectOptions")) ||
                (forbidden == "ReconnectPolicy" && name.contains("BleReconnectPolicy"))
            assertFalse("forbidden common API type: $name", !allowed && name.contains(forbidden))
        }
        if (type is ParameterizedType) type.actualTypeArguments.forEach(::assertCommonType)
    }
}
