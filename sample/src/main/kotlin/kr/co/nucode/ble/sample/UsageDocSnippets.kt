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
 */

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
import kr.co.nucode.ble.BleReconnectPolicy
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
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
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
suspend fun scanForService(
    client: BleClient,
    serviceUuid: UUID,
    timeoutMillis: Long,
): List<ScanResult> {
    val found = LinkedHashMap<PeripheralId, ScanResult>()
    client.startScan(
        ScanRequest(
            filter = BleScanFilter(serviceUuids = listOf(BleUuid(serviceUuid))),
            options = BleScanOptions(BleScanMode.LowLatency),
        ),
    )
    try {
        withTimeoutOrNull(timeoutMillis) {
            client.scanResults.collect { result -> found[result.peripheralId] = result }
        }
    } finally {
        client.stopScan()
    }
    return found.values.sortedByDescending { it.rssi }
}

// ── §5. 연결 ──────────────────────────────────────────────────────────
suspend fun connect(client: BleClient, peripheralId: PeripheralId): BleSession =
    client.connect(
        peripheralId,
        BleConnectOptions(
            reconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                maxAttempts = 3,
                initialDelayMillis = 1_000,
                maxDelayMillis = 10_000,
            ),
        ),
    )

// 문서 §5 의 "연결 직후 Ready 대기" 예시 — 문서에서는 호출부 두 줄만 보여준다.
suspend fun connectThenAwaitReady(client: BleClient, peripheralId: PeripheralId) {
    val session = connect(client, peripheralId)
    session.connectionState.first { it is ConnectionState.Ready }
    fun observeConnection(session: BleSession, scope: CoroutineScope): Job =
        session.connectionState
            .onEach { state ->
                when (state) {
                    ConnectionState.Ready -> Log.i("ble", "ready")
                    ConnectionState.Connecting,
                    ConnectionState.DiscoveringServices,
                    ConnectionState.Configuring,
                    ConnectionState.Disconnecting,
                    -> Log.d("ble", "progress: $state")
                    is ConnectionState.Recovering ->
                        Log.w("ble", "retry ${state.attempt} in ${state.retryAfterMillis}ms")
                    is ConnectionState.Disconnected -> when (val reason = state.reason) {
                        BleDisconnectReason.Requested -> Log.i("ble", "사용자 해제")
                        BleDisconnectReason.ClientClosed -> Log.i("ble", "클라이언트 종료")
                        BleDisconnectReason.Initial -> Log.d("ble", "아직 연결한 적 없음")
                        is BleDisconnectReason.Failed -> Log.e("ble", explain(reason.failure))
                    }
                }
            }
            .launchIn(scope)
}

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
    val collector = scope.launch { session.notifications(characteristic).collect(onValue) }
    session.subscribe(characteristic)
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

// ── §13. 통합 예시 ───────────────────────────────────────────────────────
class DeviceRepository(
    private val client: BleClient,
    private val scope: CoroutineScope,
    private val serviceUuid: UUID,
    private val txUuid: UUID,
    private val rxUuid: UUID,
) {
    private var session: BleSession? = null
    private var notificationJob: Job? = null

    fun observeEvents(): Job = client.events
        .onEach { event ->
            if (event is OfficialBleClientEvent.SubscriptionRestoreFailed) {
                Log.w("ble", "재구독 실패: ${event.characteristic.uuid.value}")
            }
        }
        .launchIn(scope)

    suspend fun open(peripheralId: PeripheralId, onValue: (ByteArray) -> Unit) {
        awaitAdapterReady(client)
        val opened = connect(client, peripheralId)
        session = opened
        opened.connectionState.first { it is ConnectionState.Ready }
        val rx = requireNotNull(findCharacteristic(opened, serviceUuid, rxUuid)) {
            "rx characteristic not found"
        }
        notificationJob = streamNotifications(opened, rx, scope, onValue)
    }

    suspend fun send(payload: ByteArray): WriteReceipt? {
        val opened = session ?: return null
        val tx = findCharacteristic(opened, serviceUuid, txUuid) ?: return null
        return write(opened, tx, payload)
    }

    suspend fun close() {
        notificationJob?.cancel()
        notificationJob = null
        session?.disconnect()
        session = null
    }
}
