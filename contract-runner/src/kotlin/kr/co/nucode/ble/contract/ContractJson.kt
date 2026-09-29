package kr.co.nucode.ble.contract

/**
 * 시나리오 파일용 최소 JSON 파서. JVM 단위 테스트의 android.jar 에는 동작하는 org.json 이 없어서 직접 둔다.
 * 객체는 키 순서를 지키는 Map, 배열은 List, 수는 정수면 Long 아니면 Double 이다.
 */
internal object ContractJson {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        if (!reader.atEnd()) reader.fail("JSON 뒤에 남은 글자")
        return value
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun atEnd() = at >= text.length

        fun fail(what: String): Nothing = throw ContractFormatException("JSON 오류(위치 $at): $what")

        fun skipSpace() {
            while (!atEnd() && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            skipSpace()
            if (atEnd()) fail("값이 없다")
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("예상하지 못한 글자 '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) fail("'$word' 이어야 한다")
            at += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            skipSpace()
            if (peek() == '}') { at++; return map }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("객체 키는 문자열이어야 한다")
                val key = string()
                if (key in map) fail("키 '$key' 가 두 번 나온다")
                skipSpace()
                expect(':')
                map[key] = value()
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> fail("',' 또는 '}' 이어야 한다")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            at++
            skipSpace()
            if (peek() == ']') { at++; return list }
            while (true) {
                list += value()
                skipSpace()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> fail("',' 또는 ']' 이어야 한다")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("문자열이 닫히지 않았다")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) fail("이스케이프가 끝나지 않았다")
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) fail("\\u 뒤 네 자리가 없다")
                                val code = text.substring(at, at + 4).toIntOrNull(16) ?: fail("\\u 값이 16진수가 아니다")
                                out.append(code.toChar())
                                at += 4
                            }
                            else -> fail("모르는 이스케이프 '\\$e'")
                        }
                    }
                    else -> if (c < ' ') fail("문자열 안의 제어 문자") else out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = at
            if (peek() == '-') at++
            while (!atEnd() && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val raw = text.substring(start, at)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("수가 아니다: $raw")
        }

        private fun peek(): Char = if (atEnd()) fail("입력이 끝났다") else text[at]

        private fun next(): Char = peek().also { at++ }

        private fun expect(c: Char) {
            if (next() != c) fail("'$c' 이어야 한다")
        }
    }
}
