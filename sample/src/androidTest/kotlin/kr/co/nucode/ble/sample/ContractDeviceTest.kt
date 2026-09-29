package kr.co.nucode.ble.sample

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.android.AndroidBleClientFactory
import kr.co.nucode.ble.contract.ContractRepository
import kr.co.nucode.ble.contract.ContractSource
import kr.co.nucode.ble.contract.DeviceEvent
import kr.co.nucode.ble.contract.PeripheralProfile
import kr.co.nucode.ble.contract.ScenarioEngine
import kr.co.nucode.ble.contract.ScenarioTarget
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * contract/scenarios 중 `runsOn: device`, `platforms: android` 인 것을 실기기 + nRF52840 시험 펌웨어로 돌린다.
 * 기록 줄은 logcat 태그 [TAG] 로 남긴다 — lab/run.sh 가 모아 두 폰 비교에 쓴다.
 * 시나리오마다 새 클라이언트로 시작해 이전 시나리오의 상태가 섞이지 않게 한다. 라이브러리 설정은 기본값이다.
 * 라이브러리 진단은 [DIAG_TAG] 로 남긴다(lab/run.sh 가 원본 로그로 보관).
 */
@RunWith(Parameterized::class)
class ContractDeviceTest(private val name: String) {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*requiredPermissions())

    @Test(timeout = 240_000)
    fun scenario() = runBlocking {
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true to run hardware tests",
            InstrumentationRegistry.getArguments().getString(ARG_HARDWARE_BLE) == "true",
        )
        val scenario = repository.scenario(name)
        assumeTrue("device 대상 아님", "device" in scenario.runsOn && "android" in scenario.platforms)
        val profile = repository.peripherals.getValue(scenario.peripheral)

        Log.i(DIAG_TAG, "scenario start $name")
        // 설정은 기본값이다. 로거는 동작을 바꾸지 않고 진단만 남긴다(흔들림 조사용, 판정에는 쓰지 않는다).
        val client = AndroidBleClientFactory.create(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BleConfig(logger = BleLogger { record -> Log.i(DIAG_TAG, "diag ${record.severity} ${record.failure} ${record.values}") }),
        )
        val target = try {
            awaitAdapterReady(client)
            // startScan 이 있는 시나리오는 스캔 시작 한도(30초에 5회, 대상 기기를 찾는 스캔 포함)에 걸리지 않게 먼저 기다린다.
            // SCAN_START_THROTTLED 는 계약 판정 대상이 아니다.
            if (scenario.usesScan) delay(SCAN_START_WINDOW_WAIT_MILLIS)
            DeviceTarget(client, discover(client, profile))
        } catch (e: Throwable) {
            client.close()
            throw e
        }
        val report = try {
            ScenarioEngine(target, profile).run(scenario)
        } finally {
            // 시나리오가 close 를 불렀으면 클라이언트는 이미 닫혀 있다 — 정리는 그때도 실패하지 않아야 한다.
            runCatching { withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { client.session(target.peripheralId).disconnect() } }
            client.close()
            // 기기가 링크 끊김을 알아채고 다시 광고할 시간.
            delay(SETTLE_BETWEEN_SCENARIOS_MILLIS)
        }
        report.lines().forEach { Log.i(TAG, it) }
        assertTrue(report.toString(), report.passed)
    }

    private class DeviceTarget(override val client: BleClient, override val peripheralId: PeripheralId) : ScenarioTarget {
        override val platform = "android"

        /** 펌웨어 제어 characteristic 에 명령을 쓴다. 쓰기 응답이 온 뒤 펌웨어가 300ms 지나 실행한다. */
        override suspend fun perform(event: DeviceEvent, session: BleSession, profile: PeripheralProfile) {
            session.write(
                profile.spec("probe.control").ref,
                event.command.toByteArray(Charsets.US_ASCII),
                BleWriteOptions(mode = BleWriteMode.WithResponse),
            )
        }

        /** 펌웨어 제어 characteristic 을 읽는다. 펌웨어가 `<바이트 수> <ok|bad>` 를 ASCII 로 돌려준다. */
        override suspend fun rxCheck(session: BleSession, profile: PeripheralProfile): String =
            session.read(profile.spec("probe.control").ref).toString(Charsets.US_ASCII).trim()

        /** 실기기에서는 어댑터를 코드로 바꿀 수 없다. 로더가 `runsOn` 에 device 가 있는 시나리오의 adapter 단계를 거부한다. */
        override suspend fun setAdapter(value: String) {
            error("adapter 단계는 sim 전용이다")
        }
    }

    private suspend fun awaitAdapterReady(client: BleClient) {
        withTimeout(5_000) {
            client.adapterState.first { state ->
                when (state) {
                    AdapterState.Ready -> true
                    AdapterState.Resetting -> false
                    else -> error("Bluetooth 를 쓸 수 없다: $state")
                }
            }
        }
    }

    /** 광고 중인 시험 기기를 찾는다. 앞 시나리오의 링크가 아직 남아 있으면 광고가 다시 시작될 때까지 기다린다. */
    private suspend fun discover(client: BleClient, profile: PeripheralProfile): PeripheralId {
        val expected = InstrumentationRegistry.getArguments().getString(ARG_PERIPHERAL_ID)
        val request = ScanRequest(
            filter = BleScanFilter(serviceUuids = listOf(NUS_SERVICE)),
            options = BleScanOptions(BleScanMode.LowLatency),
        )
        try {
            client.startScan(request)
        } catch (e: BleException) {
            if (e.failure.code.value != "SCAN_START_THROTTLED") throw e
            delay(SCAN_START_WINDOW_WAIT_MILLIS)
            client.startScan(request)
        }
        return try {
            withTimeout(SCAN_TIMEOUT_MILLIS) {
                client.scanResults.first { result ->
                    val nameMatches = result.advertisement.localName == profile.advertisedName
                    nameMatches && (expected == null || result.peripheralId.value.equals(expected, ignoreCase = true))
                }.peripheralId
            }
        } finally {
            client.stopScan()
        }
    }

    companion object {
        const val TAG = "BleContract"
        const val DIAG_TAG = "BleContractDiag"
        private const val ARG_HARDWARE_BLE = "hardwareBle"
        private const val ARG_PERIPHERAL_ID = "blePeripheralId"
        private const val SCAN_TIMEOUT_MILLIS = 30_000L
        private const val SCAN_START_WINDOW_WAIT_MILLIS = 31_000L
        private const val CLEANUP_TIMEOUT_MILLIS = 15_000L
        private const val SETTLE_BETWEEN_SCENARIOS_MILLIS = 1_500L
        private val NUS_SERVICE = BleUuid(java.util.UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))

        private val repository: ContractRepository by lazy {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            ContractRepository(object : ContractSource {
                override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty()
                override fun read(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
            })
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<String> = repository.scenarioNames

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
