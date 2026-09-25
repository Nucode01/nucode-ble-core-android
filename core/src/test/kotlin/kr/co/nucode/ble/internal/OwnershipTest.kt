package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공유 자원 소유권 표 — 상태·부작용 있는 공유 자원은 소유자 하나, 쓰는 문 하나.
 *
 * [TABLE] 한 행 = 자원 하나: 소유자 파일, 변경 입구(메서드·선언), 그리고 강제 방식.
 * - [Enforcement.LinkedGuard] 행은 이미 개별 가드가 강제한다. 이 테스트는 다시 검사하지 않고 가드 테스트 이름으로만
 *   연결하며, 그 가드가 아직 있는지만 확인한다(이름이 바뀌면 연결이 끊겼다고 red).
 * - [Enforcement.Direct] 행은 이 테스트가 `src/main/kotlin` 을 읽어 주석·문자열을 지운 뒤 받는 쪽 이름을 포함한
 *   정규식으로 직접 검사한다. 비교 단위는 "파일 → 메서드(선언) 집합" 이다 — 소유자 파일은 자유, 비소유 파일은
 *   허용 목록(KNOWN)과 정확히 같아야 한다. 새 위반도, 고쳐져 목록에서 빼야 할 항목도 red 다.
 *
 * 한계: 별칭 변수(`val s = session.subscriptionState; s.markActive(...)`)는 받는 쪽 이름이 달라 잡지 못한다.
 * 소유자를 실제로 세우는 단계에서 가시성·타입이 이 검사를 대체한다.
 */
class OwnershipTest {

    private sealed interface Enforcement {
        data class LinkedGuard(val testFile: String, val testNames: List<String>) : Enforcement
        data class Direct(
            val pattern: Regex,
            /** 비소유 파일 → 현재 허용된 메서드(선언) 집합. */
            val known: Map<String, Set<String>>,
        ) : Enforcement
    }

    private data class Row(
        val resource: String,
        val ownerFiles: Set<String>,
        val entryPoints: Set<String>,
        val enforcement: Enforcement,
    )

    @Test
    fun `연결된 기존 가드 테스트가 이름 그대로 남아 있다`() {
        val missing = TABLE.flatMap { row ->
            val guard = row.enforcement as? Enforcement.LinkedGuard ?: return@flatMap emptyList()
            val source = File(TEST_ROOT, guard.testFile).readText()
            guard.testNames.filterNot { source.contains("`$it`") }
                .map { "${row.resource}: ${guard.testFile} 에 `$it` 없음" }
        }
        assertTrue("가드 연결이 끊겼다 — 표를 갱신: $missing", missing.isEmpty())
    }

