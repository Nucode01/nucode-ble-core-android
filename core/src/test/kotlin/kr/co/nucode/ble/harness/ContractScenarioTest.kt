package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.contract.ContractFiles
import kr.co.nucode.ble.contract.DeviceEvent
import kr.co.nucode.ble.contract.PeripheralProfile
import kr.co.nucode.ble.contract.ScenarioEngine
import kr.co.nucode.ble.contract.ScenarioTarget
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * contract/scenarios 중 `runsOn: sim`, `platforms: android` 인 것을 가짜 플랫폼 위에서 돌린다.
 * 기록 줄(README §실행 대상과 러너)은 표준 출력에 남긴다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class ContractScenarioTest(private val name: String) {

    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    @Test
    fun scenario() = runTest {
        val repo = ContractFiles.repository
        val scenario = repo.scenario(name)
        assumeTrue("sim 대상 아님", "sim" in scenario.runsOn && "android" in scenario.platforms)
        val profile = repo.peripherals.getValue(scenario.peripheral)

        val h = harness()
        val report = try {
            ScenarioEngine(SimTarget(h, profile), profile).run(scenario)
        } finally {
            h.close()
        }
        println(report)
        assertTrue(report.toString(), report.passed)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<String> = ContractFiles.scenarioNames()
    }
}

/**
 * 기기 사건을 [FakeAndroidPlatform] 콜백으로 흉내 낸다. 실기기 펌웨어(peripheral_uart)의 동작에 맞춘다:
 * 알림은 CCCD 가 켜져 있을 때만 보내고, 링크가 끊기면 CCCD 는 꺼진다(본딩 없음).
 */
internal class SimTarget(private val h: Harness, profile: PeripheralProfile) : ScenarioTarget {
    override val platform = "android"
    override val client = h.client
    override val peripheralId = PeripheralId("F0:2F:24:A8:0F:9E")

    private val probeValue = profile.spec("probe.value").ref
    private var cccdFrom = 0
    private var notifySeq = 0

    private val deviceCccEnabled: Boolean
        get() = h.fake.cccdWrites.drop(cccdFrom).lastOrNull { it.first == probeValue }?.second == true

    override suspend fun perform(event: DeviceEvent, session: BleSession, profile: PeripheralProfile) {
        when (event) {
            DeviceEvent.LinkDrop -> dropLink(readvertiseAfterMillis = READVERTISE_MILLIS)
            is DeviceEvent.LinkDropHide -> dropLink(readvertiseAfterMillis = event.ms)
            is DeviceEvent.Notify -> repeat(event.count) { sendNotification() }
            is DeviceEvent.ReadNotify -> h.fake.beforeRead = if (event.on) {
                { ref -> if (ref == probeValue) sendNotification() }
            } else {
                null
            }
        }
    }

    private fun dropLink(readvertiseAfterMillis: Long) {
        // 펌웨어 LINK_DROP 은 원격 사용자 종료(HCI 0x13 = GATT status 19)로 끊는다.
        h.fake.linkLoss(peripheralId, status = 19)
        cccdFrom = h.fake.cccdWrites.size
        h.fake.beforeRead = null
        // autoConnect handle 이면 기기가 다시 광고할 때 OS 가 같은 handle 로 다시 잇는다.
        if (h.fake.connectAutoConnect.lastOrNull() == true) h.fake.platformRelink(peripheralId, readvertiseAfterMillis)
    }

    private companion object {
        /** 끊긴 뒤 펌웨어가 다시 광고해 OS 가 붙기까지(recycled → 광고 시작 → 연결). */
        const val READVERTISE_MILLIS = 200L
    }

    private fun sendNotification() {
        if (!deviceCccEnabled) return
        h.fake.emitNotification(peripheralId, probeValue, "N|seq=${++notifySeq}".toByteArray())
    }
}
