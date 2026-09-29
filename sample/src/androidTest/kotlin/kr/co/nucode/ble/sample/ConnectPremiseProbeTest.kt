package kr.co.nucode.ble.sample

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult as PlatformScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.android.AndroidBleClientFactory
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * 2단계 Android 전제 프로브(버리는 코드). 라이브러리 main 은 바꾸지 않는다.
 *
 * - [transportCompare]: 플랫폼 `connectGatt` 를 transport 인자 없이(기본 AUTO) / `TRANSPORT_LE` 로 번갈아 걸어
 *   링크 콜백 status·시간을 잰다. 라이브러리처럼 `getRemoteDevice(주소)` 로 기기를 얻는다.
 * - [firstConnectLibrary]: 새 프로세스의 첫 라이브러리 connect(기본 옵션) — 설치·재시작 직후 첫 연결 실패·지연.
 *   러너가 `am instrument` 로 매번 새 프로세스를 띄운다.
 * - [firstConnectRaw]: 같은 조건에서 플랫폼 `connectGatt` 한 번(transport 는 인자 `probeTransport`).
 * 기록 줄: logcat 태그 `BleProbe`, `PROBE|<case>|k=v|…`.
 */
class ConnectPremiseProbeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*permissions())

    private val args get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter: BluetoothAdapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun requireHardware() = assumeTrue(args.getString("hardwareBle") == "true")

    @Test
    fun transportCompare() {
        requireHardware()
        val rounds = args.getString("probeRounds")?.toInt() ?: 20
        val address = args.getString("blePeripheralId") ?: scanAddress()
        for (i in 1..rounds) {
            for (transport in if (i % 2 == 1) listOf("auto", "le") else listOf("le", "auto")) {
                rawConnectOnce("transport", address, transport, "round" to i, "address" to address)
                SystemClock.sleep(PAUSE_MILLIS)
            }
        }
    }

    @Test
    fun firstConnectRaw() {
        requireHardware()
        val address = args.getString("blePeripheralId") ?: DEFAULT_ADDRESS
        rawConnectOnce("first-raw", address, args.getString("probeTransport") ?: "auto", "run" to args.getString("probeRun"))
    }

    @Test
    fun firstConnectLibrary(): Unit = runBlocking {
        requireHardware()
        val address = args.getString("blePeripheralId") ?: DEFAULT_ADDRESS
        val diags = mutableListOf<String>()
        val client = AndroidBleClientFactory.create(
            context,
            BleConfig(logger = BleLogger { r -> synchronized(diags) { diags += "${r.failure.code.value}" } }),
        )
        try {
            withTimeout(10_000) { client.adapterState.first { it is AdapterState.Ready } }
            val session = client.session(PeripheralId(address))
            val states = mutableListOf<String>()
            val t0 = SystemClock.elapsedRealtime()
            val collector = launch {
                session.connectionState.collect { s -> synchronized(states) { states += "${s.summary()}@${SystemClock.elapsedRealtime() - t0}" } }
            }
            val readyAt = withTimeoutOrNull(FIRST_CONNECT_WINDOW_MILLIS) {
                runCatching { session.connect(BleConnectOptions()) }
                session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
            }
            collector.cancel()
            val final = session.connectionState.value
            val failure = ((final as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure
            val detail = failure?.platformDetail as? PlatformDetail.Android
            probe(
                "first-lib",
                "run" to args.getString("probeRun"),
                "pid" to Process.myPid(),
                "processUptimeMs" to (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
                "outcome" to if (final == ConnectionState.Ready) "ready" else if (readyAt == null) "window-timeout" else "failed",
                "elapsedMs" to (SystemClock.elapsedRealtime() - t0),
                "failure" to failure?.code?.value,
                "status" to detail?.statusCode,
                "states" to synchronized(states) { states.joinToString(">") },
                "diags" to synchronized(diags) { diags.joinToString(",") },
            )
            withTimeoutOrNull(10_000) { session.disconnect() }
        } finally {
            client.close()
        }
    }

    // ── 플랫폼 직접 호출 ───────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun rawConnectOnce(case: String, address: String, transport: String, vararg extra: Pair<String, Any?>) {
        val events = LinkedBlockingQueue<Pair<String, IntArray>>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.put("conn" to intArrayOf(status, newState))
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.put("disc" to intArrayOf(status))
            }
        }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val t0 = SystemClock.elapsedRealtime()
        val gatt = if (transport == "le") {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        var status: Int? = null
        var state: Int? = null
        var connectMs: Long? = null
        var discoverMs: Long? = null
        var discStatus: Int? = null
        val first = events.poll(LINK_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        if (first != null) {
            connectMs = SystemClock.elapsedRealtime() - t0
            status = first.second[0]; state = first.second[1]
            if (state == BluetoothProfile.STATE_CONNECTED) {
                val t1 = SystemClock.elapsedRealtime()
                gatt?.discoverServices()
                val disc = generateSequence { events.poll(10_000, TimeUnit.MILLISECONDS) }.firstOrNull { it.first == "disc" || it.first == "conn" }
                if (disc?.first == "disc") { discoverMs = SystemClock.elapsedRealtime() - t1; discStatus = disc.second[0] }
                gatt?.disconnect()
                generateSequence { events.poll(5_000, TimeUnit.MILLISECONDS) }
                    .firstOrNull { it.first == "conn" && it.second[1] == BluetoothProfile.STATE_DISCONNECTED }
            }
        }
        gatt?.close()
        probe(
            case, *extra,
            "transport" to transport,
            "outcome" to when {
                first == null -> "no-callback"
                state == BluetoothProfile.STATE_CONNECTED && status == 0 -> "connected"
                else -> "failed"
            },
            "status" to status, "newState" to state, "connectMs" to connectMs,
            "discoverMs" to discoverMs, "discStatus" to discStatus,
            "processUptimeMs" to (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
        )
    }

    @SuppressLint("MissingPermission")
    private fun scanAddress(): String {
        val found = LinkedBlockingQueue<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: PlatformScanResult) { found.offer(result.device.address) }
        }
        val scanner = adapter.bluetoothLeScanner
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        val address = try { found.poll(20, TimeUnit.SECONDS) } finally { scanner.stopScan(cb) }
        return address ?: error("NUS 광고를 20초 안에 보지 못함")
    }

    private fun ConnectionState.summary(): String = when (this) {
        is ConnectionState.Disconnected -> when (val r = reason) {
            is BleDisconnectReason.Failed -> "Disconnected(${r.failure.code.value})"
            else -> "Disconnected"
        }
        else -> this::class.simpleName ?: toString()
    }

    private fun probe(case: String, vararg values: Pair<String, Any?>) {
        Log.i(TAG, "PROBE|$case|" + values.joinToString("|") { (k, v) -> "$k=$v" })
    }

    private companion object {
        const val TAG = "BleProbe"
        const val DEFAULT_ADDRESS = "F0:2F:24:A8:0F:9E"
        const val LINK_WAIT_MILLIS = 30_000L
        const val FIRST_CONNECT_WINDOW_MILLIS = 40_000L
        const val PAUSE_MILLIS = 1_500L
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
