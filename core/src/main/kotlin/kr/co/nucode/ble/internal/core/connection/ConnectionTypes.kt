package kr.co.nucode.ble.internal.core.connection

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
