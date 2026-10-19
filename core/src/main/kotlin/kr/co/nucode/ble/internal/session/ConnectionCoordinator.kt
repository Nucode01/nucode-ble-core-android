package kr.co.nucode.ble.internal.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kr.co.nucode.ble.internal.core.connection.ConnectionCore
import kr.co.nucode.ble.internal.core.connection.ConnectionCoreState
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent
import kr.co.nucode.ble.internal.core.connection.ConnectionLifecycleSnapshot
import kr.co.nucode.ble.internal.core.connection.ConnectionStage
import kr.co.nucode.ble.internal.core.connection.ConnectionStageDeadline
import kr.co.nucode.ble.internal.core.connection.TerminalCause
import kr.co.nucode.ble.internal.core.connection.TerminalCleanupReason
import kr.co.nucode.ble.internal.port.ClockPort

/** BleRuntime가 주입하는 successful readiness 단계별 timeout 묶음. */
internal data class ConnectionReadinessTimeouts(
    val linkMillis: Long,
    val discoveryMillis: Long,
    val configurationMillis: Long,
    val disconnectCleanupMillis: Long = 5_000,
    /** cold-cache 재발견 스캔 상한; link deadline 안에서 소비된다. */
    val rediscoveryMillis: Long = 3_000,
)

/**
 * Session 하나의 connection lifecycle 셸(Runtime).
 *
 * 모든 호출과 [scope]는 BleRuntime의 단일 dispatcher에 귀속되어야 한다. 전이 판단은 순수
 * [ConnectionCore.step] 이 하고, 이 셸은 [ConnectionCoreState] 하나를 들고 있다가 사건마다
 * 상태를 먼저 확정한 뒤 돌려받은 할 일(게시·기한 등록/취소)을 목록 순서대로 실행한다.
 * Android handle/source 검사는 GattBridge의 IO 경계에 남고, callback은 generation과
 * expected stage를 이 API에 전달해야 현재 lifecycle을 변경할 수 있다.
 */
