package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.ConnectOptions

/**
 * 연결 Core 의 상태 값. 갱신은 copy 로만 한다.
 *
 * 연결 칸 여섯([platformWait]·[suppressReconnect]·[autoConnect]·[relinkAfterAdapter]·[platformHandleOpen]·
 * [requestedMtu])도 단계·원인과 같은 상태 값에 산다. [failureSessionId] 는 실패 상관 토큰으로,
 * Runtime(Session)이 만들어 초기 상태에 준다.
 */
internal data class ConnectionCoreState(
    val generation: Long = 0L,
    val stage: ConnectionStage = ConnectionStage.Disconnected,
    val terminalCause: TerminalCause? = null,
    val deadline: ConnectionStageDeadline? = null,
    val deadlineSeq: Long = 0L,
    /** 플랫폼 재연결 대기 표시 — 공개 상태 Recovering 의 유일한 근거(단계가 Disconnected 일 때만 유효). */
    val platformWait: Boolean = false,
    /** 이 handle 을 OS 재연결 대기용으로 남기지 않는다. */
    val suppressReconnect: Boolean = false,
    /** 이번 연결의 모드 — true 면 재연결 소유권이 플랫폼이다. */
    val autoConnect: Boolean = false,
    /** 어댑터 복귀 때 한 번 다시 걸 autoConnect 세션 표시. */
    val relinkAfterAdapter: Boolean = false,
    /** driver.connect 가 성공해 platform handle 이 열렸고 아직 닫지 않았다. */
    val platformHandleOpen: Boolean = false,
    /** connect() 시점에 요청한 MTU. */
    val requestedMtu: Int = 247,
    val failureSessionId: String? = null,
    /** 원인 확정을 기다리는 Ready 링크 유실(§3 어댑터 정착 창). 있으면 링크는 없고 GATT 작업을 받지 않는다. */
    val pendingLoss: PendingLinkLoss? = null,
    /** autoReconnect=false 연결의 링크 시도 기록 — 빠른 133 재시도([ConnectionRetry])의 근거. */
    val linkAttempt: LinkAttempt? = null,
)

/** Runtime 이 Core 에 넣는 사건. */
internal sealed interface ConnectionEvent {
    /** 셸 호환용 전이 기본 동작. 정책 없이 전이 규칙만 적용한다. */
    sealed interface Direct : ConnectionEvent {
        data class BeginConnection(val linkTimeoutMillis: Long?) : Direct

        data object BeginPlatformRelink : Direct

        data class Transition(
            val callbackGeneration: Long,
            val expectedStage: ConnectionStage,
            val nextStage: ConnectionStage,
            val timeoutMillis: Long?,
        ) : Direct

        data class BeginCleanup(
            val targetGeneration: Long,
            val cause: TerminalCause,
            val disconnectCleanupTimeoutMillis: Long,
        ) : Direct

        data class CompleteCleanup(val targetGeneration: Long, val fallback: TerminalCause) : Direct

        data class SupersedeForClose(val targetGeneration: Long) : Direct

        data class SupersedePlatformWait(val targetGeneration: Long, val cause: TerminalCause) : Direct

        data object CloseIdle : Direct

        /** 연결 칸 쓰기 — 단계·원인은 바꾸지 않고 할 일도 없다. */
        data class SetPlatformWait(val value: Boolean) : Direct

        data class SetSuppressReconnect(val value: Boolean) : Direct

        data class SetAutoConnect(val value: Boolean) : Direct

        data class SetRelinkAfterAdapter(val value: Boolean) : Direct

        data class SetPlatformHandleOpen(val value: Boolean) : Direct

        data class SetRequestedMtu(val value: Int) : Direct

        data class LatchIfAbsent(val targetGeneration: Long, val cause: TerminalCause) : Direct
    }

    /** 단계 기한이 만료됐다. [id] 가 현재 기한과 다르면 무시된다. */
    data class DeadlineExpired(val id: Long) : ConnectionEvent

    /**
     * 만료된 기한 [deadline] 의 정책 — 아직 그 단계의 기한일 때만 정리를 시작(Disconnecting 이 아닌 단계)하거나
     * 정리 기한으로 끝낸다(Disconnecting).
     */
    data class LifecycleDeadline(val deadline: ConnectionStageDeadline, val disconnectCleanupMillis: Long) : ConnectionEvent

    /** 어느 활성 단계에서든 정리를 시작하라는 요청(원인은 first wins). */
    data class CleanupRequested(
        val cause: TerminalCause,
        val suppressReconnect: Boolean,
        val disconnectCleanupMillis: Long,
    ) : ConnectionEvent

