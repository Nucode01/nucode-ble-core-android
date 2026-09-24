package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.session.ConnectionCoordinator
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import kr.co.nucode.ble.internal.session.toDisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCauseLatchTest {

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode(code),
        phase = FailurePhase.Connect,
        retryability = Retryability.TransientMayHelp,
    )

    private val timeoutCause = TerminalCause(TerminalCleanupReason.LinkTimeout, failure("LINK_TIMEOUT"))
    private val platformFallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null)

    @Test
    fun `beginCleanup 원인이 completeCleanup fallback보다 우선한다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}

        assertTrue(c.beginCleanup(g, timeoutCause, this, 100) {})
        assertEquals(TerminalCleanupReason.LinkTimeout, c.terminalCleanupReason)
        assertTrue(c.completeCleanup(g, platformFallback))

        assertEquals(timeoutCause, c.terminalCause)
        assertEquals(TerminalCleanupReason.LinkTimeout, c.terminalCleanupReason)
        assertEquals(ConnectionStage.Disconnected, c.stage)
    }

    @Test
    fun `latchIfAbsent 원인이 이후 beginCleanup 원인보다 우선한다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}

        assertTrue(c.latchIfAbsent(g, timeoutCause))
        assertEquals(ConnectionStage.Connecting, c.stage)
        assertFalse(c.latchIfAbsent(g, platformFallback))
        assertTrue(c.beginCleanup(g, TerminalCause(TerminalCleanupReason.UserRequested, null), this, 100) {})

        assertEquals(timeoutCause, c.terminalCause)
        assertEquals(TerminalCleanupReason.LinkTimeout, c.terminalCleanupReason)
        c.completeCleanup(g, platformFallback)
    }

    @Test
    fun `latch된 원인이 없으면 completeCleanup fallback을 기록한다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}

        assertTrue(c.completeCleanup(g, platformFallback))
        assertEquals(platformFallback, c.terminalCause)
        assertEquals(TerminalCleanupReason.PlatformDisconnected, c.terminalCleanupReason)
    }

    @Test
    fun `새 generation은 latch를 초기화한다`() = runTest {
        val c = ConnectionCoordinator()
        val g1 = c.beginConnection(this, 100) {}
        c.completeCleanup(g1, timeoutCause)

        val g2 = c.beginReconnect(this, 100) {}
        assertNull(c.terminalCause)
        assertTrue(c.completeCleanup(g2, platformFallback))
        assertEquals(platformFallback, c.terminalCause)
    }

    @Test
    fun `stale generation 호출은 거부되고 latch를 바꾸지 않는다`() = runTest {
        val c = ConnectionCoordinator()
        val g1 = c.beginConnection(this, 100) {}
        c.completeCleanup(g1, timeoutCause)
        val g2 = c.beginConnection(this, 100) {}

        assertFalse(c.latchIfAbsent(g1, timeoutCause))
        assertFalse(c.beginCleanup(g1, timeoutCause, this, 100) {})
        assertFalse(c.completeCleanup(g1, timeoutCause))
        assertNull(c.terminalCause)
        assertEquals(ConnectionStage.Connecting, c.stage)
        c.completeCleanup(g2, platformFallback)
    }

    @Test
    fun `사용자 beginCleanup 뒤 close 대체는 ClientClosed로 끝난다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}
        c.beginCleanup(g, TerminalCause(TerminalCleanupReason.UserRequested, null), this, 100) {}

        assertTrue(c.supersedeForClose(g))
        assertTrue(c.completeCleanup(g, platformFallback))
        assertEquals(TerminalCause(TerminalCleanupReason.ClientClosed, null), c.terminalCause)
    }

    @Test
    fun `이미 Disconnected면 close 대체는 원래 실패 원인을 유지한다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}
        c.beginCleanup(g, timeoutCause, this, 100) {}
        c.completeCleanup(g, platformFallback)

        assertFalse(c.supersedeForClose(g))
        assertEquals(timeoutCause, c.terminalCause)
    }

    @Test
    fun `beginCleanup UserRequested 뒤 다른 원인은 first wins로 무시된다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}
        val user = TerminalCause(TerminalCleanupReason.UserRequested, null)
        assertTrue(c.beginCleanup(g, user, this, 100) {})

        assertFalse(c.beginCleanup(g, timeoutCause, this, 100) {})
        assertFalse(c.latchIfAbsent(g, timeoutCause))
        assertEquals(user, c.terminalCause)
        c.completeCleanup(g, platformFallback)
    }

    @Test
    fun `재연결 대기 중인 Disconnected generation은 supersedeRetryPending으로 ClientClosed를 다시 게시한다`() = runTest {
        val published = mutableListOf<ConnectionStage>()
        val c = ConnectionCoordinator { published += it }
        val g = c.beginConnection(this, 100) {}
        c.completeCleanup(g, TerminalCause(TerminalCleanupReason.PlatformDisconnected, failure("LINK_FAILED")))
        published.clear()

        assertTrue(c.supersedeRetryPending(g, TerminalCause(TerminalCleanupReason.ClientClosed, null)))
        assertEquals(TerminalCause(TerminalCleanupReason.ClientClosed, null), c.terminalCause)
        assertEquals(listOf(ConnectionStage.Disconnected), published)
    }

    @Test
    fun `연결 시도 전 generation은 supersedeRetryPending하지 않는다`() {
        val c = ConnectionCoordinator()
        assertFalse(c.supersedeRetryPending(0L, TerminalCause(TerminalCleanupReason.ClientClosed, null)))
        assertNull(c.terminalCause)
    }

    @Test
    fun `supersedeRetryPending은 adapter 원인을 받고 소유자가 아닌 원인은 거부한다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}
        c.completeCleanup(g, TerminalCause(TerminalCleanupReason.PlatformDisconnected, failure("LINK_FAILED")))
        val adapterOff = TerminalCause(TerminalCleanupReason.AdapterUnavailable, failure("ADAPTER_UNAVAILABLE"))

        assertTrue(runCatching { c.supersedeRetryPending(g, timeoutCause) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(c.supersedeRetryPending(g, adapterOff))
        assertEquals(adapterOff, c.terminalCause)
    }

    @Test
    fun `latchExhaustion은 reason을 유지하고 failure만 바꾼다`() = runTest {
        val c = ConnectionCoordinator()
        val g = c.beginConnection(this, 100) {}
        c.beginCleanup(g, timeoutCause, this, 100) {}
        val exhausted = failure("RECONNECT_EXHAUSTED")

        assertTrue(c.latchExhaustion(g, platformFallback, exhausted))
        assertEquals(TerminalCause(TerminalCleanupReason.LinkTimeout, exhausted), c.terminalCause)
        c.completeCleanup(g, platformFallback)
        assertFalse(c.latchExhaustion(g, platformFallback, exhausted))
    }

    @Test
    fun `latchExhaustion은 사용자 해제와 client close를 거부한다`() = runTest {
        val exhausted = failure("RECONNECT_EXHAUSTED")
        for (reason in listOf(TerminalCleanupReason.UserRequested, TerminalCleanupReason.ClientClosed)) {
            val latched = ConnectionCoordinator()
            val g1 = latched.beginConnection(this, 100) {}
            latched.beginCleanup(g1, TerminalCause(reason, null), this, 100) {}
            assertFalse(latched.latchExhaustion(g1, platformFallback, exhausted))
            assertEquals(TerminalCause(reason, null), latched.terminalCause)
            latched.completeCleanup(g1, platformFallback)

            val fromFallback = ConnectionCoordinator()
            val g2 = fromFallback.beginConnection(this, 100) {}
            assertFalse(fromFallback.latchExhaustion(g2, TerminalCause(reason, null), exhausted))
            assertNull(fromFallback.terminalCause)
            fromFallback.completeCleanup(g2, platformFallback)
        }
    }

    @Test
    fun `종결 원인에서 DisconnectReason을 파생한다`() {
        val unattributed = failure("LINK_FAILED")

        assertEquals(
            DisconnectReason.Failed(timeoutCause.failure!!),
            timeoutCause.toDisconnectReason(generationStarted = true, unattributedFailure = { unattributed }),
        )
        assertEquals(
            DisconnectReason.Requested,
            TerminalCause(TerminalCleanupReason.UserRequested, null).toDisconnectReason(true) { unattributed },
        )
        assertEquals(
            DisconnectReason.ClientClosed,
            TerminalCause(TerminalCleanupReason.ClientClosed, null).toDisconnectReason(true) { unattributed },
        )
        assertEquals(DisconnectReason.Failed(unattributed), platformFallback.toDisconnectReason(true) { unattributed })
        assertEquals(DisconnectReason.Failed(unattributed), null.toDisconnectReason(true) { unattributed })
        assertEquals(DisconnectReason.Initial, null.toDisconnectReason(false) { unattributed })
    }
}
