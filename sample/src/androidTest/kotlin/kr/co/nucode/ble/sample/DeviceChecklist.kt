package kr.co.nucode.ble.sample

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.android.AndroidBleClientFactory

/**
 * Android 기기 점검표(iOS `ios/Samples/ManualTest` 의 1–7 항목 대응) 한 항목의 실행 기록과 판정 도구.
 *
 * 공개 API 만 쓴다. 라디오(블루투스·비행기 모드)는 shell 명령으로 바꾼다. 모든 관측은 시간순 [Entry] 로 남기고
 * 곧바로 logcat 태그 [TAG] 에 `EV|android|<항목>|+<ms>|<종류>|<내용>` 으로 낸다. 판정은
 * `CHECK|android|<항목>|PASS|FAIL|NA|<요약>`, 블루투스 꺼짐 순서 측정은 `MEASURE|android|<항목>|…` 한 줄이다.
 * `lab/checklist-android.sh` 가 이 줄들을 모은다.
 *
 * 시간은 [origin] 기준 ms(elapsedRealtime). 라이브러리 내부 GATT 콜백 시각은 공개 API 로 못 보므로 프레임워크
 * `BluetoothGatt` logcat 줄(`onClientConnectionState()`)로 오케스트레이터가 잰다. 여기서는 OS 브로드캐스트
 * (`ACTION_STATE_CHANGED`, `ACTION_ACL_DISCONNECTED`)와 라이브러리 공개 스트림의 시각을 잰다.
 */
internal class DeviceChecklist(private val item: String) : AutoCloseable {

    data class Entry(val atNanos: Long, val kind: String, val detail: String) {
        val atMillis: Long get() = atNanos / 1_000_000
    }

    private val origin = SystemClock.elapsedRealtimeNanos()
    private val entries = CopyOnWriteArrayList<Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val receiverThread = HandlerThread("checklist-receiver").apply { start() }
    private val clients = mutableListOf<BleClient>()
    private var receiverRegistered = false
    /** 마지막으로 보고한 판정(없으면 null). */
    var lastVerdict: Verdict? = null
        private set

    /** 기록 대상 기기(주소 대문자). OS ACL 브로드캐스트를 거르는 데 쓴다. */
    var peripheralId: PeripheralId? = null