    /** 플랫폼 해제 콜백 또는 정리 기한으로 [generation] 의 정리가 끝났다([status] 는 플랫폼 status). */
    data class CleanupCompleted(val generation: Long, val fallback: TerminalCause, val status: Int) : ConnectionEvent

    /** 게시 없이 [generation] 만 종결한다(connect() 의 즉시 실패 — 게시는 호출자 몫). */
    data class CompletedWithoutPublish(val generation: Long, val fallback: TerminalCause) : ConnectionEvent

    /** 즉시 종결(시작 거부·어댑터 이탈 등) — 이미 고정된 원인이 있으면 그 원인이 이긴다. */
    data class Terminate(val generation: Long, val cause: TerminalCause) : ConnectionEvent

    /** 사용자 해제 요청. [hasHandle] 은 platform handle 이 있는가(없으면 진행 중인 재발견을 끝낸다). */
    data class DisconnectRequested(val hasHandle: Boolean, val disconnectCleanupMillis: Long) : ConnectionEvent

    /** driver.disconnect 시작이 던졌다. [fallback] 이 있으면 그 원인으로 정리를 마친다. */
    data class DisconnectStartFailed(val generation: Long, val fallback: TerminalCause?) : ConnectionEvent

    /** client close. */
    data object CloseRequested : ConnectionEvent

    /** 어댑터 이탈 스윕의 세션 몫. */
    data class AdapterLost(val adapter: AdapterState) : ConnectionEvent

    /** GATT 작업 쪽이 세션을 끝내야 하는 실패(작업 기한 초과 등)를 확정했다. */
    data class OperationInvalidated(
        val failure: BleFailure,
        val suppressReconnect: Boolean,
        val severity: Severity,
    ) : ConnectionEvent

    /** GATT 작업 시작에서 권한 상실이 확인됐다. */
    data class OperationPermissionLost(val failure: BleFailure, val disconnectCleanupMillis: Long) : ConnectionEvent

    /** 세션 소유 코루틴에서 잡히지 않은 예외가 났다. */
    data class InternalFailure(val failure: BleFailure) : ConnectionEvent

    /** platform handle 을 닫았다. */
    data object HandleReleased : ConnectionEvent

    /**
     * 링크 상태 콜백. [failure] 가 null 이면 성공 status 다(플랫폼 status 를 실패 값으로 바꾸는 것은 Runtime 이다).
     * [status] 는 원인 없는 해제의 LINK_FAILED 에 담길 플랫폼 status 다. [atMillis] 는 콜백을 받은 단조 시각이다.
     */
    data class LinkStateChanged(
        val generation: Long,
        val link: LinkFact,
        val status: Int,
        val failure: BleFailure?,
        val discoveryMillis: Long,
        val disconnectCleanupMillis: Long,
        val atMillis: Long,
    ) : ConnectionEvent

    /** 종결된 세션의 플랫폼 재연결 대기 handle 에 OS 가 다시 붙였다(Bootstrap 귀속 뒤). */
    data object PlatformRelinkUp : ConnectionEvent

    /** 서비스 탐색 결과. [failure] 가 null 이면 서비스가 있는 성공이다. */
    data class ServicesDiscovered(
        val generation: Long,
        val failure: BleFailure?,
        val configurationMillis: Long,
        val disconnectCleanupMillis: Long,
    ) : ConnectionEvent

    /** driver.discoverServices 시작이 거절됐거나 던졌다. */
    data class DiscoveryStartFailed(val failure: BleFailure, val disconnectCleanupMillis: Long) : ConnectionEvent

    /** MTU 협상 결과. [failure] 가 null 이 아니면 [mtu] 대신 기본값으로 진행한다. */
    data class MtuSettled(val generation: Long, val mtu: Int, val failure: BleFailure?) : ConnectionEvent

    /** driver.requestMtu 시작이 거절됐거나 던졌다. */
    data class MtuRequestFailed(val failure: BleFailure, val disconnectCleanupMillis: Long) : ConnectionEvent

    /** 구독 복원이 끝났다. */
    data class RestoreFinished(val generation: Long) : ConnectionEvent

    /** 이미 세션이 있을 때의 connect 요청 — 중복 판정만 한다(상태는 바뀌지 않고 [ConnectionEffect.ConnectAdmitted] 를 낸다). */
    data class ConnectRequested(val options: ConnectOptions, val defaultLinkMillis: Long) : ConnectionEvent

