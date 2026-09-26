package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공유 자원 소유권 표 — 상태·부작용 있는 공유 자원은 소유자 하나, 쓰는 문 하나.
 *
 * [TABLE] 한 행 = 자원 하나: 소유자 파일, 변경 입구(메서드·선언), 그리고 강제 방식.
 * - [Enforcement.LinkedGuard] 행은 이미 개별 가드가 강제한다. 이 테스트는 다시 검사하지 않고 가드 테스트 이름으로만
 *   연결하며, 그 가드가 `@Test` 바로 뒤 `fun` 으로 남아 있고 `@Ignore` 가 없는지만 확인한다.
 * - [Enforcement.Direct] 행은 이 테스트가 `src/main/kotlin` 을 읽어 주석·문자열을 지운 뒤, 받는 쪽 접미사 정규식과
 *   행의 변경 입구(`.`·`?.`·`!!.` 호출, `::` 참조)로 직접 검사한다. 비교 단위는 "파일 → 메서드 집합" 이다 — 소유자
 *   파일은 자유(단 1건 이상 매치 — 이름이 바뀌어 무신호 통과하지 않게), 비소유 파일은 허용 목록(KNOWN)과 정확히
 *   같아야 한다. 새 위반도, 고쳐져 목록에서 빼야 할 항목도 red 다.
 * - [Enforcement.Declaration] 행은 클래스 본문·최상위의 변경 가능 generation 카운터 선언을 검사한다.
 *
 * 한계 — 다음은 잡지 못한다. 소유자를 실제로 세우는 단계에서 가시성·타입이 이 검사를 대체한다.
 * - 받는 쪽이 드러나지 않는 호출: 별칭 변수(`val s = session.subscriptionState; s.markActive(...)`),
 *   `with(x) { drain() }`·`apply`/`run`/`let` 블록, 자원 타입의 확장 함수, 문자열 템플릿 안 호출.
 * - `val`/`AtomicLong` 카운터, generation 이 아닌 이름(gen, epoch)의 카운터.
 * - `src/main/kotlin` 밖의 `.kt` 소스.
 * - 주석·문자열 제거 순서의 오류: 문자열 안 블록 주석 시작 기호(슬래시·별), `//` 주석 안 `"""`, 문자 리터럴 `'"'` 가 실제 코드를 지울 수 있다
 *   (한 번 훑는 lexer 는 보류된 advisory).
 */
class OwnershipTest {

    private sealed interface Enforcement {
        data class LinkedGuard(val testFile: String, val testNames: List<String>) : Enforcement
        data class Direct(
            /** 받는 쪽 이름 정규식(접미사). 메서드는 행의 entryPoints 에서 만든다. */
            val receivers: String,
            /** 비소유 파일 → 현재 허용된 메서드 집합. */
            val known: Map<String, Set<String>>,
            /** 받는 쪽 없이 `이름(` 으로 찾는 입구(entryPoints 의 부분집합). */
            val bareCalls: Set<String> = emptySet(),
        ) : Enforcement
        data class Declaration(
            /** 비소유 파일 → 현재 허용된 선언 이름 집합. */
            val known: Map<String, Set<String>>,
            /** 다른 자원의 번호라 이 행에서 빼는 선언 이름. */
            val excludedNames: Set<String>,
        ) : Enforcement
    }

    private data class Row(
        val resource: String,
        val ownerFiles: Set<String>,
        val entryPoints: Set<String>,
        val enforcement: Enforcement,
    )

    @Test
    fun `연결된 기존 가드 테스트가 이름 그대로 @Test 로 남아 있고 @Ignore 가 없다`() {
        val missing = TABLE.flatMap { row ->
            val guard = row.enforcement as? Enforcement.LinkedGuard ?: return@flatMap emptyList()
            val file = File(TEST_ROOT, guard.testFile)
            if (!file.isFile) return@flatMap listOf("${row.resource}: ${guard.testFile} 파일 없음")
            val source = file.readText()
            guard.testNames.mapNotNull { name ->
                val m = Regex("""((?:[ \t]*@[\w.]+(?:\([^)\n]*\))?[ \t]*\n)*)[ \t]*@Test\s*fun\s+`${Regex.escape(name)}`""")
                    .find(source)
                when {
                    m == null -> "${row.resource}: ${guard.testFile} 에 @Test fun `$name` 없음"
                    Regex("""@(?:[\w.]+\.)?Ignore\b""").containsMatchIn(m.groupValues[1]) -> "${row.resource}: ${guard.testFile} 의 `$name` 에 @Ignore"
                    else -> null
                }
            }
        }
        assertTrue("가드 연결이 끊겼다 — 표를 갱신: $missing", missing.isEmpty())
    }

