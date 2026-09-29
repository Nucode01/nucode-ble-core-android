@file:Suppress("unused")

package kr.co.nucode.ble.sample

/*
 * guide/README.md 의 Kotlin 코드 블록 컴파일 검증본.
 *
 * `// region guide:<이름>` 과 `// endregion` 사이가 문서의 코드 블록과 글자 그대로 같다.
 * 문서의 예시를 고치면 여기를 같이 고치고 `./gradlew :sample:assembleDebug` 로 확인한다.
 * Compose·ViewModel 예시는 [GuideUiSnippets.kt] 에 있다.
 *
 * 샘플 앱은 이 선언들을 호출하지 않는다. 컴파일되는 것 자체가 목적이다.
 * 공개 API(kr.co.nucode.ble, kr.co.nucode.ble.android)만 쓴다 — internal import 금지.
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ClientSnapshot
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DeliverySemantics
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.GattCharacteristic
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.WriteProgress
import kr.co.nucode.ble.WriteReceipt
import kr.co.nucode.ble.android.AndroidBleClientFactory
import kr.co.nucode.ble.android.AndroidBondResult
import kr.co.nucode.ble.android.AndroidBondState

// region guide:application
class MyApplication : Application() {
    lateinit var bleClient: BleClient
        private set

    // 화면보다 오래 살아야 하는 정리 작업(예: ViewModel 이 사라질 때 연결 해제)용
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // 프로세스당 하나만 만듭니다. Activity·ViewModel 마다 만들지 않습니다.
        bleClient = AndroidBleClientFactory.create(this)
    }
}
// endregion

// region guide:permissions
/** 런타임에 요청할 권한(앱 targetSdk 31 이상 기준). */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** 사용자에게 보여 줄 안내 문구. 쓸 수 있는 상태(Ready)면 null. */
fun adapterMessage(state: AdapterState): String? = when (state) {
    AdapterState.Ready -> null
    AdapterState.PoweredOff -> "블루투스를 켜 주세요"
    AdapterState.Resetting -> "블루투스가 다시 시작되는 중입니다"
    AdapterState.Unknown -> "블루투스 상태를 확인하는 중입니다"
    AdapterState.Unsupported -> "이 기기는 BLE 를 지원하지 않습니다"
    is AdapterState.Unauthorized ->
        if (state.canRequest) "블루투스 권한을 허용해 주세요" else "매니페스트에 권한 선언이 빠졌습니다(앱 빌드 문제)"
}
// endregion

