package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DisconnectReason

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
 * 플랫폼 status와 공개 Throwable 변환은 GattBridge/BleRuntime의 책임으로 남겨 두고 Core는
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

/**
 * 원인 확정을 기다리는 Ready 링크 유실(contract/states.md §3 어댑터 정착 창). 창 안에 어댑터가 Ready 를 벗어나면
 * 어댑터 원인으로, 창이 지나면 [cause]·[status] 로 끝낸다. 있는 동안 단계는 게시하지 않은 채 그대로다.
 */
internal data class PendingLinkLoss(
    val generation: Long,
    val cause: TerminalCause,
    val status: Int,
)

/** 현재 stage에 귀속된 deadline. 이전 generation/stage timeout과의 구분은 [id]로 한다. */
internal data class ConnectionStageDeadline(
    val generation: Long,
    val stage: ConnectionStage,
    val timeoutMillis: Long,
    val timeoutReason: TerminalCleanupReason,
    val id: Long = 0L,
)

/** 후속 task가 connection lifecycle 상태를 한 번에 읽는 단일 snapshot 계약. */
internal data class ConnectionLifecycleSnapshot(
    val generation: Long,
    val stage: ConnectionStage,
    val stageTimeoutMillis: Long?,
    val terminalCleanupReason: TerminalCleanupReason?,
)

/**
 * autoReconnect=false 연결의 링크 시도 기록(contract/states.md §2 Android 첫 직접 연결 재시도). 시각은 Runtime 시계의
 * 단조 ms 다. [connectingSinceMillis] 는 link deadline 이 시작된 시각, [requestedAtMillis] 는 이번 시도의 연결 요청
 * 시각(요청 전이면 null), [retries] 는 지금까지 다시 건 횟수다.
 */
internal data class LinkAttempt(
    val connectingSinceMillis: Long,
    val requestedAtMillis: Long?,
    val retries: Int,
)

/**
 * latch된 종결 원인에서 내부 [DisconnectReason]을 파생한다(순수 함수).
 *
 * 원인 없이 종결된 시작된 generation은 LINK_FAILED 실패로 본다. 그 [BleFailure]는 platform
 * statusCode 등 session 문맥이 필요하므로 호출자가 [unattributedFailure]로 넘긴다.
 */
internal fun TerminalCause?.toDisconnectReason(
    generationStarted: Boolean,
    unattributedFailure: BleFailure,
): DisconnectReason {
    val failure = this?.failure
    return when {
        failure != null -> DisconnectReason.Failed(failure)
        this?.reason == TerminalCleanupReason.UserRequested -> DisconnectReason.Requested
        this?.reason == TerminalCleanupReason.ClientClosed -> DisconnectReason.ClientClosed
        generationStarted -> DisconnectReason.Failed(unattributedFailure)
        else -> DisconnectReason.Initial
    }
}
