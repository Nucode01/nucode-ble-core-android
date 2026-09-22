package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.CharacteristicRef

/**
 * 기기 하나의 상태 전부. 설계 §3 규칙 2 — 기기별 상태는 객체 하나에 모은다.
 * PeripheralId 를 키로 하는 맵은 [SessionRegistry.sessions] 하나뿐이고, 그 값이 이 클래스다.
 * 병렬 맵을 만들지 않는다(설계 §1 원인②) — connState · gatt 핸들 · 구독 · retry ·
 * timeout · MTU/chunkSize · GATT scheduler를 전부 이 안으로 모아 동기화 누락을
 * 원천 차단한다. iOS 구현이 무너진 원인(설계 §1 원인②)이 정확히
 * `connectionContinuations` · `connectionTimeoutTasks` 같은 동기화 프리미티브를
 * PeripheralId 별 병렬 맵으로 따로 빼둔 것이었다 — "상태가 아니라 동기화 프리미티브라
 * 규칙 2 대상이 아니다"는 예외를 두지 않는다. 그 예외가 바로 8개를 만든 합리화다.
 *
 * coreScope의 단일 스레드가 필드 접근을 직렬화하고, callback 대기 사이의 operation 순서는
 * [gattOperationScheduler] FIFO가 보장한다. 별도 lock이나 PeripheralId별 병렬 map을 두지 않는다.
 */
internal class Session(val deviceId: PeripheralId) {

    /** Public failure correlation token. It intentionally has no relation to the peripheral ID. */
    val failureSessionId: String = UUID.randomUUID().toString()

