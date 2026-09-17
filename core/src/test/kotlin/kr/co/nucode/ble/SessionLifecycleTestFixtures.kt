package kr.co.nucode.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kr.co.nucode.ble.internal.ConnectionStage
import kr.co.nucode.ble.internal.Session
import kr.co.nucode.ble.internal.TerminalCleanupReason

private val lifecycleFixtureScope = CoroutineScope(Dispatchers.Unconfined)

/** Drives the real coordinator for tests that do not exercise platform connection callbacks. */
internal fun Session.setStateForTest(target: ConnState) {
    val coordinator = connectionCoordinator
    if (coordinator.stage != ConnectionStage.Disconnected) {
        prepareDisconnect((target as? ConnState.Disconnected)?.reason ?: DisconnectReason.Initial)
        coordinator.completeCleanup(coordinator.generation, TerminalCleanupReason.PlatformDisconnected)
    }

    if (target is ConnState.Disconnected) {
        prepareDisconnect(target.reason)
        return
    }

    val generation = coordinator.beginConnection(
        scope = lifecycleFixtureScope,
        linkTimeoutMillis = Long.MAX_VALUE,
        onTimeout = {},
    )
    if (target is ConnState.Connecting) return

    coordinator.transition(
        callbackGeneration = generation,
        expectedStage = ConnectionStage.Connecting,
        nextStage = ConnectionStage.DiscoveringServices,
        scope = lifecycleFixtureScope,
        timeoutMillis = Long.MAX_VALUE,
    )
    if (target is ConnState.DiscoveringServices) return

    coordinator.transition(
        callbackGeneration = generation,
        expectedStage = ConnectionStage.DiscoveringServices,
        nextStage = ConnectionStage.Configuring,
        scope = lifecycleFixtureScope,
        timeoutMillis = Long.MAX_VALUE,
    )
    if (target is ConnState.Configuring) return

    if (target is ConnState.Ready) {
        coordinator.transition(
            callbackGeneration = generation,
            expectedStage = ConnectionStage.Configuring,
            nextStage = ConnectionStage.Ready,
            scope = lifecycleFixtureScope,
        )
        return
    }

    check(target is ConnState.Disconnecting)
    coordinator.beginCleanup(
        targetGeneration = generation,
        reason = TerminalCleanupReason.UserRequested,
        scope = lifecycleFixtureScope,
        disconnectCleanupTimeoutMillis = Long.MAX_VALUE,
        onTimeout = {},
    )
}
