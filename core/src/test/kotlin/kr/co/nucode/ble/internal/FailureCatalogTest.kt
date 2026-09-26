package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FailureCatalog 가 소스의 실패 코드 리터럴과 일치하고, 규칙 위반이 기준선에서 늘지 않는지 강제한다. */
class FailureCatalogTest {

    @Test
    fun `카탈로그 위치 문자열의 파일은 main 에 있고 그 식별자의 본문을 가진다`() {
        val mainFiles = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .groupBy { it.name }
        val catalog = mainFiles.getValue("FailureCatalog.kt").single().readText()
        // `File.kt:ident` 와, 파일이 실제로 있는 `Class.method(` 두 형태를 모두 위치로 읽는다.
        val fileForm = Regex("""\b(\w+\.kt):(\w+)""").findAll(catalog)
            .map { it.groupValues[1] to it.groupValues[2] }
        val classForm = Regex("""\b([A-Z]\w*)\.(\w+)\(""").findAll(catalog)
            .map { "${it.groupValues[1]}.kt" to it.groupValues[2] }
            .filter { (fileName, _) -> fileName in mainFiles }
        val locations = (fileForm + classForm).toSet()
        assertTrue("위치 문자열 파싱 수가 너무 적다: ${locations.size}", locations.size > 50)
        val stale = locations.filterNot { (fileName, ident) ->
            mainFiles[fileName].orEmpty().any { holdsBody(it.readText(), ident) }
        }
        assertTrue(
            "본문이 없는 위치 문자열(선언 없음·한 줄 위임·추상 선언): " + stale.joinToString { "${it.first}:${it.second}" },
            stale.isEmpty(),
        )
    }

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
        /**
         * [source] 가 [ident] 의 본문을 가지는가 — 들여쓰기 4칸 이하(최상위·멤버) 선언만 센다(지역 변수 제외).
         * `fun` 은 본문(`{` 또는 `=` 식)이 있어야 하고, 같은 이름을 다른 객체에 넘기는 한 줄 위임
         * (`= x.ident(`)과 본문 없는 추상 선언은 본문으로 치지 않는다.
         */
        fun holdsBody(source: String, ident: String): Boolean {
            val name = Regex.escape(ident)
            val property = Regex("""(?m)^ {0,4}(?:[a-z]+ )*(?:val|var|class|object|interface) +$name\b""")
            if (property.containsMatchIn(source)) return true
            val function = Regex("""(?m)^ {0,4}(?:[a-z]+ )*fun +(?:<[^>]*> *)?(?:[\w.<>?, ]+\.)?$name *\(""")
            return function.findAll(source).any { match -> functionHasOwnBody(source, match.range.last, ident) }
        }

        /** [openParen] 의 짝 괄호 뒤를 읽어 본문 형태를 판정한다. */
        private fun functionHasOwnBody(source: String, openParen: Int, ident: String): Boolean {
            var depth = 0
            var i = openParen
            while (i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                i++
            }
            val rest = source.substring(i + 1).substringBefore('\n')
            return when {
                rest.contains('{') -> true
                rest.contains('=') -> !Regex("""=\s*\w+\.${Regex.escape(ident)}\(""").containsMatchIn(rest)
                else -> false
            }
        }

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
            // platformDetail 에 싣는 연결 해제 이유 기호 이름이며 실패 코드가 아님
            "GATT_CONN_L2C_FAILURE",
            "GATT_CONN_TIMEOUT",
            "GATT_CONN_TERMINATE_PEER_USER",
            "GATT_CONN_TERMINATE_LOCAL_HOST",
            "GATT_CONN_LMP_TIMEOUT",
            "GATT_CONN_FAILED_ESTABLISHMENT",
            "GATT_ERROR",
        )

        /** 비어 있어야 한다 — 새 항목이 생기면 규칙 위반이 새로 생긴 것. */
        val KNOWN_VIOLATIONS = emptySet<String>()
    }
}
