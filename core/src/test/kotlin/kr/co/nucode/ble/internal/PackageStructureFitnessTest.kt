package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 패키지 구조 적합도 — `src/main/kotlin` 의 `package`·`import` 줄만 읽어 구조 규칙을 강제한다.
 *
 * - `android.bluetooth` import 는 플랫폼 어댑터 패키지(`internal/platform/android`, `android`) 안에서만 허용한다.
 *   import 줄만 센다 — 문자열 리터럴 속 `android.bluetooth...` 는 대상이 아니다.
 * - `kr.co.nucode.ble*` 패키지 그래프에 순환이 없어야 한다. import 한 FQN 은 `package` 줄로 모은 알려진 패키지 중
 *   가장 긴 접두사 패키지로 매핑한다(중첩 클래스·최상위 함수·와일드카드 `x.y.*` import 모두 처리). 같은 패키지 참조는 import 가 없어 간선이 아니다.
 *
 * 한계 — FQN 직접 사용(import 없이 `kr.co.nucode.ble.x.Y` 를 코드에 쓰는 경우)은 잡지 못한다.
 */
class PackageStructureFitnessTest {

    @Test
    fun `android bluetooth import 는 플랫폼 어댑터 패키지 안에만 있다`() {
        val offenders = mainFiles().filter { (path, lines) ->
            ALLOWED_ANDROID_DIRS.none { path.startsWith(it) } &&
                imports(lines).any { it.startsWith("android.bluetooth.") || it == "android.bluetooth" }
        }.map { it.first }.sorted()
        assertTrue("플랫폼 어댑터 밖에서 android.bluetooth 를 import 한 파일:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `kr co nucode ble 패키지 그래프에 순환이 없다`() {
        val files = mainFiles().mapNotNull { (_, lines) -> packageOf(lines)?.let { it to imports(lines) } }
        val packages = files.map { it.first }.toSet()
        val graph = packages.associateWith { mutableSetOf<String>() }
        files.forEach { (pkg, imps) ->
            imps.filter { it.startsWith(ROOT_PACKAGE) }.forEach { fqn ->
                val target = packages.filter { fqn.startsWith("$it.") }.maxByOrNull { it.length }
                if (target != null && target != pkg) graph.getValue(pkg).add(target)
            }
        }
        val edgeCount = graph.values.sumOf { it.size }
        assertTrue("그래프가 비어 무신호 통과할 수 있다: 패키지 ${packages.size}, 간선 $edgeCount", packages.size > 5 && edgeCount > 5)
        val cycles = stronglyConnected(graph).filter { it.size > 1 }
        assertTrue(
            "패키지 순환 ${cycles.size}건:\n" + cycles.joinToString("\n") { it.sorted().joinToString(" <-> ") },
            cycles.isEmpty(),
        )
    }

    private fun stronglyConnected(graph: Map<String, Set<String>>): List<List<String>> {
        var index = 0
        val indices = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = HashSet<String>()
        val result = mutableListOf<List<String>>()
        fun visit(v: String) {
            indices[v] = index; low[v] = index; index++
            stack.addLast(v); onStack.add(v)
            for (w in graph[v].orEmpty()) {
                if (w !in indices) { visit(w); low[v] = minOf(low.getValue(v), low.getValue(w)) }
                else if (w in onStack) low[v] = minOf(low.getValue(v), indices.getValue(w))
            }
            if (low[v] == indices[v]) {
                val scc = mutableListOf<String>()
                do { val w = stack.removeLast(); onStack.remove(w); scc.add(w) } while (w != v)
                result.add(scc)
            }
        }
        graph.keys.sorted().forEach { if (it !in indices) visit(it) }
        return result
    }

    /** 경로(`src/main/kotlin` 기준 상대) → 블록 주석 밖의 줄. */
    private fun mainFiles(): List<Pair<String, List<String>>> {
        val root = File(MAIN_ROOT)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { file ->
            var inBlock = false
            val lines = file.readLines().mapNotNull { raw ->
                val line = raw.trim()
                when {
                    inBlock -> { if (line.contains("*/")) inBlock = false; null }
                    line.startsWith("/*") -> { if (!line.contains("*/")) inBlock = true; null }
                    else -> line
                }
            }
            file.relativeTo(root).invariantSeparatorsPath to lines
        }.toList()
    }

    private fun packageOf(lines: List<String>): String? =
        lines.firstNotNullOfOrNull { PACKAGE.matchEntire(it)?.groupValues?.get(1) }

    private fun imports(lines: List<String>): List<String> =
        lines.mapNotNull { IMPORT.matchEntire(it)?.groupValues?.get(1) }

    private companion object {
        const val MAIN_ROOT = "src/main/kotlin"
        const val ROOT_PACKAGE = "kr.co.nucode.ble"
        val ALLOWED_ANDROID_DIRS = listOf("kr/co/nucode/ble/internal/platform/android/", "kr/co/nucode/ble/android/")
        val PACKAGE = Regex("""package\s+([\w.]+)\s*;?\s*(//.*)?""")
        val IMPORT = Regex("""import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+\w+)?\s*;?\s*(//.*)?""")
    }
}
