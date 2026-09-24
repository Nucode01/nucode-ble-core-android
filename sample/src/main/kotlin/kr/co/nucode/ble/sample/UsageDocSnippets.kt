@file:Suppress("unused")

package kr.co.nucode.ble.sample

/*
 * 공개 API 컴파일 계약 — 소비자가 실제로 쓰는 호출 경로를 빌드로 고정한다.
 * [OfficialApiCompileContract] 가 "API 표면이 존재하는가"를 지킨다면, 이 파일은
 * "권장 사용 패턴이 여전히 컴파일되는가"를 지킨다.
 *
 * 공개 API 가 바뀌면 여기서 먼저 깨진다. 그때 같이 고쳐야 할 것이 하나 더 있다 —
 * 같은 코드를 담은 사용법 문서(docs/ble-core-usage.md)다. 그 문서는 저장소에
 * 추적되지 않는 로컬 자산이라(.gitignore: docs/) 빌드가 대신 확인해 주지 않는다.
 * 아래 §N 주석은 그 문서의 "## N." 과 같은 번호다.
 *
 * 샘플 앱은 이 선언들을 호출하지 않는다. 컴파일되는 것 자체가 목적이다.
 * §14(라이브러리 → Repository → ViewModel → Compose UI)는 [UsageDocUiSnippets.kt] 에 있다.
 */

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicProperty
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DeliverySemantics
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.WriteReceipt
import kr.co.nucode.ble.android.AndroidBleClientFactory
import kr.co.nucode.ble.android.AndroidBondController
import kr.co.nucode.ble.android.AndroidBondResult
import kr.co.nucode.ble.android.AndroidBondState

// ── §2. 생성과 종료 ──────────────────────────────────────────────────────
class BleApp : Application() {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var client: BleClient
        private set

    override fun onCreate() {
        super.onCreate()
        client = AndroidBleClientFactory.create(this, BleConfig())
    }

    fun shutdown() {
        client.close()
        appScope.cancel()
    }
}

// ── §3. 권한과 어댑터 상태 ──────────────────────────────────────────────────
fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun describeAdapter(state: AdapterState): String = when (state) {
    AdapterState.Ready -> "사용 가능"
    AdapterState.PoweredOff -> "블루투스를 켜 달라고 안내한다"
    AdapterState.Resetting -> "어댑터 재시작 중 — 기다린다"
    AdapterState.Unsupported -> "이 기기는 BLE 를 지원하지 않는다"
    is AdapterState.Unauthorized ->
        if (state.canRequest) "런타임 권한을 요청한다" else "매니페스트 권한 선언이 빠졌다(앱 빌드 결함)"
}

suspend fun awaitAdapterReady(client: BleClient) {
    client.adapterState.first { it is AdapterState.Ready }
}

// ── §4. 스캔 ──────────────────────────────────────────────────────────
suspend fun scanFor(
    client: BleClient,
    filter: BleScanFilter,
    timeoutMillis: Long,
): List<ScanResult> {
    val found = LinkedHashMap<PeripheralId, ScanResult>()
    client.startScan(ScanRequest(filter, BleScanOptions(BleScanMode.LowLatency)))
    try {
        withTimeoutOrNull(timeoutMillis) {
            client.scanResults.collect { result -> found[result.peripheralId] = result }
        }
    } finally {
        // 호출자가 취소하면 finally 안의 suspend 호출도 즉시 취소된다 — NonCancellable 로 감싸야 실제로 멈춘다.
        withContext(NonCancellable) { client.stopScan() }
    }
    return found.values.sortedByDescending { it.rssi }
}

suspend fun scanForService(client: BleClient, serviceUuid: UUID, timeoutMillis: Long): List<ScanResult> =
    scanFor(client, BleScanFilter(serviceUuids = listOf(BleUuid(serviceUuid))), timeoutMillis)

/** 스캔이 실패로 멈춘 이유만 흘려보낸다. `scanState` 는 StateFlow 라 유실되지 않는다. */
fun scanStopFailures(client: BleClient): Flow<BleFailure> =
    client.scanState
        .filterIsInstance<ScanState.Idle>()
        .mapNotNull { it.lastFailure }