    /** 새 연결 시도를 연다([atMillis] 시각). [rediscover] 면 링크 시작 전에 cold-cache 재발견을 먼저 한다. */
    data class BeginConnect(
        val options: ConnectOptions,
        val defaultLinkMillis: Long,
        val rediscover: Boolean,
        val atMillis: Long,
    ) : ConnectionEvent

    /**
     * 재발견 스캔이 끝났다. [failure] 는 Unavailable 의 사유·NotFound 의 실패 값이고, [invalidatedFailure] 는 그 사이
     * 사용자 해제로 끝난 호출을 무효화로 던질 때의 실패 값이다(세션 문맥이 필요해 Runtime 이 만든다).
     */
    data class RediscoveryFinished(
        val generation: Long,
        val outcome: RediscoveryFact,
        val failure: BleFailure?,
        val invalidatedFailure: BleFailure,
    ) : ConnectionEvent

    /** connect 호출자가 재발견을 기다리다 취소됐다. */
    data class ConnectCallCancelled(val generation: Long) : ConnectionEvent

    /** driver.connect 가 던졌다. */
    data class LinkStartFailed(val generation: Long, val failure: BleFailure, val kind: LinkStartFailureKind) : ConnectionEvent

    /** driver.connect 가 [atMillis] 에 성공해 handle 이 열렸다. */
    data class LinkStarted(val atMillis: Long) : ConnectionEvent

    /** 빠른 133 재시도의 driver.connect 가 던졌다 — 호출자는 이미 반환했으니 게시로 끝낸다. */
    data class RetryStartFailed(val generation: Long, val failure: BleFailure, val kind: LinkStartFailureKind) : ConnectionEvent

    /** connect 호출이 시작 실패([failure])로 던지려 한다. */
    data class ConnectCallFailed(val failure: BleFailure) : ConnectionEvent

    /** 어댑터가 Ready 로 돌아왔다([adapterReady] 는 홉 뒤 다시 관측한 값). */
    data class AdapterReturned(
        val adapterReady: Boolean,
        val defaultLinkMillis: Long,
        val rediscover: Boolean,
        val atMillis: Long,
    ) : ConnectionEvent
}

/** 링크 상태 콜백의 사실. 플랫폼 상수는 Runtime 이 이 값으로 옮긴다. */
internal enum class LinkFact { Up, Down, Connecting, Disconnecting, Unknown }

/** Runtime 이 목록 순서대로 실행할 할 일. */
internal sealed interface ConnectionEffect {
    data class StageChanged(val stage: ConnectionStage) : ConnectionEffect

    data class ArmDeadline(val deadline: ConnectionStageDeadline) : ConnectionEffect

    data object CancelDeadline : ConnectionEffect

    data class DeadlineFired(val deadline: ConnectionStageDeadline) : ConnectionEffect

    /** 진단 사건을 낸다. */
    data class Diagnostic(val severity: Severity, val failure: BleFailure) : ConnectionEffect

    /** 현재 공개 상태를 사건으로 알린다(ConnStateChanged). */
    data object Announce : ConnectionEffect

    /** 단계 게시 없이 공개 상태만 다시 파생한다(플랫폼 재연결 대기 표시가 바뀐 경우). */
    data object Republish : ConnectionEffect

    /** 대기 중인 GATT 작업을 유한 종결한다. */
    data object InvalidateOperations : ConnectionEffect

    /** driver.disconnect. 시작이 던지면 [onStartFailure] 를 사건으로 넣고, 없으면 예외를 그대로 낸다. */
    data class DisconnectLink(val onStartFailure: ConnectionEvent.DisconnectStartFailed?) : ConnectionEffect

    /** driver.close 뒤 handle 을 비우고 [ConnectionEvent.HandleReleased] 를 넣는다. */
    data object ReleaseHandle : ConnectionEffect

    /** 협상된 MTU·청크 크기를 지운다. */
    data object ClearLinkParameters : ConnectionEffect

    /** 진행 중인 재발견을 찾지 못함으로 끝낸다. */
    data object EndRediscovery : ConnectionEffect

    /** 중복 connect 판정 결과. */
    data class ConnectAdmitted(val admission: ConnectAdmission) : ConnectionEffect

    /** 이전 시도의 stale handle 을 닫고 비운다(프로세스당 GATT 클라이언트 상한 보호). */
    data object ReleaseStaleHandle : ConnectionEffect

    /** 링크 시작 전에 cold-cache 재발견 스캔을 한다. */
    data object Rediscover : ConnectionEffect

