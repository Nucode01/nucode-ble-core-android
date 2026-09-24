package kr.co.nucode.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

private val lifecycleFixtureScope = CoroutineScope(Dispatchers.Unconfined)

/** Drives the real coordinator for tests that do not exercise platform connection callbacks. */
internal fun Session.setStateForTest(target: ConnectionState) {
    require(target !is ConnectionState.Recovering) { "Recovering is a transient projection; not supported by setStateForTest" }
    val coordinator = connectionCoordinator
    if (target is ConnectionState.Disconnected) {
        if (connectionState.value == target) return
        // 공개 이유는 coordinator 가 고정한 원인의 파생뿐이다 — 원하는 이유를 원인으로 고정해 종결한다.
        if (target.reason == BleDisconnectReason.ClientClosed && coordinator.closeIdle()) return
        require(target.reason != BleDisconnectReason.Initial) { "Initial is only valid before the first attempt" }
        if (coordinator.stage == ConnectionStage.Disconnected) {
            coordinator.beginConnection(lifecycleFixtureScope, Long.MAX_VALUE) {}
        }
        coordinator.completeCleanup(coordinator.generation, target.reason.toTerminalCauseForTest())
        return
    }
    if (coordinator.stage != ConnectionStage.Disconnected) {
        coordinator.completeCleanup(
            coordinator.generation,
            TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
        )
    }

    val generation = coordinator.beginConnection(
        scope = lifecycleFixtureScope,
        linkTimeoutMillis = Long.MAX_VALUE,
        onTimeout = {},
    )
    if (target is ConnectionState.Connecting) return

    coordinator.transition(
        callbackGeneration = generation,
        expectedStage = ConnectionStage.Connecting,
        nextStage = ConnectionStage.DiscoveringServices,
        scope = lifecycleFixtureScope,
        timeoutMillis = Long.MAX_VALUE,
    )
    if (target is ConnectionState.DiscoveringServices) return

    coordinator.transition(
        callbackGeneration = generation,
        expectedStage = ConnectionStage.DiscoveringServices,
        nextStage = ConnectionStage.Configuring,
        scope = lifecycleFixtureScope,
        timeoutMillis = Long.MAX_VALUE,
    )
    if (target is ConnectionState.Configuring) return

    if (target is ConnectionState.Ready) {
        coordinator.transition(
            callbackGeneration = generation,
            expectedStage = ConnectionStage.Configuring,
            nextStage = ConnectionStage.Ready,
            scope = lifecycleFixtureScope,
        )
        return
    }

    check(target is ConnectionState.Disconnecting)
    coordinator.beginCleanup(
        targetGeneration = generation,
        cause = TerminalCause(TerminalCleanupReason.UserRequested, null),
        scope = lifecycleFixtureScope,
        disconnectCleanupTimeoutMillis = Long.MAX_VALUE,
        onTimeout = {},
    )
}

private fun BleDisconnectReason.toTerminalCauseForTest(): TerminalCause = when (this) {
    BleDisconnectReason.Initial -> error("Initial has no terminal cause")
    BleDisconnectReason.Requested -> TerminalCause(TerminalCleanupReason.UserRequested, null)
    BleDisconnectReason.ClientClosed -> TerminalCause(TerminalCleanupReason.ClientClosed, null)
    is BleDisconnectReason.Failed -> TerminalCause(TerminalCleanupReason.PlatformDisconnected, failure)
}
