package kr.co.nucode.ble.internal.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.nucode.ble.BleFailure

/** 한 connection generation이 거치는 readiness/cleanup 단계. */
internal enum class ConnectionStage {
    Disconnected,
    Connecting,
    DiscoveringServices,
    Configuring,
    Ready,
    Disconnecting,
}

/**
 * 현재 generation이 terminal cleanup에 들어간 이유.
 *
 * 플랫폼 status와 공개 Throwable 변환은 GattBridge/BleRuntime의 책임으로 남겨 두고 coordinator는
 * lifecycle 분류만 소유한다.
 */
internal enum class TerminalCleanupReason {
    LinkStartRejected,
    LinkStatusFailure,
    LinkTimeout,
    ServiceDiscoveryStartRejected,
    ServiceDiscoveryStatusFailure,
    ServiceDiscoveryTimeout,
    ConfigurationStatusFailure,
    ConfigurationTimeout,
    UserRequested,
    DisconnectCleanupTimeout,
    ClientClosed,
    AdapterUnavailable,
    PermissionDenied,
    PlatformDisconnected,
    OperationInvalidated,
}

/**
 * 한 generation의 종결 원인. generation마다 처음 기록된 값 하나만 유지된다(first wins).
 * [failure]가 있으면 공개 이유는 그 실패로 파생되고, 없으면 [reason] 분류로 파생된다.
 */
internal data class TerminalCause(
    val reason: TerminalCleanupReason,
    val failure: BleFailure?,
)

/** 현재 stage에 귀속된 deadline. 이전 generation/stage timeout과의 identity 비교에도 쓴다. */
internal data class ConnectionStageDeadline(
    val generation: Long,
    val stage: ConnectionStage,
    val timeoutMillis: Long,
    val timeoutReason: TerminalCleanupReason,
)

/** 후속 task가 connection lifecycle 상태를 한 번에 읽는 단일 snapshot 계약. */
internal data class ConnectionLifecycleSnapshot(
    val generation: Long,
    val stage: ConnectionStage,
    val stageTimeoutMillis: Long?,
    val terminalCleanupReason: TerminalCleanupReason?,
)

/** BleRuntime가 주입하는 successful readiness 단계별 timeout 묶음. */
internal data class ConnectionReadinessTimeouts(
    val linkMillis: Long,
    val discoveryMillis: Long,
    val configurationMillis: Long,
    val disconnectCleanupMillis: Long = 5_000,
    /** cold-cache 재발견 스캔 상한; link deadline 안에서 소비된다. */
    val rediscoveryMillis: Long = 3_000,
)

/** 현재 generation을 시작한 주체. 실패 후 기존 reconnect 경계로 돌아갈지 판정할 때 쓴다. */
internal enum class ConnectionAttemptOrigin {
    Explicit,
    Reconnect,
}

/**
 * Session 하나의 connection lifecycle 상태 머신.
 *
 * 모든 호출과 [scope]는 BleRuntime의 단일 dispatcher에 귀속되어야 한다. coordinator는
 * connection generation, stage, 최대 하나의 stage deadline, terminal cleanup reason을 같이
 * 전이시킨다. Android handle/source 검사는 GattBridge의 IO 경계에 남고, callback은 generation과
 * expected stage를 이 API에 전달해야 현재 lifecycle을 변경할 수 있다.
 */
