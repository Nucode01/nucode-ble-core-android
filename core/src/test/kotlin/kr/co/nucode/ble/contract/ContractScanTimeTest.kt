package kr.co.nucode.ble.contract

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.harness.SimTarget
import kr.co.nucode.ble.harness.harness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CANDIDATE-36c: scanTime 레인 판정(none·ok·flat·back)과 엔진 기록 구간(README §기록). */
@OptIn(ExperimentalCoroutinesApi::class)
class ContractScanTimeTest {

    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    private val target = PeripheralId("F0:2F:24:A8:0F:9E")
    private val other = PeripheralId("AA:BB:CC:DD:EE:01")
    private val advertisement = AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null)

    /** 결과 시각을 차례로 넣고 결과마다의 값을 돌려준다. */
    private fun judge(vararg results: Pair<PeripheralId, Long>): List<String> {
        val lane = ContractScanTime(target)
        return results.map { (id, nanos) -> lane.record(ScanResult(id, -50, advertisement, nanos)) }
    }

    @Test
    fun `대상 결과 하나뿐이면 none 이다`() {
        assertEquals(ContractScanTime.NONE, ContractScanTime(target).value)
        assertEquals(listOf("none"), judge(target to 5L))
    }

    @Test
    fun `차이가 모두 0 이면 flat, 양수가 오면 ok 다`() {
        assertEquals(listOf("none", "flat", "flat", "ok"), judge(target to 5L, target to 5L, target to 5L, target to 6L))
    }

    @Test
    fun `음수 차이가 한 번이라도 있으면 그 뒤 양수가 와도 back 이다`() {
        assertEquals(listOf("none", "ok", "back", "back", "back"), judge(target to 1L, target to 2L, target to 1L, target to 9L, target to 9L))
    }

    @Test
    fun `비대상 결과는 판정에 넣지 않고 대상 주소는 대소문자를 무시한다`() {
        val lower = PeripheralId(target.value.lowercase())
        assertEquals(
            listOf("none", "none", "none", "ok"),
            judge(target to 10L, other to 1L, other to 50L, lower to 11L),
        )
    }

    @Test
    fun `CONTRACT 출력 순서에서 scanTime 은 androidAdv 와 peer 사이다`() {
        assertEquals(Lanes.ALL.indexOf(Lanes.ANDROID_ADV) + 1, Lanes.ALL.indexOf(Lanes.SCAN_TIME))
        assertEquals(Lanes.ALL.indexOf(Lanes.SCAN_TIME) + 1, Lanes.ALL.indexOf(Lanes.PEER))
    }

    /** expect 에 scanTime 만 둔 시나리오를 sim 에서 돌린다. [scripts] 는 플랫폼 스캔 시작마다 대상 결과 시각 목록. */
    private fun run(scans: Int, scripts: List<List<Long>>, expected: List<String>): ScenarioReport {
        val peripherals = ContractFiles.repository.peripherals
        val scan = """{ "call": "startScan", "filter": { "name": "BLE_LAB_TEST" } }, { "sleep": 1000 }, { "call": "stopScan" }"""
        val steps = List(scans) { scan }.joinToString(""", { "sleep": 300 }, """)
        val expect = expected.joinToString(",", "[", "]") { "\"$it\"" }
        val scenario = ContractLoader.loadScenario(
            "t",
            """{ "format": 1, "id": "t", "summary": "s", "status": "draft", "runsOn": ["sim"], "peripheral": "probe",
                 "steps": [$steps], "expect": { "scanTime": $expect } }""",
            peripherals,
        )
        lateinit var report: ScenarioReport
        runTest {
            val h = harness(scannerRediscovery = true)
            try {
                val profile = peripherals.getValue("probe")
                val simTarget = SimTarget(h, profile)
                h.fake.scanTimeScripts.addAll(scripts)
                report = ScenarioEngine(simTarget, profile).run(scenario)
            } finally {
                h.close()
            }
        }
        assertEquals(report.toString(), expected, report.lanes[Lanes.SCAN_TIME])
        assertTrue(report.toString(), report.passed)
        return report
    }

    @Test
    fun `found 없이 scanTime 만 기대해도 스캔 하나의 대상 결과 둘이 none ok 로 남는다`() {
        run(scans = 1, scripts = listOf(listOf(1L, 2L)), expected = listOf("none", "ok"))
    }

    @Test
    fun `같은 기기를 여러 번 보고해도 연속으로 같은 값은 하나로 합친다`() {
        run(scans = 1, scripts = listOf(listOf(1L, 2L, 3L, 4L)), expected = listOf("none", "ok"))
    }

    @Test
    fun `스캔마다 대상 결과가 하나여도 startScan 에서 지우지 않아 두 스캔 사이 차이로 ok 가 된다`() {
        run(scans = 2, scripts = listOf(listOf(5L), listOf(9L)), expected = listOf("none", "ok"))
    }

    @Test
    fun `두 스캔에 걸친 판정은 앞 스캔의 flat 뒤 다음 스캔의 양수 차이로 ok 가 된다`() {
        run(scans = 2, scripts = listOf(listOf(5L, 5L), listOf(9L, 9L)), expected = listOf("none", "flat", "ok"))
    }
}
