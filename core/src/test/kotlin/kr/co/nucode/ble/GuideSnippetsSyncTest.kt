package kr.co.nucode.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `guide/` 의 Kotlin 예시가 sample 에서 컴파일되는 코드와 글자 그대로 같은지 강제한다.
 *
 * 예시 블록마다 `GuideSnippets.kt`·`GuideUiSnippets.kt` 의 `// region guide:<이름>` 본문 하나와 같아야 하고,
 * 쓰이지 않는 region 도 없어야 한다. Gradle 스크립트 블록(`// settings.gradle.kts`, `// app/build.gradle.kts`)은
 * 컴파일 대상이 아니라 제외한다. 로컬 전용 `guide/llms-android.md` 는 git 에 없어 검사하지 않는다.
 */
class GuideSnippetsSyncTest {

    @Test
    fun `가이드의 Kotlin 예시는 sample 의 guide region 과 글자 그대로 같다`() {
        val regions = SNIPPET_FILES.flatMap { file ->
            REGION.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }
        }.toMap()
        val blocks = guideDocs().flatMap { doc ->
            KOTLIN_BLOCK.findAll(doc.readText())
                .map { it.groupValues[1] }
                .filterNot { block -> GRADLE_BLOCK_PREFIXES.any { block.startsWith(it) } }
                .map { doc.relativeTo(GUIDE_DIR).path to it }
        }

        assertTrue("guide region 파싱 수가 너무 적다: ${regions.size}", regions.size > 20)
        assertTrue("가이드 예시 블록 파싱 수가 너무 적다: ${blocks.size}", blocks.size > 20)

        val unmatched = blocks.filter { (_, block) -> block !in regions.values }
            .map { (doc, block) -> "$doc: ${block.lineSequence().first()}" }
        assertTrue("sample region 과 다른 가이드 예시:\n${unmatched.joinToString("\n")}", unmatched.isEmpty())

        val used = blocks.map { it.second }.toSet()
        val unused = regions.filterValues { it !in used }.keys.sorted()
        assertTrue("가이드에서 쓰지 않는 guide region: $unused", unused.isEmpty())
    }

    private fun guideDocs(): List<File> =
        GUIDE_DIR.walkTopDown().filter { it.isFile && it.extension == "md" && it.name != "llms-android.md" }.toList()

    private companion object {
        val GUIDE_DIR = File("../guide")
        private val SAMPLE_DIR = File("../sample/src/main/kotlin/kr/co/nucode/ble/sample")
        val SNIPPET_FILES = listOf(File(SAMPLE_DIR, "GuideSnippets.kt"), File(SAMPLE_DIR, "GuideUiSnippets.kt"))
        private const val FENCE = "```"
        val KOTLIN_BLOCK = Regex("${FENCE}kotlin\\n(.*?)$FENCE", RegexOption.DOT_MATCHES_ALL)
        val REGION = Regex("""// region guide:(\S+)\n(.*?)// endregion""", RegexOption.DOT_MATCHES_ALL)
        val GRADLE_BLOCK_PREFIXES = listOf("// settings.gradle.kts", "// app/build.gradle.kts")
    }
}