    /**
     * One lifecycle owner establishes this opaque tuple before a failure is
     * projected to state, the compatibility event, or diagnostics.  Device IDs
     * intentionally never enter the public context.
     */
    fun lifecycleFailureContext(): FailureContext = FailureContext(
        sessionId = failureSessionId,
        connectionGeneration = connectionCoordinator.generation,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(
        ConnectionState.Disconnected(BleDisconnectReason.Initial),
    )
    internal val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var disconnectReason: DisconnectReason = DisconnectReason.Initial

    /** Coordinator stage에서 파생되는 compatibility view. 독립 setter를 두지 않는다. */
    val state: ConnState
        get() = connectionCoordinator.stage.toConnState(disconnectReason)

    /**
     * Projects a bounded retry delay without introducing a second connection-state owner.
     * The next coordinator stage transition replaces this transient projection atomically on the
     * core's single-session dispatcher.
     */
    fun publishRecovering(
        attempt: Int,
        retryAfterMillis: Long,
        lastFailure: BleFailure,
    ) {
        _connectionState.value = ConnectionState.Recovering(
            attempt = attempt,
            retryAfterMillis = retryAfterMillis,
            lastFailure = lastFailure,
        )
    }

    /**
     * Slice 2 connection lifecycle의 단일 소유자. connection generation, readiness stage,
     * stage deadline, terminal cleanup reason을 별도 PeripheralId map으로 흩뜨리지 않고 Session과
     * 함께 폐기한다. 기존 GattBridge/BleRuntime routing은 후속 수직 이관 task가 이 API로 옮긴다.
     */
    val connectionCoordinator: ConnectionCoordinator = ConnectionCoordinator(::publishStage)

    /** Terminal reason must be recorded before the coordinator publishes Disconnected. */
    fun prepareDisconnect(reason: DisconnectReason) {
        disconnectReason = reason
        if (connectionCoordinator.stage == ConnectionStage.Disconnected) {
            publishStage(ConnectionStage.Disconnected)
        }
    }

    private fun publishStage(stage: ConnectionStage) {
        _connectionState.value = stage.toConnState(disconnectReason).toPublicConnectionState()
    }

    /**
     * 현재 connection generation을 terminal cleanup으로 보낸 typed failure. coordinator는
     * lifecycle 분류만 보존하므로 stable failure 값은 Session이 함께 소유한다.
     */
    var terminalLifecycleCause: BleFailure? = null

    /**
     * 플랫폼 연결 핸들. 코어 안에만 산다 — 연결의 소유자가 코어라는 설계 §2 의 근거가
     * 바로 이 필드다. 공개 [kr.co.nucode.ble.BleClient] 시그니처에는 절대 나오지 않는다.
     */
    var gatt: BluetoothGatt? = null

    /** Official connect request의 effective policy. A session owns this value across retries. */
    var effectiveReconnectPolicy: BleReconnectPolicy = BleReconnectPolicy.Disabled

    /** Desired intent and current-generation active CCCD state share this one session owner. */
    val subscriptionState: SubscriptionState = SubscriptionState()

    fun isConfiguringGeneration(generation: Long): Boolean =
        connectionCoordinator.accepts(generation, ConnectionStage.Configuring)

    /** 재연결 시도 횟수. Reconnector(plan task 5)가 읽고 쓴다. */
    var retryCount: Int = 0

    /**
     * 기존 GattBridge/Reconnector 연결 타임아웃 취소용 job. 새 lifecycle stage deadline은
     * [connectionCoordinator]가 소유하며, 이 필드는 후속 수직 이관이 끝날 때까지 유지한다.
     */
    var timeoutJob: Job? = null

    /**
     * 코어가 되붙이면 안 되는 상태 — ① 사용자가 disconnect() 를 불렀다 ② connect
     * 타임아웃으로 포기했다(D2, review M-4) ③ connect 시작 자체가 실패했다(D6, review
     * M-7). Reconnector 가 "의도적 해제/포기"와 "예기치 못한 끊김"을 구분하는 유일한
     * 근거다 — 없으면 이 세 경우에도 재연결이 돈다. 재시도는 소비자가 connect() 재호출로
     * (정책은 밖 — 규칙 3 정신). iOS 구현의 disconnectRequested 딕셔너리(설계 §1 원인②)가
     * 병렬 맵이 아니라 Session 필드로 들어온 것이다.
     */
    var suppressReconnect: Boolean = false

    /**
     * 이번 연결의 모드 — connect 시작 시점에 확정된다(plan app-tunables v3).
     * true 면 재연결 소유권이 **플랫폼**이다(autoConnect gatt 는 링크가 떨어져도 시스템이
     * 기기 재등장 시 되붙인다) — Reconnector 는 예약도 실행도 하지 않는다.
     * 재연결 주체는 어느 시점에도 하나여야 한다: 플랫폼 / Reconnector / 사용자 connect.
     */
    var autoConnect: Boolean = false

    /**
     * 예약된 Reconnector 백오프 job. 기기당 하나(규칙 2 — Job 을 PeripheralId 맵으로 밖에 두면
     * §1 원인② 의 connectionTimeoutTasks 재현이다). 이 손잡이가 있어야 연결 시작·해제·스윕이
     * 예약을 **취소**할 수 있다 — 실행 시점 가드만으로는 이미 큐에 든 시도를 못 막는다.
     */
    var reconnectJob: Job? = null

    /**
     * 예약된 configuring-phase subscription restore `coreScope.launch` 손잡이,
     * 규칙 8 — delay·await 를 품은 코루틴은 손잡이를 Session/BleRuntime 가 쥔다). 손잡이가
     * 없으면 fire-and-forget 이라 새 `Connected` 가 구 재구독 루프를 대체(cancel)할 수
     * 없고, disconnect·sweep·시작 리셋 시점에도 이미 죽은 링크 위에서 재구독이 계속
     * 돈다 — `reconnectJob` 과 같은 규약으로 정리 지점마다 cancel 한다.
     */
    var resubscribeJob: Job? = null

    /** read/write/CCCD/MTU callback의 FIFO, pending, generation, poison 상태를 단독 소유한다. */
    val gattOperationScheduler: GattOperationScheduler = GattOperationScheduler()

    /**
     * connect() 시점에 요청한 MTU 값(plan blecore-app-tunables 결정① — `ConnectOptions.requestedMtu`
     * 를 상수(구 `REQUESTED_MTU`) 대신 여기 보존한다). 기본 247 은 `ConnectOptions.requestedMtu`
     * 기본값과 같다. [GattBridge.connect] 가 `options.requestedMtu` 를 저장하고,
     * `BleRuntime.advanceConnectSequence` 의 `ServicesDiscovered` 분기가 이 값을 그대로 읽어
     * `gattBridge.requestMtu` 를 부른다 — 연결별/기기별로 다른 값을 허용하려면 상수로는 안 되고
     * Session 에 둬야 한다(규칙 2, 기기별 값은 Session).
     */
    var requestedMtu: Int = 247

    /** 협상된 MTU. 협상 전에는 null — 23 을 임의로 채우지 않는다. */
    var mtu: Int? = null

    /** MTU 로부터 계산한 청크 크기. */
    var chunkSize: Int? = null

    /**
     * 서비스 재탐색 결과로 얻은 CharacteristicRef -> BluetoothGattCharacteristic 대응표.
     * 재탐색마다 `BluetoothGattCharacteristic` 인스턴스가 갈리므로, 공개 API 의 값 타입인
     * [CharacteristicRef] 를 그때그때 이 표에서 다시 찾는다(설계 §0 "char 재매칭 — 1회 해결").
     *
     * 이 맵은 PeripheralId 가 아니라 CharacteristicRef 를 키로 하는, 기기 하나의 내부 상태다.
     * 규칙 2 가 금지하는 건 PeripheralId 키 맵이 두 개째 생기는 것이지, Session 안의
     * 다른 키를 쓰는 맵이 아니다.
     *
     * 스켈레톤 단계에서는 저장·조회 자료구조만 만든다. 실제 GATT 탐색 호출은
     * GattBridge(plan task 7)가 한다 — 여기서는 채워지기 전이라 항상 비어 있고,
     * [resolve] 는 못 찾으면 조용히 null 을 돌려준다(정상 동작).
     */
    private data class DiscoveredTopology(
        val services: List<GattService>,
        val targets: Map<CharacteristicRef, BluetoothGattCharacteristic>,
    )

    /**
     * The last complete discovery result and its platform targets form one snapshot. Replacing
     * either independently would let an operation observe a service list from one discovery and
     * a characteristic handle from another.
     */
    private var discoveredTopology = DiscoveredTopology(emptyList(), emptyMap())

    private val _services = MutableStateFlow<List<GattService>>(emptyList())
    internal val services: StateFlow<List<GattService>> = _services.asStateFlow()

    /**
     * 재탐색 결과로 대응표를 통째로 갈아끼운다. `GattConnectionLifecycle` 이 공개
     * [GattService]/[GattCharacteristic] 로 조립해 넘기므로 여기서는 재매핑 없이 저장·발행한다.
     */
    fun replaceDiscoveredTopology(
        services: List<GattService>,
        targets: Map<CharacteristicRef, BluetoothGattCharacteristic>,
    ) {
        discoveredTopology = DiscoveredTopology(services.toList(), targets.toMap())
        _services.value = services
    }

    /** Legacy test/setup seam; production discovery always replaces topology and targets together. */
    fun updateDiscovered(characteristics: Map<CharacteristicRef, BluetoothGattCharacteristic>) {
        replaceDiscoveredTopology(discoveredTopology.services, characteristics)
    }

    /** CharacteristicRef 를 현재 탐색 결과에서 되찾는다. 못 찾으면 null. */
    fun resolve(charRef: CharacteristicRef): BluetoothGattCharacteristic? = discoveredTopology.targets[charRef]

    /** Public GATT operation admission is owned solely by the coordinator readiness stage. */
    fun canStartPublicGattOperation(): Boolean =
        connectionCoordinator.stage == ConnectionStage.Ready
}

private fun ConnState.toPublicConnectionState(): ConnectionState = when (this) {
    is ConnState.Disconnected -> ConnectionState.Disconnected(reason.toPublicDisconnectReason())
    ConnState.Connecting -> ConnectionState.Connecting
    ConnState.DiscoveringServices -> ConnectionState.DiscoveringServices
    ConnState.Configuring -> ConnectionState.Configuring
    ConnState.Ready -> ConnectionState.Ready
    ConnState.Disconnecting -> ConnectionState.Disconnecting
}

private fun ConnectionStage.toConnState(disconnectReason: DisconnectReason): ConnState = when (this) {
    ConnectionStage.Disconnected -> ConnState.Disconnected(disconnectReason)
    ConnectionStage.Connecting -> ConnState.Connecting
    ConnectionStage.DiscoveringServices -> ConnState.DiscoveringServices
    ConnectionStage.Configuring -> ConnState.Configuring
    ConnectionStage.Ready -> ConnState.Ready
    ConnectionStage.Disconnecting -> ConnState.Disconnecting
}

private fun DisconnectReason.toPublicDisconnectReason(): BleDisconnectReason = when (this) {
    DisconnectReason.Initial -> BleDisconnectReason.Initial
    DisconnectReason.Requested -> BleDisconnectReason.Requested
    DisconnectReason.ClientClosed -> BleDisconnectReason.ClientClosed
    is DisconnectReason.Failed -> BleDisconnectReason.Failed(failure)
}