    @Test
    fun `강제 없는 자원의 변경 입구는 소유자와 허용 목록 밖에서 호출되지 않는다`() {
        val sources = mainSources()
        assertTrue("스캔한 main 파일이 없다 — 작업 디렉터리 확인", sources.isNotEmpty())
        val problems = TABLE.flatMap { row ->
            val direct = row.enforcement as? Enforcement.Direct ?: return@flatMap emptyList()
            val matches = sources.mapValues { (_, code) -> directMatches(row, direct, code) }
            val ownerHits = matches.filterKeys { it in row.ownerFiles }.values.sumOf { it.size }
            // 소유자가 선언 파일뿐이라 스스로 부르지 않는 행(구독)은 KNOWN 항목이 정확 일치로 무신호 통과를 막는다.
            val ownerProblem = if (ownerHits == 0 && direct.known.isEmpty()) {
                listOf("[${row.resource}] 패턴이 소유자(${row.ownerFiles.sorted()})에서 더 이상 매치하지 않는다 — 이름이 바뀌었으면 표를 갱신")
            } else {
                emptyList()
            }
            val actual = matches.filterKeys { it !in row.ownerFiles }.filterValues { it.isNotEmpty() }
            ownerProblem + compare(row, actual, direct.known)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `변경 가능 generation 카운터는 ConnectionCoordinator 와 KNOWN 기준선에만 있다`() {
        val sources = mainSources()
        assertTrue("스캔한 main 파일이 없다 — 작업 디렉터리 확인", sources.isNotEmpty())
        val problems = TABLE.flatMap { row ->
            val decl = row.enforcement as? Enforcement.Declaration ?: return@flatMap emptyList()
            val actual = sources.filterKeys { it !in row.ownerFiles }
                .mapValues { (_, code) -> classLevelGenerationVars(code) - decl.excludedNames }
                .filterValues { it.isNotEmpty() }
            compare(row, actual, decl.known)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** 행의 entryPoints 로 만든 정규식이 이 코드에서 찾은 입구 이름 집합. */
    private fun directMatches(row: Row, direct: Enforcement.Direct, code: String): Set<String> {
        val methods = (row.entryPoints - direct.bareCalls).joinToString("|") { Regex.escape(it) }
        val call = Regex("""\b(?:${direct.receivers})\s*(?:(?:\?|!!)?\.\s*($methods)\s*(?:<[^(){}]*>)?\s*[({]|::\s*($methods)\b)""")
        val found = call.findAll(code).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toMutableSet()
        if (direct.bareCalls.isNotEmpty()) {
            val bare = Regex("""\b(${direct.bareCalls.joinToString("|") { Regex.escape(it) }})\s*\(""")
            found += bare.findAll(code).map { it.groupValues[1] }
        }
        return found
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
     * 클래스(객체) 본문 바로 아래 또는 최상위의 `var ...generation` 선언 이름(생성자 `var` 포함). 함수 본문·람다 안의
     * 지역 var 와 val 은 세지 않는다. 여는 중괄호 앞 머리글(직전 `{`·`}`·`;` 뒤)의 **마지막** 선언 키워드가
     * class/object/interface(`::class` 제외)면 클래스 본문, fun/init/get/set/constructor 거나 없으면 아니다.
     * 괄호 안의 `{`·`}`(기본값 람다 등)는 머리글을 끊지 않고, 그 안은 클래스 본문이 아니다.
     */
    private fun classLevelGenerationVars(code: String): Set<String> {
        val decl = Regex("""\bvar\s+(\w*[gG]eneration)\b""")
        val keyword = Regex("""(::\s*)?\b(class|object|interface|fun|init|get|set|constructor)\b""")
        val found = mutableSetOf<String>()
        // 여는 중괄호마다: 클래스 본문인지, 그 중괄호가 괄호 안에서 열렸을 때의 괄호 깊이(아니면 0).
        val stack = ArrayDeque<Pair<Boolean, Int>>()
        var parenDepth = 0
        var headerStart = 0
        var i = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '{' -> if (parenDepth > 0) {
                    stack.addLast(false to parenDepth)
                    parenDepth = 0
                } else {
                    val last = keyword.findAll(code.substring(headerStart, i))
                        .lastOrNull { it.groupValues[1].isEmpty() }?.groupValues?.get(2)
                    stack.addLast((last == "class" || last == "object" || last == "interface") to 0)
                    headerStart = i + 1
                }
                '}' -> {
                    val saved = stack.removeLastOrNull()?.second ?: 0
                    if (saved > 0) parenDepth = saved else headerStart = i + 1
                }
                ';' -> if (parenDepth == 0) headerStart = i + 1
                'v' -> {
                    val m = decl.matchAt(code, i)
                    if (m != null && (stack.isEmpty() || stack.last().first)) found += m.groupValues[1]
                }
            }
            i++
        }
        return found
    }

    private companion object {
        const val TEST_ROOT = "src/test/kotlin/kr/co/nucode/ble/internal"

        val TABLE = listOf(
            Row(
                "연결 수명주기", setOf("GattConnectionLifecycle.kt", "ConnectStarter.kt", "LinkCallbacks.kt", "LinkTeardown.kt"), setOf("StageAuthority 전이"),
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
                "중복 connect 판정", setOf("ConnectStarter.kt"), setOf("connect"),
                Enforcement.LinkedGuard("GattConnectionLifecycleTest.kt", listOf("중복 connect 판정 코드는 GattConnectionLifecycle 에만 있다")),
            ),
            Row(
                "onLeaveLink", setOf("LinkSetup.kt", "Session.kt"), setOf("onLeaveLink 대입", "onLeaveLink 호출"),
                Enforcement.LinkedGuard("GattConnectionLifecycleTest.kt", listOf("onLeaveLink 대입은 LinkSetup, 호출은 Session 에만 있다")),
            ),
            Row(
                "GATT 작업 큐",
                setOf("GattOperationEngine.kt", "OperationExecutor.kt", "OperationCallbacks.kt", "SubscriptionCommands.kt", "GattOperationScheduler.kt"),
                setOf("enqueue", "startNext", "removeQueued", "takePendingIfMatches", "drain", "poisonCurrentGeneration", "beginNextGeneration"),
                Enforcement.Direct("""\w*[sS]cheduler""", emptyMap()),
            ),
            Row(
                // GattDriver.kt(선언)·AndroidGattDriver.kt(구현)는 `*driver.` 받는 쪽으로 호출하지 않아 제외가 필요 없다.
                "GATT handle 수명", setOf("ConnectStarter.kt", "LinkTeardown.kt"), setOf("connect", "disconnect", "close"),
                Enforcement.Direct("""\w*[dD]river""", emptyMap()),
            ),
            Row(
                // 드라이버 문을 건너뛰는 `session.gatt?.close()` 같은 우회를 막는다 — 플랫폼 handle 은 드라이버 구현만 만진다.
                "플랫폼 GATT handle 원시 API", setOf("AndroidGattDriver.kt"), setOf("connect", "disconnect", "close", "connectGatt"),
                Enforcement.Direct("""\w*[gG]att""", emptyMap(), bareCalls = setOf("connectGatt")),
            ),
            Row(
                "구독 의도·활성", setOf("SubscriptionState.kt"),
                setOf("addDesired", "confirmSubscribed", "removeDesired", "clearDesired", "beginGeneration", "clearActive", "markActive"),
                Enforcement.Direct(
                    """\w*[sS]ubscriptionState""",
                    mapOf(
                        "BleRuntime.kt" to setOf("clearDesired", "confirmSubscribed", "removeDesired"),
                        "LinkSetup.kt" to setOf("markActive", "removeDesired"),
                        "Session.kt" to setOf("beginGeneration", "clearActive"),
                    ),
                ),
            ),
            Row(
                "스캔 시작 기록", setOf("Scanner.kt", "ScanStartLedger.kt"), setOf("tryStart", "clear"),
                Enforcement.Direct("""\w*[lL]edger""", emptyMap()),
            ),
            Row(
                "변경 가능 generation 카운터", setOf("ConnectionCoordinator.kt"), setOf("var generation", "var currentGeneration"),
                Enforcement.Declaration(
                    known = mapOf(
                        "GattOperationScheduler.kt" to setOf("currentGeneration"), // 자체 카운터
                        "SubscriptionState.kt" to setOf("currentGeneration"), // 받아 쓴 사본
                    ),
                    // Scanner.scanGeneration 은 스캔 자원(Scanner 소유)의 별개 번호라 이 행에서 제외한다.
                    excludedNames = setOf("scanGeneration"),
                ),
            ),
        )
    }
}
