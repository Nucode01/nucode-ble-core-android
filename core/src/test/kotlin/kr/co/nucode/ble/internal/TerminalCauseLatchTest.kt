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
import kr.co.nucode.ble.internal.core.connection.ConnectionStage
import kr.co.nucode.ble.internal.core.connection.TerminalCause
import kr.co.nucode.ble.internal.core.connection.TerminalCleanupReason
import kr.co.nucode.ble.internal.core.connection.toDisconnectReason
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
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}

        assertTrue(c.beginCleanup(TestStageAuthority, g, timeoutCause, this, 100) {})
        assertEquals(TerminalCleanupReason.LinkTimeout, c.terminalCleanupReason)
        assertTrue(c.completeCleanup(TestStageAuthority, g, platformFallback))

        assertEquals(timeoutCause, c.terminalCause)
        assertEquals(TerminalCleanupReason.LinkTimeout, c.terminalCleanupReason)
        assertEquals(ConnectionStage.Disconnected, c.stage)
    }

    @Test
    fun `latchIfAbsent 원인이 이후 beginCleanup 원인보다 우선한다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}

        assertTrue(c.latchIfAbsent(TestStageAuthority, g, timeoutCause))
        assertEquals(ConnectionStage.Connecting, c.stage)
        assertFalse(c.latchIfAbsent(TestStageAuthority, g, platformFallback))
        assertTrue(c.beginCleanup(TestStageAuthority, g, TerminalCause(TerminalCleanupReason.UserRequested, null), this, 100) {})

        assertEquals(timeoutCause, c.terminalCause)
        assertEquals(TerminalCleanupReason.LinkTimeout, c.terminalCleanupReason)
        c.completeCleanup(TestStageAuthority, g, platformFallback)
    }

    @Test
    fun `latch된 원인이 없으면 completeCleanup fallback을 기록한다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}

        assertTrue(c.completeCleanup(TestStageAuthority, g, platformFallback))
        assertEquals(platformFallback, c.terminalCause)
        assertEquals(TerminalCleanupReason.PlatformDisconnected, c.terminalCleanupReason)
    }

    @Test
    fun `새 generation은 latch를 초기화한다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g1 = c.beginConnection(TestStageAuthority, this, 100) {}
        c.completeCleanup(TestStageAuthority, g1, timeoutCause)

        val g2 = c.beginPlatformRelink(TestStageAuthority, this)
        assertNull(c.terminalCause)
        assertTrue(c.completeCleanup(TestStageAuthority, g2, platformFallback))
        assertEquals(platformFallback, c.terminalCause)
    }

    @Test
    fun `stale generation 호출은 거부되고 latch를 바꾸지 않는다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g1 = c.beginConnection(TestStageAuthority, this, 100) {}
        c.completeCleanup(TestStageAuthority, g1, timeoutCause)
        val g2 = c.beginConnection(TestStageAuthority, this, 100) {}

        assertFalse(c.latchIfAbsent(TestStageAuthority, g1, timeoutCause))
        assertFalse(c.beginCleanup(TestStageAuthority, g1, timeoutCause, this, 100) {})
        assertFalse(c.completeCleanup(TestStageAuthority, g1, timeoutCause))
        assertNull(c.terminalCause)
        assertEquals(ConnectionStage.Connecting, c.stage)
        c.completeCleanup(TestStageAuthority, g2, platformFallback)
    }

    @Test
    fun `사용자 beginCleanup 뒤 close 대체는 ClientClosed로 끝난다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}
        c.beginCleanup(TestStageAuthority, g, TerminalCause(TerminalCleanupReason.UserRequested, null), this, 100) {}

        assertTrue(c.supersedeForClose(TestStageAuthority, g))
        assertTrue(c.completeCleanup(TestStageAuthority, g, platformFallback))
        assertEquals(TerminalCause(TerminalCleanupReason.ClientClosed, null), c.terminalCause)
    }

    @Test
    fun `이미 Disconnected면 close 대체는 원래 실패 원인을 유지한다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}
        c.beginCleanup(TestStageAuthority, g, timeoutCause, this, 100) {}
        c.completeCleanup(TestStageAuthority, g, platformFallback)

        assertFalse(c.supersedeForClose(TestStageAuthority, g))
        assertEquals(timeoutCause, c.terminalCause)
    }

    @Test
    fun `beginCleanup UserRequested 뒤 다른 원인은 first wins로 무시된다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}
        val user = TerminalCause(TerminalCleanupReason.UserRequested, null)
        assertTrue(c.beginCleanup(TestStageAuthority, g, user, this, 100) {})

        assertFalse(c.beginCleanup(TestStageAuthority, g, timeoutCause, this, 100) {})
        assertFalse(c.latchIfAbsent(TestStageAuthority, g, timeoutCause))
        assertEquals(user, c.terminalCause)
        c.completeCleanup(TestStageAuthority, g, platformFallback)
    }

    @Test
    fun `재연결 대기 중인 Disconnected generation은 supersedePlatformWait으로 ClientClosed를 다시 게시한다`() = runTest {
        val published = mutableListOf<ConnectionStage>()
        val c = ConnectionCoordinator(TestClock) { published += it }
        val g = c.beginConnection(TestStageAuthority, this, 100) {}
        c.completeCleanup(TestStageAuthority, g, TerminalCause(TerminalCleanupReason.PlatformDisconnected, failure("LINK_FAILED")))
        published.clear()

        assertTrue(c.supersedePlatformWait(TestStageAuthority, g, TerminalCause(TerminalCleanupReason.ClientClosed, null)))
        assertEquals(TerminalCause(TerminalCleanupReason.ClientClosed, null), c.terminalCause)
        assertEquals(listOf(ConnectionStage.Disconnected), published)
    }

    @Test
    fun `연결 시도 전 generation은 supersedePlatformWait하지 않는다`() {
        val c = ConnectionCoordinator(TestClock)
        assertFalse(c.supersedePlatformWait(TestStageAuthority, 0L, TerminalCause(TerminalCleanupReason.ClientClosed, null)))
        assertNull(c.terminalCause)
    }

    @Test
    fun `supersedePlatformWait은 adapter 원인을 받고 소유자가 아닌 원인은 거부한다`() = runTest {
        val c = ConnectionCoordinator(TestClock)
        val g = c.beginConnection(TestStageAuthority, this, 100) {}
        c.completeCleanup(TestStageAuthority, g, TerminalCause(TerminalCleanupReason.PlatformDisconnected, failure("LINK_FAILED")))
        val adapterOff = TerminalCause(TerminalCleanupReason.AdapterUnavailable, failure("ADAPTER_UNAVAILABLE"))

        assertTrue(runCatching { c.supersedePlatformWait(TestStageAuthority, g, timeoutCause) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(c.supersedePlatformWait(TestStageAuthority, g, adapterOff))
        assertEquals(adapterOff, c.terminalCause)
    }

    @Test
    fun `종결 원인에서 DisconnectReason을 파생한다`() {
        val unattributed = failure("LINK_FAILED")

        assertEquals(
            DisconnectReason.Failed(timeoutCause.failure!!),
            timeoutCause.toDisconnectReason(generationStarted = true, unattributedFailure = unattributed),
        )
        assertEquals(
            DisconnectReason.Requested,
            TerminalCause(TerminalCleanupReason.UserRequested, null).toDisconnectReason(true, unattributed),
        )
        assertEquals(
            DisconnectReason.ClientClosed,
            TerminalCause(TerminalCleanupReason.ClientClosed, null).toDisconnectReason(true, unattributed),
        )
        assertEquals(DisconnectReason.Failed(unattributed), platformFallback.toDisconnectReason(true, unattributed))
        assertEquals(DisconnectReason.Failed(unattributed), null.toDisconnectReason(true, unattributed))
        assertEquals(DisconnectReason.Initial, null.toDisconnectReason(false, unattributed))
    }
}
