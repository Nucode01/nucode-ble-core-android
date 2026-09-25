package kr.co.nucode.ble.sample

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
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
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.android.AndroidBleClientFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실기기에서만 확인할 수 있는 전제(P1–P7)를 한 번에 돌리는 검증 키트.
 *
 * 각 테스트는 관측값을 `BlePremise` 태그에 `PREMISE|<id>|<key>=<value>|...` 한 줄 형식으로 남기고,
 * 라이브러리가 약속한 동작만 단언한다. 플랫폼 동작 자체(재연결 여부·status 값·스로틀링)는 기록만 한다.
 * 실행·기록 절차는 `sample/DEVICE_VERIFICATION.md` 를 본다.
 */
@RunWith(AndroidJUnit4::class)
class DevicePremiseVerificationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*requiredPermissions())

    private lateinit var client: BleClient
    private var activeSession: BleSession? = null
    private var bluetoothToggled = false

    /** runBlocking 자식이 아닌 이벤트 수집용 — 테스트가 중간에 던져도 매달리지 않고 [tearDown] 에서 정리된다. */
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diagnostics = mutableListOf<BleFailure>()

    @Before
    fun setUp() {
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true to run hardware tests",
            InstrumentationRegistry.getArguments().getString(ARG_HARDWARE_BLE) == "true",
        )
        client = createClient()
    }

    @After
    fun tearDown() {
        if (!::client.isInitialized) return
        if (bluetoothToggled) shell("svc bluetooth enable")
        runBlocking {
            runCatching { withTimeout(10_000) { activeSession?.disconnect() } }
            runCatching { client.stopScan() }
        }
        eventScope.cancel()
        client.close()
    }

    /** P1 — autoConnect relink: 펌웨어가 끊으면 Recovering 을 거쳐 OS 재연결로 Ready 와 구독 복원에 도달하는가. */
    @Test(timeout = 150_000)
    fun p1AutoConnectRelinkRestoresSubscriptions() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        val session = client.connect(peripheralId, BleConnectOptions(autoReconnect = true)).also { activeSession = it }
        awaitReady(session)
        val nus = session.nusCharacteristics()
        val messages = session.openNotifications(nus.tx)
        try {
            session.subscribe(nus.tx)
            val seqBefore = messages.awaitSequence()

            val t0 = SystemClock.elapsedRealtime()
            session.writeCommand(nus.rx, "IT:DISCONNECT")
            val recovering = withTimeout(RELINK_TIMEOUT_MILLIS) {
                session.connectionState.first { it is ConnectionState.Recovering }
            } as ConnectionState.Recovering
            val readyAt = withTimeoutOrNull(RELINK_TIMEOUT_MILLIS) {
                session.connectionState.first { it is ConnectionState.Ready }
                SystemClock.elapsedRealtime()
            }
            val restored = nus.tx in session.activeSubscriptions.value
            val seqAfter = if (readyAt != null) runCatching { messages.awaitSequence { it > seqBefore } }.getOrNull() else null
            premise(
                "P1",
                "outcome" to if (readyAt != null) "ready" else "timeout",
                "elapsedMs" to readyAt?.let { it - t0 },
                "lastFailure" to recovering.lastFailure.code.value,
                *recovering.lastFailure.platformFields(),
                "subscriptionRestored" to restored,
                "seqBefore" to seqBefore,
                "seqAfter" to seqAfter,
            )
            assertTrue("Ready not reached within ${RELINK_TIMEOUT_MILLIS}ms after Recovering", readyAt != null)
            assertTrue("activeSubscriptions missing ${nus.tx} after relink", restored)
            assertTrue("notify seq did not continue after relink", seqAfter != null && seqAfter > seqBefore)
        } finally {
            messages.close()
        }
    }

    /** P2 — Ready(autoReconnect) 중 BT off/on: Disconnected(ADAPTER_UNAVAILABLE) 뒤 한 번 다시 붙어 Ready 에 가는가. */
    @Test(timeout = 180_000)
    fun p2BluetoothToggleWithAutoReconnectRelinksOnce() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        val session = client.connect(peripheralId, BleConnectOptions(autoReconnect = true)).also { activeSession = it }
        awaitReady(session)
        val states = recordStates(session)

        bluetoothOff()
        val disconnected = withTimeout(ADAPTER_TIMEOUT_MILLIS) {
            session.connectionState.first { it is ConnectionState.Disconnected }
        } as ConnectionState.Disconnected
        val diagnosticsBeforeOn = diagnosticCount()
        bluetoothOn()
        val t0 = SystemClock.elapsedRealtime()
        val readyAt = withTimeoutOrNull(RELINK_TIMEOUT_MILLIS) {
            session.connectionState.first { it is ConnectionState.Ready }
            SystemClock.elapsedRealtime()
        }
        val codesAfterOn = diagnosticCodesSince(diagnosticsBeforeOn)
        premise(
            "P2",
            "outcome" to if (readyAt != null) "ready" else "timeout",
            "offReason" to disconnected.summary(),
            "elapsedMs" to readyAt?.let { it - t0 },
            "peripheralNotFound" to ("PERIPHERAL_NOT_FOUND" in codesAfterOn),
            "diagnostics" to codesAfterOn.joinToString(","),
            "connectingCount" to states.count { it == "Connecting" },
            "states" to states.joinToString(">"),
        )
        assertEquals(
            "ADAPTER_UNAVAILABLE",
            ((disconnected.reason as? BleDisconnectReason.Failed)?.failure?.code?.value),
        )
        assertTrue("Ready not reached within ${RELINK_TIMEOUT_MILLIS}ms after bt-on", readyAt != null)
    }

    /** P3 — BT off/on 으로 스택 캐시를 비운 뒤 새 client 의 autoReconnect 연결이 어디까지 가는가. 기록만 한다. */
    @Test(timeout = 180_000)
    fun p3ColdCacheAutoReconnectOutcomeIsRecorded() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        bluetoothOff()
        bluetoothOn()
        client.close()
        client = createClient()
        awaitAdapterReady()

        val t0 = SystemClock.elapsedRealtime()
        val session = client.connect(peripheralId, BleConnectOptions(autoReconnect = true)).also { activeSession = it }
        val states = recordStates(session)
        val readyAt = withTimeoutOrNull(RELINK_TIMEOUT_MILLIS) {
            session.connectionState.first { it is ConnectionState.Ready }
            SystemClock.elapsedRealtime()
        }
        val codes = diagnosticCodesSince(0)
        premise(
            "P3",
            "outcome" to if (readyAt != null) "ready" else "waiting",
            "elapsedMs" to readyAt?.let { it - t0 },
            "finalState" to session.connectionState.value.summary(),
            "peripheralNotFound" to ("PERIPHERAL_NOT_FOUND" in codes),
            "diagnostics" to codes.joinToString(","),
            "states" to states.joinToString(">"),
        )
        // 도달 여부 자체가 미지의 전제라 단언하지 않는다. 라이브러리 일관성만 본다: 실패 종료라면 사유가 있어야 한다.
        val finalState = session.connectionState.value
        if (finalState is ConnectionState.Disconnected) {
            assertTrue("unexpected terminal reason $finalState", finalState.reason is BleDisconnectReason.Failed)
        }
    }

    /**
     * P4 — 사용자 스캔(광고하지 않는 service UUID 필터)과 cold-cache 연결이 겹칠 때 Scanner 가 두 필터를 합쳐
     * 주소 결과로 연결을 진행시키는가, 그리고 scanResults 에는 사용자 필터에 맞는 결과만 나가는가.
     */
    @Test(timeout = 180_000)
    fun p4ScanFilterUnionKeepsUserResultsFiltered() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        bluetoothOff()
        bluetoothOn()
        client.close()
        client = createClient()
        awaitAdapterReady()

        val userRequest = ScanRequest(
            filter = BleScanFilter(serviceUuids = listOf(UNADVERTISED_SERVICE)),
            options = BleScanOptions(BleScanMode.LowLatency),
        )
        val results = mutableListOf<ScanResult>()
        val resultJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.scanResults.collect { synchronized(results) { results += it } }
        }
        try {
            client.startScan(userRequest)
            val t0 = SystemClock.elapsedRealtime()
            val session = client.connect(peripheralId).also { activeSession = it }
            val readyAt = withTimeoutOrNull(READY_TIMEOUT_MILLIS) {
                session.connectionState.first { it is ConnectionState.Ready }
                SystemClock.elapsedRealtime()
            }
            val snapshot = synchronized(results) { results.toList() }
            val mismatched = snapshot.filter { UNADVERTISED_SERVICE !in it.advertisement.serviceUuids }
            premise(
                "P4",
                "outcome" to if (readyAt != null) "ready" else "timeout",
                "addressResultArrived" to (readyAt != null),
                "elapsedMs" to readyAt?.let { it - t0 },
                "finalState" to session.connectionState.value.summary(),
                "scanState" to client.scanState.value.summary(),
                "userResults" to snapshot.size,
                "mismatchedUserResults" to mismatched.size,
                "leakedPeripheral" to mismatched.any { it.peripheralId == peripheralId },
                "diagnostics" to diagnosticCodesSince(0).joinToString(","),
            )
            assertTrue("scanResults leaked results not matching the user filter: $mismatched", mismatched.isEmpty())
        } finally {
            resultJob.cancel()
            runCatching { client.stopScan() }
        }
    }

    /**
     * P5 — 30 초 안에 스캔 시작 6 회: 6 번째 시작은 라이브러리 시작 한도로 SCAN_START_THROTTLED 를 던져야 한다
     * (플랫폼은 status 6 거절을 앱에 알리지 않는다). 결과 수·상태·진단은 기록한다.
     */
    @Test(timeout = 150_000)
    fun p5ScanStartThrottlingIsRecorded() = runBlocking {
        awaitAdapterReady()
        // 시작 기록은 프로세스 공유다 — 앞 테스트의 시작이 창에 남아 있으면 처음 5회가 한도에 걸린다.
        delay(SCAN_START_WINDOW_WAIT_MILLIS)
        val request = ScanRequest(
            filter = BleScanFilter(serviceUuids = listOf(NUS_SERVICE)),
            options = BleScanOptions(BleScanMode.LowLatency),
        )
        val thrownCodes = mutableListOf<String>()
        val idleFailures = mutableListOf<String>()
        val firstFiveResults = mutableListOf<Int>()
        val t0 = SystemClock.elapsedRealtime()
        repeat(SCAN_START_COUNT - 1) {
            val startResults = mutableListOf<ScanResult>()
            val startJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.scanResults.collect { synchronized(startResults) { startResults += it } }
            }
            startScanRecording(request, thrownCodes)
            delay(SHORT_SCAN_MILLIS)
            startJob.cancel()
            client.stopScan()
            firstFiveResults += synchronized(startResults) { startResults.size }
            (client.scanState.value as? ScanState.Idle)?.lastFailure?.let { idleFailures += it.code.value }
        }
        val thrownInFirstFive = thrownCodes.toList()
        val results = mutableListOf<ScanResult>()
        val resultJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.scanResults.collect { synchronized(results) { results += it } }
        }
        val sixthStartAtMs = SystemClock.elapsedRealtime() - t0
        val thrownBeforeSixth = thrownCodes.size
        startScanRecording(request, thrownCodes)
        val sixthThrown = thrownCodes.drop(thrownBeforeSixth)
        delay(FINAL_SCAN_MILLIS)
        val stateDuringSixth = client.scanState.value
        (stateDuringSixth as? ScanState.Idle)?.lastFailure?.let { idleFailures += it.code.value }
        resultJob.cancel()
        client.stopScan()
        premise(
            "P5",
            "firstFiveResults" to firstFiveResults.joinToString(","),
            "sixthStartAtMs" to sixthStartAtMs,
            "sixthResults" to synchronized(results) { results.size },
            "sixthScanState" to stateDuringSixth.summary(),
            "thrown" to thrownCodes.joinToString(","),
            "idleFailures" to idleFailures.joinToString(","),
            "diagnostics" to diagnosticCodesSince(0).joinToString(","),
        )
        // 한도 창이 풀릴 때까지 기다린다 — 시작 기록은 프로세스 공유라 뒤 테스트의 스캔 시작을 오염시키지 않게.
        delay(SCAN_START_WINDOW_WAIT_MILLIS)
        // 문서화된 예외(BleException) 밖의 예외는 startScanRecording 이 그대로 던진다. 끝나면 Idle 이어야 한다.
        assertEquals("first five startScan must not throw", emptyList<String>(), thrownInFirstFive)
        assertEquals("sixth startScan must throw SCAN_START_THROTTLED", listOf("SCAN_START_THROTTLED"), sixthThrown)
        assertTrue(
            "each of the first five starts must produce > 0 results: $firstFiveResults — all zero or a zero first " +
                "start means the peripheral is not advertising or out of range; a zero tail after non-zero starts " +
                "suggests an OEM start limit < 5; an isolated zero in the middle means intermittent reception",
            firstFiveResults.all { it > 0 },
        )
        assertTrue("scanState must be Idle after stopScan", client.scanState.value is ScanState.Idle)
    }

    /**
     * P6 — 연결 해제 status 값. (a) 사용자 disconnect 의 종료 사유, (b) peer(IT:DISCONNECT) 해제의
     * platformDetail status. (c) RF 손실(status 8)은 자동화할 수 없어 수동 절차로 남긴다.
     */
    @Test(timeout = 150_000)
    fun p6DisconnectStatusValuesAreRecorded() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()

        val first = client.connect(peripheralId).also { activeSession = it }
        awaitReady(first)
        first.disconnect()
        val userTerminal = withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
            first.connectionState.first { it is ConnectionState.Disconnected }
        } as ConnectionState.Disconnected
        premise("P6a", "reason" to userTerminal.summary())
        assertEquals(BleDisconnectReason.Requested, userTerminal.reason)

        val second = client.connect(peripheralId).also { activeSession = it }
        awaitReady(second)
        val nus = second.nusCharacteristics()
        second.writeCommand(nus.rx, "IT:DISCONNECT")
        val peerTerminal = withTimeout(RELINK_TIMEOUT_MILLIS) {
            second.connectionState.first { it is ConnectionState.Recovering || it is ConnectionState.Disconnected }
        }
        val peerFailure = when (peerTerminal) {
            is ConnectionState.Recovering -> peerTerminal.lastFailure
            is ConnectionState.Disconnected -> (peerTerminal.reason as? BleDisconnectReason.Failed)?.failure
            else -> null
        }
        premise(
            "P6b",
            "state" to peerTerminal.summary(),
            "code" to peerFailure?.code?.value,
            *(peerFailure?.platformFields() ?: emptyArray()),
            "expected" to "19/GATT_CONN_TERMINATE_PEER_USER",
        )
        premise("P6c", "outcome" to "manual", "note" to "RF-loss(status=8) needs board power-off; see DEVICE_VERIFICATION.md")
        assertTrue("peer disconnect must carry a failure: $peerTerminal", peerFailure != null)
    }

    /** P7 — 스캔 중 BT off: scanState 가 Idle(SCAN_STOPPED_BY_ADAPTER) 로 가는가, onScanFailed 가 왔는가. */
    @Test(timeout = 120_000)
    fun p7BluetoothOffDuringScanStopsScanWithAdapterFailure() = runBlocking {
        awaitAdapterReady()
        client.startScan(
            ScanRequest(
                filter = BleScanFilter(serviceUuids = listOf(NUS_SERVICE)),
                options = BleScanOptions(BleScanMode.LowLatency),
            ),
        )
        assertTrue(client.scanState.value is ScanState.Scanning)
        val before = diagnosticCount()
        val t0 = SystemClock.elapsedRealtime()
        bluetoothOff()
        val idle = withTimeout(ADAPTER_TIMEOUT_MILLIS) {
            client.scanState.first { it is ScanState.Idle }
        } as ScanState.Idle
        val elapsed = SystemClock.elapsedRealtime() - t0
        val codes = diagnosticCodesSince(before)
        premise(
            "P7",
            "scanState" to idle.summary(),
            "elapsedMs" to elapsed,
            "onScanFailedDelivered" to ("SCAN_RUNTIME_FAILED" in codes),
            "diagnostics" to codes.joinToString(","),
        )
        bluetoothOn()
        assertEquals("SCAN_STOPPED_BY_ADAPTER", idle.lastFailure?.code?.value)
    }

    private fun createClient(): BleClient {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val created = AndroidBleClientFactory.create(
            context,
            BleConfig(
                operationTimeoutMillis = 10_000,
                linkTimeoutMillis = 20_000,
                discoveryTimeoutMillis = 15_000,
                configurationTimeoutMillis = 15_000,
                disconnectCleanupTimeoutMillis = 7_000,
            ),
        )
        synchronized(diagnostics) { diagnostics.clear() }
        eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.events.collect { event ->
                if (event is OfficialBleClientEvent.Diagnostic) {
                    synchronized(diagnostics) { diagnostics += event.failure }
                    Log.i(TAG, "diag=${event.severity} ${event.failure.code.value} ${event.failure.platformDetail}")
                } else {
                    Log.i(TAG, "event=$event")
                }
            }
        }
        return created
    }

    private fun diagnosticCount(): Int = synchronized(diagnostics) { diagnostics.size }

    private fun diagnosticCodesSince(index: Int): List<String> =
        synchronized(diagnostics) { diagnostics.drop(index).map { it.code.value } }

    /** 상태 전이를 모은다. [eventScope] 에서 돌려 runBlocking 이 수집기를 기다리지 않게 한다. */
    private fun recordStates(session: BleSession): List<String> {
        val states = Collections.synchronizedList(mutableListOf<String>())
        eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.connectionState.collect {
                states += it.summary()
                Log.i(TAG, "state=${it.summary()}")
            }
        }
        return states
    }

    private suspend fun startScanRecording(request: ScanRequest, thrownCodes: MutableList<String>) {
        try {
            client.startScan(request)
        } catch (failure: BleException) {
            thrownCodes += failure.failure.code.value
        }
    }

    private fun bluetoothOff() {
        bluetoothToggled = true
        shell("svc bluetooth disable")
        runBlocking {
            withTimeout(ADAPTER_TIMEOUT_MILLIS) { client.adapterState.first { it !is AdapterState.Ready } }
        }
    }

    private fun bluetoothOn() {
        shell("svc bluetooth enable")
        runBlocking {
            withTimeout(ADAPTER_ON_TIMEOUT_MILLIS) { client.adapterState.first { it is AdapterState.Ready } }
        }
        bluetoothToggled = false
    }

    private fun shell(command: String) {
        val out = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(command)
        Log.i(TAG, "SHELL $command -> ${out.trim()}")
    }

    private fun premise(id: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, (listOf("PREMISE", id) + fields.map { (k, v) -> "$k=${v ?: "none"}" }).joinToString("|"))
    }

    private fun BleFailure.platformFields(): Array<Pair<String, Any?>> {
        val detail = platformDetail as? PlatformDetail.Android
        return arrayOf("statusCode" to detail?.statusCode, "symbolicName" to detail?.symbolicName)
    }

    private fun ConnectionState.summary(): String = when (this) {
        is ConnectionState.Disconnected -> when (val r = reason) {
            is BleDisconnectReason.Failed -> "Disconnected(Failed:${r.failure.code.value})"
            else -> "Disconnected($r)"
        }
        is ConnectionState.Recovering -> "Recovering(${lastFailure.code.value})"
        else -> toString()
    }

    private fun ScanState.summary(): String = when (this) {
        is ScanState.Idle -> "Idle(${lastFailure?.code?.value ?: "none"})"
        is ScanState.Scanning -> "Scanning"
    }

    private suspend fun awaitAdapterReady() {
        withTimeout(ADAPTER_ON_TIMEOUT_MILLIS) { client.adapterState.first { it is AdapterState.Ready } }
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
        val expectedId = InstrumentationRegistry.getArguments().getString(ARG_PERIPHERAL_ID)
        startScanRetryingThrottle(ScanRequest(BleScanFilter(serviceUuids = listOf(NUS_SERVICE)), BleScanOptions(BleScanMode.LowLatency)))
        return try {
            withTimeout(SCAN_TIMEOUT_MILLIS) {
                client.scanResults.first { expectedId == null || it.peripheralId.value.equals(expectedId, ignoreCase = true) }.peripheralId
            }
        } finally {
            client.stopScan()
        }
    }

    private suspend fun awaitReady(session: BleSession, timeoutMillis: Long = READY_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) { session.connectionState.first { it is ConnectionState.Ready } }
    }

    private fun BleSession.nusCharacteristics(): NusCharacteristics {
        val service = services.value.firstOrNull { it.ref.uuid == NUS_SERVICE }
            ?: error("NUS service was not discovered")
        val rx = service.characteristics.firstOrNull { it.ref.uuid == NUS_RX }?.ref
            ?: error("NUS RX characteristic was not discovered")
        val tx = service.characteristics.firstOrNull { it.ref.uuid == NUS_TX }?.ref
            ?: error("NUS TX characteristic was not discovered")
        return NusCharacteristics(rx, tx)
    }

    /** 알림을 문자열 채널로 모은다. 수집기는 [eventScope] 에 붙여 [tearDown] 에서 함께 정리한다. */
    private fun BleSession.openNotifications(tx: CharacteristicRef): Channel<String> {
        val messages = Channel<String>(Channel.UNLIMITED)
        eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
            notifications(tx).collect { messages.trySend(it.decodeToString().trim()) }
        }
        return messages
    }

    private suspend fun BleSession.writeCommand(rx: CharacteristicRef, command: String) {
        write(
            rx,
            command.encodeToByteArray(),
            BleWriteOptions(mode = BleWriteMode.WithResponse, chunking = BleChunking.Automatic),
        )
    }

    private suspend fun ReceiveChannel<String>.awaitSequence(predicate: (Long) -> Boolean = { true }): Long =
        withTimeout(STATUS_TIMEOUT_MILLIS) {
            while (true) {
                val message = receive()
                if (!message.startsWith("IT|NTFY|seq=")) continue
                val seq = message.substringAfter("seq=").substringBefore('|').toLongOrNull() ?: continue
                if (predicate(seq)) return@withTimeout seq
            }
            error("unreachable")
        }

    private data class NusCharacteristics(
        val rx: CharacteristicRef,
        val tx: CharacteristicRef,
    )

    private companion object {
        const val ARG_HARDWARE_BLE = "hardwareBle"
        const val ARG_PERIPHERAL_ID = "blePeripheralId"
        const val TAG = "BlePremise"
        const val SCAN_TIMEOUT_MILLIS = 20_000L
        const val READY_TIMEOUT_MILLIS = 30_000L
        /** 플랫폼 autoConnect 재연결은 느릴 수 있어 넉넉히 잡는다. */
        const val RELINK_TIMEOUT_MILLIS = 60_000L
        const val ADAPTER_TIMEOUT_MILLIS = 20_000L
        const val ADAPTER_ON_TIMEOUT_MILLIS = 30_000L
        const val DISCONNECT_TIMEOUT_MILLIS = 15_000L
        const val STATUS_TIMEOUT_MILLIS = 10_000L
        const val SCAN_START_COUNT = 6
        /** 5 회 x (1 s 스캔 + 여유) 로 30 초 창 안에 6 번째 시작이 들어가게 한다. */
        const val SHORT_SCAN_MILLIS = 1_000L
        const val FINAL_SCAN_MILLIS = 5_000L
        /** 스캔 시작 한도 창(30 s + 여유 1 s) 이상 — 창이 풀릴 때까지의 대기. */
        const val SCAN_START_WINDOW_WAIT_MILLIS = 31_000L

        val NUS_SERVICE = BleUuid(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        val NUS_RX = BleUuid(UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"))
        val NUS_TX = BleUuid(UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"))
        /** 테스트 펌웨어가 광고하지 않는 임의 service UUID (P4). */
        val UNADVERTISED_SERVICE = BleUuid(UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb"))

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