    /** driver.connect 로 링크를 시작한다. */
    data class ConnectLink(val autoConnect: Boolean) : ConnectionEffect

    /** 같은 [generation] 에서 driver.connect(autoConnect=false) 를 다시 건다(빠른 133 재시도). */
    data class RetryLink(val generation: Long) : ConnectionEffect

    /** connect 호출을 [failure] 로 끝낸다(던진다). */
    data class FailConnectCall(val failure: BleFailure) : ConnectionEffect

    /** connect 호출을 정상 반환으로 끝낸다(연결을 시작하지 않는다). */
    data object EndConnectCall : ConnectionEffect

    /** 오염된 작업 큐가 있으면 새 generation 으로 시작한다. */
    data object BeginOperationGeneration : ConnectionEffect

    /** 링크가 붙었다 — 붙은 뒤 절차(탐색 시작)를 진행한다. */
    data class LinkUp(val generation: Long) : ConnectionEffect

    /** 확정된 MTU 를 세션에 반영한다(청크 크기 포함). */
    data class ApplyMtu(val mtu: Int) : ConnectionEffect

    /** MTU 확정 뒤 절차(구독 복원)를 진행한다. */
    data class RestoreSubscriptions(val generation: Long) : ConnectionEffect
}

/** [accepted] 는 사건이 상태에 적용됐는지(stale/거부면 false, 상태·할 일 없음). */
internal data class ConnectionStep(
    val state: ConnectionCoreState,
    val effects: List<ConnectionEffect>,
    val accepted: Boolean,
)

internal object ConnectionCore {
    /**
     * 어댑터 정착 창(contract/states.md §3). 링크 해제 콜백이 어댑터 이탈 알림보다 먼저 올 수 있어 Ready 링크 유실의 원인 확정을
     * 이만큼 미룬다. iPhone XR 실측(2026-09-30): 해제 콜백 뒤 어댑터 알림까지 61~727ms.
     */
    const val ADAPTER_SETTLE_MILLIS = 1000L

    /** [PLATFORM_WAIT_SUPERSEDERS] — 사용자 해제, client close, adapter off 스윕, 권한 상실, 세션 내부 실패(작업 무효화). */
    private val PLATFORM_WAIT_SUPERSEDERS = setOf(
        TerminalCleanupReason.UserRequested,
        TerminalCleanupReason.ClientClosed,
        TerminalCleanupReason.AdapterUnavailable,
        TerminalCleanupReason.PermissionDenied,
        TerminalCleanupReason.OperationInvalidated,
    )

