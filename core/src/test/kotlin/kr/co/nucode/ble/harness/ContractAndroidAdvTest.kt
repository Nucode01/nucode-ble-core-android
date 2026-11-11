package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.android.AndroidAdvertisingDataStatus
import kr.co.nucode.ble.android.AndroidAdvertisingInfo
import kr.co.nucode.ble.android.AndroidPhy
import kr.co.nucode.ble.android.androidScanRecordBytes
import kr.co.nucode.ble.contract.ContractFiles
import kr.co.nucode.ble.contract.ContractLoader
import kr.co.nucode.ble.contract.ContractLog
import kr.co.nucode.ble.contract.ScenarioEngine
import kr.co.nucode.ble.contract.ScenarioReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CANDIDATE-36b: androidAdv 레인의 렌더·기록 구간과 sim extended 세트(extAdv)의 보임 규칙. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContractAndroidAdvTest {

    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    private fun run(steps: String, expect: String): ScenarioReport {
        val peripherals = ContractFiles.repository.peripherals
        val scenario = ContractLoader.loadScenario(
            "t",
            """{ "format": 1, "id": "t", "summary": "s", "status": "draft", "runsOn": ["sim"], "platforms": ["android"],
                 "peripheral": "probe", "steps": $steps, "expect": $expect }""",
            peripherals,
        )
        lateinit var report: ScenarioReport
        runTest {
            val h = harness(scannerRediscovery = true)
            try {
                report = ScenarioEngine(SimTarget(h, peripherals.getValue("probe")), peripherals.getValue("probe")).run(scenario)
            } finally {
                h.close()
            }
        }
        return report
    }

    private val extAdvOn = """{ "call": "connect" }, { "await": "state", "is": "Ready" }, { "device": "extAdv", "ms": 5000 },
        { "call": "disconnect" }, { "await": "state", "is": "Disconnected(Requested)" }"""

    @Test
    fun `AD 길이는 adTypes 읽기 규칙으로 읽은 구조 바이트 합이다`() {
        assertEquals(120, ContractLog.adLength(extendedSetAdvertiser().scanRecord!!))
        // 0 채움에서 끝난다.
        assertEquals(3, ContractLog.adLength(byteArrayOf(2, 1, 6, 0, 0, 0)))
        // 남은 바이트를 넘는 구조는 버린다.
        assertEquals(3, ContractLog.adLength(byteArrayOf(2, 1, 6, 9, 9, 1)))
        assertEquals(0, ContractLog.adLength(ByteArray(0)))
    }

    @Test
    fun `androidAdv 한 줄은 README 모양이다`() {
        val ext = AndroidAdvertisingInfo(false, AndroidPhy.Le1M, AndroidPhy.Le2M, 3, AndroidAdvertisingDataStatus.Complete)
        assertEquals("ext 1M 2M 3 Complete 120", ContractLog.renderAndroidAdv(ext, extendedSetAdvertiser().scanRecord))
        val legacy = AndroidAdvertisingInfo(true, AndroidPhy.LeCoded, null, null, AndroidAdvertisingDataStatus.Truncated)
        assertEquals("legacy Coded - - Truncated -", ContractLog.renderAndroidAdv(legacy, null))
    }

    @Test
    fun `sim extended 세트는 원본 120 바이트와 이름을 싣는다`() {
        val adv = extendedSetAdvertiser()
        val advertisement = kr.co.nucode.ble.AdvertisementData(adv.name, adv.serviceUuids, emptyMap(), emptyMap(), null, false)
            .withScanRecordBytes(adv.scanRecord)
        assertEquals(120, advertisement.androidScanRecordBytes!!.size)
        assertEquals("BLE_LAB_EXT", adv.name)
    }

    @Test
    fun `legacy 스캔은 extAdv 중에도 extended 세트를 보지 못한다`() {
        val report = run(
            """[$extAdvOn, { "call": "startScan", "filter": { "name": "BLE_LAB_EXT" } }, { "sleep": 2000 }, { "call": "stopScan" }]""",
            """{ "androidAdv": ["none"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `extAdv 창이 끝나면 extended 스캔도 세트를 보지 못한다`() {
        val report = run(
            """[$extAdvOn, { "sleep": 6000 },
                { "call": "startScan", "filter": { "name": "BLE_LAB_EXT" }, "extendedAdvertising": true }, { "sleep": 2000 }, { "call": "stopScan" }]""",
            """{ "androidAdv": ["none"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `startScan 마다 none 으로 돌리고 필터를 통과한 첫 결과 하나만 남긴다`() {
        val report = run(
            """[{ "call": "startScan", "filter": { "name": "BLE_LAB_TEST" }, "extendedAdvertising": true },
                { "await": "androidAdv", "is": "legacy *" }, { "sleep": 1000 }, { "call": "stopScan" },
                { "call": "startScan", "filter": { "name": "BLE_LAB_TEST" } }, { "await": "androidAdv", "is": "legacy *" },
                { "call": "stopScan" }]""",
            """{ "androidAdv": ["none", "legacy 1M - - Complete 35", "none", "legacy 1M - - Complete 35"] }""",
        )
        assertTrue(report.toString(), report.passed)
    }

    @Test
    fun `기대와 다른 androidAdv 는 실패다`() {
        val report = run(
            """[{ "call": "startScan", "filter": { "name": "BLE_LAB_TEST" }, "extendedAdvertising": true },
                { "await": "androidAdv", "is": "legacy *" }, { "call": "stopScan" }]""",
            """{ "androidAdv": ["none", "ext 1M 2M 3 Complete 120"] }""",
        )
        assertFalse(report.toString(), report.passed)
        assertTrue(report.failure!!, report.failure!!.startsWith("androidAdv 레인 불일치"))
    }
}