// ── §5. 연결 ──────────────────────────────────────────────────────────
suspend fun connect(client: BleClient, peripheralId: PeripheralId): BleSession =
    client.connect(
        peripheralId,
        // 끊겨도 OS 가 다시 붙일 때까지 Recovering 으로 기다린다. 포기는 disconnect().
        BleConnectOptions(autoReconnect = true),
    )

/**
 * Ready 가 되면 반환하고, 그 전에 Disconnected 로 끝나면 던진다.
 * `first { it is Ready }` 만 쓰면 연결이 실패했을 때 영원히 기다린다.
 * `autoReconnect = true` 면 Connecting·Recovering 에 기한이 없어 Disconnected 도 안 올 수 있다 —
 * 그래서 [timeoutMillis] 로 자르고, 넘기면 `TimeoutCancellationException` 을 던진다.
 */
suspend fun awaitReady(session: BleSession, timeoutMillis: Long = 30_000) {
    val settled = withTimeout(timeoutMillis) {
        session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
    }
    if (settled is ConnectionState.Disconnected) {
        val reason = settled.reason
        if (reason is BleDisconnectReason.Failed) throw BleException(reason.failure)
        throw IllegalStateException("Ready 전에 연결이 끝났다: $reason")
    }
}

/**
 * 직접 재연결은 앱이 한다 — autoReconnect=false 로 연결하고, 링크가 `Disconnected(Failed)` 로 끝나거나
 * 시도가 [attemptTimeoutMillis] 안에 Ready 가 되지 못하면 간격을 두고 정해진 횟수까지 `connect()` 를 다시 부른다.
 * 사용자 해제(`Requested`)·`close()`(`ClientClosed`)로 끝나면 정상 반환한다. 횟수를 다 쓰면 마지막 실패를
 * 던진다(시간 초과였으면 IllegalStateException). 이 함수를 돌리는 코루틴을 취소하면 그대로 취소된다.
 */
suspend fun reconnectWithRetry(
    client: BleClient,
    peripheralId: PeripheralId,
    maxAttempts: Int = 3,
    retryDelayMillis: Long = 2_000,
    // 기본 단계 기한의 합(rediscovery 3 s + link 15 s + discovery 10 s + configuration 10 s = 38 s)보다 짧으면
    // 코어가 아직 진행 중인 시도를 앱이 먼저 끊게 된다.
    attemptTimeoutMillis: Long = 40_000,
) {
    var failures = 0
    while (true) {
        val session = try {
            client.connect(peripheralId, BleConnectOptions(autoReconnect = false))
        } catch (e: BleException) {
            // 시작 자체가 실패한 것(예: PERIPHERAL_NOT_FOUND)도 한 번의 실패로 센다.
            if (++failures >= maxAttempts) throw e
            delay(retryDelayMillis)
            continue
        }
        val settled = withTimeoutOrNull(attemptTimeoutMillis) {
            session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
        }
        val ended = when (settled) {
            null -> null.also { session.disconnect() } // 시간 초과: 이 시도를 정리하고 재시도
            is ConnectionState.Ready -> {
                failures = 0 // Ready 까지 갔으면 예산을 되돌린다
                session.connectionState.filterIsInstance<ConnectionState.Disconnected>().first()
            }
            else -> settled as ConnectionState.Disconnected
        }
        val failure = ended?.let {
            (it.reason as? BleDisconnectReason.Failed)?.failure ?: return // 사용자 해제·close — 재시도하지 않는다
        }
        if (++failures >= maxAttempts) {
            throw failure?.let(::BleException) ?: IllegalStateException("${attemptTimeoutMillis}ms 안에 Ready 가 되지 않았다")
        }
        delay(retryDelayMillis)
    }
}

suspend fun connectThenAwaitReady(client: BleClient, peripheralId: PeripheralId): BleSession {
    val session = connect(client, peripheralId)
    try {
        awaitReady(session)
    } catch (e: TimeoutCancellationException) {
        session.disconnect() // 기한 없는 autoReconnect 연결을 뒤에 남기지 않는다
        throw e
    }
    return session
}

