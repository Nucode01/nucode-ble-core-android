package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FailureCatalog 가 소스의 실패 코드 리터럴과 일치하고, 규칙 위반이 기준선에서 늘지 않는지 강제한다. */
class FailureCatalogTest {

    @Test
    fun `소스의 실패 코드 리터럴과 카탈로그 코드가 양방향으로 일치한다`() {
        val pattern = Regex("\"([A-Z][A-Z0-9_]{3,})\"")
        val sourceCodes = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FailureCatalog.kt" }
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
            .filterNot { it in NON_CODE_LITERALS }
            .toSet()
        val catalogCodes = FailureCatalog.specs.map { it.code }.toSet()

        assertEquals(
            "소스에만 있음=${(sourceCodes - catalogCodes).sorted()}, 카탈로그에만 있음=${(catalogCodes - sourceCodes).sorted()}",
            sourceCodes,
            catalogCodes,
        )
    }

    @Test
    fun `카탈로그는 중복 코드가 없고 모든 경로에 context와 evidence가 있다`() {
        val codes = FailureCatalog.specs.map { it.code }
        assertEquals("중복 코드: ${codes.groupBy { it }.filter { it.value.size > 1 }.keys}", codes.size, codes.toSet().size)
        FailureCatalog.specs.forEach { spec ->
            assertTrue("${spec.code}: 경로 없음", spec.routes.isNotEmpty())
            spec.routes.forEach { route ->
                assertTrue("${spec.code}: context 비어 있음", route.context.isNotBlank())
                assertTrue("${spec.code} | ${route.context}: evidence 비어 있음", route.evidence.isNotBlank())
            }
        }
    }

    @Test
    fun `규칙 위반 목록이 고정된 기준선과 같다`() {
        val actual = FailureCatalog.violations().map { (spec, route) -> "${spec.code} | ${route.context}" }.toSortedSet()
        assertEquals(KNOWN_VIOLATIONS.toSortedSet(), actual)
    }

    private companion object {
        /** 실패 코드가 아닌 대문자 리터럴. */
        val NON_CODE_LITERALS = setOf(
            // @Suppress 인자
            "DEPRECATION",
            "UNCHECKED_CAST",
            "UNUSED_PARAMETER",
            // platformDetail 에 싣는 GATT status 기호 이름이며 실패 코드가 아님
            "GATT_INSUFFICIENT_AUTHENTICATION",
            "GATT_INSUFFICIENT_AUTHORIZATION",
            "GATT_INSUFFICIENT_ENCRYPTION",
        )

        /** 비어 있어야 한다 — 새 항목이 생기면 규칙 위반이 새로 생긴 것. */
        val KNOWN_VIOLATIONS = emptySet<String>()
    }
}
