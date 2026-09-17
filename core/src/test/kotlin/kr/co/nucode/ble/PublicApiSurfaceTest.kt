package kr.co.nucode.ble

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiSurfaceTest {
    @Test
    fun `checked in API dump matches JVM signatures of Kotlin public declarations`() {
        val baseline = java.io.File("api/ble-core.api")
        assertTrue("public API baseline must be checked in at ${baseline.path}", baseline.isFile)

        val expected = baseline.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .sorted()
        val actual = publicApiSignatures()

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private fun publicApiSignatures(): List<String> {
        val sourceRoot = java.io.File("src/main/kotlin")
        val declaration = Regex(
            "^public (?:(?:data|sealed|value|fun) )?(?:class|interface|object|enum class) ([A-Za-z0-9_]+)",
            RegexOption.MULTILINE,
        )
        val topLevelTypes = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                val packageName = Regex("^package ([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
                    .find(source)?.groupValues?.get(1).orEmpty()
                declaration.findAll(source).map { match -> "$packageName.${match.groupValues[1]}" }
            }
            .distinct()
            .toList()

        return buildList {
            topLevelTypes.forEach { addPublicSignatures(Class.forName(it)) }
        }.sorted()
    }

    @Test
    fun `legacy entrypoint and models are absent from the public dump`() {
        val dump = java.io.File("api/ble-core.api").readText()
        listOf(
            "BleCore", "BleEvent", "DeviceId", "CharRef", "ConnState", "ConnectOptions",
            "ReconnectPolicy", "DisconnectReason", "WriteResult", "WriteType", "Chunking",
            "Snapshot", "DeviceSnapshot",
        ).forEach { forbidden ->
            val qualifiedName = "kr.co.nucode.ble.$forbidden"
            assertFalse(
                "legacy public API remains: $forbidden",
                Regex("(^|[^A-Za-z0-9_$])${Regex.escape(qualifiedName)}([^A-Za-z0-9_$]|$)")
                    .containsMatchIn(dump),
            )
        }
    }

    @Test
    fun `core source owns no product codec beacon or Android app component`() {
        val sourceRoot = java.io.File("src/main/kotlin")
        val productionDeclarations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { file ->
                file.readLines().filterNot { line -> line.trimStart().startsWith("//") || line.trimStart().startsWith("*") }
                    .joinToString("\n")
            }
        listOf(
            Regex("^package .*\\.(?:codec|beacon)(?:\\.|$)", RegexOption.MULTILINE),
            Regex("\\b(?:class|interface|object)\\s+.*(?:Codec|Beacon)\\b"),
        ).forEach { forbidden -> assertFalse(forbidden.containsMatchIn(productionDeclarations)) }

        val manifest = java.io.File("src/main/AndroidManifest.xml").readText().lowercase()
        listOf("<uses-permission", "<activity", "<service", "foreground_service").forEach { forbidden ->
            assertFalse("core manifest must not own $forbidden", manifest.contains(forbidden))
        }
    }

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

    @Test
    fun `diagnostic public contract does not expose Android or throwable types`() {
        val sources = java.io.File("src/main/kotlin/kr/co/nucode/ble/Diagnostics.kt").readText()

        listOf("android\\.", "Bluetooth", "Throwable", "Exception", "DeviceId", "PeripheralId").forEach { forbidden ->
            assertFalse("forbidden diagnostic API identifier: $forbidden", sources.contains(forbidden))
        }
        assertTrue(BleLogger::class.java.isInterface)
    }

    @Test
    fun `diagnostic record retains typed failure projection without a message escape hatch`() {
        val failure = BleFailure(
            category = FailureCategory("vendor"),
            code = FailureCode("UNKNOWN_STATUS"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Unknown,
            platformDetail = PlatformDetail.Android("vendor.domain", 731),
            message = "private platform exception",
        )

        val diagnosticFailure = DiagnosticFailure(failure)

        assertEquals(failure.category, diagnosticFailure.category)
        assertEquals(failure.code, diagnosticFailure.code)
        assertEquals(failure.platformDetail, diagnosticFailure.platformDetail)
        assertFalse(DiagnosticFailure::class.java.declaredFields.any { it.name.contains("message", ignoreCase = true) })
    }

    @Test
    fun `logger-only observation is outside the public BleEvent hierarchy`() {
        val eventSource = java.io.File("src/main/kotlin/kr/co/nucode/ble/BleEvent.kt").readText()
        val observationDeclaration = eventSource.substringAfter("internal class DiagnosticObservation")
            .substringBefore("/**\n * Structured official projection")

        assertFalse(observationDeclaration.contains(": BleEvent"))
        assertFalse(BleEvent::class.java.declaredClasses.any { it.simpleName == "DiagnosticObservation" })
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

    private fun MutableList<String>.addPublicSignatures(type: Class<*>) {
        val typeFlags = buildList {
            add(Modifier.toString(type.modifiers))
            if (type.isSynthetic) add("synthetic")
        }.filter { it.isNotBlank() }.joinToString(" ")
        add(
            "TYPE $typeFlags ${type.name} extends=${type.genericSuperclass?.typeName ?: "-"} " +
                "implements=${type.genericInterfaces.joinToString(",") { it.typeName }}",
        )

        type.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .filterNot { it.isSynthetic }
            .forEach { constructor ->
                val flags = buildList {
                    add(Modifier.toString(constructor.modifiers))
                    if (constructor.isSynthetic) add("synthetic")
                }.filter { it.isNotBlank() }.joinToString(" ")
                add(
                    "CONSTRUCTOR $flags ${type.name}(" +
                        constructor.genericParameterTypes.joinToString(",") { it.typeName } +
                        ") throws=${constructor.genericExceptionTypes.joinToString(",") { it.typeName }}",
                )
            }
        type.declaredFields
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .filterNot { it.isSynthetic }
            .forEach { field ->
                val flags = buildList {
                    add(Modifier.toString(field.modifiers))
                    if (field.isSynthetic) add("synthetic")
                    if (field.isEnumConstant) add("enum")
                }.filter { it.isNotBlank() }.joinToString(" ")
                add("FIELD $flags ${field.genericType.typeName} ${type.name}.${field.name}")
            }
        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .filterNot { method ->
                method.isSynthetic ||
                    method.name in setOf("equals", "hashCode", "toString")
            }
            .forEach { method ->
                val flags = buildList {
                    add(Modifier.toString(method.modifiers))
                    if (method.isDefault) add("default")
                    if (method.isBridge) add("bridge")
                    if (method.isSynthetic) add("synthetic")
                }.filter { it.isNotBlank() }.joinToString(" ")
                val typeParameters = method.typeParameters.joinToString(",", prefix = "<", postfix = ">") { parameter ->
                    parameter.name + ":" + parameter.bounds.joinToString("&") { it.typeName }
                }
                add(
                    "METHOD $flags $typeParameters ${method.genericReturnType.typeName} ${type.name}.${method.name}(" +
                        method.genericParameterTypes.joinToString(",") { it.typeName } +
                        ") throws=${method.genericExceptionTypes.joinToString(",") { it.typeName }}",
                )
            }
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .forEach { nested -> addPublicSignatures(nested) }
    }
}
