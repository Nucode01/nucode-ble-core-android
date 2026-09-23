package kr.co.nucode.ble.internal.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    var terminalCleanupReason: TerminalCleanupReason? = null
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
        terminalCleanupReason = null
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
     * cleanup side effect와 deadline을 중복 생성하지 않는다.
     */
    fun beginCleanup(
        targetGeneration: Long,
        reason: TerminalCleanupReason,
        scope: CoroutineScope,
        disconnectCleanupTimeoutMillis: Long,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        if (stage == ConnectionStage.Disconnecting) return false

        check(ConnectionStage.Disconnecting in allowedSuccessors(stage)) {
            "현재 stage에서는 cleanup을 시작할 수 없다: $stage"
        }
        terminalCleanupReason = reason
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
     * [reason]이 null이면 beginCleanup에서 기록한 원인을 보존한다.
     */
    fun completeCleanup(
        targetGeneration: Long,
        reason: TerminalCleanupReason? = null,
        beforeStageChanged: () -> Unit = {},
    ): Boolean {
        if (targetGeneration != generation || stage == ConnectionStage.Disconnected) return false
        check(ConnectionStage.Disconnected in allowedSuccessors(stage)) {
            "현재 stage에서는 terminal cleanup을 완료할 수 없다: $stage"
        }

        cancelDeadline()
        terminalCleanupReason = reason ?: terminalCleanupReason ?: TerminalCleanupReason.PlatformDisconnected
        beforeStageChanged()
        stage = ConnectionStage.Disconnected
        onStageChanged(stage)
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
}