    fun step(state: ConnectionCoreState, event: ConnectionEvent): ConnectionStep = when (event) {
        is ConnectionEvent.DeadlineExpired -> deadlineExpired(state, event)
        is ConnectionEvent.LifecycleDeadline -> ConnectionCleanup.lifecycleDeadline(state, event)
        is ConnectionEvent.CleanupRequested ->
            ConnectionCleanup.beginCleanup(state, event.cause, event.suppressReconnect, event.disconnectCleanupMillis)
        is ConnectionEvent.CleanupCompleted ->
            ConnectionCleanup.cleanupCompleted(state, event.generation, event.fallback, event.status)
        is ConnectionEvent.CompletedWithoutPublish ->
            ConnectionCleanup.completeWithoutPublish(state, event.generation, event.fallback)
        is ConnectionEvent.Terminate -> ConnectionCleanup.terminate(state, event.generation, event.cause)
        is ConnectionEvent.DisconnectRequested -> ConnectionCleanup.disconnectRequested(state, event)
        is ConnectionEvent.DisconnectStartFailed -> ConnectionCleanup.disconnectStartFailed(state, event)
        is ConnectionEvent.CloseRequested -> ConnectionCleanup.close(state)
        is ConnectionEvent.AdapterLost -> ConnectionCleanup.adapterLost(state, event.adapter)
        is ConnectionEvent.OperationInvalidated -> ConnectionCleanup.operationInvalidated(state, event)
        is ConnectionEvent.OperationPermissionLost ->
            ConnectionCleanup.operationPermissionLost(state, event.failure, event.disconnectCleanupMillis)
        is ConnectionEvent.InternalFailure -> ConnectionCleanup.internalFailure(state, event.failure)
        is ConnectionEvent.HandleReleased -> ConnectionCleanup.handleReleased(state)
        is ConnectionEvent.LinkStateChanged -> ConnectionLink.linkStateChanged(state, event)
        is ConnectionEvent.PlatformRelinkUp -> ConnectionLink.platformRelinkUp(state)
        is ConnectionEvent.ServicesDiscovered -> ConnectionLink.servicesDiscovered(state, event)
        is ConnectionEvent.DiscoveryStartFailed -> ConnectionLink.discoveryStartFailed(state, event)
        is ConnectionEvent.MtuSettled -> ConnectionLink.mtuSettled(state, event)
        is ConnectionEvent.MtuRequestFailed -> ConnectionLink.mtuRequestFailed(state, event)
        is ConnectionEvent.RestoreFinished -> ConnectionLink.restoreFinished(state, event)
        is ConnectionEvent.ConnectRequested -> ConnectionStart.admit(state, event.options, event.defaultLinkMillis)
        is ConnectionEvent.BeginConnect ->
            ConnectionStart.beginConnect(state, event.options, event.defaultLinkMillis, event.rediscover, event.atMillis)
        is ConnectionEvent.RediscoveryFinished -> ConnectionStart.rediscoveryFinished(state, event)
        is ConnectionEvent.ConnectCallCancelled -> ConnectionStart.callCancelled(state, event.generation)
        is ConnectionEvent.LinkStartFailed -> ConnectionStart.linkStartFailed(state, event)
        is ConnectionEvent.LinkStarted -> ConnectionStart.linkStarted(state, event.atMillis)
        is ConnectionEvent.RetryStartFailed -> ConnectionRetry.startFailed(state, event)
        is ConnectionEvent.ConnectCallFailed -> ConnectionStart.callFailed(state, event.failure)
        is ConnectionEvent.AdapterReturned -> ConnectionStart.adapterReturned(state, event)
        is ConnectionEvent.Direct.BeginConnection -> beginAttempt(state, event.linkTimeoutMillis)
        is ConnectionEvent.Direct.BeginPlatformRelink -> beginAttempt(state, null)
        is ConnectionEvent.Direct.Transition -> transition(state, event)
        is ConnectionEvent.Direct.BeginCleanup -> beginCleanup(state, event)
        is ConnectionEvent.Direct.CompleteCleanup -> completeCleanup(state, event)
        is ConnectionEvent.Direct.SupersedeForClose -> supersedeForClose(state, event.targetGeneration)
        is ConnectionEvent.Direct.SupersedePlatformWait -> supersedePlatformWait(state, event)
        is ConnectionEvent.Direct.CloseIdle -> closeIdle(state)
        is ConnectionEvent.Direct.LatchIfAbsent -> latchIfAbsent(state, event)
        is ConnectionEvent.Direct.SetPlatformWait -> accepted(state.copy(platformWait = event.value))
        is ConnectionEvent.Direct.SetSuppressReconnect -> accepted(state.copy(suppressReconnect = event.value))
        is ConnectionEvent.Direct.SetAutoConnect -> accepted(state.copy(autoConnect = event.value))
        is ConnectionEvent.Direct.SetRelinkAfterAdapter -> accepted(state.copy(relinkAfterAdapter = event.value))
        is ConnectionEvent.Direct.SetPlatformHandleOpen -> accepted(state.copy(platformHandleOpen = event.value))
        is ConnectionEvent.Direct.SetRequestedMtu -> accepted(state.copy(requestedMtu = event.value))
    }

    fun snapshot(state: ConnectionCoreState): ConnectionLifecycleSnapshot = ConnectionLifecycleSnapshot(
        generation = state.generation,
        stage = state.stage,
        stageTimeoutMillis = state.deadline?.timeoutMillis,
        terminalCleanupReason = state.terminalCause?.reason,
    )

    /** 상태를 바꾸지 않고 callback 귀속만 검사한다. */
    fun accepts(state: ConnectionCoreState, callbackGeneration: Long, expectedStage: ConnectionStage): Boolean =
        callbackGeneration == state.generation && expectedStage == state.stage

    /**
     * 공개 연결 상태 파생(§1·§3). 단계에서 공개 값을 정하되, 플랫폼 재연결 대기 중인 Disconnected 는
     * 마지막 실패를 담은 Recovering 이다.
     */
    fun publicState(state: ConnectionCoreState): ConnectionState = when (state.stage) {
        ConnectionStage.Disconnected ->
            if (state.platformWait) {
                ConnectionState.Recovering(
                    lastFailure = (disconnectReason(state) as? DisconnectReason.Failed)?.failure
                        ?: unattributedLinkDisconnect(state, status = 0),
                )
            } else {
                ConnectionState.Disconnected(disconnectReason(state).toPublicDisconnectReason())
            }
        ConnectionStage.Connecting -> ConnectionState.Connecting
        ConnectionStage.DiscoveringServices -> ConnectionState.DiscoveringServices
        ConnectionStage.Configuring -> ConnectionState.Configuring
        ConnectionStage.Ready -> ConnectionState.Ready
        ConnectionStage.Disconnecting -> ConnectionState.Disconnecting
    }

