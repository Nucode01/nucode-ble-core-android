package kr.co.nucode.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

private val lifecycleFixtureScope = CoroutineScope(Dispatchers.Unconfined)

/** Drives the real coordinator for tests that do not exercise platform connection callbacks. */
internal fun Session.setStateForTest(target: ConnectionState) {
    require(target !is ConnectionState.Recovering) { "Recovering is a transient projection; not supported by setStateForTest" }
    val coordinator = connectionCoordinator
    if (coordinator.stage != ConnectionStage.Disconnected) {
        prepareDisconnect((target as? ConnectionState.Disconnected)?.reason?.toInternalForTest() ?: DisconnectReason.Initial)
        coordinator.completeCleanup(coordinator.generation, TerminalCleanupReason.PlatformDisconnected)
    }

    if (target is ConnectionState.Disconnected) {
        prepareDisconnect(target.reason.toInternalForTest())
        return
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
        reason = TerminalCleanupReason.UserRequested,
        scope = lifecycleFixtureScope,
        disconnectCleanupTimeoutMillis = Long.MAX_VALUE,
        onTimeout = {},
    )
}

private fun BleDisconnectReason.toInternalForTest(): DisconnectReason = when (this) {
    BleDisconnectReason.Initial -> DisconnectReason.Initial
    BleDisconnectReason.Requested -> DisconnectReason.Requested
    BleDisconnectReason.ClientClosed -> DisconnectReason.ClientClosed
    is BleDisconnectReason.Failed -> DisconnectReason.Failed(failure)
}
