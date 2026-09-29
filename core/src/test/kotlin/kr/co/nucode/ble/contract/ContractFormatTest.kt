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
}