    /** 고정된 종결 원인에서 파생한 내부 종료 이유. 원인 없는 해제는 [status] 를 담은 LINK_FAILED 다. */
    fun disconnectReason(state: ConnectionCoreState, status: Int = 0): DisconnectReason =
        state.terminalCause.toDisconnectReason(
            generationStarted = state.generation > 0L,
            unattributedFailure = unattributedLinkDisconnect(state, status),
        )

    /** 원인 없이 끝난 시작된 generation 의 실패 — 세션 상관 문맥(sessionId·generation)을 싣는다. */
    fun unattributedLinkDisconnect(state: ConnectionCoreState, status: Int): BleFailure =
        FailureMapper.unattributedLinkDisconnect(
            statusCode = status,
            context = FailureContext(sessionId = state.failureSessionId, connectionGeneration = state.generation),
        )

    /** 플랫폼 재연결 대기 중인가 — 대기 표시가 서 있고 단계가 Disconnected 다. */
    fun awaitingPlatformRelink(state: ConnectionCoreState): Boolean =
        state.platformWait && state.stage == ConnectionStage.Disconnected

    /** 새 generation 이 열리는 단계 게시인가(구독 활성 집합의 generation 경계). */
    fun startsGeneration(stage: ConnectionStage): Boolean = stage == ConnectionStage.Connecting

    /** 링크를 떠나는 단계 게시인가(활성 구독 집합이 끝나는 경계). */
    fun leavesLink(stage: ConnectionStage): Boolean =
        stage == ConnectionStage.Disconnecting || stage == ConnectionStage.Disconnected

    /** [generation] 이 현재 generation 이고 Ready 다. */
    fun readyFor(state: ConnectionCoreState, generation: Long): Boolean =
        accepts(state, generation, ConnectionStage.Ready)

    fun inDiscovery(state: ConnectionCoreState, generation: Long): Boolean =
        accepts(state, generation, ConnectionStage.DiscoveringServices)

    fun inConfiguring(state: ConnectionCoreState, generation: Long): Boolean =
        accepts(state, generation, ConnectionStage.Configuring)

    /** 현재 generation 이 끝났다(Disconnected). */
    fun isEnded(state: ConnectionCoreState): Boolean = state.stage == ConnectionStage.Disconnected

    /** 공개 GATT 작업 접수는 Ready 에서만 허용된다(§5). */
    fun admitsPublicGattOperation(state: ConnectionCoreState): Boolean =
        state.stage == ConnectionStage.Ready && state.pendingLoss == null

    /** 서비스 목록이 있을 수 있는 단계인가 — 탐색 전(Disconnected·Connecting·DiscoveringServices)엔 없다. */
    fun hasTopology(state: ConnectionCoreState): Boolean = when (state.stage) {
        ConnectionStage.Disconnected,
        ConnectionStage.Connecting,
        ConnectionStage.DiscoveringServices,
        -> false
        ConnectionStage.Configuring,
        ConnectionStage.Ready,
        ConnectionStage.Disconnecting,
        -> true
    }

    private fun DisconnectReason.toPublicDisconnectReason(): BleDisconnectReason = when (this) {
        DisconnectReason.Initial -> BleDisconnectReason.Initial
        DisconnectReason.Requested -> BleDisconnectReason.Requested
        DisconnectReason.ClientClosed -> BleDisconnectReason.ClientClosed
        is DisconnectReason.Failed -> BleDisconnectReason.Failed(failure)
    }

    internal fun rejected(state: ConnectionCoreState) = ConnectionStep(state, emptyList(), accepted = false)

    internal fun accepted(state: ConnectionCoreState, effects: List<ConnectionEffect> = emptyList()) =
        ConnectionStep(state, effects, accepted = true)

    private fun deadlineExpired(state: ConnectionCoreState, event: ConnectionEvent.DeadlineExpired): ConnectionStep {
        val deadline = state.deadline
        if (deadline == null || deadline.id != event.id) return rejected(state)
        return accepted(state.copy(deadline = null), listOf(ConnectionEffect.DeadlineFired(deadline)))
    }

