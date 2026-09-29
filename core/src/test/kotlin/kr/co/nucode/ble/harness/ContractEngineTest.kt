package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.contract.ContractFiles
import kr.co.nucode.ble.contract.ContractLoader
import kr.co.nucode.ble.contract.ScenarioEngine
import kr.co.nucode.ble.contract.ScenarioReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 러너가 어긋남을 실패로 잡는지. 통과만 보는 ContractScenarioTest 로는 러너가 늘 PASS 를 내는 결함을 못 찾는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContractEngineTest {

    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    private fun run(steps: String, expect: String): ScenarioReport {
        val peripherals = ContractFiles.repository.peripherals
        val scenario = ContractLoader.loadScenario(
            "t",
            """{ "format": 1, "id": "t", "summary": "s", "status": "draft", "runsOn": ["sim"], "peripheral": "probe",
                 "steps": $steps, "expect": $expect }""",
            peripherals,
        )
        lateinit var report: ScenarioReport
        runTest {
            val h = harness()
            try {
                report = ScenarioEngine(SimTarget(h, peripherals.getValue("probe")), peripherals.getValue("probe")).run(scenario)
            } finally {
                h.close()
            }
        }
        return report
    }

    private val connectToReady = """{ "call": "connect" }, { "await": "state", "is": "Ready" }"""

    @Test
    fun `기대와 다른 레인은 실패`() {
        val report = run("[$connectToReady]", """{ "state": ["Connecting", "Ready"] }""")
        assertFalse(report.toString(), report.passed)
        assertTrue(report.failure!!, report.failure!!.startsWith("state 레인 불일치"))
    }

    @Test
    fun `오지 않는 상태를 기다리면 그 단계에서 실패하고 뒤 단계는 건너뛴다`() {
        val report = run(
            """[$connectToReady, { "await": "state", "is": "Recovering(*)", "timeoutMs": 1000 }, { "call": "disconnect" }]""",
            "{}",
        )
        assertTrue(report.failure!!, report.failure!!.startsWith("steps[2]"))
        assertEquals(listOf("connect ok"), report.lanes["result"])
    }

    @Test
    fun `async 호출은 await result 로 기다린다`() {
        val report = run(
            """[$connectToReady, { "call": "read", "char": "probe.value", "async": true, "name": "r" }, { "await": "result", "name": "r" }]""",
            """{ "result": ["connect ok", "read probe.value ok"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `Ready 가 아닐 때 기기 사건은 실패`() {
        val report = run("""[{ "device": "linkDrop" }]""", "{}")
        assertTrue(report.failure!!, report.failure!!.startsWith("steps[0]"))
    }

    @Test
    fun `await state 패턴은 기대값 항목과 같은 규칙이다`() {
        val report = run("""[{ "call": "connect" }, { "await": "state", "is": "?Ready" }]""", "{}")
        assertTrue(report.toString(), report.passed)
    }
}