fun describeConnection(state: ConnectionState): String = when (state) {
    ConnectionState.Connecting -> "연결 중"
    ConnectionState.DiscoveringServices -> "서비스 탐색 중"
    ConnectionState.Configuring -> "설정 중"
    ConnectionState.Ready -> "연결됨"
    ConnectionState.Disconnecting -> "해제 중"
    is ConnectionState.Recovering -> "재연결 대기 중"
    is ConnectionState.Disconnected -> when (val reason = state.reason) {
        BleDisconnectReason.Initial -> "연결 안 됨"
        BleDisconnectReason.Requested -> "연결 해제됨"
        BleDisconnectReason.ClientClosed -> "클라이언트 종료됨"
        is BleDisconnectReason.Failed -> "끊김: ${reason.failure.code.value}"
    }
}

fun observeConnection(session: BleSession, scope: CoroutineScope): Job =
    session.connectionState
        .onEach { state -> Log.i("ble", "${session.peripheralId.value}: ${describeConnection(state)}") }
        .launchIn(scope)

// ── §6. 서비스와 캐릭터리스틱 찾기 ──────────────────────────────────────────────
fun findCharacteristic(
    session: BleSession,
    serviceUuid: UUID,
    characteristicUuid: UUID,
): CharacteristicRef? =
    session.services.value
        .firstOrNull { it.ref.uuid.value == serviceUuid }
        ?.characteristics
        ?.firstOrNull { it.ref.uuid.value == characteristicUuid }
        ?.ref

fun writableCharacteristics(session: BleSession): List<CharacteristicRef> =
    session.services.value
        .flatMap { it.characteristics }
        .filter { CharacteristicProperty.WriteWithResponse in it.properties }
        .map { it.ref }

// ── §7. read / write ────────────────────────────────────────────────
suspend fun read(session: BleSession, characteristic: CharacteristicRef): ByteArray? =
    try {
        session.read(characteristic)
    } catch (e: BleException) {
        Log.e("ble", explain(e.failure))
        null
    }

suspend fun write(
    session: BleSession,
    characteristic: CharacteristicRef,
    payload: ByteArray,
): WriteReceipt? =
    try {
        session.write(
            characteristic,
            payload,
            BleWriteOptions(mode = BleWriteMode.WithResponse, chunking = BleChunking.Automatic),
        )
    } catch (e: BleException) {
        val progress = e.failure.writeProgress
        if (progress != null) {
            Log.e(
                "ble",
                "write 실패: ${progress.bytesCompleted}/${progress.totalBytes} bytes 전달됨, " +
                    "chunk ${progress.chunksCompleted}/${progress.totalChunks}, " +
                    "마지막 청크 전달 여부 불명=${progress.currentChunkDeliveryUnknown}",
            )
        }
        null
    }

// ── §8. notify 구독 ───────────────────────────────────────────────────
suspend fun streamNotifications(
    session: BleSession,
    characteristic: CharacteristicRef,
    scope: CoroutineScope,
    onValue: (ByteArray) -> Unit,
): Job {
    // UNDISPATCHED: launch 가 반환되기 전에 수집자가 등록된다 — subscribe() 직후 값도 놓치지 않는다.
    val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.notifications(characteristic).collect(onValue)
    }
    try {
        session.subscribe(characteristic)
    } catch (e: BleException) {
        collector.cancel()
        throw e
    }
    return collector
}

suspend fun stopNotifications(
    session: BleSession,
    characteristic: CharacteristicRef,
    collector: Job,
) {
    session.unsubscribe(characteristic)
    collector.cancel()
}

/** 이 캐릭터리스틱의 통지가 지금 연결에서 켜져 있는가. 재연결 뒤 복원에 실패하면 Ready 여도 false 다. */
fun isReceiving(session: BleSession, characteristic: CharacteristicRef): Flow<Boolean> =
    session.activeSubscriptions.map { characteristic in it }

// ── §9. 실패 처리 ───────────────────────────────────────────────────────
fun explain(failure: BleFailure): String {
    val head = "${failure.category.value}/${failure.code.value} phase=${failure.phase}"
    val advice = when (failure.retryability) {
        Retryability.Never -> "재시도해도 같은 결과다 — 입력이나 앱 설정을 고친다"
        Retryability.AfterEnvironmentChange -> "블루투스/권한 같은 환경이 바뀐 뒤에 다시 시도한다"
        Retryability.NewConnectionMayHelp -> "연결을 새로 맺은 뒤 다시 시도한다"
        Retryability.TransientMayHelp -> "잠깐 뒤 그대로 다시 시도해도 된다"
        Retryability.Unknown -> "분류 불가 — 진단 로그를 본다"
    }
    return "$head :: $advice"
}