    private fun beginAttempt(state: ConnectionCoreState, linkTimeoutMillis: Long?): ConnectionStep {
        check(state.stage == ConnectionStage.Disconnected) {
            "Disconnected에서만 새 connection generation을 시작할 수 있다: ${state.stage}"
        }
        check(state.generation != Long.MAX_VALUE) { "connection generation이 소진됐다" }

        val next = state.copy(generation = state.generation + 1, terminalCause = null, pendingLoss = null, linkAttempt = null)
        return enterStage(next, ConnectionStage.Connecting, linkTimeoutMillis)
    }

    private fun transition(state: ConnectionCoreState, event: ConnectionEvent.Direct.Transition): ConnectionStep {
        if (!accepts(state, event.callbackGeneration, event.expectedStage)) return rejected(state)
        check(event.nextStage in allowedSuccessors(state.stage)) {
            "허용되지 않은 connection stage 전이: ${state.stage} -> ${event.nextStage}"
        }
        check(event.nextStage != ConnectionStage.Disconnecting && event.nextStage != ConnectionStage.Disconnected) {
            "cleanup 전이는 beginCleanup/completeCleanup API를 사용해야 한다: ${event.nextStage}"
        }
        return enterStage(state, event.nextStage, event.timeoutMillis)
    }

    /**
     * 어느 active stage에서든 terminal cleanup을 시작한다. 이미 Disconnecting이면 거부라 cleanup
     * side effect와 deadline을 중복 생성하지 않는다. 원인은 아직 없을 때만 기록된다(first wins) —
     * Disconnecting 게시 전에 고정되므로 게시 안에서 재진입한 종결도 고정된 원인을 본다.
     */
    private fun beginCleanup(state: ConnectionCoreState, event: ConnectionEvent.Direct.BeginCleanup): ConnectionStep {
        if (event.targetGeneration != state.generation || state.stage == ConnectionStage.Disconnected) {
            return rejected(state)
        }
        if (state.stage == ConnectionStage.Disconnecting) return rejected(state)

        check(ConnectionStage.Disconnecting in allowedSuccessors(state.stage)) {
            "현재 stage에서는 cleanup을 시작할 수 없다: ${state.stage}"
        }
        val latched = state.copy(terminalCause = state.terminalCause ?: event.cause, pendingLoss = null)
        return enterStage(latched, ConnectionStage.Disconnecting, event.disconnectCleanupTimeoutMillis)
    }

    /** 이미 latch된 원인은 보존하고 없을 때만 fallback 을 기록한 채 Disconnected 로 확정한다. */
    private fun completeCleanup(state: ConnectionCoreState, event: ConnectionEvent.Direct.CompleteCleanup): ConnectionStep {
        if (event.targetGeneration != state.generation || state.stage == ConnectionStage.Disconnected) {
            return rejected(state)
        }
        check(ConnectionStage.Disconnected in allowedSuccessors(state.stage)) {
            "현재 stage에서는 terminal cleanup을 완료할 수 없다: ${state.stage}"
        }
        val next = state.copy(
            stage = ConnectionStage.Disconnected,
            terminalCause = state.terminalCause ?: event.fallback,
            deadline = null,
            pendingLoss = null,
        )
        return accepted(next, listOf(ConnectionEffect.CancelDeadline, ConnectionEffect.StageChanged(next.stage)))
    }

    /**
     * client close 전용 원인 대체. 아직 Disconnected 가 아닌 [targetGeneration] 에 한해 원인을
     * ClientClosed 로 바꾼다 — 이어지는 completeCleanup 이 게시한다.
     */
    private fun supersedeForClose(state: ConnectionCoreState, targetGeneration: Long): ConnectionStep {
        if (targetGeneration != state.generation || state.stage == ConnectionStage.Disconnected) return rejected(state)
        return accepted(state.copy(terminalCause = TerminalCause(TerminalCleanupReason.ClientClosed, null)))
    }

    /**
     * 플랫폼 재연결 대기 중(이미 Disconnected)인 시작된 generation 의 원인을 대체하고 Disconnected 를
     * 다시 게시한다. 소유자는 사용자 해제·client close·adapter off 스윕·권한 상실·내부 실패(작업 무효화)뿐이다(다른 reason 은 require 로 거부).
     */
    private fun supersedePlatformWait(
        state: ConnectionCoreState,
        event: ConnectionEvent.Direct.SupersedePlatformWait,
    ): ConnectionStep {
        require(event.cause.reason in PLATFORM_WAIT_SUPERSEDERS) {
            "supersedePlatformWait: ${event.cause.reason} is not an owner"
        }
        if (event.targetGeneration != state.generation ||
            state.stage != ConnectionStage.Disconnected ||
            state.generation == 0L
        ) {
            return rejected(state)
        }
        return accepted(
            state.copy(terminalCause = event.cause),
            listOf(ConnectionEffect.StageChanged(state.stage)),
        )
    }

