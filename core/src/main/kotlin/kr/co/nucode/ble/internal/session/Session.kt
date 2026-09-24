package kr.co.nucode.ble.internal.session

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.internal.gatt.GattOperationScheduler

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

    /**
     * 마지막 terminal reason — [connectionCoordinator] 가 고정한 [TerminalCause] 의 읽기 전용 파생.
     * 쓰기 경로는 없다(plan terminal-cause v2 불변식 ②). 연결 시도 전(generation 0)만 Initial 이다.
     */
    val disconnectReason: DisconnectReason
        get() = connectionCoordinator.terminalCause.toDisconnectReason(
            generationStarted = connectionCoordinator.generation > 0L,
            // lifecycle 이 종결 완료 전에 원인 없는 해제를 status 를 담은 실패로 고정하므로 여기엔
            // 도달하지 않는다. 방어적 기본값만 둔다(status 미상 = 0).
            unattributedFailure = { GattFailureFactory.unattributedLinkDisconnect(this, status = 0) },
        )

    /** Coordinator stage 의 읽기 전용 view. 내부 판단의 유일한 연결 단계다. */
    val stage: ConnectionStage
        get() = connectionCoordinator.stage


    /**
     * Slice 2 connection lifecycle의 단일 소유자. connection generation, readiness stage,
     * stage deadline, terminal cleanup reason을 별도 PeripheralId map으로 흩뜨리지 않고 Session과
     * 함께 폐기한다. 기존 GattBridge/BleRuntime routing은 후속 수직 이관 task가 이 API로 옮긴다.
     */
    val connectionCoordinator: ConnectionCoordinator = ConnectionCoordinator(::publishStage)

    /**
     * generation 이 링크를 떠나는 stage 이벤트(Disconnecting/Disconnected)의 단일 listener 슬롯.
     * 붙은 뒤 절차의 소유자(lifecycle)가 연결 시작 때 건다 — Session 은 누가 거는지 모른다.
     */
    var onLeaveLink: () -> Unit = {}

    /**
     * [stageEvent] = false 는 stage 는 그대로이고 플랫폼 재연결 대기만 바뀐 재파생이다([beginPlatformWait]/
     * [endPlatformWait]) — generation 경계 부수효과 없이 공개 상태만 다시 계산한다.
     */
    private fun publishStage(stage: ConnectionStage, stageEvent: Boolean = true) {
        // CCCD 활성 집합의 generation 경계는 stage 이벤트 하나가 정한다(호출자별 beginGeneration 금지).
        // Connecting = 새 generation 시작 — Disconnected 에서만 beginAttempt 가 generation 을 올린 뒤 들어오므로
        // 어떤 경로(명시 connect·플랫폼 자발 재연결)든 여기서 새 generation 이 열린다.
        // Disconnecting/Disconnected = 링크를 떠나는 순간 활성 집합도 끝난다 — 다음 generation 까지 기다리면
        // 끊긴 동안 activeSubscriptions 가 꺼진 구독을 켜진 것으로 보여준다. 구독 의도(desired)는 유지한다.
        if (stageEvent && stage == ConnectionStage.Connecting) {
            subscriptionState.beginGeneration(connectionCoordinator.generation)
        } else if (stageEvent && (stage == ConnectionStage.Disconnecting || stage == ConnectionStage.Disconnected)) {
            subscriptionState.clearActive()
            onLeaveLink()
        }
        _connectionState.value = if (stage == ConnectionStage.Disconnected && awaitingPlatformRelink) {
            // 플랫폼 재연결 대기 — 공개 상태는 Recovering 으로 파생한다(plan autoconnect-reconnect v2).
            ConnectionState.Recovering(
                lastFailure = (disconnectReason as? DisconnectReason.Failed)?.failure
                    ?: GattFailureFactory.unattributedLinkDisconnect(this, status = 0),
            )
        } else {
            stage.toPublicConnectionState(disconnectReason)
        }
    }

    /**
     * 현재 connection generation을 terminal cleanup으로 보낸 typed failure — coordinator 가 고정한
     * [TerminalCause.failure] 의 읽기 전용 view.
     */
    val terminalLifecycleCause: BleFailure?
        get() = connectionCoordinator.terminalCause?.failure

    /**
     * 플랫폼 연결 핸들. 코어 안에만 산다 — 연결의 소유자가 코어라는 설계 §2 의 근거가
     * 바로 이 필드다. 공개 [kr.co.nucode.ble.BleClient] 시그니처에는 절대 나오지 않는다.
     */
    var gatt: BluetoothGatt? = null

    /**
     * driver.connect 가 성공해 platform handle 이 열렸고 아직 close 하지 않았다. [gatt] 로 대신하지 않는
     * 이유: GattDriver 계약상 시작에 성공한 driver 가 null handle 을 돌려줄 수 있다(테스트 driver).
     * 쓰는 곳은 lifecycle 의 handle 열기·닫기 지점뿐이다([gatt] 대입과 같은 자리). 읽는 곳은 종결 완료 시
     * 플랫폼 재연결 대기를 시작할지(남길 handle 이 있는가) 한 곳이다 — 대기 여부 자체는 [awaitingPlatformRelink].
     */
    var platformHandleOpen: Boolean = false

    /** Desired intent and current-generation active CCCD state share this one session owner. */
    val subscriptionState: SubscriptionState = SubscriptionState()

    fun isConfiguringGeneration(generation: Long): Boolean =
        connectionCoordinator.accepts(generation, ConnectionStage.Configuring)

    /**
     * 이 handle 을 OS 재연결 대기용으로 남기지 않는다 — autoConnect 가 아닌 연결(연결 시작 시점에 세운다),
     * 또는 사용자 해제·client close·어댑터 꺼짐·시작 실패·Ready 전 실패(첫 연결·플랫폼 재연결 generation 모두)로
     * 끝난 연결. true 면 종결 완료 경로가 handle 을 닫고 대기를 시작하지 않는다. 대기 판정 자체는
     * [awaitingPlatformRelink] 의 몫이다. 쓰는 곳은 lifecycle 뿐이다.
     */
    var suppressReconnect: Boolean = false

    /**
     * 이번 연결의 모드 — connect 시작 시점에 확정된다(plan app-tunables v3).
     * true 면 재연결 소유권이 **플랫폼**이다(autoConnect gatt 는 링크가 떨어져도 시스템이
     * 기기 재등장 시 되붙인다). 라이브러리는 링크 재시작을 스스로 예약하지 않는다.
     */
    var autoConnect: Boolean = false

    /**
     * 플랫폼 재연결 대기 중인가 — autoConnect 연결이 끝났는데 lifecycle 이 handle 을 OS 대기용으로 남겼다
     * ([beginPlatformWait]). 공개 상태 Recovering 의 유일한 근거이고, Bootstrap 귀속(CallbackAttribution)과
     * 사용자 해제·client close·어댑터 꺼짐 스윕이 대기를 끝낼지(ConnectionCoordinator.supersedePlatformWait)의 근거다.
     * handle 열림·suppressReconnect 같은 파생 입력으로 계산하지 않는다 — 입력이 게시 없이 바뀌면 공개 상태와
     * 어긋난다(review H1). 쓰는 곳은 lifecycle 의 [beginPlatformWait]/[endPlatformWait] 뿐이다(generation 이 다시
     * 시작되면 stage 가 Disconnected 가 아니므로 false).
     */
    val awaitingPlatformRelink: Boolean
        get() = platformWait && connectionCoordinator.stage == ConnectionStage.Disconnected

    private var platformWait: Boolean = false

    /**
     * lifecycle 전용 — 대기를 세운 채 [complete](끝나지 않은 generation 의 종결 — coordinator 가 Disconnected 를
     * 게시한다)를 실행한다. 게시 시점에 대기가 이미 서 있으므로 Recovering 이 한 번에 게시된다(중간 Disconnected 없음).
     * 종결되지 않았으면 대기를 되돌린다. 끝에 공개 상태를 다시 파생한다.
     */
    fun beginPlatformWait(complete: () -> Boolean): Boolean {
        check(connectionCoordinator.stage != ConnectionStage.Disconnected) {
            "플랫폼 재연결 대기는 종결 전이와 함께 시작한다: ${connectionCoordinator.stage}"
        }
        platformWait = true
        val completed = complete()
        if (!completed) platformWait = false
        publishStage(connectionCoordinator.stage, stageEvent = false)
        return completed
    }

    /**
     * lifecycle 전용 — 대기를 끝낸다(handle close·재연결 generation 시작·대기 종결 원인 대체). [transition] 은
     * 대기를 내린 뒤 실행되므로 그 안의 게시도 대기 없는 상태에서 파생된다. 대기가 아니었으면 [transition] 만
     * 실행한다. 끝에 공개 상태를 다시 파생한다.
     */
    fun endPlatformWait(transition: () -> Unit = {}) {
        val wasWaiting = platformWait
        platformWait = false
        transition()
        if (wasWaiting) publishStage(connectionCoordinator.stage, stageEvent = false)
    }

    /**
     * 어댑터 복귀 때 한 번 다시 걸 autoConnect 세션인가 — 어댑터 꺼짐 스윕이 autoConnect 세션을
     * 끝냈을 때 lifecycle 이 세우고, 사용자 해제·close·새 connect 가 내린다. BleRuntime 이 어댑터
     * Ready 복귀를 관측하면 이 세션에만 connectGatt(autoConnect = true) 를 한 번 건다(예약·재시도 없음).
     */
    var relinkAfterAdapter: Boolean = false

    /** read/write/CCCD/MTU callback의 FIFO, pending, generation, poison 상태를 단독 소유한다. */
    val gattOperationScheduler: GattOperationScheduler = GattOperationScheduler()

    /**
     * connect() 시점에 요청한 MTU 값(plan blecore-app-tunables 결정① — `ConnectOptions.requestedMtu`
     * 를 상수(구 `REQUESTED_MTU`) 대신 여기 보존한다). 기본 247 은 `ConnectOptions.requestedMtu`
     * 기본값과 같다. [GattBridge.connect] 가 `options.requestedMtu` 를 저장하고,
     * `LinkSetup.onServicesDiscovered` 가 이 값을 그대로 읽어
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

private fun ConnectionStage.toPublicConnectionState(
    disconnectReason: DisconnectReason,
): ConnectionState = when (this) {
    ConnectionStage.Disconnected -> ConnectionState.Disconnected(disconnectReason.toPublicDisconnectReason())
    ConnectionStage.Connecting -> ConnectionState.Connecting
    ConnectionStage.DiscoveringServices -> ConnectionState.DiscoveringServices
    ConnectionStage.Configuring -> ConnectionState.Configuring
    ConnectionStage.Ready -> ConnectionState.Ready
    ConnectionStage.Disconnecting -> ConnectionState.Disconnecting
}

private fun DisconnectReason.toPublicDisconnectReason(): BleDisconnectReason = when (this) {
    DisconnectReason.Initial -> BleDisconnectReason.Initial
    DisconnectReason.Requested -> BleDisconnectReason.Requested
    DisconnectReason.ClientClosed -> BleDisconnectReason.ClientClosed
    is DisconnectReason.Failed -> BleDisconnectReason.Failed(failure)
}

/**
 * latch된 종결 원인에서 내부 [DisconnectReason]을 파생한다(순수 함수, 아직 공개 경로에 연결되지 않음).
 *
 * 원인 없이 종결된 시작된 generation은 LINK_FAILED 실패로 본다. 그 [BleFailure]는 platform
 * statusCode 등 session 문맥이 필요하므로 호출자가 [unattributedFailure]로 넘긴다(필요할 때만 생성).
 */
internal fun TerminalCause?.toDisconnectReason(
    generationStarted: Boolean,
    unattributedFailure: () -> BleFailure,
): DisconnectReason {
    val failure = this?.failure
    return when {
        failure != null -> DisconnectReason.Failed(failure)
        this?.reason == TerminalCleanupReason.UserRequested -> DisconnectReason.Requested
        this?.reason == TerminalCleanupReason.ClientClosed -> DisconnectReason.ClientClosed
        generationStarted -> DisconnectReason.Failed(unattributedFailure())
        else -> DisconnectReason.Initial
    }
}
