package kr.co.nucode.ble.sample

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.android.AndroidBleClientFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adapter-state baseline: Bluetooth off -> on while a session is Ready.
 * Records the observed adapterState / connectionState sequence to logcat (tag BleBaseline)
 * so a later run can be diffed against it. Asserts only the minimum contract.
 */
@RunWith(AndroidJUnit4::class)
class AdapterStateBaselineTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*requiredPermissions())

    private lateinit var client: BleClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapterLog = mutableListOf<String>()
    private val connectionLog = mutableListOf<String>()

    @Before
    fun setUp() {
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true to run hardware tests",
            InstrumentationRegistry.getArguments().getString("hardwareBle") == "true",
        )
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        client = AndroidBleClientFactory.create(
            context,
            BleConfig(linkTimeoutMillis = 20_000, discoveryTimeoutMillis = 15_000, configurationTimeoutMillis = 15_000),
        )
        client.adapterState.onEach { record(adapterLog, "adapter", it.toString()) }.launchIn(scope)
    }

    @After
    fun tearDown() {
        shell("svc bluetooth enable")
        scope.cancel()
        if (::client.isInitialized) client.close()
        Log.i(TAG, "SEQUENCE adapter=${adapterLog.joinToString(" > ")}")
        Log.i(TAG, "SEQUENCE connection=${connectionLog.joinToString(" > ")}")
    }

    @Test(timeout = 180_000)
    fun bluetoothOffThenOnSweepsSessionAndAllowsReconnect() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        val session = client.connect(peripheralId)
        session.connectionState.onEach { record(connectionLog, "conn", it.summary()) }.launchIn(scope)
        awaitReady(session)
        Log.i(TAG, "STEP ready peripheral=${peripheralId.value}")

        shell("svc bluetooth disable")
        val offState = withTimeout(20_000) { client.adapterState.first { it !is AdapterState.Ready } }
        Log.i(TAG, "STEP bt-off adapterState=$offState")
        val disconnected = withTimeout(20_000) {
            session.connectionState.first { it is ConnectionState.Disconnected } as ConnectionState.Disconnected
        }
        Log.i(TAG, "STEP bt-off connectionState=${disconnected.summary()}")

        shell("svc bluetooth enable")
        val onState = withTimeout(30_000) { client.adapterState.first { it is AdapterState.Ready } }
        Log.i(TAG, "STEP bt-on adapterState=$onState")

        // Regression oracle for cold-cache rediscovery: a direct connect (no app-level rescan) must
        // reach Ready after the toggle. Before the fix this timed out with LINK_TIMEOUT.
        val t0 = System.currentTimeMillis()
        val direct = client.connect(peripheralId)
        awaitReady(direct, 30_000)
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "STEP reconnect-direct Ready in ${elapsed}ms state=${direct.connectionState.value.summary()}")
        runCatching { withTimeout(10_000) { client.session(peripheralId).disconnect() } }

        assertTrue("expected PoweredOff after bt-off, got $offState", offState is AdapterState.PoweredOff)
        assertTrue("expected Disconnected(Failed) after bt-off, got ${disconnected.summary()}", disconnected.reason is BleDisconnectReason.Failed)
    }

    private fun ConnectionState.summary(): String = when (this) {
        is ConnectionState.Disconnected -> when (val r = reason) {
            is BleDisconnectReason.Failed -> "Disconnected(Failed:${r.failure.code.value}/${r.failure.phase})"
            else -> "Disconnected($r)"
        }
        is ConnectionState.Recovering -> "Recovering(${lastFailure.code.value})"
        else -> toString()
    }

    private fun record(log: MutableList<String>, kind: String, value: String) {
        synchronized(log) { log += value }
        Log.i(TAG, "OBS $kind=$value")
    }

    private fun shell(command: String) {
        val out = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(command)
        Log.i(TAG, "SHELL $command -> ${out.trim()}")
    }

    private suspend fun awaitAdapterReady() {
        withTimeout(10_000) { client.adapterState.first { it is AdapterState.Ready } }
    }

    /**
     * 스캔 시작 — SCAN_START_THROTTLED(프로세스 공유 시작 한도, 앞 테스트의 시작 포함)면 한도 창이 풀릴 때까지
     * 기다렸다 1회 재시도한다.
     */
    private suspend fun startScanRetryingThrottle(request: ScanRequest) {
        try {
            client.startScan(request)
        } catch (e: BleException) {
            if (e.failure.code.value != "SCAN_START_THROTTLED") throw e
            delay(SCAN_START_WINDOW_WAIT_MILLIS)
            client.startScan(request)
        }
    }

    private suspend fun discoverPeripheral(): PeripheralId {
        val expectedId = InstrumentationRegistry.getArguments().getString("blePeripheralId")
        startScanRetryingThrottle(ScanRequest(BleScanFilter(serviceUuids = listOf(NUS_SERVICE)), BleScanOptions(BleScanMode.LowLatency)))
        return try {
            withTimeout(20_000) {
                client.scanResults.first { expectedId == null || it.peripheralId.value.equals(expectedId, ignoreCase = true) }.peripheralId
            }
        } finally {
            client.stopScan()
        }
    }

    private suspend fun awaitReady(session: BleSession, timeoutMillis: Long = 30_000) {
        withTimeout(timeoutMillis) { session.connectionState.first { it is ConnectionState.Ready } }
    }

    private companion object {
        /** 스캔 시작 한도 창(30 s + 여유 1 s) 이상 — 창이 풀릴 때까지의 대기. */
        const val SCAN_START_WINDOW_WAIT_MILLIS = 31_000L
        const val TAG = "BleBaseline"
        val NUS_SERVICE = BleUuid(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