fun userMessage(failure: BleFailure): String = when (failure.code.value) {
    "ADAPTER_UNAVAILABLE" -> "블루투스를 켜 주세요"
    "PERMISSION_DENIED" -> "블루투스 권한이 필요합니다"
    "PERIPHERAL_NOT_FOUND" -> "기기를 찾지 못했습니다. 가까이 두고 다시 시도하세요"
    "LINK_TIMEOUT", "LINK_FAILED" -> "기기와 연결하지 못했습니다"
    "NOT_READY", "NOT_CONNECTED" -> "기기가 아직 준비되지 않았습니다"
    // 보안 요구(인증·암호화 부족)는 별도 코드가 아니라 GATT_STATUS_FAILED + AfterEnvironmentChange 로 온다.
    "GATT_STATUS_FAILED" ->
        if (failure.retryability == Retryability.AfterEnvironmentChange) "기기와 페어링이 필요할 수 있습니다" else "기기가 요청을 거절했습니다"
    "SCAN_ALREADY_RUNNING" -> "이미 다른 조건으로 찾는 중입니다"
    "SCAN_STOPPED_BY_ADAPTER" -> "블루투스가 꺼져 찾기를 멈췄습니다"
    else -> "블루투스 오류 (${failure.code.value})"
}

// ── §10. 진단 로깅 ───────────────────────────────────────────────────────
fun configWithLogger(safe: Set<CharacteristicRef>): BleConfig = BleConfig(
    logger = BleLogger { record: DiagnosticRecord ->
        Log.d(
            "ble-diag",
            "[${record.severity}] ${record.failure.category.value}/${record.failure.code.value} " +
                record.values.entries.joinToString { "${it.key}=${it.value}" },
        )
    },
    diagnosticOptions = DiagnosticOptions(
        rawPayloadsEnabled = true,
        maxRawPayloadBytes = 64,
        safeCharacteristics = safe,
    ),
)

// ── §11. 본딩 (Android 전용) ─────────────────────────────────────────────
fun bondController(context: Context): AndroidBondController =
    AndroidBleClientFactory.createBondController(context)

suspend fun ensureBonded(
    controller: AndroidBondController,
    peripheralId: PeripheralId,
): Boolean {
    if (controller.bondState(peripheralId) == AndroidBondState.Bonded) return true
    return when (controller.createBond(peripheralId, timeoutMillis = 30_000)) {
        AndroidBondResult.Bonded, AndroidBondResult.AlreadyBonded -> true
        AndroidBondResult.StartRejected, AndroidBondResult.Failed, AndroidBondResult.TimedOut -> false
    }
}

// ── §12. snapshot ────────────────────────────────────────────────────
suspend fun dumpSnapshot(client: BleClient): String {
    val snapshot = client.snapshot()
    return buildString {
        appendLine("adapter=${snapshot.adapterState} scanning=${snapshot.isScanning}")
        snapshot.sessions.forEach { session ->
            appendLine(
                "${session.peripheralId.value} ${session.connectionState} " +
                    "services=${session.services.size} subscriptions=${session.subscriptions.size}",
            )
        }
    }
}

// ── §13.1 이름으로 찾아 가장 가까운 기기에 연결 ──────────────────────────────────
suspend fun connectNearest(client: BleClient, namePrefix: String, scanMillis: Long = 5_000): BleSession? {
    awaitAdapterReady(client)
    val nearest = scanFor(client, BleScanFilter(namePrefix = namePrefix), scanMillis).firstOrNull()
        ?: return null
    return connectThenAwaitReady(client, nearest.peripheralId)
}

// ── §13.2 요청-응답 (write 하고 notify 로 온 응답 하나를 기다린다) ─────────────────────
suspend fun request(
    session: BleSession,
    command: CharacteristicRef,
    response: CharacteristicRef,
    payload: ByteArray,
    timeoutMillis: Long = 3_000,
): ByteArray = coroutineScope {
    // 응답 수집을 write 보다 먼저 등록한다. 구독(subscribe)은 연결 초기에 한 번 해 두었다고 가정한다.
    val reply = async(start = CoroutineStart.UNDISPATCHED) { session.notifications(response).first() }
    try {
        session.write(command, payload)
        withTimeout(timeoutMillis) { reply.await() }
    } finally {
        reply.cancel()
    }
}