    @Test
    fun `강제 없는 자원의 변경 입구는 소유자와 허용 목록 밖에서 호출되지 않는다`() {
        val sources = mainSources()
        assertTrue("스캔한 main 파일이 없다 — 작업 디렉터리 확인", sources.isNotEmpty())
        val problems = TABLE.flatMap { row ->
            if (row === GENERATION_ROW) return@flatMap emptyList() // 선언 검사는 아래 전용 테스트
            val direct = row.enforcement as? Enforcement.Direct ?: return@flatMap emptyList()
            val actual = sources.filterKeys { it !in row.ownerFiles }
                .mapValues { (_, code) -> direct.pattern.findAll(code).map { it.groupValues[1] }.toSet() }
                .filterValues { it.isNotEmpty() }
            compare(row, actual, direct.known)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `변경 가능 generation 카운터는 ConnectionCoordinator 와 KNOWN 기준선에만 있다`() {
        val sources = mainSources()
        assertTrue("스캔한 main 파일이 없다 — 작업 디렉터리 확인", sources.isNotEmpty())
        val row = GENERATION_ROW
        val actual = sources.filterKeys { it !in row.ownerFiles && it !in GENERATION_EXCLUDED }
            .mapValues { (_, code) -> classLevelGenerationVars(code) }
            .filterValues { it.isNotEmpty() }
        val problems = compare(row, actual, GENERATION_KNOWN)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `표의 모든 행은 소유자와 변경 입구를 가진다`() {
        TABLE.forEach {
            assertTrue(it.resource, it.ownerFiles.isNotEmpty() && it.entryPoints.isNotEmpty())
        }
    }

    private fun compare(row: Row, actual: Map<String, Set<String>>, known: Map<String, Set<String>>): List<String> {
        val problems = mutableListOf<String>()
        (actual.keys + known.keys).sorted().forEach { file ->
            val found = actual[file].orEmpty()
            val allowed = known[file].orEmpty()
            (found - allowed).sorted().forEach {
                problems += "[${row.resource}] $file 에서 $it — 소유자(${row.ownerFiles.sorted()})를 경유하거나 표를 갱신"
            }
            (allowed - found).sorted().forEach {
                problems += "[${row.resource}] $file 의 $it 가 사라졌다 — 허용 목록(KNOWN)에서 빼도록 표를 갱신"
            }
        }
        return problems
    }

    /** 파일 이름 → 주석·문자열을 지운 코드. */
    private fun mainSources(): Map<String, String> {
        val files = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // 표는 파일 이름으로 소유자를 적는다 — 같은 이름의 파일이 생기면 한쪽이 가려지므로 막는다.
        val duplicated = files.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(duplicated.isEmpty()) { "같은 이름의 main 파일이 있어 표가 파일을 구분하지 못한다: $duplicated" }
        return files.associate { it.name to strip(it.readText()) }
    }

    private fun strip(code: String): String = code
        .replace(Regex("""(?s)/\*.*?\*/"""), " ")
        .replace(Regex("(?s)\"\"\".*?\"\"\""), "\"\"")
        .replace(Regex(""""(?:\\.|[^"\\\n])*""""), "\"\"")
        .replace(Regex("""'(?:\\.|[^'\\\n])'"""), "' '")
        .replace(Regex("""//[^\n]*"""), "")

    /**
     * 클래스(객체) 본문 바로 아래 또는 최상위의 `var ...generation` 선언 이름. 함수 본문·람다 안의 지역 var 와 val 은
     * 세지 않는다. 여는 중괄호 앞 머리글에 class/object/interface 가 있고 fun 이 없으면 클래스 본문으로 본다.
     */
    private fun classLevelGenerationVars(code: String): Set<String> {
        val decl = Regex("""\bvar\s+(\w*[gG]eneration)\b""")
        val found = mutableSetOf<String>()
        val stack = ArrayDeque<Boolean>()
        var headerStart = 0
        var i = 0
        while (i < code.length) {
            when (code[i]) {
                '{' -> {
                    val header = code.substring(headerStart, i)
                    stack.addLast(Regex("""\b(class|object|interface)\b""").containsMatchIn(header) &&
                        !Regex("""\bfun\b""").containsMatchIn(header))
                    headerStart = i + 1
                }
                '}' -> {
                    stack.removeLastOrNull()
                    headerStart = i + 1
                }
                ';' -> headerStart = i + 1
                'v' -> {
                    val m = decl.matchAt(code, i)
                    if (m != null && (i == 0 || !code[i - 1].isLetterOrDigit()) && (stack.isEmpty() || stack.last())) {
                        found += m.groupValues[1]
                    }
                }
            }
            i++
        }
        return found
    }

    private companion object {
        const val TEST_ROOT = "src/test/kotlin/kr/co/nucode/ble/internal"

        fun receiverCall(receivers: String, methods: String) =
            Regex("""\b(?:$receivers)\s*\??\.\s*($methods)\s*[({]""")

        val GENERATION_KNOWN = mapOf(
            "GattOperationScheduler.kt" to setOf("currentGeneration"), // 자체 카운터
            "SubscriptionState.kt" to setOf("currentGeneration"), // 받아 쓴 사본
        )

        // Scanner.scanGeneration 은 스캔 자원(Scanner 소유)의 별개 번호라 연결 generation 카운터 행에서 제외한다.
        val GENERATION_EXCLUDED = setOf("Scanner.kt")

        val GENERATION_ROW = Row(
            resource = "변경 가능 generation 카운터",
            ownerFiles = setOf("ConnectionCoordinator.kt"),
            entryPoints = setOf("var generation", "var currentGeneration"),
            enforcement = Enforcement.Direct(Regex("""\bvar\s+(\w*[gG]eneration)\b"""), GENERATION_KNOWN),
        )

        val TABLE = listOf(
            Row(
                "연결 수명주기", setOf("GattConnectionLifecycle.kt"), setOf("StageAuthority 전이"),
                Enforcement.LinkedGuard(
                    "GattConnectionLifecycleTest.kt",
                    listOf("StageAuthority 는 정의·전이 파라미터·lifecycle 토큰에만 나온다"),
                ),
            ),
            Row(
                "플랫폼 스캔", setOf("AndroidScanner.kt"), setOf("startScan", "stopScan"),
                Enforcement.LinkedGuard("ScannerContractTest.kt", listOf("플랫폼 스캔 시작·정지는 AndroidScanner 에만 있다")),
            ),
            Row(
                "콜백 귀속", setOf("CallbackAttribution.kt"), setOf("source.matches"),
                Enforcement.LinkedGuard("CallbackAttributionTest.kt", listOf("callback 출처 판정은 CallbackAttribution 에만 있다")),
            ),
            Row(
                "공개 연결 상태 쓰기 · 구독 generation 시작", setOf("Session.kt"), setOf("publishStage"),
                Enforcement.LinkedGuard(
                    "SubscriptionStateTest.kt",
                    listOf(
                        "공개 연결 상태 쓰기는 Session publishStage 에만 있다",
                        "구독 generation 시작은 Session publishStage 에만 있다",
                    ),
                ),
            ),
            Row(
                "ConnStateChanged 생성", setOf("GattConnectionLifecycle.kt"), setOf("publishState"),
                Enforcement.LinkedGuard("GattConnectionLifecycleTest.kt", listOf("ConnStateChanged 는 lifecycle publishState 에서만 만든다")),
            ),
            Row(
                "중복 connect 판정", setOf("GattConnectionLifecycle.kt"), setOf("connect"),
                Enforcement.LinkedGuard("GattConnectionLifecycleTest.kt", listOf("중복 connect 판정 코드는 GattConnectionLifecycle 에만 있다")),
            ),
            Row(
                "onLeaveLink", setOf("LinkSetup.kt", "Session.kt"), setOf("onLeaveLink 대입", "onLeaveLink 호출"),
                Enforcement.LinkedGuard("GattConnectionLifecycleTest.kt", listOf("onLeaveLink 대입은 LinkSetup, 호출은 Session 에만 있다")),
            ),
            Row(
                "GATT 작업 큐", setOf("GattOperationEngine.kt", "GattOperationScheduler.kt"),
                setOf("startNext", "removeQueued", "takePendingIfMatches", "drain", "poisonCurrentGeneration", "beginNextGeneration"),
                Enforcement.Direct(
                    receiverCall(
                        "scheduler|gattOperationScheduler",
                        "startNext|removeQueued|takePendingIfMatches|drain|poisonCurrentGeneration|beginNextGeneration",
                    ),
                    emptyMap(),
                ),
            ),
            Row(
                // GattDriver.kt(선언)·AndroidGattDriver.kt(구현)는 `driver.` 받는 쪽으로 호출하지 않아 제외가 필요 없다.
                "GATT handle 수명", setOf("GattConnectionLifecycle.kt"), setOf("connect", "disconnect", "close"),
                Enforcement.Direct(receiverCall("driver", "connect|disconnect|close"), emptyMap()),
            ),
            Row(
                "구독 의도·활성", setOf("SubscriptionState.kt"),
                setOf("addDesired", "confirmSubscribed", "removeDesired", "clearDesired", "beginGeneration", "clearActive", "markActive"),
                Enforcement.Direct(
                    receiverCall(
                        "subscriptionState",
                        "addDesired|confirmSubscribed|removeDesired|clearDesired|beginGeneration|clearActive|markActive",
                    ),
                    mapOf(
                        "BleRuntime.kt" to setOf("clearDesired", "confirmSubscribed", "removeDesired"),
                        "LinkSetup.kt" to setOf("markActive", "removeDesired"),
                        "Session.kt" to setOf("beginGeneration", "clearActive"),
                    ),
                ),
            ),
            Row(
                "스캔 시작 기록", setOf("Scanner.kt", "ScanStartLedger.kt"), setOf("tryStart", "clear"),
                Enforcement.Direct(receiverCall("startLedger|scanStartLedger", "tryStart|clear"), emptyMap()),
            ),
            GENERATION_ROW,
        )
    }
}
