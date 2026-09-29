package kr.co.nucode.ble.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 구조 규칙 — 제품 소스(`src/main/kotlin`)의 import 와 코드 속 FQN 참조를 층으로 분류해 경계를 강제한다.
 *
 * 층: Model(`kr.co.nucode.ble` 정확히 + `internal.model`), Ports(`internal.port`), Driver(`internal.platform.android`),
 * Factory(`kr.co.nucode.ble.android`), Domain(그 밖의 `internal*`).
 * 허용 방향: Model→Model, Ports→Model·Ports, Domain→Model·Ports·Domain, Driver→Model·Ports·Driver, Factory→모두.
 *
 * 참조 = import 줄 + 코드 본문의 `kr.co.nucode.ble...` 이름. 주석과 문자열 리터럴 내용은 지우고 본다.
 * 예외 목록으로 위반을 덮지 않는다. 실패 메시지는 `파일 → 참조` 한 줄씩.
 */
class ArchitectureRulesTest {

    @Test
    fun R1_noPackageCycles() {
        val files = analyze(productSources())
        val guard = signalGuard(files)
        assertTrue("무신호 통과 방지 가드 실패:\n${guard.joinToString("\n")}", guard.isEmpty())
        val cycles = packageCycles(files) + layerCycles(files)
        assertTrue("순환 ${cycles.size}건:\n${cycles.joinToString("\n")}", cycles.isEmpty())
    }

    @Test
    fun R1_domainDoesNotUsePlatformTypes() {
        val files = analyze(productSources())
        assertTrue("검사 대상 파일이 없다", files.any { it.layer in INNER_LAYERS })
        val v = platformTypeViolations(files)
        assertTrue("Model·Ports·Domain 이 플랫폼 타입을 참조한다 ${v.size}건:\n${v.joinToString("\n")}", v.isEmpty())
    }

    @Test
    fun R1_driverDoesNotUseDomain() {
        val files = analyze(productSources())
        assertTrue("Driver 층 파일이 없다", files.any { it.layer == Layer.Driver })
        val v = driverDomainViolations(files)
        assertTrue("Driver 가 Model·Ports·Driver 밖을 참조한다 ${v.size}건:\n${v.joinToString("\n")}", v.isEmpty())
        val usesPorts = files.any { f -> f.layer == Layer.Driver && f.refs.any { f.resolve(it, files)?.let(::layerOfPackage) == Layer.Ports } }
        assertTrue("Driver 층이 Ports 를 참조하지 않는다(Ports 층이 비었거나 Driver 가 포트를 쓰지 않음)", usesPorts)
    }

    @Test
    fun R1_onlyFactoryConstructsDriver() {
        val files = analyze(productSources())
        assertTrue(
            "Driver 층이 선언한 class 가 없다",
            files.any { it.layer == Layer.Driver && CLASS_DECL.containsMatchIn(it.code) },
        )
        val v = driverConstructionViolations(files)
        assertTrue("Driver 생성 규칙 위반 ${v.size}건:\n${v.joinToString("\n")}", v.isEmpty())
    }

