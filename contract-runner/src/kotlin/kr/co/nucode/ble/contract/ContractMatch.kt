package kr.co.nucode.ble.contract

/**
 * 기대값 대조(contract/README.md §기대값 대조). 시험 사례는 contract/format-tests/match-cases.json.
 * 목록 전체를 대조하고, 항목 안에서는 `*` 만 특수 글자다. 앞의 `?` 하나는 선택 항목, `??` 는 `?` 로 시작하는 필수 항목.
 */
internal object ContractMatch {

    fun matches(expected: List<String>, actual: List<String>): Boolean {
        val items = expected.map(::parseItem)
        // from(i, j): items[i..] 가 actual[j..] 와 끝까지 맞는가.
        val memo = HashMap<Long, Boolean>()
        fun from(i: Int, j: Int): Boolean {
            if (i == items.size) return j == actual.size
            val key = i.toLong() shl 32 or j.toLong()
            memo[key]?.let { return it }
            val (optional, pattern) = items[i]
            val result = (j < actual.size && glob(pattern, actual[j]) && from(i + 1, j + 1)) ||
                (optional && from(i + 1, j))
            memo[key] = result
            return result
        }
        return from(0, 0)
    }

    /** `await state` 의 패턴 대조. 기대값 한 항목과 같은 규칙이다(`??` 이스케이프 포함). */
    fun entry(pattern: String, entry: String): Boolean = matches(listOf(pattern), listOf(entry))

    /** 한 항목 안의 `*` 대조. */
    private fun glob(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var starP = -1
        var starT = 0
        while (t < text.length) {
            when {
                p < pattern.length && pattern[p] != '*' && pattern[p] == text[t] -> { p++; t++ }
                p < pattern.length && pattern[p] == '*' -> { starP = p++; starT = t }
                starP >= 0 -> { p = starP + 1; t = ++starT }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    private fun parseItem(raw: String): Pair<Boolean, String> = when {
        raw.startsWith("??") -> false to raw.substring(1)
        raw.startsWith("?") -> true to raw.substring(1)
        else -> false to raw
    }
}
