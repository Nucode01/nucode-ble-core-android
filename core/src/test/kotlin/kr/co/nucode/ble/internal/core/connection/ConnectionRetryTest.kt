package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.ConnectOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 첫 직접 연결의 빠른 133 재시도(contract/states.md §2) — 순수 `step` 표 시험.
 * 시각은 사건에 싣는 단조 ms 다. 연결 요청은 시각 [T0] 에 나가고 link deadline 은 [LINK_MILLIS] 다.
 */
class ConnectionRetryTest {

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode(code),
        phase = FailurePhase.Link,
        retryability = Retryability.Unknown,
    )

    private val linkFailed = failure("LINK_FAILED")

    /** autoReconnect=false(기본) 연결을 [at] 에 시작하고 같은 시각에 연결 요청을 낸 상태. */
    private fun started(
        at: Long = T0,
        options: ConnectOptions = ConnectOptions(autoConnect = false),
        linkMillis: Long = LINK_MILLIS,
    ): ConnectionCoreState {
        val began = ConnectionCore.step(
            ConnectionCoreState(failureSessionId = "s"),
            ConnectionEvent.BeginConnect(options, linkMillis, rediscover = false, atMillis = at),
        )
        return ConnectionCore.step(began.state, ConnectionEvent.LinkStarted(atMillis = at)).state
    }

    private fun ConnectionCoreState.link(
        at: Long,
        status: Int = 133,
        fact: LinkFact = LinkFact.Down,
        failure: BleFailure? = linkFailed,
    ) = ConnectionCore.step(
        this,
        ConnectionEvent.LinkStateChanged(generation, fact, status, failure, 100, CLEANUP_MILLIS, atMillis = at),
    )

    /** 다시 건 driver.connect 가 [at] 에 성공했다. */
    private fun ConnectionCoreState.relinked(at: Long) =
        ConnectionCore.step(this, ConnectionEvent.LinkStarted(atMillis = at)).state

    private fun ConnectionCoreState.shown() = ConnectionCore.publicState(this)

    private fun ConnectionCoreState.failedCode(): String? =
        ((shown() as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    private fun ConnectionStep.retried(): Boolean = effects.any { it is ConnectionEffect.RetryLink }

    @Test
    fun `빠른 133 은 Connecting 과 기한을 그대로 두고 handle 을 닫은 뒤 같은 generation 으로 다시 건다`() {
        val connecting = started()
        val step = connecting.link(at = T0 + 300)

        assertTrue(step.accepted)
        assertEquals(ConnectionState.Connecting, step.state.shown())
        assertEquals(ConnectionStage.Connecting, step.state.stage)
        assertEquals(connecting.generation, step.state.generation)
        assertEquals(connecting.deadline, step.state.deadline)
        assertNull(step.state.terminalCause)
        assertFalse(step.state.platformHandleOpen)
        assertEquals(LinkAttempt(T0, requestedAtMillis = null, retries = 1), step.state.linkAttempt)
        assertEquals(
            listOf(
                ConnectionEffect.Diagnostic(Severity.Warning, linkFailed),
                ConnectionEffect.ReleaseStaleHandle,
                ConnectionEffect.RetryLink(connecting.generation),
            ),
            step.effects,
        )
    }

    @Test
    fun `다시 건 시도가 붙으면 중간 Disconnected 없이 DiscoveringServices 로 간다`() {
        val retried = started().link(at = T0 + 300).state.relinked(at = T0 + 310)
        assertTrue(retried.platformHandleOpen)
        assertEquals(T0 + 310, retried.linkAttempt?.requestedAtMillis)

        val up = retried.link(at = T0 + 900, status = 0, fact = LinkFact.Up, failure = null)
        assertEquals(ConnectionState.DiscoveringServices, up.state.shown())
        assertTrue(ConnectionEffect.LinkUp(retried.generation) in up.effects)
        assertFalse(up.effects.any { it is ConnectionEffect.StageChanged && it.stage == ConnectionStage.Disconnected })
    }

    @Test
    fun `시도 3번이 모두 빠른 133 이면 세 번째 133 때 LINK_FAILED 로 끝난다`() {
        val first = started().link(at = T0 + 300)
        assertTrue(first.retried())
        val second = first.state.relinked(at = T0 + 310).link(at = T0 + 600)
        assertTrue(second.retried())
        assertEquals(2, second.state.linkAttempt?.retries)

        val third = second.state.relinked(at = T0 + 610).link(at = T0 + 900)
        assertFalse(third.retried())
        assertEquals("LINK_FAILED", third.state.failedCode())
        assertTrue(ConnectionEffect.Diagnostic(Severity.Error, linkFailed) in third.effects)
        assertTrue(ConnectionEffect.ReleaseHandle in third.effects)
    }

    @Test
    fun `요청 뒤 5000ms 안의 133 만 다시 건다 - 경계 5000 은 포함, 5001 은 제외`() {
        assertTrue(started().link(at = T0 + 5_000).retried())

        val late = started().link(at = T0 + 5_001)
        assertFalse(late.retried())
        assertEquals("LINK_FAILED", late.state.failedCode())
    }

    @Test
    fun `남은 link deadline 이 5000ms 미만이면 다시 걸지 않는다 - 경계 5000 은 다시 건다`() {
        // 기한 9000ms 를 T0 에 시작하고 요청은 늦게 나갔다(재발견 등). 133 이 T0+4000 이면 남은 기한은 5000(다시 건다),
        // T0+4001 이면 4999(끝난다).
        val atEdge = started(linkMillis = 9_000).relinked(at = T0 + 4_000).link(at = T0 + 4_000)
        assertTrue(atEdge.retried())

        val short = started(linkMillis = 9_000).relinked(at = T0 + 4_001).link(at = T0 + 4_001)
        assertFalse(short.retried())
        assertEquals("LINK_FAILED", short.state.failedCode())
    }

    @Test
    fun `기본 link deadline 이 5000ms 이하인 연결은 재시도가 없다`() {
        val step = started(linkMillis = 4_000).link(at = T0 + 100)
        assertFalse(step.retried())
        assertEquals("LINK_FAILED", step.state.failedCode())
    }

    @Test
    fun `재시도 중 link deadline 이 다하면 LINK_TIMEOUT 이다`() {
        val retried = started().link(at = T0 + 300).state.relinked(at = T0 + 310)
        val deadline = requireNotNull(retried.deadline)

        val fired = ConnectionCore.step(retried, ConnectionEvent.LifecycleDeadline(deadline, CLEANUP_MILLIS))
        assertEquals(ConnectionState.Disconnecting, fired.state.shown())
        val ended = ConnectionCore.step(
            fired.state,
            ConnectionEvent.CleanupCompleted(retried.generation, TerminalCause(TerminalCleanupReason.PlatformDisconnected, null), 0),
        )
        assertEquals("LINK_TIMEOUT", ended.state.failedCode())
    }

    @Test
    fun `재시도 중 disconnect 는 Requested, close 는 ClientClosed 로 끝난다`() {
        val retried = started().link(at = T0 + 300).state.relinked(at = T0 + 310)

        val disconnecting = ConnectionCore.step(retried, ConnectionEvent.DisconnectRequested(hasHandle = true, CLEANUP_MILLIS))
        val requested = ConnectionCore.step(
            disconnecting.state,
            ConnectionEvent.CleanupCompleted(retried.generation, TerminalCause(TerminalCleanupReason.PlatformDisconnected, null), 0),
        )
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), requested.state.shown())

        val closed = ConnectionCore.step(retried, ConnectionEvent.CloseRequested)
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), closed.state.shown())
    }

    @Test
    fun `재시도 뒤 다시 건 handle 이 133 을 내기 전 사용자 해제가 원인을 고정하면 다시 걸지 않는다`() {
        val retried = started().link(at = T0 + 300).state.relinked(at = T0 + 310)
        val disconnecting = ConnectionCore.step(retried, ConnectionEvent.DisconnectRequested(hasHandle = true, CLEANUP_MILLIS))

        val late = disconnecting.state.link(at = T0 + 400)
        assertFalse(late.retried())
    }

    @Test
    fun `재시도 뒤 어댑터 이탈은 ADAPTER_UNAVAILABLE 로 끝난다`() {
        val retried = started().link(at = T0 + 300).state.relinked(at = T0 + 310)
        val lost = ConnectionCore.step(retried, ConnectionEvent.AdapterLost(AdapterState.PoweredOff))
        assertEquals("ADAPTER_UNAVAILABLE", lost.state.failedCode())
    }

    @Test
    fun `133 이 아닌 status 는 다시 걸지 않는다`() {
        for (status in listOf(8, 19, 62, 147)) {
            val step = started().link(at = T0 + 300, status = status)
            assertFalse("status $status", step.retried())
            assertEquals("status $status", "LINK_FAILED", step.state.failedCode())
        }
    }

    @Test
    fun `링크 사실과 무관하게 Connecting 의 빠른 133 이면 다시 건다`() {
        assertTrue(started().link(at = T0 + 300, fact = LinkFact.Connecting).retried())
    }

    @Test
    fun `autoReconnect 연결은 다시 걸지 않는다`() {
        val auto = started(options = ConnectOptions(autoConnect = true, timeoutMillis = LINK_MILLIS))
        assertNull(auto.linkAttempt)
        assertFalse(auto.link(at = T0 + 300).retried())
    }

    @Test
    fun `연결 요청 시각이 없으면 다시 걸지 않는다`() {
        val began = ConnectionCore.step(
            ConnectionCoreState(failureSessionId = "s"),
            ConnectionEvent.BeginConnect(ConnectOptions(autoConnect = false), LINK_MILLIS, rediscover = true, atMillis = T0),
        ).state
        assertNull(began.linkAttempt?.requestedAtMillis)
        assertFalse(began.link(at = T0 + 300).retried())
    }

    @Test
    fun `DiscoveringServices 이후의 133 은 다시 걸지 않는다`() {
        val discovering = started().link(at = T0 + 300, status = 0, fact = LinkFact.Up, failure = null).state
        assertEquals(ConnectionState.DiscoveringServices, discovering.shown())

        val step = discovering.link(at = T0 + 400)
        assertFalse(step.retried())
        assertEquals("LINK_FAILED", step.state.failedCode())
    }

    @Test
    fun `이전 generation 의 133 은 다시 걸지 않는다`() {
        val connecting = started()
        val stale = ConnectionCore.step(
            connecting,
            ConnectionEvent.LinkStateChanged(
                connecting.generation - 1, LinkFact.Down, 133, linkFailed, 100, CLEANUP_MILLIS, atMillis = T0 + 300,
            ),
        )
        assertFalse(stale.retried())
        assertEquals(ConnectionState.Connecting, stale.state.shown())
    }

    @Test
    fun `다시 건 driver connect 가 던지면 그 시작 실패로 고정하고 게시한다`() {
        val retried = started().link(at = T0 + 300).state
        val startFailure = failure("LINK_START_FAILED")

        val ended = ConnectionCore.step(
            retried,
            ConnectionEvent.RetryStartFailed(retried.generation, startFailure, LinkStartFailureKind.Rejected),
        )
        assertTrue(ended.accepted)
        assertEquals("LINK_START_FAILED", ended.state.failedCode())
        assertEquals(TerminalCleanupReason.LinkStartRejected, ended.state.terminalCause?.reason)
        assertTrue(ConnectionEffect.Announce in ended.effects)
        assertTrue(ConnectionEffect.Diagnostic(Severity.Error, startFailure) in ended.effects)
    }

    @Test
    fun `이미 끝난 generation 의 재시도 시작 실패는 무시한다`() {
        val retried = started().link(at = T0 + 300).state
        val closed = ConnectionCore.step(retried, ConnectionEvent.CloseRequested).state

        val late = ConnectionCore.step(
            closed,
            ConnectionEvent.RetryStartFailed(retried.generation, failure("LINK_START_FAILED"), LinkStartFailureKind.Rejected),
        )
        assertFalse(late.accepted)
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), late.state.shown())
    }

    private companion object {
        const val T0 = 1_000_000L
        const val LINK_MILLIS = 15_000L
        const val CLEANUP_MILLIS = 5_000L
    }
}