internal class ConnectionCoordinator(
    private val onStageChanged: (ConnectionStage) -> Unit = {},
) {
    var generation: Long = 0L
        private set

    var stage: ConnectionStage = ConnectionStage.Disconnected
        private set

    /** [terminalCause] 의 분류만 비추는 읽기 전용 view. 쓰기 경로는 없다. */
    val terminalCleanupReason: TerminalCleanupReason?
        get() = terminalCause?.reason

    /** 현재 generation에 처음 latch된 종결 원인. 새 generation 시작 시 null로 초기화된다. */
    var terminalCause: TerminalCause? = null
        private set

    var attemptOrigin: ConnectionAttemptOrigin = ConnectionAttemptOrigin.Explicit
        private set

    var activeDeadline: ConnectionStageDeadline? = null
        private set

    private var deadlineJob: Job? = null

    val snapshot: ConnectionLifecycleSnapshot
        get() = ConnectionLifecycleSnapshot(
            generation = generation,
            stage = stage,
            stageTimeoutMillis = activeDeadline?.timeoutMillis,
            terminalCleanupReason = terminalCleanupReason,
        )

    /** 상태를 바꾸지 않고 callback 귀속만 검사한다. 실패 callback 처리에도 같은 규칙을 쓴다. */
    fun accepts(
        callbackGeneration: Long,
        expectedStage: ConnectionStage,
    ): Boolean = callbackGeneration == generation && expectedStage == stage

    /** Disconnected에서 새 generation을 시작하고 link deadline을 등록한다. */
    fun beginConnection(
        scope: CoroutineScope,
        linkTimeoutMillis: Long,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Long = beginAttempt(
        scope = scope,
        linkTimeoutMillis = linkTimeoutMillis,
        origin = ConnectionAttemptOrigin.Explicit,
        onTimeout = onTimeout,
    )

    /** 기존 platform handle의 reconnect 시작을 새 generation과 link deadline에 귀속한다. */
    fun beginReconnect(
        scope: CoroutineScope,
        linkTimeoutMillis: Long,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Long = beginAttempt(
        scope = scope,
        linkTimeoutMillis = linkTimeoutMillis,
        origin = ConnectionAttemptOrigin.Reconnect,
        onTimeout = onTimeout,
    )

    private fun beginAttempt(
        scope: CoroutineScope,
        linkTimeoutMillis: Long,
        origin: ConnectionAttemptOrigin,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Long {
        check(stage == ConnectionStage.Disconnected) {
            "Disconnected에서만 새 connection generation을 시작할 수 있다: $stage"
        }
        check(generation != Long.MAX_VALUE) { "connection generation이 소진됐다" }

        generation += 1
        terminalCause = null
        attemptOrigin = origin
        enterStage(
            scope = scope,
            nextStage = ConnectionStage.Connecting,
            timeoutMillis = linkTimeoutMillis,
            onTimeout = onTimeout,
        )
        return generation
    }

    /**
     * callback의 generation/stage가 현재 값과 일치할 때만 다음 readiness stage로 전이한다.
     * stale callback은 false를 반환하고 현재 deadline을 포함한 상태를 바꾸지 않는다.
     */
    fun transition(
        callbackGeneration: Long,
        expectedStage: ConnectionStage,
        nextStage: ConnectionStage,
        scope: CoroutineScope,
        timeoutMillis: Long? = null,
        onTimeout: (ConnectionStageDeadline) -> Unit = {},
    ): Boolean {
        if (!accepts(callbackGeneration, expectedStage)) return false
        check(nextStage in allowedSuccessors(stage)) {
            "허용되지 않은 connection stage 전이: $stage -> $nextStage"
        }
        check(nextStage != ConnectionStage.Disconnecting && nextStage != ConnectionStage.Disconnected) {
            "cleanup 전이는 beginCleanup/completeCleanup API를 사용해야 한다: $nextStage"
        }

        enterStage(scope, nextStage, timeoutMillis, onTimeout)
        return true
    }

    /**
     * 어느 active stage에서든 terminal cleanup을 시작한다. 이미 Disconnecting이면 no-op이라
     * cleanup side effect와 deadline을 중복 생성하지 않는다. [cause]는 아직 latch된 원인이 없을
     * 때만 기록된다(first wins) — 먼저 고정된 원인을 바꾸는 인자는 없다.
     */
    fun beginCleanup(
        targetGeneration: Long,
        cause: TerminalCause,
        scope: CoroutineScope,
        disconnectCleanupTimeoutMillis: Long,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        if (stage == ConnectionStage.Disconnecting) return false

        check(ConnectionStage.Disconnecting in allowedSuccessors(stage)) {
            "현재 stage에서는 cleanup을 시작할 수 없다: $stage"
        }
        // Disconnecting 게시(onStageChanged) 전에 고정한다 — 순수 first wins. 게시 안에서 재진입한
        // 종결이 원인을 바꾸려면 명시적 권한([supersedeForClose])이 있어야 한다.
        if (terminalCause == null) terminalCause = cause
        enterStage(
            scope = scope,
            nextStage = ConnectionStage.Disconnecting,
            timeoutMillis = disconnectCleanupTimeoutMillis,
            onTimeout = onTimeout,
        )
        return true
    }

    /**
     * platform disconnect callback 또는 bounded local cleanup 완료를 terminal 상태로 확정한다.
     * 이미 latch된 원인이 있으면 보존하고, 없을 때만 [fallback]을 기록한다. 원인은 Disconnected
     * 게시(onStageChanged) 전에 고정되므로 게시 시점의 파생이 최종 원인을 본다.
     */
    fun completeCleanup(
        targetGeneration: Long,
        fallback: TerminalCause,
    ): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        check(ConnectionStage.Disconnected in allowedSuccessors(stage)) {
            "현재 stage에서는 terminal cleanup을 완료할 수 없다: $stage"
        }

        cancelDeadline()
        if (terminalCause == null) terminalCause = fallback
        stage = ConnectionStage.Disconnected
        onStageChanged(stage)
        return true
    }

    /**
     * client close 전용 원인 대체. 아직 Disconnected 가 아닌(종결되지 않은) [targetGeneration] 에 한해
     * latch된 원인을 `TerminalCause(ClientClosed, null)` 로 바꾼다 — 이어지는 completeCleanup 이 게시한다.
     *
     * v1 계약상 client close 로 끝난 세션은 `Disconnected(ClientClosed)` 여야 한다. 이미 Disconnected 인
     * generation(또는 stale generation)은 원래 원인을 유지하고 false — 재연결 대기 중인 종결
     * generation 은 [supersedeRetryPending] 이 다룬다.
     */
    fun supersedeForClose(targetGeneration: Long): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        terminalCause = TerminalCause(TerminalCleanupReason.ClientClosed, null)
        return true
    }

    /**
     * 재연결 대기 중(이미 Disconnected 지만 공개 상태 Recovering 이거나 reconnect job 예약됨)인
     * 시작된 [targetGeneration] 의 원인을 [cause] 로 대체하고 Disconnected 를 다시 게시(onStageChanged)해
     * Recovering 을 대체한다. 재시도 대기 여부 판정은 호출자 몫이다(세션의 reconnect job·공개 상태).
     *
     * first wins 의 예외이며 소유자는 정확히 둘이다 — 둘 다 "회복을 끝내는" 주체이고, 회복 중인 세션은
     * 앱 관점에서 아직 종착이 아니므로 그 종착 원인은 회복을 끝낸 쪽이다:
     * - client close → `ClientClosed` (v1 계약: 닫힌 클라이언트의 세션은 Disconnected(ClientClosed)).
     * - adapter off 스윕 → `AdapterUnavailable`/`PermissionDenied` (어댑터가 죽으면 재시도는 불가능하다).
     *
     * 다른 reason 은 거부한다(require). stale generation·종결되지 않은 generation·generation 0 이면 false.
     */
    fun supersedeRetryPending(targetGeneration: Long, cause: TerminalCause): Boolean {
        require(cause.reason in RETRY_PENDING_SUPERSEDERS) { "supersedeRetryPending: ${cause.reason} is not an owner" }
        if (targetGeneration != generation || stage != ConnectionStage.Disconnected || generation == 0L) return false
        terminalCause = cause
        onStageChanged(stage)
        return true
    }

    /**
     * 연결 시도 전(generation 0) 세션의 닫힘을 첫 원인으로 고정하고 Disconnected 를 다시 게시한다.
     * 시작된 generation 이거나 이미 원인이 있으면 아무 것도 하지 않는다(first wins).
     */
    fun closeIdle(): Boolean {
        if (generation != 0L || stage != ConnectionStage.Disconnected || terminalCause != null) return false
        terminalCause = TerminalCause(TerminalCleanupReason.ClientClosed, null)
        onStageChanged(stage)
        return true
    }

    /**
     * 재연결 소진 선판정 전용 latch(plan terminal-cause v3 §접근 "소진 선판정"). 종결 완료 직전에
     * 이미 고정된 원인(없으면 [fallback])의 **failure 만** [exhausted] 로 바꾼다 — reason 은 유지한다.
     *
     * 사용자 해제(UserRequested)·client close(ClientClosed)는 재시도 대상이 아니므로 소진으로 바꾸지
     * 않고 false 를 반환한다. stale generation 이거나 이미 Disconnected 여도 false. 일반 변환 인자는
     * 없다 — first wins 의 예외는 이 좁은 판결(같은 원인에 "재시도 없음"을 덧씌움)뿐이다.
     */
    fun latchExhaustion(
        targetGeneration: Long,
        fallback: TerminalCause,
        exhausted: BleFailure,
    ): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        val base = terminalCause ?: fallback
        if (base.reason == TerminalCleanupReason.UserRequested || base.reason == TerminalCleanupReason.ClientClosed) {
            return false
        }
        terminalCause = TerminalCause(base.reason, exhausted)
        return true
    }

    /**
     * stage를 바꾸지 않고 현재 generation에 원인만 latch한다(callback 실패 분기용).
     * stale generation이거나 이미 latch된 원인이 있으면 false.
     */
    fun latchIfAbsent(
        targetGeneration: Long,
        cause: TerminalCause,
    ): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        if (terminalCause != null) return false
        terminalCause = cause
        return true
    }

    private fun enterStage(
        scope: CoroutineScope,
        nextStage: ConnectionStage,
        timeoutMillis: Long?,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ) {
        val timeoutReason = timeoutReasonFor(nextStage)
        if (timeoutReason == null) {
            require(timeoutMillis == null) { "$nextStage 단계에는 deadline이 없다" }
        } else {
            requireNotNull(timeoutMillis) { "$nextStage 단계에는 timeoutMillis가 필요하다" }
            require(timeoutMillis > 0) { "timeoutMillis는 0보다 커야 한다: $timeoutMillis" }
        }

        cancelDeadline()
        stage = nextStage
        onStageChanged(stage)
        if (timeoutReason == null) return

        val deadline = ConnectionStageDeadline(
            generation = generation,
            stage = nextStage,
            timeoutMillis = requireNotNull(timeoutMillis),
            timeoutReason = timeoutReason,
        )
        activeDeadline = deadline
        deadlineJob = scope.launch {
            delay(deadline.timeoutMillis)
            if (activeDeadline !== deadline) return@launch

            activeDeadline = null
            deadlineJob = null
            onTimeout(deadline)
        }
    }

    private fun cancelDeadline() {
        deadlineJob?.cancel()
        deadlineJob = null
        activeDeadline = null
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

    private companion object {
        /** [supersedeRetryPending] 의 소유자 — client close 와 adapter off 스윕. */
        val RETRY_PENDING_SUPERSEDERS = setOf(
            TerminalCleanupReason.ClientClosed,
            TerminalCleanupReason.AdapterUnavailable,
            TerminalCleanupReason.PermissionDenied,
        )
    }
}
