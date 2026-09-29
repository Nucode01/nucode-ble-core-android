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
    fun `cancel 은 끝나지 않은 async 호출을 cancelled 로 남기고 그 호출의 반환은 남기지 않는다`() {
        val report = run(
            """[$connectToReady, { "call": "write", "char": "nus.rx", "pattern": 4096, "async": true, "name": "w" },
                { "sleep": 100 }, { "cancel": "w" }, { "await": "result", "name": "w" }, { "sleep": 3000 }]""",
            "{}",
        )
        assertEquals(report.toString(), listOf("connect ok", "write nus.rx cancelled"), report.lanes["result"])
    }

    @Test
    fun `이미 끝난 async 호출의 cancel 은 아무것도 남기지 않는다`() {
        val report = run(
            """[$connectToReady, { "call": "read", "char": "probe.value", "async": true, "name": "r" }, { "await": "result", "name": "r" },
                { "cancel": "r" }]""",
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

    // ── 확장 형식(README §형식 넓히기) ──────────────────────────────────────

    private val nusPattern = """{ "call": "write", "char": "nus.rx", "pattern": 1024 }"""

    @Test
    fun `확장 레인은 expect 에 있을 때만 기록하고 출력한다`() {
        val plain = run("[$connectToReady]", """{ "state": ["*"] }""")
        assertFalse(plain.toString(), plain.toString().contains("|adapter|"))
        assertFalse(plain.toString(), plain.toString().contains("|services|"))
        val extended = run("[$connectToReady]", """{ "adapter": ["Ready"], "services": ["*"] }""")
        assertTrue(extended.toString(), extended.toString().contains("|adapter|Ready"))
        assertTrue(extended.toString(), extended.toString().contains("|services|{nus.rx,nus.tx,probe.control,probe.value}"))
    }

    @Test
    fun `services 레인은 연결 전 빈 집합 다음에 기기 정의의 기호를 남긴다`() {
        val report = run("[$connectToReady]", """{ "services": ["{}", "{nus.rx,nus.tx,probe.control,probe.value}"] }""")
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `pattern write 와 rxCheck 는 받은 바이트 수와 순서를 peer 레인에 남긴다`() {
        val report = run(
            """[$connectToReady, $nusPattern, { "device": "rxCheck" }]""",
            """{ "result": ["connect ok", "write nus.rx ok"], "peer": ["rx 1024 ok"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `나눠 쓴 pattern 도 순서가 맞으면 ok 다`() {
        val report = run(
            """[$connectToReady, { "call": "write", "char": "nus.rx", "pattern": 512, "mode": "withoutResponse", "chunking": "fixed", "chunkBytes": 20 },
                { "sleep": 300 }, { "device": "rxCheck" }]""",
            """{ "peer": ["rx 512 ok"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `pattern 이 아닌 쓰기가 섞이면 rxCheck 는 bad 이고 ok 기대는 실패`() {
        val report = run(
            """[$connectToReady, { "call": "write", "char": "nus.rx", "text": "hello" }, { "device": "rxCheck" }]""",
            """{ "peer": ["rx 5 ok"] }""",
        )
        assertFalse(report.toString(), report.passed)
        assertTrue(report.failure!!, report.failure!!.startsWith("peer 레인 불일치"))
        assertEquals(listOf("rx 5 bad"), report.lanes["peer"])
    }

    @Test
    fun `Ready 가 아닐 때 rxCheck 는 실패`() {
        val report = run("""[{ "device": "rxCheck" }]""", """{ "peer": [] }""")
        assertTrue(report.failure!!, report.failure!!.startsWith("steps[0]"))
    }

    @Test
    fun `adapter 단계는 adapter 레인에 바뀐 값을 남긴다`() {
        val report = run(
            """[{ "adapter": "PoweredOff" }, { "await": "adapter", "is": "PoweredOff", "timeoutMs": 1000 }, { "adapter": "Ready" }]""",
            """{ "adapter": ["Ready", "PoweredOff", "Ready"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `형식의 어댑터 값은 모두 sim 에서 만들어지고 Unknown 도 기록된다`() {
        val report = run(
            """[{ "adapter": "Unknown" }, { "await": "adapter", "is": "Unknown", "timeoutMs": 1000 }, { "adapter": "Ready" }]""",
            """{ "adapter": ["Ready", "Unknown", "Ready"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `이름 필터 스캔은 대상 기기를 첫 결과 하나만 found 로 남긴다`() {
        val report = run(
            """[{ "call": "startScan", "filter": { "name": "Nordic_UART_Service" } }, { "await": "found", "count": 1, "timeoutMs": 2000 },
                { "sleep": 500 }, { "call": "stopScan" }, { "await": "scan", "is": "Idle", "timeoutMs": 1000 }]""",
            """{ "scan": ["Idle", "Scanning", "Idle"], "found": ["probe"], "result": ["startScan ok", "stopScan ok"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `광고하지 않는 서비스로 거른 스캔에는 결과가 없다`() {
        val report = run(
            """[{ "call": "startScan", "filter": { "services": ["probe"] } }, { "sleep": 1000 }, { "call": "stopScan" }]""",
            """{ "found": [] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `오지 않는 found 를 기다리면 그 단계에서 실패`() {
        val report = run(
            """[{ "call": "startScan", "filter": { "services": ["probe"] } }, { "await": "found", "count": 1, "timeoutMs": 1000 }]""",
            """{ "found": ["probe"] }""",
        )
        assertTrue(report.failure!!, report.failure!!.startsWith("steps[1]"))
    }

    @Test
    fun `snapshot 은 결과 줄에 어댑터·스캔·세션을 붙인다`() {
        val report = run(
            """[{ "call": "snapshot" }, $connectToReady, { "call": "subscribe", "char": "probe.value" }, { "call": "snapshot" }]""",
            """{ "result": ["snapshot ok adapter=Ready scanning=false sessions=[probe:Disconnected(Initial):{}]", "connect ok",
                "subscribe probe.value ok", "snapshot ok adapter=Ready scanning=false sessions=[probe:Ready:{probe.value}]"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `close 호출은 ok 로 남고 이후 호출은 실패 코드로 남는다`() {
        val report = run(
            """[$connectToReady, { "call": "close" }, { "sleep": 100 }, { "call": "read", "char": "probe.value" }]""",
            """{ "result": ["connect ok", "close ok", "read probe.value fail *"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }
}