// ── §13.3 긴 데이터 전송과 부분 실패 ─────────────────────────────────────────
sealed interface SendOutcome {
    data class Delivered(val confirmedByDevice: Boolean) : SendOutcome
    data class Partial(val bytesDelivered: Int, val lastChunkUncertain: Boolean) : SendOutcome
    data class NotSent(val failure: BleFailure) : SendOutcome
}

suspend fun sendLarge(session: BleSession, characteristic: CharacteristicRef, data: ByteArray): SendOutcome =
    try {
        val receipt = session.write(characteristic, data, BleWriteOptions(chunking = BleChunking.Automatic))
        SendOutcome.Delivered(confirmedByDevice = receipt.delivery == DeliverySemantics.RemoteAcknowledged)
    } catch (e: BleException) {
        val progress = e.failure.writeProgress
        if (progress == null || progress.bytesCompleted == 0 && !progress.currentChunkDeliveryUnknown) {
            SendOutcome.NotSent(e.failure)
        } else {
            SendOutcome.Partial(progress.bytesCompleted, progress.currentChunkDeliveryUnknown)
        }
    }

// ── §13.4 Ready 인 동안만 주기적으로 read ──────────────────────────────────────
@OptIn(ExperimentalCoroutinesApi::class)
fun pollWhileReady(session: BleSession, characteristic: CharacteristicRef, periodMillis: Long): Flow<ByteArray> =
    session.connectionState
        .map { it is ConnectionState.Ready }
        .distinctUntilChanged()
        .flatMapLatest { ready ->
            if (!ready) {
                emptyFlow()
            } else {
                flow {
                    while (true) {
                        val value = try {
                            session.read(characteristic)
                        } catch (e: BleException) {
                            // 끊기는 중이면 곧 Ready 가 풀리고, 다음 Ready 에서 다시 시작한다.
                            Log.w("ble", explain(e.failure))
                            return@flow
                        }
                        emit(value)
                        delay(periodMillis)
                    }
                }
            }
        }

fun logBatteryLevel(session: BleSession, scope: CoroutineScope): Job {
    // 배터리 서비스(0x180F)의 레벨(0x2A19)을 30초마다
    val battery = CharacteristicRef(
        ServiceRef(BleUuid(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))),
        BleUuid(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")),
    )
    return pollWhileReady(session, battery, periodMillis = 30_000)
        .onEach { level -> Log.i("ble", "battery ${level.firstOrNull()?.toInt()}%") }
        .launchIn(scope)
}

// ── §13.5 블루투스 껐다 켜기까지 버티는 연결 유지 ─────────────────────────────────
fun keepConnected(client: BleClient, peripheralId: PeripheralId, scope: CoroutineScope): Job = scope.launch {
    client.adapterState.collectLatest { adapter ->
        if (adapter != AdapterState.Ready) return@collectLatest // 꺼지면 collectLatest 가 아래 루프를 취소한다
        while (true) {
            try {
                val session = connectThenAwaitReady(client, peripheralId)
                val ended = session.connectionState.first { it is ConnectionState.Disconnected }
                val reason = (ended as ConnectionState.Disconnected).reason
                if (reason == BleDisconnectReason.Requested || reason == BleDisconnectReason.ClientClosed) return@collectLatest
            } catch (e: BleException) {
                if (e.failure.retryability == Retryability.Never) return@collectLatest
                Log.w("ble", explain(e.failure))
            }
            delay(5_000)
        }
    }
}

// ── §13.6 여러 기기 동시 연결 ───────────────────────────────────────────────
suspend fun connectAll(client: BleClient, ids: List<PeripheralId>): Map<PeripheralId, BleSession?> =
    coroutineScope {
        ids.map { id ->
            async {
                id to try {
                    connectThenAwaitReady(client, id)
                } catch (e: BleException) {
                    Log.w("ble", "${id.value}: ${explain(e.failure)}")
                    null
                }
            }
        }.awaitAll().toMap()
    }
