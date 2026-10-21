package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.CharacteristicProperty
import kr.co.nucode.ble.contract.ContractFiles
import kr.co.nucode.ble.contract.ContractLog
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

    private val probeProperties = "{nus.rx=write+writeWithoutResponse,nus.tx=notify,probe.control=read+write,probe.value=notify+read}"

    @Test
    fun `properties 레인은 expect 에 있을 때만 기록하고 연결 전 빈 집합 다음에 기기 정의의 어휘를 남긴다`() {
        val plain = run("[$connectToReady]", """{ "services": ["*"] }""")
        assertFalse(plain.toString(), plain.toString().contains("|properties|"))
        val report = run("[$connectToReady]", """{ "properties": ["{}", "$probeProperties"] }""")
        assertTrue(report.toString(), report.passed)
        val wrong = run("[$connectToReady]", """{ "properties": ["{}", "{nus.rx=write}"] }""")
        assertFalse(wrong.toString(), wrong.passed)
    }

    @Test
    fun `properties 줄은 services 다음 peer 앞에 낸다`() {
        val report = run(
            """[$connectToReady, $nusPattern, { "device": "rxCheck" }]""",
            """{ "peer": ["*"], "properties": ["*"], "services": ["*"] }""",
        )
        val lanes = report.lines().map { it.split('|')[3] }
        assertTrue(report.toString(), lanes.lastIndexOf("services") < lanes.indexOf("properties"))
        assertTrue(report.toString(), lanes.lastIndexOf("properties") < lanes.indexOf("peer"))
    }

    @Test
    fun `properties 어휘 변환은 기기 정의 어휘를 이름순으로 잇는다`() {
        val cases = listOf(
            emptyMap<String, Set<CharacteristicProperty>>() to "{}",
            mapOf("a" to emptySet<CharacteristicProperty>()) to "{a=}",
            mapOf("a" to setOf(CharacteristicProperty.WriteWithResponse)) to "{a=write}",
            mapOf("a" to CharacteristicProperty.entries.toSet()) to "{a=indicate+notify+read+write+writeWithoutResponse}",
            mapOf("b" to setOf(CharacteristicProperty.Notify), "a" to setOf(CharacteristicProperty.Read)) to "{a=read,b=notify}",
        )
        for ((input, expected) in cases) assertEquals(input.toString(), expected, ContractLog.renderProperties(input))
    }

    @Test
    fun `descriptors 레인은 expect 에 있을 때만 기록하고 연결 전 빈 집합 다음에 탐색 순서의 이름을 남긴다`() {
        val plain = run("[$connectToReady]", """{ "services": ["*"] }""")
        assertFalse(plain.toString(), plain.toString().contains("|descriptors|"))
        val report = run("[$connectToReady]", """{ "descriptors": ["{}", "{nus.rx=,nus.tx=ccc,probe.control=cud+echo,probe.value=ccc+cpf}"] }""")
        assertTrue(report.toString(), report.passed)
        val wrong = run("[$connectToReady]", """{ "descriptors": ["{}", "{nus.rx=,nus.tx=ccc,probe.control=echo+cud,probe.value=ccc+cpf}"] }""")
        assertFalse(wrong.toString(), wrong.passed)
    }

    @Test
    fun `descriptors 줄은 properties 다음 adTypes 앞에 낸다`() {
        val report = run(
            """[{ "call": "startScan" }, { "sleep": 300 }, { "call": "stopScan" }, $connectToReady]""",
            """{ "adTypes": ["*"], "descriptors": ["*"], "properties": ["*"] }""",
        )
        val lanes = report.lines().map { it.split('|')[3] }
        assertTrue(report.toString(), lanes.lastIndexOf("properties") < lanes.indexOf("descriptors"))
        assertTrue(report.toString(), lanes.lastIndexOf("descriptors") < lanes.indexOf("adTypes"))
    }

    @Test
    fun `descriptors 줄은 이름순 characteristic 에 탐색 순서 그대로 잇는다`() {
        val cases = listOf(
            emptyMap<String, List<String>>() to "{}",
            mapOf("a" to emptyList<String>()) to "{a=}",
            mapOf("b" to listOf("z", "a"), "a" to listOf("ccc")) to "{a=ccc,b=z+a}",
        )
        for ((input, expected) in cases) assertEquals(input.toString(), expected, ContractLog.renderDescriptors(input))
    }

    @Test
    fun `readDescriptor 결과 줄은 값을 대문자 16진수로 붙이고 쓰기 뒤 읽으면 쓴 값이다`() {
        val report = run(
            """[$connectToReady, { "call": "readDescriptor", "desc": "probe.value/cpf" },
                { "call": "writeDescriptor", "desc": "probe.control/echo", "hex": "a55a01" },
                { "call": "readDescriptor", "desc": "probe.control/echo" }]""",
            """{ "result": ["connect ok", "readDescriptor probe.value/cpf ok 19000027010000",
                "writeDescriptor probe.control/echo ok", "readDescriptor probe.control/echo ok A55A01"] }""",
        )
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

    private val scanUntilAdTypes = """{ "call": "startScan", "filter": { "name": "Nordic_UART_Service" } },
        { "await": "adTypes", "is": "{01,07,09}", "timeoutMs": 2000 }, { "call": "stopScan" }"""

    @Test
    fun `adTypes 레인은 시작할 때 빈 집합을 남기고 expect 에 없으면 기록하지 않는다`() {
        val idle = run("""[{ "sleep": 300 }]""", """{ "adTypes": ["{}"] }""")
        assertTrue(idle.toString(), idle.passed)
        val plain = run(
            """[{ "call": "startScan", "filter": { "name": "Nordic_UART_Service" } }, { "sleep": 500 }, { "call": "stopScan" }]""",
            """{ "found": ["probe"] }""",
        )
        assertTrue(plain.toString(), plain.passed)
        assertFalse(plain.toString(), plain.toString().contains("|adTypes|"))
    }

    @Test
    fun `adTypes 레인은 sim 원본의 Flags·Name·128-bit UUID 를 모으고 startScan 마다 비운다`() {
        val report = run(
            "[$scanUntilAdTypes, $scanUntilAdTypes]",
            """{ "adTypes": ["{}", "{01,07,09}", "{}", "{01,07,09}"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `adTypes 줄은 properties 다음 peer 앞에 낸다`() {
        val report = run(
            """[$scanUntilAdTypes, $connectToReady, $nusPattern, { "device": "rxCheck" }]""",
            """{ "peer": ["*"], "adTypes": ["*"], "properties": ["*"] }""",
        )
        val lanes = report.lines().map { it.split('|')[3] }
        assertTrue(report.toString(), lanes.lastIndexOf("properties") < lanes.indexOf("adTypes"))
        assertTrue(report.toString(), lanes.lastIndexOf("adTypes") < lanes.indexOf("peer"))
    }

    @Test
    fun `adTypes 읽기는 길이 0 에서 끝내고 남은 바이트를 넘는 구조는 버린다`() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        val cases = listOf(
            bytes() to emptySet(),
            bytes(0x02, 0x01, 0x06, 0x03, 0x09, 0x41, 0x42, 0x11, 0x07) + ByteArray(16) to setOf(0x01, 0x09, 0x07),
            bytes(0x02, 0x01, 0x06, 0x00, 0x05, 0xFF, 0, 0, 0, 0) to setOf(0x01),
            bytes(0x02, 0x01, 0x06, 0x09, 0x09, 0x41) to setOf(0x01),
            bytes(0x02, 0x01, 0x06, 0x02) to setOf(0x01),
            bytes(0x01, 0xFF) to setOf(0xFF),
        )
        for ((raw, expected) in cases) assertEquals(raw.joinToString(), expected, ContractLog.adTypes(raw))
    }

    @Test
    fun `adTypes 줄은 두 자리 대문자 16진수를 정렬해 쉼표로 잇는다`() {
        assertEquals("{}", ContractLog.renderAdTypes(emptySet()))
        assertEquals("{01,07,09,FF}", ContractLog.renderAdTypes(setOf(0xFF, 0x09, 0x01, 0x07)))
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
