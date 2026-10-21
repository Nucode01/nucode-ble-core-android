package kr.co.nucode.ble

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kr.co.nucode.ble.internal.model.BleEvent
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
        val topLevelMember = Regex("^public (?:(?:val|var) |fun (?!interface))", RegexOption.MULTILINE)
        val topLevelTypes = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                val packageName = Regex("^package ([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
                    .find(source)?.groupValues?.get(1).orEmpty()
                val types = declaration.findAll(source).map { match -> "$packageName.${match.groupValues[1]}" }
                // top-level 공개 함수·속성(확장 포함)은 파일 facade 클래스 `<파일>Kt` 에 정적 멤버로 들어간다.
                val facade = "$packageName.${file.nameWithoutExtension}Kt".takeIf { topLevelMember.containsMatchIn(source) }
                types + listOfNotNull(facade)
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
            "BleCore", "BleEvent", "ConnState", "ConnectOptions",
            "ReconnectPolicy", "DisconnectReason", "WriteResult", "WriteType",
            // 라이브러리 재연결 시간표 제거(0.2.0 Breaking) — 공개 이름도 다시 나오면 안 된다.
            "BleReconnectPolicy",
        ).forEach { forbidden ->
            // 패키지와 무관하게 이름으로 찾는다 — 이 타입들은 internal 하위 패키지로 옮겨졌으므로
            // 공개 패키지 FQN 만 보면 어느 패키지에서든 다시 공개되는 것을 놓친다.
            assertFalse(
                "legacy public API remains: $forbidden",
                Regex("[.$]${Regex.escape(forbidden)}([^A-Za-z0-9_$]|$)").containsMatchIn(dump),
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
        val sources = listOf("PublicModels.kt", "AdvertisementData.kt", "BleClient.kt", "BleSession.kt").map { name ->
            java.io.File("src/main/kotlin/kr/co/nucode/ble/$name").readText()
        }.joinToString("\n")

        listOf("android\\.", "BluetoothGatt", "(?<!Ble)\\bConnectOptions\\b", "\\b(?:Ble)?ReconnectPolicy\\b").forEach {
            forbidden -> assertFalse(
                "forbidden public API identifier: $forbidden",
                Regex(forbidden).containsMatchIn(sources),
            )
        }
        assertTrue(sources.contains("BleConnectOptions"))
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

        listOf(BleClient::class.java, BleSession::class.java).forEach { surface ->
            surface.methods.filter { it.declaringClass == surface }.forEach { method ->
                (method.genericParameterTypes.toList() + method.genericReturnType).forEach { type ->
                    assertFalse(type.typeName.contains("ConnectOptions") && !type.typeName.contains("BleConnectOptions"))
                    assertFalse(type.typeName.contains("ReconnectPolicy"))
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

        listOf("android\\.", "Bluetooth", "Throwable", "Exception", "PeripheralId").forEach { forbidden ->
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
        val eventSource = java.io.File("src/main/kotlin/kr/co/nucode/ble/internal/model/BleEvent.kt").readText()
        val observationDeclaration = eventSource.substringAfter("internal class DiagnosticObservation")
            .substringBefore("\n}\n") // 선언 본문 끝(0열 닫는 괄호)까지

        assertFalse(observationDeclaration.contains(": BleEvent"))
        assertFalse(BleEvent::class.java.declaredClasses.any { it.simpleName == "DiagnosticObservation" })
    }

    private fun assertCommonType(type: Type) {
        val name = type.typeName
        listOf("android.", "BluetoothGatt", "ConnectOptions", "ReconnectPolicy").forEach { forbidden ->
            val allowed = forbidden == "ConnectOptions" && name.contains("BleConnectOptions")
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
