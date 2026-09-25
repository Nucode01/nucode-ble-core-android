package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.session.ConnectionCoordinator
import kr.co.nucode.ble.internal.session.ConnectionLifecycleSnapshot
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.ConnectionStageDeadline
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    @Test
    fun `BleConfig는 lifecycle 단계별 기본 timeout을 노출한다`() {
        val config = BleConfig()

        assertEquals(15_000L, config.linkTimeoutMillis)
        assertEquals(10_000L, config.discoveryTimeoutMillis)
        assertEquals(10_000L, config.configurationTimeoutMillis)
        assertEquals(5_000L, config.disconnectCleanupTimeoutMillis)
    }

    @Test
    fun `readiness state machine은 discovery와 configuration을 건너뛰지 않는다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val generation = coordinator.beginConnection(TestStageAuthority, this, 100) {}

        assertEquals(ConnectionStage.Connecting, coordinator.stage)
        assertFailsWithIllegalState {
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.Ready,
                scope = this,
            )
        }

        assertTrue(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = this,
                timeoutMillis = 200,
            ),
        )
        assertTrue(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.DiscoveringServices,
                nextStage = ConnectionStage.Configuring,
                scope = this,
                timeoutMillis = 300,
            ),
        )
        assertTrue(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Configuring,
                nextStage = ConnectionStage.Ready,
                scope = this,
            ),
        )

        assertEquals(ConnectionStage.Ready, coordinator.stage)
        assertNull(coordinator.activeDeadline)
        assertNull(coordinator.snapshot.stageTimeoutMillis)
    }

    @Test
    fun `stage 전이는 이전 deadline을 취소해 동시에 하나만 활성화한다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val timedOut = mutableListOf<ConnectionStageDeadline>()
        val generation = coordinator.beginConnection(TestStageAuthority, this, 100, timedOut::add)
        val linkDeadline = coordinator.activeDeadline

        advanceTimeBy(50)
        runCurrent()
        assertTrue(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = this,
                timeoutMillis = 100,
                onTimeout = timedOut::add,
            ),
        )
        val discoveryDeadline = coordinator.activeDeadline

        assertFalse(linkDeadline === discoveryDeadline)
        assertEquals(100L, coordinator.snapshot.stageTimeoutMillis)

        advanceTimeBy(50)
        runCurrent()
        assertTrue(timedOut.isEmpty())
        assertSame(discoveryDeadline, coordinator.activeDeadline)

        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(discoveryDeadline), timedOut)
        assertNull(coordinator.activeDeadline)
    }

    @Test
    fun `stale generation과 wrong stage callback은 lifecycle을 바꾸지 않는다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val generation = coordinator.beginConnection(TestStageAuthority, this, 100) {}
        val currentDeadline = coordinator.activeDeadline

        assertFalse(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation - 1,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = this,
                timeoutMillis = 200,
            ),
        )
        assertFalse(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Configuring,
                nextStage = ConnectionStage.Ready,
                scope = this,
            ),
        )

        assertEquals(ConnectionStage.Connecting, coordinator.stage)
        assertSame(currentDeadline, coordinator.activeDeadline)
        assertEquals(generation, coordinator.snapshot.generation)
    }

    @Test
    fun `cleanup은 active stage 어디서든 멱등적이고 terminal reason을 보존한다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val timedOut = mutableListOf<ConnectionStageDeadline>()
        val generation = coordinator.beginConnection(TestStageAuthority, this, 100) {}

        assertTrue(
            coordinator.beginCleanup(
                TestStageAuthority,
                targetGeneration = generation,
                cause = TerminalCause(TerminalCleanupReason.UserRequested, null),
                scope = this,
                disconnectCleanupTimeoutMillis = 50,
                onTimeout = timedOut::add,
            ),
        )
        val cleanupDeadline = coordinator.activeDeadline
        assertEquals(ConnectionStage.Disconnecting, coordinator.stage)
        assertEquals(TerminalCleanupReason.UserRequested, coordinator.terminalCleanupReason)

        assertFalse(
            coordinator.beginCleanup(
                TestStageAuthority,
                targetGeneration = generation,
                cause = TerminalCause(TerminalCleanupReason.LinkStatusFailure, null),
                scope = this,
                disconnectCleanupTimeoutMillis = 10,
                onTimeout = timedOut::add,
            ),
        )
        assertSame(cleanupDeadline, coordinator.activeDeadline)
        assertEquals(TerminalCleanupReason.UserRequested, coordinator.terminalCleanupReason)

        assertTrue(coordinator.completeCleanup(TestStageAuthority, generation, PLATFORM_FALLBACK))
        assertFalse(coordinator.completeCleanup(TestStageAuthority, generation, PLATFORM_FALLBACK))
        assertEquals(ConnectionStage.Disconnected, coordinator.stage)
        assertEquals(TerminalCleanupReason.UserRequested, coordinator.terminalCleanupReason)
        assertNull(coordinator.activeDeadline)

        advanceTimeBy(50)
        runCurrent()
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun `deadline callback은 generation stage timeout reason을 함께 전달한다`() = runTest {
        val coordinator = ConnectionCoordinator()
        var observed: ConnectionStageDeadline? = null
        val generation = coordinator.beginConnection(TestStageAuthority, this, 25) { observed = it }

        advanceTimeBy(25)
        runCurrent()

        assertEquals(
            ConnectionStageDeadline(
                generation = generation,
                stage = ConnectionStage.Connecting,
                timeoutMillis = 25,
                timeoutReason = TerminalCleanupReason.LinkTimeout,
            ),
            observed,
        )
        assertNull(coordinator.activeDeadline)
    }

    @Test
    fun `disconnect cleanup callback이 누락돼도 deadline에서 Disconnected로 끝난다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val generation = coordinator.beginConnection(TestStageAuthority, this, 100) {}

        assertTrue(
            coordinator.beginCleanup(
                TestStageAuthority,
                targetGeneration = generation,
                cause = TerminalCause(TerminalCleanupReason.UserRequested, null),
                scope = this,
                disconnectCleanupTimeoutMillis = 25,
                onTimeout = { deadline ->
                    assertEquals(TerminalCleanupReason.DisconnectCleanupTimeout, deadline.timeoutReason)
                    coordinator.completeCleanup(TestStageAuthority, deadline.generation, PLATFORM_FALLBACK)
                },
            ),
        )

        advanceTimeBy(25)
        runCurrent()

        assertEquals(ConnectionStage.Disconnected, coordinator.stage)
        assertEquals(TerminalCleanupReason.UserRequested, coordinator.terminalCleanupReason)
        assertNull(coordinator.activeDeadline)
    }

    @Test
    fun `ClientClosed terminalization은 lifecycle deadline을 취소하고 stale callback을 거부한다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val timedOut = mutableListOf<ConnectionStageDeadline>()
        val generation = coordinator.beginConnection(TestStageAuthority, this, 50, timedOut::add)

        assertTrue(coordinator.completeCleanup(TestStageAuthority, generation, TerminalCause(TerminalCleanupReason.ClientClosed, null)))
        assertFalse(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = this,
                timeoutMillis = 50,
            ),
        )

        advanceTimeBy(50)
        runCurrent()

        assertEquals(ConnectionStage.Disconnected, coordinator.stage)
        assertEquals(TerminalCleanupReason.ClientClosed, coordinator.terminalCleanupReason)
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun `retained reconnect는 fresh generation을 deadline 없이 시작한다`() = runTest {
        val coordinator = ConnectionCoordinator()
        val firstGeneration = coordinator.beginConnection(TestStageAuthority, this, 100) {}
        assertTrue(
            coordinator.completeCleanup(
                TestStageAuthority,
                firstGeneration,
                PLATFORM_FALLBACK,
            ),
        )

        val reconnectGeneration = coordinator.beginPlatformRelink(TestStageAuthority, this)

        assertEquals(firstGeneration + 1, reconnectGeneration)
        assertEquals(ConnectionStage.Connecting, coordinator.stage)
        assertNull(coordinator.activeDeadline)
        assertFalse(
            coordinator.transition(
                TestStageAuthority,
                callbackGeneration = firstGeneration,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = this,
                timeoutMillis = 100,
            ),
        )
    }

    @Test
    fun `Session은 자기 connection coordinator를 독립 소유한다`() {
        val first = Session(PeripheralId("first"))
        val second = Session(PeripheralId("second"))

        assertFalse(first.connectionCoordinator === second.connectionCoordinator)
        assertEquals(
            ConnectionLifecycleSnapshot(0, ConnectionStage.Disconnected, null, null),
            first.connectionCoordinator.snapshot,
        )
    }

    private fun assertFailsWithIllegalState(block: () -> Unit) {
        try {
            block()
            fail("IllegalStateException이 필요하다")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}

private val PLATFORM_FALLBACK = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null)