    /** 연결 시도 전(generation 0) 세션의 닫힘을 첫 원인으로 고정하고 Disconnected 를 다시 게시한다. */
    private fun closeIdle(state: ConnectionCoreState): ConnectionStep {
        if (state.generation != 0L || state.stage != ConnectionStage.Disconnected || state.terminalCause != null) {
            return rejected(state)
        }
        return accepted(
            state.copy(terminalCause = TerminalCause(TerminalCleanupReason.ClientClosed, null)),
            listOf(ConnectionEffect.StageChanged(state.stage)),
        )
    }

    /** stage를 바꾸지 않고 현재 generation에 원인만 latch한다(callback 실패 분기용). */
    private fun latchIfAbsent(state: ConnectionCoreState, event: ConnectionEvent.Direct.LatchIfAbsent): ConnectionStep {
        if (event.targetGeneration != state.generation || state.stage == ConnectionStage.Disconnected) {
            return rejected(state)
        }
        if (state.terminalCause != null) return rejected(state)
        return accepted(state.copy(terminalCause = event.cause))
    }

    private fun enterStage(state: ConnectionCoreState, nextStage: ConnectionStage, timeoutMillis: Long?): ConnectionStep {
        // Connecting 의 link deadline 만 선택이다 — null 이면 deadline 없음(autoConnect 첫 연결·플랫폼 재연결).
        val timeoutReason = if (nextStage == ConnectionStage.Connecting && timeoutMillis == null) {
            null
        } else {
            timeoutReasonFor(nextStage)
        }
        if (timeoutReason == null) {
            require(timeoutMillis == null) { "$nextStage 단계에는 deadline이 없다" }
        } else {
            requireNotNull(timeoutMillis) { "$nextStage 단계에는 timeoutMillis가 필요하다" }
            require(timeoutMillis > 0) { "timeoutMillis는 0보다 커야 한다: $timeoutMillis" }
        }

        val effects = listOf(ConnectionEffect.CancelDeadline, ConnectionEffect.StageChanged(nextStage))
        if (timeoutReason == null) {
            return accepted(state.copy(stage = nextStage, deadline = null), effects)
        }
        val seq = state.deadlineSeq + 1
        val deadline = ConnectionStageDeadline(
            generation = state.generation,
            stage = nextStage,
            timeoutMillis = requireNotNull(timeoutMillis),
            timeoutReason = timeoutReason,
            id = seq,
        )
        return accepted(
            state.copy(stage = nextStage, deadline = deadline, deadlineSeq = seq),
            effects + ConnectionEffect.ArmDeadline(deadline),
        )
    }

    private fun timeoutReasonFor(stage: ConnectionStage): TerminalCleanupReason? = when (stage) {
        ConnectionStage.Connecting -> TerminalCleanupReason.LinkTimeout
        ConnectionStage.DiscoveringServices -> TerminalCleanupReason.ServiceDiscoveryTimeout
        ConnectionStage.Configuring -> TerminalCleanupReason.ConfigurationTimeout
        ConnectionStage.Disconnecting -> TerminalCleanupReason.DisconnectCleanupTimeout
        ConnectionStage.Ready,
        ConnectionStage.Disconnected,
        -> null
    }

    private fun allowedSuccessors(stage: ConnectionStage): Set<ConnectionStage> = when (stage) {
        ConnectionStage.Disconnected -> setOf(ConnectionStage.Connecting)
        ConnectionStage.Connecting -> setOf(
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Disconnecting,
            ConnectionStage.Disconnected,
        )
        ConnectionStage.DiscoveringServices -> setOf(
            ConnectionStage.Configuring,
            ConnectionStage.Disconnecting,
            ConnectionStage.Disconnected,
        )
        ConnectionStage.Configuring -> setOf(
            ConnectionStage.Ready,
            ConnectionStage.Disconnecting,
            ConnectionStage.Disconnected,
        )
        ConnectionStage.Ready -> setOf(
            ConnectionStage.Disconnecting,
            ConnectionStage.Disconnected,
        )
        ConnectionStage.Disconnecting -> setOf(ConnectionStage.Disconnected)
    }
}