    @Test
    fun ruleCheckerCatchesSeededViolations() {
        // 순환: 두 패키지가 서로 import
        val cyc = analyze(
            listOf(
                Src("a/A.kt", "package kr.co.nucode.ble.internal.a\nimport kr.co.nucode.ble.internal.b.B\nclass A"),
                Src("b/B.kt", "package kr.co.nucode.ble.internal.b\nimport kr.co.nucode.ble.internal.a.A\nclass B"),
            ),
        )
        assertEquals(1, packageCycles(cyc).size)

        // 층 순환: Domain → Driver, Driver → Domain
        val layered = analyze(
            listOf(
                Src("d/D.kt", "package kr.co.nucode.ble.internal.d\nimport kr.co.nucode.ble.internal.platform.android.Drv\nclass D"),
                Src("p/Drv.kt", "package kr.co.nucode.ble.internal.platform.android\nimport kr.co.nucode.ble.internal.d.D\nclass Drv"),
            ),
        )
        assertTrue(layerCycles(layered).isNotEmpty())

        // 플랫폼 타입: Domain 의 android import, 코드 속 FQN, Driver import
        val plat = analyze(
            listOf(
                Src(
                    "d/D.kt",
                    "package kr.co.nucode.ble.internal.d\nimport android.content.Context\n" +
                        "import kr.co.nucode.ble.internal.platform.android.Drv\n" +
                        "class D { val t = androidx.core.Foo() }",
                ),
                Src("p/Drv.kt", "package kr.co.nucode.ble.internal.platform.android\nclass Drv"),
            ),
        )
        val platV = platformTypeViolations(plat)
        assertTrue(platV.toString(), platV.any { it.endsWith("→ android.content.Context") })
        assertTrue(platV.toString(), platV.any { it.endsWith("→ androidx.core.Foo") })
        assertTrue(platV.toString(), platV.any { it.endsWith("→ kr.co.nucode.ble.internal.platform.android.Drv") })

        // Driver → Domain
        val drvDom = analyze(
            listOf(
                Src("p/Drv.kt", "package kr.co.nucode.ble.internal.platform.android\nimport kr.co.nucode.ble.internal.session.X\nclass Drv"),
                Src("s/X.kt", "package kr.co.nucode.ble.internal.session\nclass X"),
            ),
        )
        assertEquals(1, driverDomainViolations(drvDom).size)

        // Domain 의 Driver 생성, Factory 에서 생성 안 된 포트 구현 Driver
        val ctor = analyze(
            listOf(
                Src("port/P.kt", "package kr.co.nucode.ble.internal.port\ninternal interface AndroidPort"),
                Src(
                    "p/Drv.kt",
                    "package kr.co.nucode.ble.internal.platform.android\n" +
                        "import kr.co.nucode.ble.internal.port.AndroidPort\n" +
                        "internal class AndroidFoo(val x: Int) : AndroidPort\n" +
                        "internal class AndroidBar(\n    val y: Int,\n) : AndroidPort {\n}\n" +
                        "internal class AndroidBaz(\n    val f: () -> Unit,\n    val g: Int\n) : AndroidPort",
                ),
                Src("d/D.kt", "package kr.co.nucode.ble.internal.d\nclass D { val f = AndroidFoo(1) }"),
                Src("f/F.kt", "package kr.co.nucode.ble.android\nfun make() = AndroidBar(2)"),
            ),
        )
        val ctorV = driverConstructionViolations(ctor)
        assertTrue(ctorV.toString(), ctorV.any { it.startsWith("d/D.kt → AndroidFoo(") })
        assertTrue(ctorV.toString(), ctorV.any { it.contains("AndroidFoo") && it.contains("Factory") })
        assertTrue(ctorV.toString(), ctorV.none { it.contains("AndroidBar") })
        assertTrue(ctorV.toString(), ctorV.any { it.contains("AndroidBaz") && it.contains("Factory") })

        // 주석·문자열 속 가짜 위반은 무시
        val clean = analyze(
            listOf(
                Src(
                    "d/D.kt",
                    "package kr.co.nucode.ble.internal.d\n" +
                        "// import android.content.Context\n" +
                        "/* nested /* import androidx.x.Y */ AndroidFoo(1) kr.co.nucode.ble.internal.session.X */\n" +
                        "/** KDoc [kr.co.nucode.ble.internal.platform.android.Drv] */\n" +
                        "class D { val s = \"android.os.Build AndroidFoo(1) kr.co.nucode.ble.internal.platform.android.Drv\"\n" +
                        "  val r = \"\"\"androidx.a.B\n AndroidFoo(2)\"\"\"\n  val c = '\"'\n  val t = \"a\\\"android.os.X\" }",
                ),
                Src("p/Drv.kt", "package kr.co.nucode.ble.internal.platform.android\nclass AndroidFoo"),
            ),
        )
        assertEquals(emptyList<String>(), platformTypeViolations(clean))
        assertEquals(emptyList<String>(), driverConstructionViolations(clean))
        assertEquals(emptyList<String>(), driverDomainViolations(clean))
        // kr.co.nucode.ble.android 패키지 이름은 플랫폼 SDK 로 오인하지 않는다
        val factoryPkg = analyze(
            listOf(Src("f/F.kt", "package kr.co.nucode.ble.android\nimport kr.co.nucode.ble.android.Y\nclass F")),
        )
        assertEquals(emptyList<String>(), factoryPkg.single().sdk)
    }

    // ---- 분석기 -------------------------------------------------------------------------------------------------

    private enum class Layer { Model, Ports, Domain, Driver, Factory }

    private class Src(val path: String, val text: String)

    private class Parsed(
        val path: String,
        val pkg: String,
        val layer: Layer,
        /** 주석·문자열을 지운 코드(package 줄 포함). */
        val code: String,
        /** `kr.co.nucode.ble...` 이름(import 와 코드 본문). */
        val refs: List<String>,
        /** 플랫폼 SDK(`android.*`, `androidx.*`) 이름. */
        val sdk: List<String>,
    ) {
        /** [ref] 를 알려진 패키지 중 가장 긴 접두사로 푼다. 풀리지 않으면 null. */
        fun resolve(ref: String, all: List<Parsed>): String? =
            all.map { it.pkg }.distinct().filter { ref == it || ref.startsWith("$it.") }.maxByOrNull { it.length }
    }

