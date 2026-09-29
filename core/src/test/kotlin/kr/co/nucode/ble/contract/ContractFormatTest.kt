package kr.co.nucode.ble.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** contract/ 형식 1 을 이 러너가 README 대로 읽고 대조하는지. 같은 사례를 iOS 러너(BleContract)도 돌린다. */
class ContractFormatTest {

    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    @Test
    fun `모든 기기 정의와 시나리오를 읽는다`() {
        val repo = ContractFiles.repository
        assertTrue("기기 정의가 없다", repo.peripherals.isNotEmpty())
        assertTrue("시나리오가 없다", repo.scenarioNames.isNotEmpty())
        repo.scenarioNames.forEach { repo.scenario(it) }
    }

    @Test
    fun `대조 규칙 시험 사례를 모두 통과한다`() {
        val root = ContractJson.parse(ContractFiles.repository.readText("format-tests/match-cases.json")) as Map<*, *>
        val cases = root["cases"] as List<*>
        val wrong = cases.map { it as Map<*, *> }.filter { case ->
            @Suppress("UNCHECKED_CAST")
            ContractMatch.matches(case["expected"] as List<String>, case["actual"] as List<String>) != case["match"]
        }
        assertEquals("틀린 사례: $wrong", emptyList<Any>(), wrong)
    }

    @Test
    fun `형식에 어긋난 시나리오는 거부한다`() {
        val peripherals = ContractFiles.repository.peripherals
        val base = """"format": 1, "id": "x", "summary": "s", "status": "draft", "runsOn": ["sim"], "peripheral": "probe""""
        val bad = mapOf(
            "모르는 최상위 키" to """{ $base, "steps": [], "expect": {}, "extra": 1 }""",
            "모르는 단계" to """{ $base, "steps": [{ "jump": 1 }], "expect": {} }""",
            "모르는 call" to """{ $base, "steps": [{ "call": "pair" }], "expect": {} }""",
            "모르는 기호 이름" to """{ $base, "steps": [{ "call": "read", "char": "nope" }], "expect": {} }""",
            "러너 전용 characteristic" to """{ $base, "steps": [{ "call": "read", "char": "probe.control" }], "expect": {} }""",
            "속성 없는 read" to """{ $base, "steps": [{ "call": "read", "char": "nus.rx" }], "expect": {} }""",
            "모르는 레인" to """{ $base, "steps": [], "expect": { "gatt": [] } }""",
            "async 에 name 없음" to """{ $base, "steps": [{ "call": "connect", "async": true }], "expect": {} }""",
            "없는 async 기다림" to """{ $base, "steps": [{ "await": "result", "name": "r" }], "expect": {} }""",
            "모르는 기기 사건" to """{ $base, "steps": [{ "device": "reboot" }], "expect": {} }""",
            "형식 버전" to """{ $base, "steps": [], "expect": {} }""".replace("\"format\": 1", "\"format\": 2"),
        )
        for ((what, text) in bad) {
            try {
                ContractLoader.loadScenario("x", text, peripherals)
                fail("거부해야 한다: $what")
            } catch (expected: ContractFormatException) {
                // 거부됨
            }
        }
        try {
            ContractLoader.loadScenario("y", """{ $base, "steps": [], "expect": {} }""", peripherals)
            fail("id 와 파일 이름이 다르면 거부해야 한다")
        } catch (expected: ContractFormatException) {
            // 거부됨
        }
    }

    @Test
    fun `읽기 규칙 시험 사례를 파일 읽기와 같은 경로로 모두 맞춘다`() {
        val repo = ContractFiles.repository
        val root = ContractJson.parse(repo.readText("format-tests/load-cases.json")) as Map<*, *>
        val peripherals = repo.peripherals
        val wrong = mutableListOf<String>()
        fun accepted(load: () -> Unit) = try { load(); true } catch (e: ContractFormatException) { false }

        val scenarioCases = (root["scenarioCases"] as List<*>).map { it as Map<*, *> }
        val peripheralCases = (root["peripheralCases"] as List<*>).map { it as Map<*, *> }
        assertTrue("시나리오 사례가 없다", scenarioCases.isNotEmpty())
        assertTrue("기기 정의 사례가 없다", peripheralCases.isNotEmpty())
        for (case in scenarioCases) {
            val ok = accepted { ContractLoader.loadScenarioValue("x", case["scenario"], peripherals) }
            if (ok != case["valid"]) wrong += "scenario: ${case["note"]} (valid=${case["valid"]})"
        }
        for (case in peripheralCases) {
            val ok = accepted { ContractLoader.loadPeripheralValue("p", case["peripheral"]) }
            if (ok != case["valid"]) wrong += "peripheral: ${case["note"]} (valid=${case["valid"]})"
        }
        assertEquals("틀린 사례: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `확장 형식의 거부는 읽을 때 잡는다`() {
        val peripherals = ContractFiles.repository.peripherals
        val head = """"format": 1, "id": "x", "summary": "s", "status": "draft", "peripheral": "probe""""
        fun scenario(runsOn: String, steps: String, expect: String = "{}") =
            """{ $head, "runsOn": $runsOn, "steps": $steps, "expect": $expect }"""
        val bad = mapOf(
            "device 가 있는 시나리오의 adapter 단계" to scenario("""["sim","device"]""", """[{ "adapter": "Ready" }]"""),
            "expect 에 없는 scan 기다림" to scenario("""["sim"]""", """[{ "await": "scan", "is": "Idle" }]"""),
            "expect 에 없는 peer 를 쓰는 rxCheck" to scenario("""["sim"]""", """[{ "device": "rxCheck" }]"""),
            "256 의 배수가 아닌 pattern" to scenario("""["sim"]""", """[{ "call": "write", "char": "nus.rx", "pattern": 300 }]"""),
            "chunkBytes 없는 fixed" to scenario("""["sim"]""", """[{ "call": "write", "char": "nus.rx", "text": "a", "chunking": "fixed" }]"""),
            "text 와 pattern 을 함께" to scenario("""["sim"]""", """[{ "call": "write", "char": "nus.rx", "text": "a", "pattern": 256 }]"""),
            "모르는 서비스 기호의 필터" to scenario("""["sim"]""", """[{ "call": "startScan", "filter": { "services": ["nope"] } }]"""),
            "text 가 문자열이 아님" to scenario("""["sim"]""", """[{ "call": "write", "char": "nus.rx", "text": 1 }]"""),
            "앞에 없는 async 이름의 cancel" to scenario("""["sim"]""", """[{ "cancel": "w" }]"""),
            "같은 이름을 두 번 cancel" to scenario(
                """["sim"]""",
                """[{ "call": "read", "char": "probe.value", "async": true, "name": "r" }, { "cancel": "r" }, { "cancel": "r" }]""",
            ),
        )
        for ((what, text) in bad) {
            try {
                ContractLoader.loadScenario("x", text, peripherals)
                fail("거부해야 한다: $what")
            } catch (expected: ContractFormatException) {
                // 거부됨
            }
        }
    }
}