// region guide:nus-refs
// 예시 UUID: Nordic UART Service(NUS). 실제 기기의 UUID 로 바꿉니다.
val NUS_SERVICE = ServiceRef(BleUuid(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")))
val NUS_RX = CharacteristicRef(NUS_SERVICE, BleUuid(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"))) // 앱 → 기기(write)
val NUS_TX = CharacteristicRef(NUS_SERVICE, BleUuid(UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"))) // 기기 → 앱(notify)
// endregion

// region guide:quick-start
suspend fun quickStart(client: BleClient): Unit = coroutineScope {
    // 1) 스캔: NUS 서비스를 광고하는 첫 기기를 10초 안에 찾습니다.
    client.startScan(ScanRequest(BleScanFilter(serviceUuids = listOf(NUS_SERVICE.uuid))))
    val found = try {
        withTimeout(10_000) { client.scanResults.first() }
    } finally {
        client.stopScan()
    }

    // 2) 연결: connect() 는 연결을 "시작"하면 돌아옵니다. Ready 는 따로 기다립니다.
    val session = client.connect(found.peripheralId)
    val settled = withTimeout(40_000) {
        session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
    }
    check(settled == ConnectionState.Ready) { "연결 실패: $settled" }

    // 3) notify: 수집을 먼저 걸고 나서 구독합니다.
    val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
        session.notifications(NUS_TX).collect { bytes -> Log.i("ble", "수신: ${bytes.decodeToString()}") }
    }
    session.subscribe(NUS_TX)

    // 4) write
    val receipt = session.write(NUS_RX, "hello".encodeToByteArray())
    Log.i("ble", "보냄: ${receipt.bytesCompleted} bytes / ${receipt.chunksCompleted} chunks")

    // 5) 정리: 수집 중지 → 연결 해제. client 는 닫지 않습니다(앱 수명 동안 유지).
    delay(2_000)
    receiver.cancel()
    session.disconnect()
}
// endregion

// region guide:scan
/** [durationMillis] 동안 스캔해 신호가 가장 센(RSSI 최대) 기기를 고릅니다. 못 찾으면 null. */
suspend fun scanNearest(
    client: BleClient,
    filter: BleScanFilter,
    durationMillis: Long = 5_000,
): ScanResult? = coroutineScope {
    val latest = HashMap<PeripheralId, ScanResult>()
    // 수집을 먼저 등록해야 startScan 직후의 결과도 받습니다.
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        client.scanResults.collect { latest[it.peripheralId] = it } // 같은 기기는 최신 값으로 덮어씁니다
    }
    client.startScan(ScanRequest(filter, BleScanOptions(BleScanMode.LowLatency))) // 실패하면 여기서 던집니다
    try {
        delay(durationMillis)
    } finally {
        client.stopScan() // 호출자가 취소돼도 스캔은 끝까지 멈춥니다
    }
    collector.cancelAndJoin()
    latest.values.maxByOrNull { it.rssi }
}

suspend fun scanExamples(client: BleClient) {
    // 이름이 "NUS-" 로 시작하는 기기(광고 패킷의 local name 기준, 대소문자 구분)
    val byName = BleScanFilter(namePrefix = "NUS-")
    // NUS 서비스 UUID 를 광고하는 기기
    val byService = BleScanFilter(serviceUuids = listOf(NUS_SERVICE.uuid))
    // 제조사 데이터: 0x0059(Nordic Semiconductor 의 company ID) 이고 첫 바이트가 0x01 인 기기(예시 값)
    val byManufacturer = BleScanFilter(
        manufacturerData = listOf(
            BleManufacturerDataFilter(companyId = 0x0059, data = byteArrayOf(0x01), mask = byteArrayOf(0xFF.toByte())),
        ),
    )
    for (filter in listOf(byName, byService, byManufacturer)) {
        val nearest = scanNearest(client, filter) ?: continue
        Log.i("ble", "${nearest.peripheralId.value} ${nearest.advertisement.localName} ${nearest.rssi} dBm")
    }
}

/**
 * 스캔이 명령 밖에서 실패로 멈춘 이유만 흘려보냅니다(블루투스 꺼짐 등).
 * scanState 는 StateFlow 라 최신 값은 잃지 않지만, 느린 수집자는 중간 상태를 건너뛸 수 있습니다.
 * 새로 수집을 시작하면 지금 남아 있는 Idle(실패)가 바로 한 번 옵니다. 이미 지난 실패일 수 있습니다.
 */
fun scanStopReasons(client: BleClient): Flow<BleFailure> =
    client.scanState.filterIsInstance<ScanState.Idle>().mapNotNull { it.lastFailure }
// endregion

// region guide:connect
/** Ready 또는 Disconnected 가 될 때까지 기다립니다. [timeoutMillis] 안에 둘 다 아니면 null. */
suspend fun awaitSettled(session: BleSession, timeoutMillis: Long): ConnectionState? =
    withTimeoutOrNull(timeoutMillis) {
        session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
    }

/** Ready 가 되지 못한 연결 시도. [state] 가 null 이면 시간 초과입니다. */
class ConnectFailedException(val state: ConnectionState?) : Exception("Ready 가 되지 못했습니다: $state")

/**
 * 연결하고 Ready 까지 기다립니다.
 * - Ready 전에 실패로 끊기면 그 원인을 BleException 으로 던집니다.
 * - 시간 안에 결론이 안 나면 시도를 disconnect() 로 정리하고 ConnectFailedException(null) 을 던집니다.
 *   disconnect() 는 이 세션의 구독 의도도 모두 지우므로, 그 뒤 다시 연결하면 subscribe 를 다시 해야 합니다.
 */
suspend fun connectAndAwaitReady(
    client: BleClient,
    peripheralId: PeripheralId,
    options: BleConnectOptions = BleConnectOptions(),
    timeoutMillis: Long = 40_000,
): BleSession {
    val session = client.connect(peripheralId, options) // 즉시 실패(권한·어댑터·옵션 충돌 등)는 여기서 던집니다
    when (val settled = awaitSettled(session, timeoutMillis)) {
        ConnectionState.Ready -> return session
        is ConnectionState.Disconnected -> {
            val reason = settled.reason
            if (reason is BleDisconnectReason.Failed) throw BleException(reason.failure)
            throw ConnectFailedException(settled) // 누군가 disconnect() 했거나 client 가 닫혔습니다
        }
        else -> {
            session.disconnect() // autoReconnect 연결은 기한이 없으므로 앱이 끊어야 합니다
            throw ConnectFailedException(settled)
        }
    }
}
// endregion

// region guide:connect-retry
/**
 * autoReconnect = false 로 연결하고, 실패하면 간격을 두고 최대 [attempts] 번까지 다시 시도합니다.
 * 다시 해도 소용없는 실패(Never·AfterEnvironmentChange)는 바로 던집니다.
 */
suspend fun connectWithRetry(
    client: BleClient,
    peripheralId: PeripheralId,
    attempts: Int = 3,
    retryDelayMillis: Long = 2_000,
): BleSession {
    var last: Exception? = null
    repeat(attempts) { attempt ->
        if (attempt > 0) delay(retryDelayMillis)
        try {
            return connectAndAwaitReady(client, peripheralId, BleConnectOptions(autoReconnect = false))
        } catch (e: BleException) {
            val retryability = e.failure.retryability
            if (retryability == Retryability.Never || retryability == Retryability.AfterEnvironmentChange) throw e
            last = e
        } catch (e: ConnectFailedException) {
            if (e.state != null) throw e // 사용자 해제·close 로 끝났으면 다시 걸지 않습니다
            last = e
        }
    }
    throw checkNotNull(last)
}
// endregion

// region guide:services
/** Ready 인 세션에서 UUID 로 캐릭터리스틱을 찾습니다. 없으면 null. */
fun lookupCharacteristic(session: BleSession, serviceUuid: UUID, characteristicUuid: UUID): GattCharacteristic? {
    check(session.connectionState.value == ConnectionState.Ready) { "services 는 Ready 일 때만 믿을 수 있습니다" }
    return session.services.value
        .firstOrNull { it.ref.uuid.value == serviceUuid }
        ?.characteristics
        ?.firstOrNull { it.ref.uuid.value == characteristicUuid }
}

/** 탐색된 GATT 구조를 로그로 남깁니다. */
fun logGattTable(session: BleSession) {
    for (service in session.services.value) {
        Log.i("ble", "service ${service.ref.uuid.value} #${service.ref.instanceIndex}")
        for (characteristic in service.characteristics) {
            Log.i("ble", "  └ ${characteristic.ref.uuid.value} #${characteristic.ref.instanceIndex} ${characteristic.properties}")
        }
    }
}

/** 같은 UUID 의 서비스가 두 개 있을 때, 두 번째 서비스 안의 캐릭터리스틱을 가리키는 법. */
fun secondServiceInstance(serviceUuid: UUID, characteristicUuid: UUID): CharacteristicRef =
    CharacteristicRef(
        service = ServiceRef(BleUuid(serviceUuid), instanceIndex = 1),
        uuid = BleUuid(characteristicUuid),
    )
// endregion

// region guide:read
// 예시 UUID: Battery Service(0x180F) 의 Battery Level(0x2A19). 값은 0~100 한 바이트입니다.
val BATTERY_LEVEL = CharacteristicRef(
    ServiceRef(BleUuid(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))),
    BleUuid(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")),
)

suspend fun readBatteryPercent(session: BleSession): Int? =
    try {
        session.read(BATTERY_LEVEL).firstOrNull()?.toInt()?.and(0xFF)
    } catch (e: BleException) {
        Log.w("ble", "read 실패: ${e.failure.code.value} retry=${e.failure.retryability}")
        null
    }
// endregion

// region guide:write
/** write 결과를 앱이 다루기 쉬운 모양으로 바꿉니다. */
sealed interface SendResult {
    /** 전부 보냈습니다. confirmed = 기기가 받았다고 응답했는가(WithResponse 일 때만 true). */
    data class Sent(val bytes: Int, val chunks: Int, val confirmed: Boolean) : SendResult

    /** 한 바이트도 보내기 전에 실패했습니다. 같은 데이터를 다시 보내도 중복되지 않습니다. */
    data class NotSent(val failure: BleFailure) : SendResult

    /** 보내는 도중 실패했습니다. progress.bytesCompleted 까지만 확실하고, 실패한 청크는 전달 여부를 모릅니다. */
    data class Interrupted(val progress: WriteProgress, val failure: BleFailure) : SendResult
}

suspend fun sendData(
    session: BleSession,
    target: CharacteristicRef,
    data: ByteArray,
    mode: BleWriteMode = BleWriteMode.WithResponse,
): SendResult =
    try {
        // Automatic: 협상된 MTU 에 맞춰 코어가 자릅니다(요청 MTU 247 이 받아들여지면 청크 244 바이트).
        val receipt = session.write(target, data, BleWriteOptions(mode = mode, chunking = BleChunking.Automatic))
        SendResult.Sent(
            bytes = receipt.bytesCompleted,
            chunks = receipt.chunksCompleted,
            confirmed = receipt.delivery == DeliverySemantics.RemoteAcknowledged,
        )
    } catch (e: BleException) {
        val progress = e.failure.writeProgress
        if (progress == null) SendResult.NotSent(e.failure) else SendResult.Interrupted(progress, e.failure)
    }
// endregion

// region guide:write-fixed
/** 펌웨어가 한 번에 20 바이트까지만 처리할 때: 청크 크기를 고정하고 응답 없이 보냅니다. */
suspend fun sendIn20ByteChunks(session: BleSession, data: ByteArray): WriteReceipt =
    session.write(
        NUS_RX,
        data,
        BleWriteOptions(mode = BleWriteMode.WithoutResponse, chunking = BleChunking.Fixed(20)),
    )
// endregion

// region guide:notify
/**
 * notify/indicate 수신을 시작합니다. 돌려준 Job 을 취소하면 수집이 멈춥니다.
 * 수집을 먼저 등록하고(UNDISPATCHED) 그다음 구독해야 구독 직후 값도 놓치지 않습니다.
 */
suspend fun startReceiving(
    scope: CoroutineScope,
    session: BleSession,
    characteristic: CharacteristicRef,
    onValue: (ByteArray) -> Unit,
): Job {
    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.notifications(characteristic).collect { onValue(it) }
    }
    try {
        session.subscribe(characteristic) // CCCD 쓰기 응답까지 기다립니다
    } catch (e: Throwable) {
        job.cancel()
        throw e
    }
    return job
}

suspend fun stopReceiving(session: BleSession, characteristic: CharacteristicRef, job: Job) {
    job.cancel()
    session.unsubscribe(characteristic) // 기기 쪽 해제 실패는 던지지 않고 events 의 Diagnostic 으로만 알립니다
}
// endregion

// region guide:request-response
/** NUS 위의 요청-응답. 명령을 RX 에 쓰고 TX 로 오는 첫 notify 를 응답으로 받습니다. */
class NusClient(private val session: BleSession) {
    private val lock = Mutex() // 응답이 섞이지 않도록 한 번에 요청 하나만

    /** 전제: 연결 직후 session.subscribe(NUS_TX) 를 한 번 해 두었습니다. */
    suspend fun request(command: ByteArray, timeoutMillis: Long = 3_000): ByteArray = lock.withLock {
        coroutineScope {
            // 응답 수집을 write 보다 먼저 등록합니다.
            val reply = async(start = CoroutineStart.UNDISPATCHED) { session.notifications(NUS_TX).first() }
            try {
                session.write(NUS_RX, command)
                withTimeout(timeoutMillis) { reply.await() } // 시간 제한은 응답 대기에만 겁니다
            } finally {
                reply.cancel()
            }
        }
    }
}
// endregion

// region guide:keep-connected
/**
 * 링크 끊김과 블루투스 off/on 을 넘어 유지되는 연결.
 * autoReconnect = true 면 링크가 끊겨도 Recovering 상태로 OS 재연결을 기다리고, 블루투스가 다시 켜지면
 * 코어가 한 번 다시 연결을 겁니다. 구독은 Ready 전에 자동 복원되므로 수집·구독은 한 번만 합니다.
 */
suspend fun openPersistentNus(
    client: BleClient,
    peripheralId: PeripheralId,
    scope: CoroutineScope,
    onValue: (ByteArray) -> Unit,
): Pair<BleSession, Job> {
    val session = connectAndAwaitReady(client, peripheralId, BleConnectOptions(autoReconnect = true))
    val receiver = try {
        startReceiving(scope, session, NUS_TX, onValue)
    } catch (e: Exception) {
        session.disconnect()
        throw e
    }
    return session to receiver
}

/** 연결과 수신(notify) 상태를 한 줄 문구로 봅니다. */
fun linkStatus(session: BleSession): Flow<String> =
    combine(session.connectionState, session.activeSubscriptions) { state, active ->
        when (state) {
            ConnectionState.Ready -> if (NUS_TX in active) "연결됨" else "연결됨(수신 복원 실패)"
            is ConnectionState.Recovering -> "재연결 대기 중(${state.lastFailure.code.value})"
            is ConnectionState.Disconnected -> "끊김: ${state.reason}"
            else -> "연결 중…"
        }
    }
// endregion

// region guide:keep-connected-manual
/**
 * autoReconnect 없이 앱이 직접 다시 연결하는 방식. 블루투스가 꺼지면 기다렸다가 켜지면 다시 겁니다.
 * 사용자가 disconnect() 했거나, client 가 닫혔거나, 다시 해도 소용없는 실패(Never)면 반환한 Job 이 끝납니다(취소로 끝남).
 * 링크가 끊겨도 구독 의도는 남아 코어가 다시 연결할 때 복원합니다. 단 connectAndAwaitReady 가 시간 초과로
 * disconnect() 한 뒤에는 의도가 지워졌으므로 연결되면 다시 subscribe 해야 합니다.
 */
fun keepConnectedByApp(client: BleClient, peripheralId: PeripheralId, scope: CoroutineScope): Job = scope.launch {
    client.adapterState.collectLatest { adapter ->
        if (adapter != AdapterState.Ready) return@collectLatest // 꺼지면 collectLatest 가 아래 루프를 취소합니다
        while (true) {
            val session = try {
                connectAndAwaitReady(client, peripheralId)
            } catch (e: BleException) {
                if (e.failure.retryability == Retryability.Never) break
                delay(5_000)
                continue
            } catch (e: ConnectFailedException) {
                if (e.state != null) break // 사용자 해제·close
                delay(5_000)
                continue
            }
            val ended = session.connectionState.filterIsInstance<ConnectionState.Disconnected>().first()
            if (ended.reason !is BleDisconnectReason.Failed) break // 사용자 해제·close
            delay(2_000)
        }
        // 여기 오면 멈출 때입니다. return@collectLatest 는 이 블록만 끝내고 adapterState 수집은 계속되므로,
        // 블루투스가 다시 켜질 때 사용자가 끊은 기기에 또 연결합니다. 그래서 Job 전체를 취소합니다.
        this@launch.cancel()
    }
}
// endregion

// region guide:multi-device
/** 여러 기기에 동시에 연결합니다. 기기별 실패는 null 로 모읍니다. */
suspend fun connectAllAtOnce(client: BleClient, ids: List<PeripheralId>): Map<PeripheralId, BleSession?> =
    coroutineScope {
        ids.map { id ->
            async {
                id to try {
                    connectAndAwaitReady(client, id)
                } catch (e: BleException) {
                    Log.w("ble", "${id.value}: ${e.failure.code.value}")
                    null
                } catch (e: ConnectFailedException) {
                    Log.w("ble", "${id.value}: ${e.message}")
                    null
                }
            }
        }.awaitAll().toMap()
    }
// endregion

// region guide:bonding
/** 본딩(페어링)이 필요한 기기: 연결 전에 본딩을 끝내고 연결합니다(실기기 검증과 같은 순서). */
suspend fun bondThenConnect(context: Context, client: BleClient, peripheralId: PeripheralId): BleSession {
    val bonds = AndroidBleClientFactory.createBondController(context)
    if (bonds.bondState(peripheralId) != AndroidBondState.Bonded) {
        when (bonds.createBond(peripheralId, timeoutMillis = 30_000)) {
            AndroidBondResult.Bonded, AndroidBondResult.AlreadyBonded -> Unit
            AndroidBondResult.StartRejected -> error("시스템이 본딩 시작을 거절했습니다")
            AndroidBondResult.Failed -> error("본딩 실패(사용자 거절·키 불일치 등)")
            AndroidBondResult.TimedOut -> error("30초 안에 본딩이 끝나지 않았습니다")
        }
    }
    return connectAndAwaitReady(client, peripheralId)
}
// endregion

// region guide:errors
/** 실패를 보고 다음 행동을 고릅니다. 분기는 code 와 retryability 로만 합니다(message 는 로그용). */
enum class NextAction { RetrySoon, Reconnect, FixEnvironment, GiveUp, Investigate }

fun nextActionFor(failure: BleFailure): NextAction = when (failure.retryability) {
    Retryability.TransientMayHelp -> NextAction.RetrySoon // 잠시 뒤 같은 호출
    Retryability.NewConnectionMayHelp -> NextAction.Reconnect // 연결을 새로 맺은 뒤 다시
    Retryability.AfterEnvironmentChange -> NextAction.FixEnvironment // 블루투스 켜기·권한·본딩 뒤 다시
    Retryability.Never -> NextAction.GiveUp // 입력이나 앱 설정을 고쳐야 하는 문제
    Retryability.Unknown -> NextAction.Investigate // 로그를 보고, 필요하면 연결부터 다시
}

fun userMessageFor(failure: BleFailure): String = when (failure.code.value) {
    "ADAPTER_UNAVAILABLE" -> "블루투스를 켜 주세요"
    "PERMISSION_DENIED" -> "블루투스 권한이 필요합니다"
    "BLE_UNSUPPORTED" -> "이 기기는 BLE 를 지원하지 않습니다"
    "PERIPHERAL_NOT_FOUND" -> "기기를 찾지 못했습니다. 가까이 두고 다시 시도하세요"
    "LINK_TIMEOUT", "LINK_FAILED", "LINK_START_FAILED" -> "기기와 연결하지 못했습니다"
    "NOT_READY", "NOT_CONNECTED" -> "기기가 아직 준비되지 않았습니다"
    // 인증·암호화 부족은 별도 코드가 아니라 GATT_STATUS_FAILED + AfterEnvironmentChange 로 옵니다.
    "GATT_STATUS_FAILED" ->
        if (failure.retryability == Retryability.AfterEnvironmentChange) "기기와 페어링이 필요합니다" else "기기가 요청을 거절했습니다"
    "SCAN_START_THROTTLED" -> "잠시 후 다시 검색해 주세요"
    else -> "블루투스 오류 (${failure.code.value})"
}

/** 스캔 시작이 Android 한도(30초에 5회)에 걸리면 한 번 기다렸다가 다시 시작합니다. */
suspend fun startScanPolitely(client: BleClient, request: ScanRequest) {
    try {
        client.startScan(request)
    } catch (e: BleException) {
        if (e.failure.code.value != "SCAN_START_THROTTLED") throw e
        delay(31_000)
        client.startScan(request)
    }
}
// endregion

// region guide:events
/** events 는 "무슨 일이 있었는지" 로그용입니다. 현재 상태는 StateFlow 에서 읽습니다. */
fun logEvents(client: BleClient, scope: CoroutineScope): Job = scope.launch {
    client.events.collect { event ->
        when (event) {
            is OfficialBleClientEvent.Connection -> Log.i("ble", "${event.peripheralId.value} → ${event.state}")
            is OfficialBleClientEvent.Services -> Log.i("ble", "${event.peripheralId.value} 서비스 ${event.services.size}개")
            is OfficialBleClientEvent.SubscriptionRestoreFailed ->
                Log.w("ble", "구독 복원 실패 ${event.characteristic.uuid.value}: ${event.failure.code.value}")
            is OfficialBleClientEvent.Diagnostic -> Log.d("ble", "[${event.severity}] ${event.failure.code.value}")
        }
    }
}
// endregion

// region guide:diagnostics
/** 디버그 빌드에서만 진단 로그를 켭니다. 원문 바이트는 safe 로 지정한 캐릭터리스틱만 남깁니다. */
fun bleConfigFor(debug: Boolean): BleConfig = BleConfig(
    logger = if (debug) BleLogger { record -> Log.d("ble-diag", formatDiagnostic(record)) } else null,
    diagnosticOptions = DiagnosticOptions(
        rawPayloadsEnabled = debug,
        maxRawPayloadBytes = 64,
        safeCharacteristics = setOf(BATTERY_LEVEL), // 원문을 남겨도 되는 것
        sensitiveCharacteristics = setOf(NUS_RX, NUS_TX), // 항상 가림(Redacted). safe 와 겹치면 이쪽이 이깁니다
    ),
)

fun formatDiagnostic(record: DiagnosticRecord): String {
    val failure = record.failure
    val values = record.values.entries.joinToString { (key, value) ->
        when (value) {
            is DiagnosticValue.Payload -> "$key=${value.bytes.joinToString("") { "%02x".format(it) }}(${value.originalLength}B)"
            else -> "$key=$value"
        }
    }
    return "[${record.severity}] ${failure.code.value} phase=${failure.phase} retry=${failure.retryability} $values"
}
// endregion

// region guide:snapshot
suspend fun logSnapshot(client: BleClient) {
    val snapshot = client.snapshot()
    Log.i("ble", "adapter=${snapshot.adapterState} scanning=${snapshot.isScanning}")
    for (session in snapshot.sessions) {
        Log.i(
            "ble",
            "${session.peripheralId.value} ${session.connectionState} " +
                "services=${session.services.size} 구독의도=${session.subscriptions.size}",
        )
    }
}
// endregion

// region guide:close
/** client 를 영구히 닫습니다. 닫은 client 는 다시 쓸 수 없습니다 — 필요하면 새로 만듭니다. */
suspend fun shutdownBle(client: BleClient): ClientSnapshot {
    client.close() // 즉시 돌아옵니다. 정리는 뒤에서 진행됩니다
    return client.snapshot() // 정리가 끝날 때까지 기다린 뒤 닫힌 시점의 스냅샷을 돌려줍니다
}
// endregion