    private fun analyze(sources: List<Src>): List<Parsed> = sources.map { s ->
        val code = stripCommentsAndStrings(s.text)
        val pkg = PACKAGE_LINE.find(code)?.groupValues?.get(1) ?: error("package 줄이 없다: ${s.path}")
        val layer = layerOfPackage(pkg) ?: error("층에 속하지 않는 패키지: ${s.path} ($pkg)")
        val body = code.replace(PACKAGE_LINE, "")
        Parsed(
            path = s.path,
            pkg = pkg,
            layer = layer,
            code = code,
            refs = ROOT_REF.findAll(body).map { it.value }.distinct().toList(),
            sdk = SDK_REF.findAll(body).map { it.value }.distinct().toList(),
        )
    }

    private fun layerOfPackage(pkg: String): Layer? = when {
        pkg == ROOT || pkg.under("$ROOT.internal.model") -> Layer.Model
        pkg.under("$ROOT.internal.port") -> Layer.Ports
        pkg.under("$ROOT.internal.platform.android") -> Layer.Driver
        pkg.under("$ROOT.android") -> Layer.Factory
        pkg.under("$ROOT.internal") -> Layer.Domain
        else -> null
    }

    private fun String.under(prefix: String) = this == prefix || startsWith("$prefix.")

    private fun allowed(from: Layer): Set<Layer> = when (from) {
        Layer.Model -> setOf(Layer.Model)
        Layer.Ports -> setOf(Layer.Model, Layer.Ports)
        Layer.Domain -> setOf(Layer.Model, Layer.Ports, Layer.Domain)
        Layer.Driver -> setOf(Layer.Model, Layer.Ports, Layer.Driver)
        Layer.Factory -> Layer.values().toSet()
    }

    private fun packageEdges(files: List<Parsed>): Map<String, Set<String>> {
        val graph = files.map { it.pkg }.distinct().associateWith { mutableSetOf<String>() }
        files.forEach { f ->
            f.refs.mapNotNull { f.resolve(it, files) }.filter { it != f.pkg }.forEach { graph.getValue(f.pkg).add(it) }
        }
        return graph
    }

    private fun packageCycles(files: List<Parsed>): List<String> =
        stronglyConnected(packageEdges(files)).filter { it.size > 1 }
            .map { "패키지 순환: " + it.sorted().joinToString(" <-> ") }

    private fun layerCycles(files: List<Parsed>): List<String> {
        val graph = Layer.values().associateWith { mutableSetOf<Layer>() }
        val evidence = HashMap<Pair<Layer, Layer>, MutableList<String>>()
        files.forEach { f ->
            f.refs.mapNotNull { f.resolve(it, files) }.forEach { pkg ->
                val to = layerOfPackage(pkg) ?: return@forEach
                if (to != f.layer) {
                    graph.getValue(f.layer).add(to)
                    evidence.getOrPut(f.layer to to) { mutableListOf() }.add("${f.path} → $pkg")
                }
            }
        }
        return stronglyConnected(graph.mapKeys { it.key.name }.mapValues { e -> e.value.map { it.name }.toSet() })
            .filter { it.size > 1 }
            .map { scc ->
                val members = scc.toSet()
                val lines = evidence.filter { (k, _) -> k.first.name in members && k.second.name in members }
                    .flatMap { (k, v) -> v.sorted().map { "  ${k.first} → ${k.second}: $it" } }
                "층 순환: " + scc.sorted().joinToString(" <-> ") + "\n" + lines.joinToString("\n")
            }
    }

    private fun signalGuard(files: List<Parsed>): List<String> {
        val out = mutableListOf<String>()
        val graph = packageEdges(files)
        val edges = graph.values.sumOf { it.size }
        if (!(graph.size > 5 && edges > 5)) out += "그래프가 비어 무신호 통과할 수 있다: 패키지 ${graph.size}, 간선 $edges"
        Layer.values().filter { layer -> files.none { it.layer == layer } }.forEach { out += "$it 층에 파일이 없다" }
        return out
    }

    private fun platformTypeViolations(files: List<Parsed>): List<String> = files.filter { it.layer in INNER_LAYERS }.flatMap { f ->
        f.sdk.map { "${f.path} → $it" } +
            f.refs.filter { f.resolve(it, files)?.let(::layerOfPackage) == Layer.Driver }.map { "${f.path} → $it" }
    }.sorted()

    private fun driverDomainViolations(files: List<Parsed>): List<String> =
        files.filter { it.layer == Layer.Driver }.flatMap { f ->
            f.refs.filter { ref ->
                val layer = f.resolve(ref, files)?.let(::layerOfPackage)
                layer != null && layer !in allowed(Layer.Driver)
            }.map { "${f.path} → $it" }
        }.sorted()