    /** OS 가 보낸 어댑터·ACL 브로드캐스트. 라이브러리 리시버와 독립인 별도 스레드에서 받아 시각을 찍는다. */
    private val osReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val prev = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)
                    val now = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    mark(OS_ADAPTER, "${adapterName(prev)}->${adapterName(now)}")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val address = runCatching { device?.address }.getOrNull()?.uppercase()
                    val target = peripheralId?.value
                    if (target == null || address == target) {
                        val what = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected"
                        mark(OS_ACL, "$what $address")
                    }
                }
            }
        }
    }

    init {
        mark("begin", "item=$item sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} uptimeMs=${SystemClock.elapsedRealtime()}")
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(osReceiver, filter, null, Handler(receiverThread.looper))
        receiverRegistered = true
    }

    fun now(): Long = SystemClock.elapsedRealtimeNanos() - origin

    fun mark(kind: String, detail: String): Entry {
        val entry = Entry(now(), kind, detail)
        entries += entry
        Log.i(TAG, "EV|android|$item|+${entry.atMillis}|$kind|$detail")
        return entry
    }

    fun since(at: Long, vararg kinds: String): List<Entry> =
        entries.filter { it.atNanos >= at && (kinds.isEmpty() || it.kind in kinds) }

    fun firstSince(at: Long, kind: String, predicate: (String) -> Boolean = { true }): Entry? =
        entries.firstOrNull { it.atNanos >= at && it.kind == kind && predicate(it.detail) }

    // ── 클라이언트·세션 ────────────────────────────────────────────────────────

    /** 새 클라이언트(새 앱 실행에 해당). 설정은 기본값, 진단만 기록한다. */
    fun newClient(): BleClient {
        val client = AndroidBleClientFactory.create(
            context,
            BleConfig(logger = BleLogger { record ->
                mark(DIAG, "${record.severity} ${record.failure.code.value} op=${record.failure.context.operationKind} ${record.values}")
            }),
        )
        clients += client
        mark("client", "created #${clients.size}")
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.adapterState.collect { mark(ADAPTER, it.label()) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.scanState.collect { mark(SCAN, it.label()) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is OfficialBleClientEvent.Connection ->
                        if (peripheralId == null || event.peripheralId == peripheralId) mark(EVENT, event.state.label())
                    is OfficialBleClientEvent.Diagnostic -> mark(DIAG_EVENT, "${event.severity} ${event.failure.code.value}")
                    is OfficialBleClientEvent.SubscriptionRestoreFailed -> mark(DIAG_EVENT, "restoreFailed ${event.failure.code.value}")
                    else -> Unit
                }
            }
        }
        return client
    }

    /** 세션 공개 상태를 관측한다(연결 상태, activeSubscriptions, probe.value 알림). */
    fun observe(session: BleSession) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.connectionState.collect { mark(STATE, it.label()) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.activeSubscriptions.collect { set -> mark(SUBS, set.joinToString(",", "{", "}") { it.label() }) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.notifications(PROBE_VALUE).collect { mark(NOTIFY, it.toString(Charsets.US_ASCII)) }
        }
    }

    suspend fun awaitAdapter(client: BleClient, timeoutMillis: Long, predicate: (AdapterState) -> Boolean): AdapterState? =
        withTimeoutOrNull(timeoutMillis) { client.adapterState.first(predicate) }

    suspend fun awaitReady(session: BleSession, timeoutMillis: Long = 30_000): Boolean =
        withTimeoutOrNull(timeoutMillis) { session.connectionState.first { it is ConnectionState.Ready } } != null

    suspend fun awaitState(session: BleSession, label: String, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { session.connectionState.first { it.label() == label } } != null

    /** 광고 중인 시험 기기를 찾는다(NUS 서비스 + 광고 이름). 스캔 시작 한도에 걸리면 한도 창이 풀린 뒤 한 번 더 한다. */
    suspend fun discover(client: BleClient): PeripheralId {
        val request = ScanRequest(BleScanFilter(serviceUuids = listOf(NUS_SERVICE)), BleScanOptions(BleScanMode.LowLatency))
        for (attempt in 1..2) {
            try {
                client.startScan(request)
            } catch (e: BleException) {
                mark(CALL, "discover startScan ${e.code()}")
                if (e.code() != "SCAN_START_THROTTLED" || attempt == 2) throw e
                delay(SCAN_WINDOW_MILLIS)
                continue
            }
            val found = try {
                withTimeoutOrNull(DISCOVER_TIMEOUT_MILLIS) {
                    client.scanResults.first { it.advertisement.localName == ADVERTISED_NAME }.peripheralId
                }
            } finally {
                client.stopScan()
            }
            if (found != null) {
                peripheralId = found
                mark(CALL, "discover found ${found.value}")
                return found
            }
            mark(CALL, "discover timeout attempt=$attempt")
            // 결과가 안 오는 것은 OS 스캔 한도(앱 단위, 30초에 5회)일 수 있다.
            if (attempt == 1) delay(SCAN_WINDOW_MILLIS)
        }
        error("시험 기기(${ADVERTISED_NAME})를 찾지 못했다")
    }

    /** 사용자 스캔(항목 1: 스캔이 어댑터 꺼짐으로 끝나는지 본다). 기기는 연결 중이라 광고하지 않아도 된다. */
    suspend fun startUserScan(client: BleClient) {
        runCatching { client.startScan(ScanRequest(BleScanFilter(namePrefix = "checklist-none"), BleScanOptions(BleScanMode.LowPower))) }
            .onFailure { mark(CALL, "startScan ${(it as? BleException)?.code() ?: it}") }
            .onSuccess { mark(CALL, "startScan ok") }
    }

    /** 연결 → Ready → probe.value 구독 → 알림 1회 확인. 실패하면 이유를 돌려준다. */
    suspend fun connectReadySubscribed(session: BleSession, autoReconnect: Boolean): String? {
        runCatching { session.connect(BleConnectOptions(autoReconnect = autoReconnect)) }
            .onFailure { return "connect ${(it as? BleException)?.code() ?: it}".also { r -> mark(CALL, r) } }
        mark(CALL, "connect(autoReconnect=$autoReconnect) returned")
        if (!awaitReady(session)) return "30초 안에 Ready 없음(${session.connectionState.value.label()})"
        runCatching { session.subscribe(PROBE_VALUE) }
            .onFailure { return "subscribe ${(it as? BleException)?.code() ?: it}".also { r -> mark(CALL, r) } }
        mark(CALL, "subscribe probe.value ok")
        val before = now()
        requestNotifications(session, 1)
        if (!awaitEntry(before, NOTIFY, 10_000)) return "구독 뒤 알림 없음"
        return null
    }

    /** 펌웨어 제어 characteristic 으로 probe.value 알림 n 개를 보내게 한다(300ms 뒤 실행). */
    suspend fun requestNotifications(session: BleSession, count: Int) {
        runCatching {
            session.write(PROBE_CONTROL, "NOTIFY $count".toByteArray(Charsets.US_ASCII), BleWriteOptions(mode = BleWriteMode.WithResponse))
        }.onFailure { mark(CALL, "write NOTIFY ${(it as? BleException)?.code() ?: it}") }
            .onSuccess { mark(CALL, "write NOTIFY $count ok") }
    }

    suspend fun awaitEntry(at: Long, kind: String, timeoutMillis: Long, predicate: (String) -> Boolean = { true }): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (firstSince(at, kind, predicate) != null) return true
            delay(50)
        }
        return firstSince(at, kind, predicate) != null
    }

    /** 명령 결과를 코드로 기록한다. 성공이면 "ok". */
    suspend fun call(name: String, block: suspend () -> Unit): String {
        val result = try {
            block()
            "ok"
        } catch (e: BleException) {
            e.code()
        }
        mark(CALL, "$name -> $result")
        return result
    }

    // ── 라디오 ──────────────────────────────────────────────────────────────

    fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }.trim()
        return out
    }

    /** 블루투스를 켜거나 끈다. 돌려주는 값은 명령을 부르기 직전 시각(측정 기준). */
    fun bluetooth(on: Boolean): Long {
        val at = mark(CMD, "bluetooth ${if (on) "enable" else "disable"}").atNanos
        val out = shell("cmd bluetooth_manager ${if (on) "enable" else "disable"}")
        mark(CMD, "bluetooth -> ${out.replace('\n', ' ')}")
        return at
    }

    fun airplane(on: Boolean): Long {
        val at = mark(CMD, "airplane ${if (on) "enable" else "disable"}").atNanos
        val out = shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        mark(CMD, "airplane -> ${out.replace('\n', ' ')}")
        return at
    }

    fun setting(namespace: String, key: String): String = shell("settings get $namespace $key")

    /** 라디오를 시험 전 상태(블루투스 켬, 비행기 모드 끔)로 되돌린다. */
    fun restoreRadios() {
        if (setting("global", "airplane_mode_on") == "1") airplane(false)
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) bluetooth(true)
        shell("cmd bluetooth_manager wait-for-state:STATE_ON")
    }

    // ── 보고 ───────────────────────────────────────────────────────────────

    fun report(verdict: Verdict) {
        lastVerdict = verdict
        Log.i(TAG, "CHECK|android|$item|${verdict.status}|${verdict.summary}")
    }

    /**
     * 라디오를 끈 명령([offAt]) 뒤의 순서와 시각을 한 줄로 낸다. 기준은 명령 호출 시각이다. 없는 값은 `-`.
     * `linkVsAdapter` = (첫 공개 연결 전이) − (라이브러리 adapterState 이탈). 음수면 링크 쪽이 먼저 보였다.
     */
    fun measureOff(offAt: Long, radio: String) {
        fun rel(e: Entry?): String = e?.let { "+${(it.atNanos - offAt) / 1_000_000}" } ?: "-"
        val turningOff = firstSince(offAt, OS_ADAPTER) { it.endsWith("TURNING_OFF") }
        val osOff = firstSince(offAt, OS_ADAPTER) { it.endsWith("->OFF") }
        val acl = firstSince(offAt, OS_ACL) { it.startsWith("disconnected") }
        val libAdapter = firstSince(offAt, ADAPTER) { it != "Ready" }
        val firstLink = firstSince(offAt, EVENT)
        val final = firstSince(offAt, EVENT) { it == OFF_FINAL }
        val gap = if (firstLink != null && libAdapter != null) "${(firstLink.atNanos - libAdapter.atNanos) / 1_000_000}" else "-"
        Log.i(
            TAG,
            "MEASURE|android|$item|radio=$radio|osTurningOff=${rel(turningOff)}|osOff=${rel(osOff)}|osAclDisconnected=${rel(acl)}" +
                "|libAdapter=${rel(libAdapter)}(${libAdapter?.detail ?: "-"})|libFirstLink=${rel(firstLink)}(${firstLink?.detail ?: "-"})" +
                "|libFinal=${rel(final)}|linkVsAdapterMs=$gap",
        )
    }

    override fun close() {
        if (lastVerdict == null) Log.i(TAG, "CHECK|android|$item|FAIL|판정 전에 끝남(예외)")
        runBlocking {
            for (client in clients) {
                peripheralId?.let { id -> runCatching { withTimeout(10_000) { client.session(id).disconnect() } } }
                runCatching { client.stopScan() }
            }
        }
        clients.forEach { runCatching { it.close() } }
        runCatching { restoreRadios() }
        scope.cancel()
        if (receiverRegistered) runCatching { context.unregisterReceiver(osReceiver) }
        receiverThread.quitSafely()
        mark("end", "timeline entries=${entries.size}")
    }

    data class Verdict(val status: String, val summary: String) {
        companion object {
            fun pass(summary: String) = Verdict("PASS", summary)
            fun fail(summary: String) = Verdict("FAIL", summary)
            fun na(summary: String) = Verdict("NA", summary)
        }
    }

    companion object {
        const val TAG = "BleChecklist"
        const val OS_ADAPTER = "os-adapter"
        const val OS_ACL = "os-acl"
        const val ADAPTER = "adapter"
        const val SCAN = "scan"
        const val EVENT = "event"
        const val STATE = "state"
        const val SUBS = "subs"
        const val NOTIFY = "notify"
        const val DIAG = "diag"
        const val DIAG_EVENT = "diag-event"
        const val CALL = "call"
        const val CMD = "cmd"

        const val OFF_FINAL = "Disconnected(Failed:ADAPTER_UNAVAILABLE)"
        const val LINK_FAILED = "Recovering(LINK_FAILED)"
        const val SCAN_STOPPED = "Idle(Failed:SCAN_STOPPED_BY_ADAPTER)"
        const val ADVERTISED_NAME = "Nordic_UART_Service"
        private const val DISCOVER_TIMEOUT_MILLIS = 30_000L
        private const val SCAN_WINDOW_MILLIS = 31_000L

        val NUS_SERVICE = BleUuid(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        private val PROBE_SERVICE = ServiceRef(BleUuid(UUID.fromString("6e7a0001-5c3a-4b8e-9d1f-0a1b2c3d4e5f")))
        val PROBE_VALUE = CharacteristicRef(PROBE_SERVICE, BleUuid(UUID.fromString("6e7a0002-5c3a-4b8e-9d1f-0a1b2c3d4e5f")))
        val PROBE_CONTROL = CharacteristicRef(PROBE_SERVICE, BleUuid(UUID.fromString("6e7a0003-5c3a-4b8e-9d1f-0a1b2c3d4e5f")))

        fun BleException.code(): String = failure.code.value

        fun ConnectionState.label(): String = when (this) {
            is ConnectionState.Disconnected -> when (val r = reason) {
                is BleDisconnectReason.Failed -> "Disconnected(Failed:${r.failure.code.value})"
                BleDisconnectReason.Initial -> "Disconnected(Initial)"
                BleDisconnectReason.Requested -> "Disconnected(Requested)"
                BleDisconnectReason.ClientClosed -> "Disconnected(ClientClosed)"
            }
            is ConnectionState.Recovering -> "Recovering(${lastFailure.code.value})"
            ConnectionState.Connecting -> "Connecting"
            ConnectionState.DiscoveringServices -> "DiscoveringServices"
            ConnectionState.Configuring -> "Configuring"
            ConnectionState.Ready -> "Ready"
            ConnectionState.Disconnecting -> "Disconnecting"
        }

        fun AdapterState.label(): String = when (this) {
            is AdapterState.Unauthorized -> "Unauthorized(canRequest=$canRequest)"
            else -> toString()
        }

        fun ScanState.label(): String = when (this) {
            is ScanState.Idle -> lastFailure?.let { "Idle(Failed:${it.code.value})" } ?: "Idle"
            is ScanState.Scanning -> "Scanning"
        }

        fun CharacteristicRef.label(): String = when (this) {
            PROBE_VALUE -> "probe.value"
            PROBE_CONTROL -> "probe.control"
            else -> uuid.value.toString()
        }

        fun adapterName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "STATE_$state"
        }
    }
}