internal class ConnectionCoordinator(
    private val clock: ClockPort,
    failureSessionId: String? = null,
    private val onRepublish: () -> Unit = {},
    private val onStageChanged: (ConnectionStage) -> Unit = {},
) {
    private var state = ConnectionCoreState(failureSessionId = failureSessionId)

    private var deadlineJob: Job? = null

    /** Core 상태 값의 읽기 전용 view — 공개 상태 파생·연결 칸 읽기는 [ConnectionCore] 질의로 한다. */
    val coreState: ConnectionCoreState
        get() = state

    val generation: Long
        get() = state.generation

    val stage: ConnectionStage
        get() = state.stage

    /** [terminalCause] 의 분류만 비추는 읽기 전용 view. 쓰기 경로는 없다. */
    val terminalCleanupReason: TerminalCleanupReason?
        get() = state.terminalCause?.reason

    /** 현재 generation에 처음 latch된 종결 원인. 새 generation 시작 시 null로 초기화된다. */
    val terminalCause: TerminalCause?
        get() = state.terminalCause

    val activeDeadline: ConnectionStageDeadline?
        get() = state.deadline

    /** 연결 사건에 싣는 단조 시각(ms) — Core 는 시계를 갖지 않으므로 시각은 사건으로 들어간다. */
    val nowMillis: Long
        get() = clock.elapsedMillis()

    val snapshot: ConnectionLifecycleSnapshot
        get() = ConnectionCore.snapshot(state)

    /** 상태를 바꾸지 않고 callback 귀속만 검사한다. 실패 callback 처리에도 같은 규칙을 쓴다. */
    fun accepts(
        callbackGeneration: Long,
        expectedStage: ConnectionStage,
    ): Boolean = ConnectionCore.accepts(state, callbackGeneration, expectedStage)

    /**
     * Disconnected에서 새 generation을 시작하고 link deadline을 등록한다.
     * [linkTimeoutMillis] 가 null 이면 이 Connecting 에는 deadline 이 없다 — autoConnect 첫 연결은
     * OS 가 무기한 기다리는 것이 의미라 끝내는 쪽은 사용자 해제·close·어댑터 스윕뿐이다.
     */
    fun beginConnection(
        authority: StageAuthority,
        scope: CoroutineScope,
        linkTimeoutMillis: Long?,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Long {
        apply(ConnectionEvent.Direct.BeginConnection(linkTimeoutMillis), scope, onTimeout)
        return state.generation
    }

    /**
     * callback의 generation/stage가 현재 값과 일치할 때만 다음 readiness stage로 전이한다.
     * stale callback은 false를 반환하고 현재 deadline을 포함한 상태를 바꾸지 않는다.
     */
    fun transition(
        authority: StageAuthority,
        callbackGeneration: Long,
        expectedStage: ConnectionStage,
        nextStage: ConnectionStage,
        scope: CoroutineScope,
        timeoutMillis: Long? = null,
        onTimeout: (ConnectionStageDeadline) -> Unit = {},
    ): Boolean = apply(
        ConnectionEvent.Direct.Transition(callbackGeneration, expectedStage, nextStage, timeoutMillis),
        scope,
        onTimeout,
    )

    /**
     * 어느 active stage에서든 terminal cleanup을 시작한다. 이미 Disconnecting이면 no-op이라
     * cleanup side effect와 deadline을 중복 생성하지 않는다. [cause]는 아직 latch된 원인이 없을
     * 때만 기록된다(first wins) — 먼저 고정된 원인을 바꾸는 인자는 없다.
     */
    fun beginCleanup(
        authority: StageAuthority,
        targetGeneration: Long,
        cause: TerminalCause,
        scope: CoroutineScope,
        disconnectCleanupTimeoutMillis: Long,
        onTimeout: (ConnectionStageDeadline) -> Unit,
    ): Boolean = apply(
        ConnectionEvent.Direct.BeginCleanup(targetGeneration, cause, disconnectCleanupTimeoutMillis),
        scope,
        onTimeout,
    )

    /**
     * platform disconnect callback 또는 bounded local cleanup 완료를 terminal 상태로 확정한다.
     * 이미 latch된 원인이 있으면 보존하고, 없을 때만 [fallback]을 기록한다. 원인은 Disconnected
     * 게시(onStageChanged) 전에 고정되므로 게시 시점의 파생이 최종 원인을 본다.
     */
    fun completeCleanup(
        authority: StageAuthority,
        targetGeneration: Long,
        fallback: TerminalCause,
    ): Boolean = apply(ConnectionEvent.Direct.CompleteCleanup(targetGeneration, fallback))

    /**
     * 연결 시도 전(generation 0) 세션의 닫힘을 첫 원인으로 고정하고 Disconnected 를 다시 게시한다.
     * 시작된 generation 이거나 이미 원인이 있으면 아무 것도 하지 않는다(first wins).
     */
    fun closeIdle(authority: StageAuthority): Boolean = apply(ConnectionEvent.Direct.CloseIdle)

    /** [generation] 이 현재 generation 이고 Ready 다. */
    fun readyFor(generation: Long): Boolean = ConnectionCore.readyFor(state, generation)

    fun inDiscovery(generation: Long): Boolean = ConnectionCore.inDiscovery(state, generation)

    fun inConfiguring(generation: Long): Boolean = ConnectionCore.inConfiguring(state, generation)

    val isEnded: Boolean
        get() = ConnectionCore.isEnded(state)

    val hasTopology: Boolean
        get() = ConnectionCore.hasTopology(state)

    val admitsPublicGattOperation: Boolean
        get() = ConnectionCore.admitsPublicGattOperation(state)

    /** 연결 칸 쓰기 입구 — 단계·원인은 바꾸지 않는다. 시험의 상태 준비 전용이고 main 은 부르지 않는다(R3 (e) 가 본다). */
    fun setPlatformWait(value: Boolean) {
        apply(ConnectionEvent.Direct.SetPlatformWait(value))
    }

    fun setSuppressReconnect(value: Boolean) {
        apply(ConnectionEvent.Direct.SetSuppressReconnect(value))
    }

    fun setAutoConnect(value: Boolean) {
        apply(ConnectionEvent.Direct.SetAutoConnect(value))
    }

    fun setPlatformHandleOpen(value: Boolean) {
        apply(ConnectionEvent.Direct.SetPlatformHandleOpen(value))
    }

    fun setRequestedMtu(value: Int) {
        apply(ConnectionEvent.Direct.SetRequestedMtu(value))
    }

    /**
     * Core 사건 입구 — 정리·종료 정책 사건은 실행기([run])가 넣는다. 게시·기한 할 일은 이 셸이 실행하고
     * 나머지 할 일(driver·operation hook·진단)은 목록 순서대로 [run] 에 넘긴다.
     */
    fun dispatch(
        event: ConnectionEvent,
        scope: CoroutineScope,
        onTimeout: (ConnectionStageDeadline) -> Unit,
        run: (ConnectionEffect) -> Unit,
    ): Boolean = apply(event, scope, onTimeout, run)

    /** 상태를 먼저 확정한 뒤 할 일을 목록 순서대로 실행한다 — 실행 중 재진입한 사건은 확정된 상태를 본다. */
    private fun apply(
        event: ConnectionEvent,
        scope: CoroutineScope? = null,
        onTimeout: (ConnectionStageDeadline) -> Unit = {},
        run: (ConnectionEffect) -> Unit = {},
    ): Boolean {
        val step = ConnectionCore.step(state, event)
        state = step.state
        step.effects.forEach { execute(it, scope, onTimeout, run) }
        return step.accepted
    }

    private fun execute(
        effect: ConnectionEffect,
        scope: CoroutineScope?,
        onTimeout: (ConnectionStageDeadline) -> Unit,
        run: (ConnectionEffect) -> Unit,
    ) {
        when (effect) {
            is ConnectionEffect.StageChanged -> onStageChanged(effect.stage)
            is ConnectionEffect.CancelDeadline -> {
                deadlineJob?.cancel()
                deadlineJob = null
            }
            is ConnectionEffect.ArmDeadline -> {
                // 게시 안에서 재진입한 종결이 이미 이 기한을 걷었으면 등록하지 않는다.
                val deadline = effect.deadline
                if (state.deadline?.id != deadline.id) return
                deadlineJob = requireNotNull(scope) { "deadline 등록에는 scope가 필요하다" }.launch {
                    clock.sleep(deadline.timeoutMillis)
                    apply(ConnectionEvent.DeadlineExpired(deadline.id), scope, onTimeout)
                }
            }
            is ConnectionEffect.DeadlineFired -> {
                deadlineJob = null
                onTimeout(effect.deadline)
            }
            is ConnectionEffect.Republish -> onRepublish()
            else -> run(effect)
        }
    }
}

/**
 * 전이 기본 동작(`Direct.*` 사건) 입구의 권한 토큰. [ConnectionCoordinator] 의 기본 동작 API 는 이 값을 첫 인자로 요구한다.
 * main 은 전이 기본 동작을 부르지 않는다 — 정책 사건은 [ConnectionCoordinator.dispatch] 로 Core 에 넣는다. 그래서 main 에는
 * 하위 타입이 없고, 시험의 상태 준비만 TestStageAuthority 로 부른다.
 * sealed 가 아닌 이유: 테스트 소스셋은 다른 모듈이라 sealed 하위 타입을 만들 수 없다.
 * main 에서 하위 타입을 선언하지 못하게 하는 것은 GattConnectionLifecycleTest 가드가 맡는다.
 */
internal abstract class StageAuthority protected constructor()
