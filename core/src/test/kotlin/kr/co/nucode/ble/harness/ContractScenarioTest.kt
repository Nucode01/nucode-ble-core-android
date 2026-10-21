package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.util.UUID
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
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

        val h = harness(scannerRediscovery = true)
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
    private val nusRx = profile.characteristics["nus.rx"]?.ref

    /** 이 연결에서 기기가 받은 nus.rx 를 세기 시작하는 write 기록 위치(링크가 끊기면 0 부터 다시 센다). */
    private var rxFrom = 0

    init {
        // 서비스 목록은 기기 정의를 따른다(GAP 는 실기기처럼 남긴다).
        h.fake.services = listOf(h.fake.services.first()) + profile.characteristics.values.groupBy { it.service }.map { (service, chars) ->
            GattServiceSnapshot(
                serviceUuid = service,
                characteristics = chars.map {
                    GattCharacteristicSnapshot(service, it.uuid, instanceIndex = 0, propertyMask = propertyMask(it.properties))
                },
            )
        }
        // 실기기처럼 write 가 시간을 쓴다: 측정한 MTU 65(조각 62바이트, probes/ios/results/wwr-20260929-171507.txt)와
        // ATT 왕복 하나에 연결 간격 하나. 이게 없으면 가상 시간 0 에 긴 write 가 끝나 호출자 취소(§5-3)를 시험할 수 없다.
        h.fake.mtuResult = SIM_MTU
        h.fake.onWrite = CallbackVariant.Late(WRITE_ACK_MILLIS)
        // 실기기 펌웨어처럼 연결되어 있지 않고 숨지 않았을 때 광고한다(이름과, advertised 인 서비스 UUID).
        val advertised = profile.services.values.filter { it.advertised }.map { it.uuid }
        h.fake.advertiser = FakeAndroidPlatform.Advertiser(
            id = peripheralId,
            name = profile.advertisedName,
            serviceUuids = advertised.map { BleUuid(it) }.toSet(),
            scanRecord = scanRecord(profile.advertisedName, advertised),
            // 링크가 없을 때(연결 전·연결 시도 중·OS 재연결 대기 중) 광고한다. Connecting 중에도 광고해야 connect 의 확보 스캔이 찾는다.
            visible = {
                when (client.session(peripheralId).connectionState.value) {
                    is ConnectionState.Disconnected, ConnectionState.Connecting, is ConnectionState.Recovering -> true
                    else -> false
                }
            },
        )
    }

    /**
     * Android 원본 바이트(README §sim): 실기기 펌웨어 기본 배치처럼 광고 데이터 Flags(0x01, 0x06)·Complete Local Name(0x09) 뒤에
     * scan response 의 Complete List of 128-bit Service UUIDs(0x07, little-endian)를 붙인다. 0 채움은 붙이지 않는다.
     */
    private fun scanRecord(name: String, services: List<UUID>): ByteArray {
        fun ad(type: Int, value: ByteArray) = byteArrayOf((value.size + 1).toByte(), type.toByte()) + value
        val uuids = services.fold(ByteArray(0)) { acc, uuid ->
            acc + ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array().reversedArray()
        }
        return ad(0x01, byteArrayOf(0x06)) + ad(0x09, name.toByteArray(Charsets.UTF_8)) +
            (if (services.isEmpty()) ByteArray(0) else ad(0x07, uuids))
    }

    private fun propertyMask(properties: Set<String>): Int = properties.fold(0) { mask, p ->
        mask or when (p) {
            "read" -> BluetoothGattCharacteristic.PROPERTY_READ
            "write" -> BluetoothGattCharacteristic.PROPERTY_WRITE
            "writeWithoutResponse" -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            "notify" -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
            "indicate" -> BluetoothGattCharacteristic.PROPERTY_INDICATE
            else -> 0
        }
    }
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

    /** 펌웨어의 rxCheck 와 같은 규칙: 이 연결에서 nus.rx 로 받은 누적 k 번째 바이트가 k mod 256 인가. */
    override suspend fun rxCheck(session: BleSession, profile: PeripheralProfile): String {
        val received = h.fake.writes.drop(rxFrom)
            .filter { it.ref == nusRx && it.epoch == h.fake.epoch }
            .flatMap { it.value.asList() }
        val ok = received.withIndex().all { (k, b) -> b == (k % 256).toByte() }
        return "${received.size} ${if (ok) "ok" else "bad"}"
    }

    override suspend fun setAdapter(value: String) {
        val state = when (value) {
            "Unknown" -> AdapterState.Unknown
            "Ready" -> AdapterState.Ready
            "PoweredOff" -> AdapterState.PoweredOff
            "Resetting" -> AdapterState.Resetting
            "Unsupported" -> AdapterState.Unsupported
            "Unauthorized(true)" -> AdapterState.Unauthorized(canRequest = true)
            "Unauthorized(false)" -> AdapterState.Unauthorized(canRequest = false)
            else -> error("어댑터 값 '$value' 를 만들 수 없다")
        }
        h.fake.setAdapter(state)
        // 플랫폼이 앱에 알리는 홉(코어 디스패처에 예약된 refreshAdapterState)이 끝나게 한다.
        delay(1)
    }

    private fun dropLink(readvertiseAfterMillis: Long) {
        // 펌웨어 LINK_DROP 은 원격 사용자 종료(HCI 0x13 = GATT status 19)로 끊는다.
        h.fake.linkLoss(peripheralId, status = 19)
        cccdFrom = h.fake.cccdWrites.size
        rxFrom = h.fake.writes.size
        h.fake.hideAdvertising(readvertiseAfterMillis)
        h.fake.beforeRead = null
        // autoConnect handle 이면 기기가 다시 광고할 때 OS 가 같은 handle 로 다시 잇는다.
        if (h.fake.connectAutoConnect.lastOrNull() == true) h.fake.platformRelink(peripheralId, readvertiseAfterMillis)
    }

    private companion object {
        /** 끊긴 뒤 펌웨어가 다시 광고해 OS 가 붙기까지(recycled → 광고 시작 → 연결). */
        const val READVERTISE_MILLIS = 200L

        const val SIM_MTU = 65

        /** write 조각 하나의 응답까지(연결 간격 하나로 본다). */
        const val WRITE_ACK_MILLIS = 15L
    }

    private fun sendNotification() {
        if (!deviceCccEnabled) return
        h.fake.emitNotification(peripheralId, probeValue, "N|seq=${++notifySeq}".toByteArray())
    }
}