    private fun driverConstructionViolations(files: List<Parsed>): List<String> {
        val out = mutableListOf<String>()
        val portNames = files.filter { it.layer == Layer.Ports }
            .flatMap { INTERFACE_DECL.findAll(it.code).map { m -> m.groupValues[1] }.toList() }.toSet()
        val driverClasses = files.filter { it.layer == Layer.Driver }.flatMap { f ->
            CLASS_DECL.findAll(f.code).map { m -> m.groupValues[1] to superTypeNames(f.code, m.range.last + 1) }.toList()
        }
        val names = driverClasses.map { it.first }.toSet()
        for (f in files) {
            if (f.layer == Layer.Driver || f.layer == Layer.Factory) continue
            for (n in names.sorted()) {
                if (constructs(f.code, n)) out += "${f.path} → $n( (Driver 는 Factory 에서만 만든다)"
            }
        }
        val factory = files.filter { it.layer == Layer.Factory }
        for ((name, supers) in driverClasses) {
            if (supers.none { it in portNames }) continue
            if (factory.none { constructs(it.code, name) }) out += "$name → Ports 를 구현하지만 Factory 소스에서 생성되지 않는다"
        }
        return out.sorted()
    }

    private fun constructs(code: String, name: String) = Regex("(?<!\\w)${Regex.escape(name)}\\s*\\(").containsMatchIn(code)

    /** `class Name` 다음 위치에서 상위 타입 목록(첫 깊이 0 `:` 뒤 ~ 본문 `{` 또는 선언 끝)의 대문자 식별자. */
    private fun superTypeNames(code: String, from: Int): List<String> {
        var depth = 0
        var colon = -1
        var i = from
        while (i < code.length) {
            val c = code[i]
            if (c == '(' || c == '<' || c == '[') depth++
            else if (c == ')' || (c == '>' && code.getOrNull(i - 1) != '-') || c == ']') depth--
            else if (depth == 0 && c == '{') break
            else if (depth == 0 && c == ':' && colon < 0) colon = i
            else if (depth == 0 && c == '\n') {
                val soFar = code.substring(from, i).trimEnd()
                val next = code.substring(i).trimStart().firstOrNull()
                if (!(next == ':' || next == '{' || soFar.endsWith(",") || soFar.endsWith(":"))) break
            }
            i++
        }
        if (colon < 0) return emptyList()
        return IDENT_UPPER.findAll(code.substring(colon + 1, i)).map { it.value }.toList()
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

    /** 주석(중첩 블록 포함)과 문자열·문자 리터럴 내용을 공백으로 바꾼다. 줄바꿈은 유지한다. */
    private fun stripCommentsAndStrings(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        fun blank(c: Char) = if (c == '\n') '\n' else ' '
        while (i < n) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> while (i < n && text[i] != '\n') { sb.append(' '); i++ }
                text.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < n) {
                        if (text.startsWith("/*", i)) { depth++; sb.append("  "); i += 2 }
                        else if (text.startsWith("*/", i)) { depth--; sb.append("  "); i += 2; if (depth == 0) break }
                        else { sb.append(blank(text[i])); i++ }
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    sb.append("   "); i += 3
                    while (i < n && !text.startsWith("\"\"\"", i)) { sb.append(blank(text[i])); i++ }
                    if (i < n) { sb.append("   "); i += 3 }
                }
                c == '"' -> {
                    sb.append(' '); i++
                    while (i < n && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < n) { sb.append("  "); i += 2 } else { sb.append(' '); i++ }
                    }
                    if (i < n && text[i] == '"') { sb.append(' '); i++ }
                }
                c == '\'' && i + 1 < n && text[i + 1] == '\\' -> {
                    sb.append("  "); i += 2
                    while (i < n && text[i] != '\'' && text[i] != '\n') { sb.append(' '); i++ }
                    if (i < n && text[i] == '\'') { sb.append(' '); i++ }
                }
                c == '\'' && i + 2 < n && text[i + 2] == '\'' -> { sb.append("   "); i += 3 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun productSources(): List<Src> = File(MAIN_ROOT).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { Src(it.relativeTo(File(MAIN_ROOT)).path, it.readText()) }
        .sortedBy { it.path }
        .toList()

    private companion object {
        const val MAIN_ROOT = "src/main/kotlin"
        const val ROOT = "kr.co.nucode.ble"
        val INNER_LAYERS = setOf(Layer.Model, Layer.Ports, Layer.Domain)
        val PACKAGE_LINE = Regex("(?m)^[ \\t]*package[ \\t]+([\\w.]+)[ \\t]*$")
        val ROOT_REF = Regex("(?<![\\w.])kr\\.co\\.nucode\\.ble(?:\\.\\w+)*")
        val SDK_REF = Regex("(?<![\\w.])(?:android|androidx)(?:\\.[A-Za-z]\\w*)+")
        val CLASS_DECL = Regex("(?<!::)\\bclass\\s+(\\w+)")
        val INTERFACE_DECL = Regex("\\binterface\\s+(\\w+)")
        val IDENT_UPPER = Regex("\\b[A-Z]\\w*")
    }
}
