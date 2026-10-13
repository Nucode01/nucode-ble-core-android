package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect.ArmDeadline
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect.CancelDeadline
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect.DeadlineFired
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect.StageChanged
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent.DeadlineExpired
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent.LifecycleDeadline
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent.Direct
import kr.co.nucode.ble.internal.model.ConnectOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 연결 Core 의 순수 `step` 표 시험. 코루틴·가짜 driver 없이 값만 넣고 값만 본다. */
class ConnectionCoreTest {

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode(code),
        phase = FailurePhase.Connect,
        retryability = Retryability.TransientMayHelp,
    )

    private val linkTimeout = TerminalCause(TerminalCleanupReason.LinkTimeout, failure("LINK_TIMEOUT"))
    private val platformFallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null)

    private fun ConnectionCoreState.apply(event: ConnectionEvent): ConnectionCoreState {
        val step = ConnectionCore.step(this, event)
        assertTrue("event rejected: $event", step.accepted)
        return step.state
    }

    private fun ConnectionCoreState.connecting(linkMillis: Long? = 100) = apply(Direct.BeginConnection(linkMillis))

    private fun ConnectionCoreState.moveTo(next: ConnectionStage, millis: Long?) =
        apply(Direct.Transition(generation, stage, next, millis))

    private fun ConnectionCoreState.ready() = connecting()
        .moveTo(ConnectionStage.DiscoveringServices, 100)
        .moveTo(ConnectionStage.Configuring, 100)
        .moveTo(ConnectionStage.Ready, null)

    @Test
    fun `P01 첫 연결은 Connecting 게시와 link deadline 을 할 일로 낸다`() {
        val step = ConnectionCore.step(ConnectionCoreState(), Direct.BeginConnection(100))

        assertTrue(step.accepted)
        assertEquals(1L, step.state.generation)
        assertEquals(ConnectionStage.Connecting, step.state.stage)
        val deadline = requireNotNull(step.state.deadline)
        assertEquals(TerminalCleanupReason.LinkTimeout, deadline.timeoutReason)
        assertEquals(
            listOf(CancelDeadline, StageChanged(ConnectionStage.Connecting), ArmDeadline(deadline)),
            step.effects,
        )
    }

    @Test
    fun `P02 deadline 없는 Connecting 은 기한 할 일이 없다`() {
        val step = ConnectionCore.step(ConnectionCoreState(), Direct.BeginConnection(null))

        assertNull(step.state.deadline)
        assertEquals(listOf(CancelDeadline, StageChanged(ConnectionStage.Connecting)), step.effects)
    }

    @Test
    fun `P03 stage 후속 표를 벗어난 전이는 IllegalState 다`() {
        val s = ConnectionCoreState().connecting()

        assertThrows(IllegalStateException::class.java) {
            ConnectionCore.step(s, Direct.Transition(s.generation, s.stage, ConnectionStage.Ready, null))
        }
        assertThrows(IllegalStateException::class.java) {
            ConnectionCore.step(s, Direct.Transition(s.generation, s.stage, ConnectionStage.Disconnecting, 10))
        }
        assertThrows(IllegalStateException::class.java) { ConnectionCore.step(s, Direct.BeginConnection(100)) }
    }

    @Test
    fun `P04 stage 별 timeoutMillis 규칙을 어기면 IllegalArgument 다`() {
        val s = ConnectionCoreState().connecting()

        assertThrows(IllegalArgumentException::class.java) {
            ConnectionCore.step(s, Direct.Transition(s.generation, s.stage, ConnectionStage.DiscoveringServices, null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionCore.step(s, Direct.Transition(s.generation, s.stage, ConnectionStage.DiscoveringServices, 0))
        }
        val configuring = s.moveTo(ConnectionStage.DiscoveringServices, 100).moveTo(ConnectionStage.Configuring, 100)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionCore.step(configuring, Direct.Transition(1, configuring.stage, ConnectionStage.Ready, 100))
        }
    }

    @Test
    fun `P05 generation 이 소진되면 새 연결을 시작하지 못한다`() {
        val exhausted = ConnectionCoreState(generation = Long.MAX_VALUE)

        assertThrows(IllegalStateException::class.java) { ConnectionCore.step(exhausted, Direct.BeginConnection(100)) }
    }

    @Test
    fun `P06 cleanup 은 원인을 Disconnecting 게시 전에 고정하고 first wins 를 지킨다`() {
        val s = ConnectionCoreState().ready()

        val step = ConnectionCore.step(s, Direct.BeginCleanup(s.generation, linkTimeout, 50))

        assertTrue(step.accepted)
        assertEquals(ConnectionStage.Disconnecting, step.state.stage)
        assertEquals(linkTimeout, step.state.terminalCause)
        assertEquals(listOf(CancelDeadline, StageChanged(ConnectionStage.Disconnecting)), step.effects.take(2))
        // 이미 Disconnecting: 중복 cleanup 은 거부(상태·할 일 없음).
        val again = ConnectionCore.step(step.state, Direct.BeginCleanup(s.generation, platformFallback, 50))
        assertFalse(again.accepted)
        assertSame(step.state, again.state)
        assertTrue(again.effects.isEmpty())
        // 완료 fallback 은 고정된 원인을 바꾸지 않는다.
        val done = ConnectionCore.step(step.state, Direct.CompleteCleanup(s.generation, platformFallback))
        assertEquals(linkTimeout, done.state.terminalCause)
        assertEquals(ConnectionStage.Disconnected, done.state.stage)
        assertNull(done.state.deadline)
        assertEquals(listOf(CancelDeadline, StageChanged(ConnectionStage.Disconnected)), done.effects)
    }

    @Test
    fun `P07 latchIfAbsent 원인이 이후 cleanup 원인보다 우선한다`() {
        val s = ConnectionCoreState().connecting()

        val latched = s.apply(Direct.LatchIfAbsent(s.generation, linkTimeout))
        assertFalse(ConnectionCore.step(latched, Direct.LatchIfAbsent(s.generation, platformFallback)).accepted)
        val cleaned = latched.apply(Direct.BeginCleanup(s.generation, platformFallback, 50))

        assertEquals(linkTimeout, cleaned.terminalCause)
    }

    @Test
    fun `P08 supersedeForClose 는 종결되지 않은 generation 의 원인만 ClientClosed 로 바꾼다`() {
        val active = ConnectionCoreState().connecting().apply(Direct.LatchIfAbsent(1, linkTimeout))

        val closed = active.apply(Direct.SupersedeForClose(1))
        assertEquals(TerminalCleanupReason.ClientClosed, closed.terminalCause?.reason)
        assertFalse(ConnectionCore.step(active, Direct.SupersedeForClose(2)).accepted)

        val ended = active.apply(Direct.CompleteCleanup(1, platformFallback))
        assertFalse(ConnectionCore.step(ended, Direct.SupersedeForClose(1)).accepted)
        assertEquals(linkTimeout, ended.terminalCause)
    }

    @Test
    fun `P09 재연결 대기 대체는 소유자 셋과 대기 중인 generation 에만 허용된다`() {
        val ended = ConnectionCoreState().connecting()
            .apply(Direct.BeginCleanup(1, platformFallback, 50))
            .apply(Direct.CompleteCleanup(1, platformFallback))
        val owners = listOf(
            TerminalCleanupReason.UserRequested,
            TerminalCleanupReason.ClientClosed,
            TerminalCleanupReason.AdapterUnavailable,
            TerminalCleanupReason.PermissionDenied,
            TerminalCleanupReason.OperationInvalidated,
        )

        owners.forEach { reason ->
            val cause = TerminalCause(reason, null)
            val step = ConnectionCore.step(ended, Direct.SupersedePlatformWait(1, cause))
            assertTrue(step.accepted)
            assertEquals(cause, step.state.terminalCause)
            assertEquals(listOf<ConnectionEffect>(StageChanged(ConnectionStage.Disconnected)), step.effects)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionCore.step(ended, Direct.SupersedePlatformWait(1, linkTimeout))
        }
        val userCause = TerminalCause(TerminalCleanupReason.UserRequested, null)
        assertFalse(ConnectionCore.step(ended, Direct.SupersedePlatformWait(2, userCause)).accepted)
        assertFalse(ConnectionCore.step(ConnectionCoreState(), Direct.SupersedePlatformWait(0, userCause)).accepted)
        assertFalse(ConnectionCore.step(ConnectionCoreState().connecting(), Direct.SupersedePlatformWait(1, userCause)).accepted)
    }

    @Test
    fun `P10 closeIdle 은 연결 시도 전 세션의 닫힘을 첫 원인으로만 고정한다`() {
        val idle = ConnectionCoreState()

        val step = ConnectionCore.step(idle, Direct.CloseIdle)
        assertTrue(step.accepted)
        assertEquals(TerminalCleanupReason.ClientClosed, step.state.terminalCause?.reason)
        assertEquals(listOf<ConnectionEffect>(StageChanged(ConnectionStage.Disconnected)), step.effects)
        assertFalse(ConnectionCore.step(step.state, Direct.CloseIdle).accepted)
        assertFalse(ConnectionCore.step(idle.connecting(), Direct.CloseIdle).accepted)
    }

    @Test
    fun `P11 기한 만료는 현재 기한을 걷고 DeadlineFired 를 낸다`() {
        val s = ConnectionCoreState().connecting()
        val deadline = requireNotNull(s.deadline)

        val step = ConnectionCore.step(s, DeadlineExpired(deadline.id))

        assertTrue(step.accepted)
        assertNull(step.state.deadline)
        assertEquals(listOf<ConnectionEffect>(DeadlineFired(deadline)), step.effects)
        assertEquals(ConnectionStage.Connecting, step.state.stage)
    }

    @Test
    fun `P12 단계가 옮겨지면 기한 id 가 새로 매겨진다`() {
        val s = ConnectionCoreState().connecting()
        val next = s.moveTo(ConnectionStage.DiscoveringServices, 100)

        assertEquals(1L, s.deadline?.id)
        assertEquals(2L, next.deadline?.id)
        assertEquals(2L, next.deadlineSeq)
    }

    @Test
    fun `C06_이전 기한 id 의 DeadlineExpired 와 이전 generation 의 콜백은 상태도 할 일도 없다`() {
        val connecting = ConnectionCoreState().connecting()
        val staleId = requireNotNull(connecting.deadline).id
        val discovering = connecting.moveTo(ConnectionStage.DiscoveringServices, 100)

        val staleDeadline = ConnectionCore.step(discovering, DeadlineExpired(staleId))
        assertFalse(staleDeadline.accepted)
        assertSame(discovering, staleDeadline.state)
        assertTrue(staleDeadline.effects.isEmpty())

        val noDeadline = ConnectionCore.step(ConnectionCoreState().ready(), DeadlineExpired(staleId))
        assertFalse(noDeadline.accepted)
        assertTrue(noDeadline.effects.isEmpty())

        val staleGeneration = ConnectionCore.step(
            discovering,
            Direct.Transition(discovering.generation - 1, discovering.stage, ConnectionStage.Configuring, 100),
        )
        assertFalse(staleGeneration.accepted)
        assertSame(discovering, staleGeneration.state)
        assertTrue(staleGeneration.effects.isEmpty())

        val wrongStage = ConnectionCore.step(
            discovering,
            Direct.Transition(discovering.generation, ConnectionStage.Connecting, ConnectionStage.Configuring, 100),
        )
        assertFalse(wrongStage.accepted)
        assertTrue(wrongStage.effects.isEmpty())
    }

    @Test
    fun `C21_GATT 작업 허용 질의는 Ready 에서만 참이다`() {
        val connecting = ConnectionCoreState().connecting()
        val discovering = connecting.moveTo(ConnectionStage.DiscoveringServices, 100)
        val configuring = discovering.moveTo(ConnectionStage.Configuring, 100)
        val ready = configuring.moveTo(ConnectionStage.Ready, null)
        val disconnecting = ready.apply(Direct.BeginCleanup(ready.generation, platformFallback, 100))
        val disconnected = disconnecting.apply(Direct.CompleteCleanup(disconnecting.generation, platformFallback))

        val admits = listOf(ConnectionCoreState(), connecting, discovering, configuring, ready, disconnecting, disconnected)
            .associate { it.stage to ConnectionCore.admitsPublicGattOperation(it) }

        assertEquals(
            mapOf(
                ConnectionStage.Disconnected to false,
                ConnectionStage.Connecting to false,
                ConnectionStage.DiscoveringServices to false,
                ConnectionStage.Configuring to false,
                ConnectionStage.Ready to true,
                ConnectionStage.Disconnecting to false,
            ),
            admits,
        )
    }

    @Test
    fun `publicState 는 단계마다 공개 상태를 파생하고 Recovering 은 대기 중인 Disconnected 일 때만이다`() {
        val idle = ConnectionCoreState()
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Initial), ConnectionCore.publicState(idle))

        val connecting = idle.connecting()
        assertEquals(ConnectionState.Connecting, ConnectionCore.publicState(connecting))
        val discovering = connecting.moveTo(ConnectionStage.DiscoveringServices, 100)
        assertEquals(ConnectionState.DiscoveringServices, ConnectionCore.publicState(discovering))
        val configuring = discovering.moveTo(ConnectionStage.Configuring, 100)
        assertEquals(ConnectionState.Configuring, ConnectionCore.publicState(configuring))
        val ready = configuring.moveTo(ConnectionStage.Ready, null)
        assertEquals(ConnectionState.Ready, ConnectionCore.publicState(ready))

        // 대기 표시는 단계가 Disconnected 가 아니면 공개 상태에 영향이 없다.
        val waitingReady = ready.apply(Direct.SetPlatformWait(true))
        assertEquals(ConnectionState.Ready, ConnectionCore.publicState(waitingReady))
        val disconnecting = waitingReady.apply(Direct.BeginCleanup(ready.generation, linkTimeout, 100))
        assertEquals(ConnectionState.Disconnecting, ConnectionCore.publicState(disconnecting))

        // 대기 표시가 서고 Disconnected 면 Recovering(마지막 실패).
        val recovering = disconnecting.apply(Direct.CompleteCleanup(ready.generation, platformFallback))
        assertEquals(ConnectionState.Recovering(linkTimeout.failure!!), ConnectionCore.publicState(recovering))
        assertTrue(ConnectionCore.awaitingPlatformRelink(recovering))

        // 대기 표시가 없으면 같은 원인이 Disconnected(Failed) 다.
        val notWaiting = recovering.apply(Direct.SetPlatformWait(false))
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.Failed(linkTimeout.failure!!)),
            ConnectionCore.publicState(notWaiting),
        )
        assertFalse(ConnectionCore.awaitingPlatformRelink(notWaiting))
    }

    @Test
    fun `publicState 는 원인 없이 끝난 시작된 generation 을 세션 문맥을 담은 LINK_FAILED 로 파생한다`() {
        val state = ConnectionCoreState(failureSessionId = "s-1")
            .connecting()
            .apply(Direct.BeginCleanup(1L, platformFallback, 100))
            .apply(Direct.CompleteCleanup(1L, platformFallback))

        val shown = ConnectionCore.publicState(state) as ConnectionState.Disconnected
        val failure = (shown.reason as BleDisconnectReason.Failed).failure
        assertEquals("LINK_FAILED", failure.code.value)
        assertEquals("s-1", failure.context.sessionId)
        assertEquals(1L, failure.context.connectionGeneration)
        assertEquals(DisconnectReason.Failed(failure), ConnectionCore.disconnectReason(state))
    }

    @Test
    fun `연결 칸 쓰기 사건은 단계와 원인을 바꾸지 않고 할 일도 없다`() {
        val s = ConnectionCoreState().connecting()

        val written = listOf(
            Direct.SetSuppressReconnect(true),
            Direct.SetAutoConnect(true),
            Direct.SetRelinkAfterAdapter(true),
            Direct.SetPlatformHandleOpen(true),
            Direct.SetRequestedMtu(185),
            Direct.SetPlatformWait(true),
        ).fold(s) { acc, event ->
            val step = ConnectionCore.step(acc, event)
            assertTrue(step.effects.isEmpty())
            step.state
        }

        assertTrue(written.suppressReconnect && written.autoConnect && written.relinkAfterAdapter)
        assertTrue(written.platformHandleOpen && written.platformWait)
        assertEquals(185, written.requestedMtu)
        assertEquals(s.copy(
            suppressReconnect = true, autoConnect = true, relinkAfterAdapter = true,
            platformHandleOpen = true, platformWait = true, requestedMtu = 185,
        ), written)
    }

    // ── 정리·종료 정책 (T4) ─────────────────────────────────────────────────

    private val cleanupMillis = 5_000L

    private fun ConnectionCoreState.linkDown(status: Int = 133) =
        apply(ConnectionEvent.CleanupCompleted(generation, platformFallback, status))

    private fun ConnectionCoreState.shown() = ConnectionCore.publicState(this)

    private fun ConnectionCoreState.failedCode(): String? =
        ((shown() as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    private fun ConnectionCoreState.autoReady() = ready()
        .apply(Direct.SetAutoConnect(true))
        .apply(Direct.SetPlatformHandleOpen(true))

    private fun List<ConnectionEffect>.diagnostics() = filterIsInstance<ConnectionEffect.Diagnostic>()

    /** DeadlineExpired 가 전한 기한을 정책 사건으로 다시 넣는다(실행기가 하는 일). */
    private fun ConnectionCoreState.expire(): ConnectionStep {
        val expired = ConnectionCore.step(this, DeadlineExpired(requireNotNull(deadline).id))
        val fired = expired.effects.filterIsInstance<DeadlineFired>().single().deadline
        return ConnectionCore.step(expired.state, LifecycleDeadline(fired, cleanupMillis))
    }

    @Test
    fun `C05_활성 단계의 기한 만료는 Disconnecting 으로 정리하고 링크 해제 뒤 단계별 시간 초과 코드로 끝난다`() {
        val start = ConnectionCoreState(failureSessionId = "s-1")
        val connecting = start.connecting()
        val discovering = connecting.moveTo(ConnectionStage.DiscoveringServices, 100)
        val configuring = discovering.moveTo(ConnectionStage.Configuring, 100)
        val rows = listOf(
            connecting to "LINK_TIMEOUT",
            discovering to "SERVICE_DISCOVERY_TIMEOUT",
            configuring to "CONFIGURATION_TIMEOUT",
        )
        rows.forEach { (before, code) ->
            val step = before.expire()

            assertTrue(step.accepted)
            assertEquals(ConnectionStage.Disconnecting, step.state.stage)
            assertEquals(ConnectionState.Disconnecting, step.state.shown())
            assertTrue(step.state.suppressReconnect)
            assertEquals(code, step.state.terminalCause?.failure?.code?.value)
            assertEquals("s-1", step.state.terminalCause?.failure?.context?.sessionId)
            val diagnostic = step.effects.diagnostics().single()
            assertEquals(Severity.Warning, diagnostic.severity)
            assertEquals(code, diagnostic.failure.code.value)
            val kinds = step.effects.map { it::class }
            assertTrue(kinds.indexOf(StageChanged::class) < kinds.indexOf(ConnectionEffect.DisconnectLink::class))
            assertTrue(ConnectionEffect.InvalidateOperations in step.effects)
            assertTrue(step.effects.any { it is ArmDeadline })

            val done = step.state.linkDown()
            assertEquals(code, done.failedCode())
            assertEquals(ConnectionStage.Disconnected, done.stage)
        }
    }

    @Test
    fun `C07_사용자 해제는 Requested 로 끝나고 늦은 실패 status 는 원인을 바꾸지 않는다`() {
        val ready = ConnectionCoreState().ready()

        val step = ConnectionCore.step(ready, ConnectionEvent.DisconnectRequested(hasHandle = true, cleanupMillis))

        assertTrue(step.accepted)
        assertEquals(ConnectionState.Disconnecting, step.state.shown())
        assertTrue(step.state.suppressReconnect)
        assertEquals(TerminalCleanupReason.UserRequested, step.state.terminalCause?.reason)
        assertTrue(step.effects.none { it is ConnectionEffect.EndRediscovery })
        val late = step.state.apply(
            ConnectionEvent.CleanupCompleted(
                ready.generation,
                TerminalCause(TerminalCleanupReason.PlatformDisconnected, failure("LINK_FAILED")),
                status = 133,
            ),
        )
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), late.shown())
        assertEquals(TerminalCleanupReason.UserRequested, late.terminalCause?.reason)
    }

    @Test
    fun `C07_handle 이 없는 사용자 해제는 진행 중인 재발견을 끝내는 할 일을 낸다`() {
        val connecting = ConnectionCoreState().connecting()

        val step = ConnectionCore.step(connecting, ConnectionEvent.DisconnectRequested(hasHandle = false, cleanupMillis))

        assertEquals(ConnectionStage.Disconnecting, step.state.stage)
        assertEquals(ConnectionEffect.EndRediscovery, step.effects.last())
        // 이미 정리 중이어도 재발견 종료는 낸다.
        assertEquals(
            ConnectionEffect.EndRediscovery,
            ConnectionCore.step(step.state, ConnectionEvent.DisconnectRequested(false, cleanupMillis)).effects.last(),
        )
    }

    @Test
    fun `C07_disconnect 시작 실패는 진단을 남기고 그 원인으로 정리를 마친다`() {
        val ready = ConnectionCoreState().ready()
        val requested = ConnectionCore.step(ready, ConnectionEvent.DisconnectRequested(true, cleanupMillis))
        val start = requested.effects.filterIsInstance<ConnectionEffect.DisconnectLink>().single().onStartFailure
        requireNotNull(start)

        val step = ConnectionCore.step(requested.state, start)

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), step.state.shown())
        val diagnostics = step.effects.diagnostics()
        assertEquals("DISCONNECT_START_FAILED", diagnostics.first().failure.code.value)
        assertEquals(Severity.Warning, diagnostics.first().severity)
    }

    @Test
    fun `C08_Disconnecting 기한 만료는 고정된 원인으로 끝나고 정리 기한 진단을 낸다`() {
        val requested = ConnectionCore.step(ConnectionCoreState().ready(), ConnectionEvent.DisconnectRequested(true, cleanupMillis))

        val step = requested.state.expire()

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), step.state.shown())
        assertEquals("DISCONNECT_CLEANUP_TIMEOUT", step.effects.diagnostics().first().failure.code.value)
        assertEquals(Severity.Warning, step.effects.diagnostics().first().severity)
    }

    @Test
    fun `C08_Disconnecting 기한 만료는 정리를 시작한 시간 초과 원인을 유지한다`() {
        val step = ConnectionCoreState().connecting().expire().state.expire()

        assertEquals("LINK_TIMEOUT", step.state.failedCode())
        assertEquals("DISCONNECT_CLEANUP_TIMEOUT", step.effects.diagnostics().first().failure.code.value)
    }

    @Test
    fun `C13_Ready 에서 어댑터가 이탈하면 어댑터 원인으로 끝난다`() {
        val ready = ConnectionCoreState(failureSessionId = "s-1").ready()

        val off = ConnectionCore.step(ready, ConnectionEvent.AdapterLost(AdapterState.PoweredOff))
        val denied = ConnectionCore.step(ready, ConnectionEvent.AdapterLost(AdapterState.Unauthorized(canRequest = true)))

        assertEquals("ADAPTER_UNAVAILABLE", off.state.failedCode())
        assertEquals("PERMISSION_DENIED", denied.state.failedCode())
        assertEquals("s-1", off.state.terminalCause?.failure?.context?.sessionId)
        assertTrue(off.state.suppressReconnect)
        assertEquals("ADAPTER_UNAVAILABLE", off.effects.diagnostics().first().failure.code.value)
        assertTrue(ConnectionEffect.ReleaseHandle in off.effects)
    }

    @Test
    fun `C13_이미 끝나고 대기도 없는 세션은 어댑터 이탈에 조용하다`() {
        val ended = ConnectionCoreState().ready().linkDown()

        val step = ConnectionCore.step(ended, ConnectionEvent.AdapterLost(AdapterState.PoweredOff))

        assertFalse(step.accepted)
        assertTrue(step.effects.isEmpty())
        assertEquals("LINK_FAILED", ended.failedCode())
    }

    @Test
    fun `C14_재연결 대기 중 어댑터가 이탈하면 Recovering 을 어댑터 원인의 Disconnected 로 대체한다`() {
        val waiting = ConnectionCoreState().autoReady().linkDown()
        assertTrue(waiting.shown() is ConnectionState.Recovering)
        assertEquals("LINK_FAILED", (waiting.shown() as ConnectionState.Recovering).lastFailure.code.value)

        val step = ConnectionCore.step(waiting, ConnectionEvent.AdapterLost(AdapterState.PoweredOff))

        assertEquals("ADAPTER_UNAVAILABLE", step.state.failedCode())
        assertFalse(step.state.platformWait)
        assertTrue(step.state.suppressReconnect)
        assertTrue(step.state.relinkAfterAdapter)
        val kinds = step.effects.map { it::class }
        assertTrue(kinds.indexOf(StageChanged::class) < kinds.indexOf(ConnectionEffect.DisconnectLink::class))
        assertEquals(ConnectionEffect.Announce, step.effects.last())
    }

    @Test
    fun `C14_autoConnect 가 아닌 세션은 어댑터 이탈로 끝나도 복귀 재연결 표시가 서지 않는다`() {
        val step = ConnectionCore.step(ConnectionCoreState().ready(), ConnectionEvent.AdapterLost(AdapterState.PoweredOff))

        assertFalse(step.state.relinkAfterAdapter)
    }

    @Test
    fun `C16_모든 활성 단계와 Recovering 과 Initial 은 close 로 ClientClosed 가 된다`() {
        val start = ConnectionCoreState()
        val connecting = start.connecting()
        val discovering = connecting.moveTo(ConnectionStage.DiscoveringServices, 100)
        val configuring = discovering.moveTo(ConnectionStage.Configuring, 100)
        val ready = configuring.moveTo(ConnectionStage.Ready, null)
        val disconnecting = ConnectionCore.step(ready, ConnectionEvent.DisconnectRequested(true, cleanupMillis)).state
        val recovering = ConnectionCoreState().autoReady().linkDown()
        val rows = listOf(start, connecting, discovering, configuring, ready, disconnecting, recovering)

        rows.forEach { before ->
            val step = ConnectionCore.step(before.copy(relinkAfterAdapter = true), ConnectionEvent.CloseRequested)

            assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), step.state.shown())
            assertTrue(step.state.suppressReconnect || before === start)
            assertFalse(step.state.relinkAfterAdapter)
            assertFalse(step.state.platformWait)
            assertTrue(ConnectionEffect.ReleaseHandle in step.effects)
        }
    }

    @Test
    fun `C16_연결 시도 전 close 는 게시만 다시 하고 announce 하지 않는다 †`() {
        val step = ConnectionCore.step(ConnectionCoreState(), ConnectionEvent.CloseRequested)

        assertEquals(TerminalCleanupReason.ClientClosed, step.state.terminalCause?.reason)
        assertTrue(step.effects.contains(StageChanged(ConnectionStage.Disconnected)))
        assertFalse(step.effects.contains(ConnectionEffect.Announce))
    }

    @Test
    fun `C16_대기 없이 이미 끝난 세션의 close 는 원래 원인을 유지한다 †`() {
        val ended = ConnectionCoreState().ready().linkDown()

        val step = ConnectionCore.step(ended, ConnectionEvent.CloseRequested)

        assertEquals("LINK_FAILED", step.state.failedCode())
        assertFalse(step.effects.contains(ConnectionEffect.Announce))
        assertEquals(listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.ReleaseHandle), step.effects)
    }

    @Test
    fun `C17_Recovering 에서 사용자 해제는 대기를 끝내고 Requested 로 handle 을 닫는다`() {
        val waiting = ConnectionCoreState().autoReady().linkDown()

        val step = ConnectionCore.step(waiting.copy(relinkAfterAdapter = true), ConnectionEvent.DisconnectRequested(true, cleanupMillis))

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), step.state.shown())
        assertFalse(step.state.platformWait)
        assertFalse(step.state.relinkAfterAdapter)
        assertTrue(step.state.suppressReconnect)
        assertTrue(ConnectionEffect.ReleaseHandle in step.effects)
        val released = ConnectionCore.step(step.state, ConnectionEvent.HandleReleased)
        assertFalse(released.state.platformHandleOpen)
    }

    @Test
    fun `C17_대기 없이 이미 끝난 세션의 사용자 해제는 재연결 억제만 세운다`() {
        val ended = ConnectionCoreState().ready().linkDown()

        val step = ConnectionCore.step(ended.copy(suppressReconnect = false), ConnectionEvent.DisconnectRequested(true, cleanupMillis))

        assertTrue(step.state.suppressReconnect)
        assertTrue(step.effects.isEmpty())
        assertEquals("LINK_FAILED", step.state.failedCode())
    }

    @Test
    fun `C22_Ready 의 작업 무효화는 autoReconnect 여도 Recovering 없이 Failed 로 끝난다`() {
        val ready = ConnectionCoreState().autoReady()
        val timeout = failure("OPERATION_TIMEOUT")

        val step = ConnectionCore.step(ready, ConnectionEvent.OperationInvalidated(timeout, suppressReconnect = true, severity = Severity.Warning))

        assertEquals("OPERATION_TIMEOUT", step.state.failedCode())
        assertFalse(step.state.platformWait)
        assertTrue(step.state.shown() is ConnectionState.Disconnected)
        assertEquals(Severity.Warning, step.effects.diagnostics().single().severity)
        val kinds = step.effects.map { it::class }
        assertEquals(ConnectionEffect.Diagnostic::class, kinds.first())
        assertTrue(kinds.indexOf(StageChanged::class) < kinds.indexOf(ConnectionEffect.DisconnectLink::class))
        assertEquals(
            listOf<ConnectionEffect>(
                ConnectionEffect.DisconnectLink(null),
                ConnectionEffect.ReleaseHandle,
                ConnectionEffect.ClearLinkParameters,
                ConnectionEffect.Announce,
            ),
            step.effects.takeLast(4),
        )
    }

    @Test
    fun `C22_작업 쪽 권한 상실은 PermissionDenied 정리를 시작하고 내부 실패는 Error 진단으로 끝낸다`() {
        val ready = ConnectionCoreState().ready()
        val denied = ConnectionCore.step(ready, ConnectionEvent.OperationPermissionLost(failure("PERMISSION_DENIED"), cleanupMillis))

        assertEquals(TerminalCleanupReason.PermissionDenied, denied.state.terminalCause?.reason)
        assertEquals(ConnectionStage.Disconnecting, denied.state.stage)
        assertTrue(denied.state.suppressReconnect)

        val internal = ConnectionCore.step(ready, ConnectionEvent.InternalFailure(failure("INTERNAL_TRANSPORT_ERROR")))
        assertEquals("INTERNAL_TRANSPORT_ERROR", internal.state.failedCode())
        assertEquals(Severity.Error, internal.effects.diagnostics().single().severity)
    }

    @Test
    fun `C22_재연결 대기 중 내부 실패는 Error 진단과 함께 그 세션을 Failed 로 끝내고 handle 을 닫는다`() {
        val waiting = ConnectionCoreState().autoReady().linkDown()
        assertTrue(waiting.shown() is ConnectionState.Recovering)

        val step = ConnectionCore.step(waiting, ConnectionEvent.InternalFailure(failure("INTERNAL_TRANSPORT_ERROR")))

        assertTrue(step.accepted)
        assertEquals("INTERNAL_TRANSPORT_ERROR", step.state.failedCode())
        assertFalse(step.state.platformWait)
        assertTrue(step.state.suppressReconnect)
        assertEquals(Severity.Error, step.effects.diagnostics().single().severity)
        assertEquals(ConnectionEffect.Announce, step.effects.last())
    }

    @Test
    fun `이전 기한 정책 사건은 다른 단계의 기한을 정리하지 않는다`() {
        val connecting = ConnectionCoreState().connecting()
        val staleDeadline = requireNotNull(connecting.deadline)
        val discovering = connecting.moveTo(ConnectionStage.DiscoveringServices, 100)

        val step = ConnectionCore.step(discovering, LifecycleDeadline(staleDeadline, cleanupMillis))

        assertFalse(step.accepted)
        assertSame(discovering, step.state)
        assertTrue(step.effects.isEmpty())
    }

    // ── 링크 콜백 정책 (T5) ─────────────────────────────────────────────────

    private fun ConnectionCoreState.linkEvent(
        fact: LinkFact,
        status: Int = 0,
        failure: BleFailure? = null,
        generation: Long = this.generation,
    ) = ConnectionEvent.LinkStateChanged(generation, fact, status, failure, 100, cleanupMillis, atMillis = 0)

    private fun ConnectionCoreState.linkStep(fact: LinkFact, status: Int = 0, failure: BleFailure? = null) =
        ConnectionCore.step(this, linkEvent(fact, status, failure))

    private fun ConnectionCoreState.discovered(failure: BleFailure? = null, generation: Long = this.generation) =
        ConnectionCore.step(this, ConnectionEvent.ServicesDiscovered(generation, failure, 100, cleanupMillis))

    private fun ConnectionCoreState.mtuSettled(mtu: Int = 247, failure: BleFailure? = null, generation: Long = this.generation) =
        ConnectionCore.step(this, ConnectionEvent.MtuSettled(generation, mtu, failure))

    private fun ConnectionCoreState.lastFailureCode(): String? =
        (shown() as? ConnectionState.Recovering)?.lastFailure?.code?.value

    private fun ConnectionCoreState.linkUp() = apply(linkEvent(LinkFact.Up))

    @Test
    fun `C03_Connecting 의 링크 실패는 autoReconnect 여부와 상관없이 Failed 로 끝나고 handle 을 닫는다`() {
        listOf(true, false).forEach { auto ->
            val connecting = ConnectionCoreState(failureSessionId = "s-1").connecting()
                .apply(Direct.SetAutoConnect(auto))
                .apply(Direct.SetPlatformHandleOpen(true))
            val link = failure("LINK_FAILED")

            val step = connecting.linkStep(LinkFact.Down, status = 133, failure = link)

            assertTrue(step.accepted)
            assertEquals(ConnectionStage.Disconnected, step.state.stage)
            assertSame(link, (step.state.terminalCause?.failure))
            assertEquals(TerminalCleanupReason.LinkStatusFailure, step.state.terminalCause?.reason)
            assertEquals("LINK_FAILED", step.state.failedCode())
            assertFalse(step.state.platformWait)
            assertTrue(step.state.shown() is ConnectionState.Disconnected)
            assertEquals(Severity.Error, step.effects.diagnostics().first().severity)
            assertTrue(ConnectionEffect.ReleaseHandle in step.effects)
            assertEquals(ConnectionEffect.Announce, step.effects.last())
        }
    }

    @Test
    fun `C03_Down 이 아닌 링크 실패는 Connecting 일 때만 정리를 시작한다`() {
        val connecting = ConnectionCoreState().connecting()
        val begun = connecting.linkStep(LinkFact.Connecting, status = 133, failure = failure("LINK_FAILED"))
        assertEquals(ConnectionStage.Disconnecting, begun.state.stage)
        assertTrue(begun.state.suppressReconnect)
        assertTrue(ConnectionEffect.DisconnectLink(ConnectionEvent.DisconnectStartFailed(1L, begun.state.terminalCause)) in begun.effects)

        // readiness 가 진행된 뒤 늦게 온 non-terminal 실패는 진단만 남기고 단계·기한을 건드리지 않는다.
        val discovering = connecting.linkUp()
        val late = discovering.linkStep(LinkFact.Connecting, status = 133, failure = failure("LINK_FAILED"))
        assertFalse(late.accepted)
        assertSame(discovering, late.state)
        assertEquals(1, late.effects.size)
        assertEquals(Severity.Error, late.effects.diagnostics().single().severity)
    }

    @Test
    fun `C03_링크 성공은 Connecting 을 DiscoveringServices 로 옮기고 탐색 절차를 낸다`() {
        val connecting = ConnectionCoreState().connecting()

        val step = connecting.linkStep(LinkFact.Up)

        assertEquals(ConnectionStage.DiscoveringServices, step.state.stage)
        assertEquals(ConnectionState.DiscoveringServices, step.state.shown())
        assertEquals(TerminalCleanupReason.ServiceDiscoveryTimeout, step.state.deadline?.timeoutReason)
        assertEquals(
            listOf(
                CancelDeadline,
                StageChanged(ConnectionStage.DiscoveringServices),
                ArmDeadline(requireNotNull(step.state.deadline)),
                ConnectionEffect.BeginOperationGeneration,
                ConnectionEffect.Announce,
                ConnectionEffect.LinkUp(1L),
            ),
            step.effects,
        )
        // Connecting 이 아니거나 다른 generation 의 성공은 받지 않는다.
        assertFalse(step.state.linkStep(LinkFact.Up).accepted)
        assertFalse(ConnectionCore.step(connecting, connecting.linkEvent(LinkFact.Up, generation = 99L)).accepted)
    }

    @Test
    fun `C04_탐색 status 실패와 서비스 0개는 Disconnecting 을 거쳐 Failed 로 끝나고 Recovering 이 아니다`() {
        val discovering = ConnectionCoreState(failureSessionId = "s-1").connecting().linkUp()
            .apply(Direct.SetAutoConnect(true))
            .apply(Direct.SetPlatformHandleOpen(true))
        listOf("SERVICE_DISCOVERY_FAILED", "EMPTY_SERVICE_DISCOVERY").forEach { code ->
            val step = discovering.discovered(failure(code))

            assertEquals(ConnectionStage.Disconnecting, step.state.stage)
            assertEquals(ConnectionState.Disconnecting, step.state.shown())
            assertEquals(TerminalCleanupReason.ServiceDiscoveryStatusFailure, step.state.terminalCause?.reason)
            assertTrue(step.state.suppressReconnect)
            assertEquals(Severity.Error, step.effects.diagnostics().single().severity)
            assertEquals(ConnectionEffect.Diagnostic::class, step.effects.first()::class)

            val done = step.state.linkDown()
            assertEquals(code, done.failedCode())
            assertFalse(done.platformWait)
        }
    }

    @Test
    fun `C04_탐색 성공은 Configuring 으로 옮기고 다른 generation·단계의 결과는 받지 않는다`() {
        val discovering = ConnectionCoreState().connecting().linkUp()

        val ok = discovering.discovered()
        assertEquals(ConnectionStage.Configuring, ok.state.stage)
        assertEquals(TerminalCleanupReason.ConfigurationTimeout, ok.state.deadline?.timeoutReason)

        assertFalse(discovering.discovered(generation = 99L).accepted)
        assertFalse(discovering.discovered(failure("SERVICE_DISCOVERY_FAILED"), generation = 99L).accepted)
        assertFalse(ok.state.discovered().accepted)
        assertFalse(ok.state.discovered(failure("SERVICE_DISCOVERY_FAILED")).accepted)
    }

    @Test
    fun `C04_탐색·MTU 시작 실패는 진단 뒤 정리하고 권한 상실은 PermissionDenied 로 구분한다`() {
        val discovering = ConnectionCoreState().connecting().linkUp()

        val rejected = ConnectionCore.step(
            discovering,
            ConnectionEvent.DiscoveryStartFailed(failure("GATT_START_REJECTED"), cleanupMillis),
        )
        assertEquals(TerminalCleanupReason.ServiceDiscoveryStartRejected, rejected.state.terminalCause?.reason)
        assertEquals(Severity.Error, rejected.effects.diagnostics().single().severity)
        assertEquals(ConnectionStage.Disconnecting, rejected.state.stage)

        val denied = ConnectionCore.step(
            discovering,
            ConnectionEvent.DiscoveryStartFailed(failure("PERMISSION_DENIED"), cleanupMillis),
        )
        assertEquals(TerminalCleanupReason.PermissionDenied, denied.state.terminalCause?.reason)

        val configuring = discovering.discovered().state
        val mtuDenied = ConnectionCore.step(
            configuring,
            ConnectionEvent.MtuRequestFailed(failure("PERMISSION_DENIED"), cleanupMillis),
        )
        assertEquals(TerminalCleanupReason.PermissionDenied, mtuDenied.state.terminalCause?.reason)
        assertEquals(Severity.Warning, mtuDenied.effects.diagnostics().single().severity)
        assertEquals(ConnectionStage.Disconnecting, mtuDenied.state.stage)

        val mtuRejected = ConnectionCore.step(
            configuring,
            ConnectionEvent.MtuRequestFailed(failure("GATT_START_REJECTED"), cleanupMillis),
        )
        assertEquals(ConnectionStage.Configuring, mtuRejected.state.stage)
        assertEquals(
            listOf(
                ConnectionEffect.Diagnostic(Severity.Warning, failure("GATT_START_REJECTED")),
                ConnectionEffect.ApplyMtu(ConnectionLink.DEFAULT_MTU),
                ConnectionEffect.RestoreSubscriptions(1L),
            ),
            mtuRejected.effects,
        )
    }

    @Test
    fun `C09_autoReconnect Ready 의 링크 해제는 Recovering 이고 handle 을 닫지 않고 Connecting 을 게시하지 않는다`() {
        val ready = ConnectionCoreState(failureSessionId = "s-1").autoReady()

        val step = ready.linkStep(LinkFact.Down, status = 8, failure = failure("LINK_FAILED"))

        assertEquals(ConnectionStage.Disconnected, step.state.stage)
        assertTrue(step.state.platformWait)
        assertTrue(step.state.shown() is ConnectionState.Recovering)
        assertEquals("LINK_FAILED", step.state.lastFailureCode())
        assertEquals(TerminalCleanupReason.PlatformDisconnected, step.state.terminalCause?.reason)
        assertFalse(step.state.suppressReconnect)
        assertFalse(ConnectionEffect.ReleaseHandle in step.effects)
        assertTrue(step.effects.none { it == StageChanged(ConnectionStage.Connecting) })
        assertTrue(step.state.platformHandleOpen)
    }

    @Test
    fun `C09_성공 status 의 링크 해제도 Ready 에서는 Recovering 이고 원인 없는 LINK_FAILED 를 status 와 함께 고정한다`() {
        val ready = ConnectionCoreState(failureSessionId = "s-1").autoReady()

        val step = ready.linkStep(LinkFact.Down, status = 0)

        assertTrue(step.state.shown() is ConnectionState.Recovering)
        assertEquals("LINK_FAILED", step.state.lastFailureCode())
        assertEquals(Severity.Warning, step.effects.diagnostics().single().severity)
    }

    @Test
    fun `C10_플랫폼 재연결은 Connecting 게시 없이 새 generation 으로 탐색 구성 Ready 까지 간다`() {
        val recovering = ConnectionCoreState().autoReady().linkStep(LinkFact.Down, 8, failure("LINK_FAILED")).state

        val relink = ConnectionCore.step(recovering, ConnectionEvent.PlatformRelinkUp)

        assertTrue(relink.accepted)
        assertEquals(recovering.generation + 1, relink.state.generation)
        assertEquals(ConnectionStage.Connecting, relink.state.stage)
        assertFalse(relink.state.platformWait)
        assertNull(relink.state.deadline)
        assertNull(relink.state.terminalCause)
        assertTrue(relink.effects.none { it is ArmDeadline || it == ConnectionEffect.Announce })
        assertEquals(ConnectionEffect.Republish, relink.effects.last())

        val discovering = relink.state.linkUp()
        assertEquals(ConnectionState.DiscoveringServices, discovering.shown())
        val configuring = discovering.discovered().state
        assertEquals(ConnectionState.Configuring, configuring.shown())
        val settled = configuring.mtuSettled()
        assertEquals(
            listOf<ConnectionEffect>(
                ConnectionEffect.ApplyMtu(247),
                ConnectionEffect.RestoreSubscriptions(configuring.generation),
            ),
            settled.effects,
        )
        val ready = ConnectionCore.step(configuring, ConnectionEvent.RestoreFinished(configuring.generation))
        assertEquals(ConnectionState.Ready, ready.state.shown())
        assertEquals(ConnectionEffect.Announce, ready.effects.last())
    }

    @Test
    fun `C10_플랫폼 재연결 대기가 아니면 PlatformRelinkUp 은 새 generation 을 열지 않는다`() {
        listOf(ConnectionCoreState(), ConnectionCoreState().ready(), ConnectionCoreState().connecting()).forEach { s ->
            val step = ConnectionCore.step(s, ConnectionEvent.PlatformRelinkUp)
            assertFalse(step.accepted)
            assertSame(s, step.state)
            assertTrue(step.effects.isEmpty())
        }
    }

    @Test
    fun `C10_MTU 협상 실패는 진단 뒤 기본 MTU 로 진행한다`() {
        val configuring = ConnectionCoreState().connecting().linkUp().discovered().state
        val mtuFailure = failure("GATT_STATUS_FAILED")

        val step = configuring.mtuSettled(mtu = 185, failure = mtuFailure)

        assertEquals(
            listOf(
                ConnectionEffect.Diagnostic(Severity.Warning, mtuFailure),
                ConnectionEffect.ApplyMtu(23),
                ConnectionEffect.RestoreSubscriptions(configuring.generation),
            ),
            step.effects,
        )
        assertEquals(185, (configuring.mtuSettled(mtu = 185).effects.first() as ConnectionEffect.ApplyMtu).mtu)
    }

    @Test
    fun `C11_autoReconnect 가 아닌 Ready 의 링크 해제는 Failed 로 끝나고 handle 을 닫는다`() {
        val ready = ConnectionCoreState(failureSessionId = "s-1").ready()
            .apply(Direct.SetSuppressReconnect(true))
            .apply(Direct.SetPlatformHandleOpen(true))

        // 어댑터 정착 창(states.md §3): 곧바로 끝내지 않고 창이 지난 뒤 LINK_FAILED 로 확정한다.
        val pending = ready.linkStep(LinkFact.Down, status = 8, failure = failure("LINK_FAILED"))
        assertEquals(ConnectionState.Ready, pending.state.shown())
        assertTrue(pending.effects.none { it is StageChanged })

        val step = pending.state.expire()

        assertEquals("LINK_FAILED", step.state.failedCode())
        assertFalse(step.state.platformWait)
        assertTrue(ConnectionEffect.ReleaseHandle in step.effects)
    }

    @Test
    fun `C12_플랫폼 재연결 generation 이 Ready 전에 실패하면 Failed 로 끝나고 대기로 돌아가지 않는다`() {
        val recovering = ConnectionCoreState().autoReady().linkStep(LinkFact.Down, 8, failure("LINK_FAILED")).state
        val relinked = ConnectionCore.step(recovering, ConnectionEvent.PlatformRelinkUp).state
        assertTrue(relinked.autoConnect)
        assertTrue(relinked.platformHandleOpen)

        val failedInConnecting = relinked.linkStep(LinkFact.Down, 133, failure("LINK_FAILED"))
        assertEquals("LINK_FAILED", failedInConnecting.state.failedCode())
        assertFalse(failedInConnecting.state.platformWait)
        assertTrue(ConnectionEffect.ReleaseHandle in failedInConnecting.effects)

        // 성공 status 의 Ready 전 해제도 같은 규칙이다(status 0 은 원인 없는 LINK_FAILED).
        val silentInDiscovery = relinked.linkUp().linkStep(LinkFact.Down, status = 0)
        assertEquals("LINK_FAILED", silentInDiscovery.state.failedCode())
        assertFalse(silentInDiscovery.state.platformWait)
        assertTrue(silentInDiscovery.state.suppressReconnect)
        assertTrue(ConnectionEffect.ReleaseHandle in silentInDiscovery.effects)
    }

    @Test
    fun `C23_Ready 전이는 현재 generation 의 Configuring 복원 완료일 때만 일어난다`() {
        val configuring = ConnectionCoreState().connecting().linkUp().discovered().state

        assertFalse(ConnectionCore.step(configuring, ConnectionEvent.RestoreFinished(configuring.generation + 1)).accepted)
        assertFalse(configuring.mtuSettled(generation = configuring.generation + 1).accepted)
        val discovering = ConnectionCoreState().connecting().linkUp()
        assertFalse(ConnectionCore.step(discovering, ConnectionEvent.RestoreFinished(discovering.generation)).accepted)
        assertFalse(discovering.mtuSettled().accepted)
        val rejected = ConnectionCore.step(configuring, ConnectionEvent.RestoreFinished(configuring.generation + 1))
        assertSame(configuring, rejected.state)
        assertTrue(rejected.effects.isEmpty())
        assertEquals(ConnectionStage.Configuring, configuring.stage)

        val ready = ConnectionCore.step(configuring, ConnectionEvent.RestoreFinished(configuring.generation))
        assertEquals(ConnectionStage.Ready, ready.state.stage)
        assertNull(ready.state.deadline)
    }

    // ── 연결 시작 정책 (T6) ─────────────────────────────────────────────────

    private val linkMillis = 15_000L

    private fun ConnectionCoreState.begin(
        options: ConnectOptions = ConnectOptions(autoConnect = false),
        rediscover: Boolean = false,
    ) = ConnectionCore.step(this, ConnectionEvent.BeginConnect(options, linkMillis, rediscover, atMillis = 0))

    private fun ConnectionCoreState.admit(options: ConnectOptions): ConnectAdmission {
        val step = ConnectionCore.step(this, ConnectionEvent.ConnectRequested(options, linkMillis))
        assertSame("판정은 상태를 바꾸지 않는다", this, step.state)
        return step.effects.filterIsInstance<ConnectionEffect.ConnectAdmitted>().single().admission
    }

    @Test
    fun `C01_첫 연결은 Configuring 을 건너뛰지 않고 Ready 에 이른다`() {
        val began = ConnectionCoreState().begin()

        assertTrue(began.accepted)
        assertEquals(ConnectionState.Connecting, began.state.shown())
        val linkDeadline = requireNotNull(began.state.deadline)
        assertEquals(linkMillis, linkDeadline.timeoutMillis)
        assertEquals(
            listOf<ConnectionEffect>(
                CancelDeadline,
                StageChanged(ConnectionStage.Connecting),
                ArmDeadline(linkDeadline),
                ConnectionEffect.Announce,
                ConnectionEffect.InvalidateOperations,
                ConnectionEffect.BeginOperationGeneration,
                ConnectionEffect.ReleaseStaleHandle,
                ConnectionEffect.ConnectLink(autoConnect = false),
            ),
            began.effects,
        )
        val started = ConnectionCore.step(began.state, ConnectionEvent.LinkStarted(atMillis = 0)).state
        val up = ConnectionCore.step(started, started.linkEvent(LinkFact.Up))
        assertEquals(ConnectionState.DiscoveringServices, up.state.shown())
        assertTrue(ConnectionEffect.LinkUp(started.generation) in up.effects)
        val configuring = up.state.discovered().state
        assertEquals(ConnectionState.Configuring, configuring.shown())
        val settled = configuring.mtuSettled()
        assertEquals(
            listOf<ConnectionEffect>(
                ConnectionEffect.ApplyMtu(247),
                ConnectionEffect.RestoreSubscriptions(configuring.generation),
            ),
            settled.effects,
        )
        val ready = ConnectionCore.step(configuring, ConnectionEvent.RestoreFinished(configuring.generation))
        assertEquals(ConnectionState.Ready, ready.state.shown())
        assertEquals(ConnectionEffect.Announce, ready.effects.last())
    }

    @Test
    fun `C01_이번 시도의 모드는 링크 시작 전에 서고 이전 시도의 재연결 억제를 물려받지 않는다`() {
        val old = ConnectionCoreState(requestedMtu = 23)
            .apply(Direct.SetSuppressReconnect(true))
            .apply(Direct.SetRelinkAfterAdapter(true))
            .apply(Direct.SetPlatformHandleOpen(true))

        val plain = old.begin(ConnectOptions(autoConnect = false, requestedMtu = 185)).state

        assertTrue(plain.suppressReconnect)
        assertFalse(plain.autoConnect || plain.relinkAfterAdapter || plain.platformHandleOpen)
        assertEquals(185, plain.requestedMtu)
        val auto = old.begin(ConnectOptions(autoConnect = true)).state
        assertFalse(auto.suppressReconnect)
        assertTrue(auto.autoConnect)
    }

    @Test
    fun `C02_autoConnect 첫 연결은 Connecting 이고 링크 기한 할 일이 없다`() {
        val began = ConnectionCoreState().begin(ConnectOptions(autoConnect = true))

        assertEquals(ConnectionState.Connecting, began.state.shown())
        assertNull(began.state.deadline)
        assertTrue(began.effects.none { it is ArmDeadline })
        assertEquals(ConnectionEffect.ConnectLink(autoConnect = true), began.effects.last())
        // 명시 timeout 이 있으면 autoConnect 여도 그 값이 기한이다.
        val explicit = ConnectionCoreState().begin(ConnectOptions(autoConnect = true, timeoutMillis = 500)).state
        assertEquals(500L, explicit.deadline?.timeoutMillis)
    }

    @Test
    fun `C02_재발견이 필요하면 링크 시작 대신 재발견 할 일로 끝난다`() {
        val began = ConnectionCoreState().begin(rediscover = true)

        assertEquals(ConnectionEffect.Rediscover, began.effects.last())
        assertTrue(began.effects.none { it is ConnectionEffect.ConnectLink })
    }

    @Test
    fun `C15_어댑터 꺼짐으로 끝난 autoConnect 세션은 복귀 때 새 generation 의 Connecting 을 한 번만 연다`() {
        val lost = ConnectionCore.step(ConnectionCoreState().autoReady(), ConnectionEvent.AdapterLost(AdapterState.PoweredOff)).state
        assertTrue(lost.relinkAfterAdapter)

        val returned = ConnectionCore.step(lost, ConnectionEvent.AdapterReturned(true, linkMillis, rediscover = false, atMillis = 0))

        assertTrue(returned.accepted)
        assertEquals(lost.generation + 1, returned.state.generation)
        assertEquals(ConnectionState.Connecting, returned.state.shown())
        assertNull(returned.state.deadline)
        assertFalse(returned.state.relinkAfterAdapter)
        assertTrue(returned.state.autoConnect && !returned.state.suppressReconnect)
        assertEquals(1, returned.effects.count { it == StageChanged(ConnectionStage.Connecting) })
        assertEquals(ConnectionEffect.ConnectLink(autoConnect = true), returned.effects.last())
        // 두 번째 복귀 관측은 아무 것도 하지 않는다.
        val again = ConnectionCore.step(returned.state, ConnectionEvent.AdapterReturned(true, linkMillis, rediscover = false, atMillis = 0))
        assertFalse(again.accepted)
        assertSame(returned.state, again.state)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun `C15_복귀 재연결 대상이 아니거나 어댑터가 다시 꺼졌으면 할 일이 없다`() {
        val plain = ConnectionCore.step(ConnectionCoreState().ready(), ConnectionEvent.AdapterLost(AdapterState.PoweredOff)).state
        val userEnded = ConnectionCore.step(
            ConnectionCoreState().autoReady().apply(Direct.SetRelinkAfterAdapter(true)),
            ConnectionEvent.DisconnectRequested(hasHandle = true, cleanupMillis),
        ).state
        val lost = ConnectionCore.step(ConnectionCoreState().autoReady(), ConnectionEvent.AdapterLost(AdapterState.PoweredOff)).state

        listOf(plain, userEnded).forEach { state ->
            val step = ConnectionCore.step(state, ConnectionEvent.AdapterReturned(true, linkMillis, rediscover = false, atMillis = 0))
            assertFalse(step.accepted)
            assertTrue(step.effects.isEmpty())
        }
        val off = ConnectionCore.step(lost, ConnectionEvent.AdapterReturned(false, linkMillis, rediscover = false, atMillis = 0))
        assertFalse(off.accepted)
        assertSame(lost, off.state)
        assertTrue(off.effects.isEmpty())
        // 사용자 해제는 복귀 재연결 표시도 내린다.
        assertFalse(userEnded.relinkAfterAdapter)
    }

    @Test
    fun `C18_정리 중 connect 는 거절하고 같은 옵션은 합류하고 다른 옵션은 충돌이며 상태를 바꾸지 않는다`() {
        val disconnecting = ConnectionCore.step(
            ConnectionCoreState().ready(),
            ConnectionEvent.DisconnectRequested(hasHandle = true, cleanupMillis),
        ).state
        val during = disconnecting.admit(ConnectOptions(autoConnect = false)) as ConnectAdmission.Conflict
        assertEquals("CONNECT_DURING_DISCONNECT", during.code)
        assertEquals(Retryability.TransientMayHelp, during.retryability)

        val mode = ConnectOptions(autoConnect = false, requestedMtu = 185)
        val same = ConnectionCoreState().begin(mode).state
        val ready = same.moveTo(ConnectionStage.DiscoveringServices, 100).moveTo(ConnectionStage.Configuring, 100)
            .moveTo(ConnectionStage.Ready, null)
        val waiting = ConnectionCoreState().autoReady().linkDown().apply(Direct.SetPlatformWait(true))
        listOf(same, ready).forEach { state ->
            assertEquals(ConnectAdmission.Joined, state.admit(mode))
            val conflict = state.admit(mode.copy(requestedMtu = 23)) as ConnectAdmission.Conflict
            assertEquals("CONNECTION_OPTIONS_CONFLICT", conflict.code)
            assertEquals(Retryability.Never, conflict.retryability)
            assertTrue(state.admit(mode.copy(autoConnect = true)) is ConnectAdmission.Conflict)
        }
        // link 단계의 기한만 비교한다 — 이미 소진된 기한은 Ready 에서 충돌이 아니다.
        assertTrue(same.admit(mode.copy(timeoutMillis = 99)) is ConnectAdmission.Conflict)
        assertEquals(ConnectAdmission.Joined, ready.admit(mode.copy(timeoutMillis = 99)))
        assertEquals(ConnectAdmission.Joined, ConnectionCoreState().begin(ConnectOptions(autoConnect = true)).state
            .admit(ConnectOptions(autoConnect = true)))
        // Recovering(플랫폼 재연결 대기)은 같은 옵션이면 합류, 다르면 충돌, 끝난 세션은 진행한다.
        assertTrue(waiting.shown() is ConnectionState.Recovering)
        assertEquals(ConnectAdmission.Joined, waiting.admit(ConnectOptions(autoConnect = true)))
        assertTrue(waiting.admit(ConnectOptions(autoConnect = false)) is ConnectAdmission.Conflict)
        assertEquals(ConnectAdmission.Proceed, ConnectionCoreState().admit(ConnectOptions()))
        assertEquals(ConnectAdmission.Proceed, ready.linkDown().admit(ConnectOptions(autoConnect = false, requestedMtu = 185)))
    }

    @Test
    fun `C19_재발견에서 못 찾으면 autoConnect 가 아닐 때만 호출 실패로 끝난다`() {
        val notFound = failure("PERIPHERAL_NOT_FOUND")
        val invalidated = failure("OPERATION_INVALIDATED")
        fun finished(state: ConnectionCoreState) = ConnectionCore.step(
            state,
            ConnectionEvent.RediscoveryFinished(state.generation, RediscoveryFact.NotFound, notFound, invalidated),
        )

        val plain = ConnectionCoreState().begin(rediscover = true).state
        val failed = finished(plain)
        assertEquals(ConnectionStage.Disconnected, failed.state.stage)
        assertEquals("PERIPHERAL_NOT_FOUND", failed.state.failedCode())
        assertTrue(failed.state.suppressReconnect)
        assertEquals(TerminalCleanupReason.LinkStartRejected, failed.state.terminalCause?.reason)
        assertEquals(ConnectionEffect.FailConnectCall(notFound), failed.effects.last())
        assertTrue(ConnectionEffect.InvalidateOperations in failed.effects)
        assertTrue(failed.effects.none { it == ConnectionEffect.Announce || it is ConnectionEffect.ConnectLink })

        val auto = ConnectionCoreState().begin(ConnectOptions(autoConnect = true), rediscover = true).state
        val kept = finished(auto)
        assertEquals(ConnectionStage.Connecting, kept.state.stage)
        assertNull(kept.state.terminalCause)
        assertEquals(
            listOf<ConnectionEffect>(
                ConnectionEffect.Diagnostic(Severity.Warning, notFound),
                ConnectionEffect.ConnectLink(autoConnect = true),
            ),
            kept.effects,
        )
        // 찾았거나 스캔을 못 쓴 폴백은 링크를 그대로 시작한다.
        val found = ConnectionCore.step(plain, ConnectionEvent.RediscoveryFinished(plain.generation, RediscoveryFact.Found, null, invalidated))
        assertEquals(listOf<ConnectionEffect>(ConnectionEffect.ConnectLink(autoConnect = false)), found.effects)
        val unavailable = ConnectionCore.step(
            plain,
            ConnectionEvent.RediscoveryFinished(plain.generation, RediscoveryFact.Unavailable, failure("SCAN_START_FAILED"), invalidated),
        )
        assertEquals(Severity.Warning, unavailable.effects.diagnostics().single().severity)
        assertEquals(ConnectionEffect.ConnectLink(autoConnect = false), unavailable.effects.last())
    }

    @Test
    fun `C19_재발견 중 세션이 끝났으면 링크를 시작하지 않고 고정된 원인으로 호출을 끝낸다`() {
        val invalidated = failure("OPERATION_INVALIDATED")
        val began = ConnectionCoreState().begin(rediscover = true).state
        fun finished(state: ConnectionCoreState) = ConnectionCore.step(
            state,
            ConnectionEvent.RediscoveryFinished(state.generation, RediscoveryFact.Found, null, invalidated),
        )

        // 사용자 해제: 재발견 resume 이 온 시점에 Disconnecting 을 직접 끝내고 호출을 무효화한다.
        val disconnecting = ConnectionCore.step(began, ConnectionEvent.DisconnectRequested(hasHandle = false, cleanupMillis)).state
        val byUser = finished(disconnecting)
        assertEquals(ConnectionStage.Disconnected, byUser.state.stage)
        assertEquals(BleDisconnectReason.Requested, (byUser.state.shown() as ConnectionState.Disconnected).reason)
        assertEquals(ConnectionEffect.FailConnectCall(invalidated), byUser.effects.last())
        // close: 실패 없이 정상 반환한다(Runtime 의 closed 검사가 던진다).
        val closed = ConnectionCore.step(began, ConnectionEvent.CloseRequested).state
        assertEquals(ConnectionEffect.EndConnectCall, finished(closed).effects.last())
        // 실패로 이미 끝났으면 그 실패로 던진다.
        val failedFirst = ConnectionCore.step(began, ConnectionEvent.OperationInvalidated(failure("X_FAIL"), true, Severity.Warning)).state
        val call = finished(failedFirst).effects.last() as ConnectionEffect.FailConnectCall
        assertEquals("X_FAIL", call.failure.code.value)
    }

    @Test
    fun `C19_재발견을 기다리던 호출자가 취소되면 열린 generation 을 닫는다`() {
        val began = ConnectionCoreState().begin(rediscover = true).state

        val cancelled = ConnectionCore.step(began, ConnectionEvent.ConnectCallCancelled(began.generation))

        assertEquals(ConnectionStage.Disconnected, cancelled.state.stage)
        assertEquals(BleDisconnectReason.Requested, (cancelled.state.shown() as ConnectionState.Disconnected).reason)
        assertTrue(cancelled.state.suppressReconnect)
        assertEquals(
            listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.Announce),
            cancelled.effects.takeLast(2),
        )
        // 이미 다른 사건이 끝냈으면 건드리지 않는다.
        val stale = ConnectionCore.step(cancelled.state, ConnectionEvent.ConnectCallCancelled(began.generation))
        assertFalse(stale.accepted)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `C20_링크 시작 거절은 시작 거절 코드로 끝나고 호출 실패이며 handle 을 남기지 않는다`() {
        val began = ConnectionCoreState().begin(ConnectOptions(autoConnect = true)).state
        val rejected = failure("GATT_START_REJECTED")

        val step = ConnectionCore.step(began, ConnectionEvent.LinkStartFailed(began.generation, rejected, LinkStartFailureKind.Rejected))

        assertEquals(ConnectionStage.Disconnected, step.state.stage)
        assertEquals(TerminalCleanupReason.LinkStartRejected, step.state.terminalCause?.reason)
        assertEquals("GATT_START_REJECTED", step.state.failedCode())
        assertTrue(step.state.suppressReconnect)
        assertFalse(step.state.platformWait)
        assertFalse(step.state.platformHandleOpen)
        assertEquals(ConnectionEffect.FailConnectCall(rejected), step.effects.last())
        assertTrue(step.effects.none { it == ConnectionEffect.Announce || it == ConnectionEffect.ReleaseHandle })
        assertTrue(step.state.shown() is ConnectionState.Disconnected)
        // 권한 거부는 PermissionDenied 로 분류된다.
        val denied = ConnectionCore.step(
            began,
            ConnectionEvent.LinkStartFailed(began.generation, failure("PERMISSION_DENIED"), LinkStartFailureKind.PermissionDenied),
        )
        assertEquals(TerminalCleanupReason.PermissionDenied, denied.state.terminalCause?.reason)
    }

    @Test
    fun `C20_시작 성공은 handle 열림과 요청 시각만 세우고 시작 실패로 던지는 호출은 종결을 게시한다`() {
        val began = ConnectionCoreState().begin().state

        val started = ConnectionCore.step(began, ConnectionEvent.LinkStarted(atMillis = 7))
        assertTrue(started.state.platformHandleOpen)
        assertEquals(
            began.copy(platformHandleOpen = true, linkAttempt = began.linkAttempt?.copy(requestedAtMillis = 7)),
            started.state,
        )
        assertTrue(started.effects.isEmpty())

        // 아직 열려 있으면 종결 API 가 원인을 고정하고 게시한다.
        val open = ConnectionCore.step(began, ConnectionEvent.ConnectCallFailed(failure("GATT_START_REJECTED")))
        assertEquals("GATT_START_REJECTED", open.state.failedCode())
        assertTrue(open.state.suppressReconnect)
        assertEquals(1, open.effects.count { it == ConnectionEffect.Announce })
        // 이미 시작 경로가 종결했으면 게시만 남는다.
        val ended = ConnectionCore.step(
            began,
            ConnectionEvent.LinkStartFailed(began.generation, failure("GATT_START_REJECTED"), LinkStartFailureKind.Rejected),
        ).state
        val published = ConnectionCore.step(ended, ConnectionEvent.ConnectCallFailed(failure("GATT_START_REJECTED")))
        assertEquals(ended.terminalCause, published.state.terminalCause)
        assertEquals(listOf<ConnectionEffect>(ConnectionEffect.Announce), published.effects)
    }

    // ── 어댑터 정착 창 (states.md §3) ──────────────────────────────────────────

    private fun ConnectionCoreState.linkLost(status: Int = 8, failure: BleFailure? = failure("LINK_FAILED")) =
        linkStep(LinkFact.Down, status, failure)

    private fun ConnectionCoreState.adapterLost(adapter: AdapterState = AdapterState.PoweredOff) =
        ConnectionCore.step(this, ConnectionEvent.AdapterLost(adapter))

    /** Ready 에서 링크를 잃으면 Recovering 으로 가지 않는 세션: autoReconnect 아님, 또는 handle 을 남기지 않는 autoConnect. */
    private fun ConnectionCoreState.manualReady() = ready().apply(Direct.SetPlatformHandleOpen(true))

    private fun ConnectionCoreState.autoNoWaitReady() = ready()
        .apply(Direct.SetAutoConnect(true))
        .apply(Direct.SetSuppressReconnect(true))
        .apply(Direct.SetPlatformHandleOpen(true))

    @Test
    fun `S01_Ready 링크 유실은 게시 없이 창을 열고 창 안의 어댑터 이탈이 ADAPTER_UNAVAILABLE 로 끝낸다`() {
        val lost = ConnectionCoreState(failureSessionId = "s-1").manualReady().linkLost()

        assertTrue(lost.accepted)
        assertEquals(ConnectionState.Ready, lost.state.shown())
        assertTrue(lost.effects.none { it is StageChanged || it is ConnectionEffect.Announce })
        assertTrue(ConnectionEffect.InvalidateOperations in lost.effects)
        val armed = lost.effects.filterIsInstance<ArmDeadline>().single().deadline
        assertEquals(ConnectionCore.ADAPTER_SETTLE_MILLIS, armed.timeoutMillis)
        assertFalse(ConnectionCore.admitsPublicGattOperation(lost.state))

        val off = lost.state.adapterLost()
        assertEquals("ADAPTER_UNAVAILABLE", off.state.failedCode())
        assertNull(off.state.pendingLoss)
        assertNull(off.state.deadline)
        assertFalse(off.state.relinkAfterAdapter)
        assertTrue(CancelDeadline in off.effects)
    }

    @Test
    fun `S02_handle 을 남기지 않는 autoConnect 도 창 안의 어댑터 이탈이면 복귀 재연결을 건다`() {
        val lost = ConnectionCoreState().autoNoWaitReady().linkLost()
        assertEquals(ConnectionState.Ready, lost.state.shown())

        val off = lost.state.adapterLost()
        assertEquals("ADAPTER_UNAVAILABLE", off.state.failedCode())
        assertTrue(off.state.relinkAfterAdapter)
    }

    @Test
    fun `S03_플랫폼 재연결 대기(Recovering)는 창을 두지 않고 어댑터 이탈이 대기를 끝낸다`() {
        val recovering = ConnectionCoreState().autoReady().linkLost()
        assertEquals("LINK_FAILED", recovering.state.lastFailureCode())
        assertNull(recovering.state.pendingLoss)

        val off = recovering.state.adapterLost()
        assertEquals("ADAPTER_UNAVAILABLE", off.state.failedCode())
        assertTrue(off.state.relinkAfterAdapter)
    }

    @Test
    fun `S04_창이 지나면 지금처럼 LINK_FAILED 이고 늦은 어댑터 이탈은 원인을 바꾸지 않는다`() {
        listOf(
            ConnectionCoreState().manualReady() to false,
            ConnectionCoreState().autoNoWaitReady() to false,
        ).forEach { (ready, _) ->
            val elapsed = ready.linkLost().state.expire()
            assertEquals("LINK_FAILED", elapsed.state.failedCode())
            assertNull(elapsed.state.pendingLoss)
            assertTrue(elapsed.effects.diagnostics().isEmpty())
            val late = elapsed.state.adapterLost()
            assertFalse(late.accepted)
            assertEquals("LINK_FAILED", late.state.failedCode())
            assertFalse(late.state.relinkAfterAdapter)
        }
        // 성공 status 의 해제도 같다(원인 없는 LINK_FAILED)
        val silent = ConnectionCoreState().manualReady().linkLost(status = 0, failure = null)
        assertEquals(ConnectionState.Ready, silent.state.shown())
        assertEquals("LINK_FAILED", silent.state.expire().state.failedCode())
    }

    @Test
    fun `S05_어댑터 이탈이 먼저 오면 뒤늦은 링크 해제는 원인을 바꾸지 않는다`() {
        val off = ConnectionCoreState().manualReady().adapterLost()
        assertEquals("ADAPTER_UNAVAILABLE", off.state.failedCode())

        val late = off.state.linkLost()
        assertEquals("ADAPTER_UNAVAILABLE", late.state.failedCode())
        assertNull(late.state.pendingLoss)
    }

    @Test
    fun `S06_창 안의 권한 상실은 PERMISSION_DENIED 로 끝낸다`() {
        val denied = ConnectionCoreState().manualReady().linkLost().state
            .adapterLost(AdapterState.Unauthorized(canRequest = false))
        assertEquals("PERMISSION_DENIED", denied.state.failedCode())
    }

    @Test
    fun `S07_창 동안 disconnect 는 Disconnecting 을 거쳐 해제 콜백 없이 Requested 로 끝나고 창 기한은 무시된다`() {
        val lost = ConnectionCoreState().manualReady().linkLost().state
        val staleDeadline = requireNotNull(lost.deadline)

        val step = ConnectionCore.step(lost, ConnectionEvent.DisconnectRequested(hasHandle = true, cleanupMillis))

        assertEquals(
            listOf(ConnectionStage.Disconnecting, ConnectionStage.Disconnected),
            step.effects.filterIsInstance<StageChanged>().map { it.stage },
        )
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), step.state.shown())
        assertNull(step.state.pendingLoss)
        assertFalse(ConnectionCore.step(step.state, LifecycleDeadline(staleDeadline, cleanupMillis)).accepted)
    }

    @Test
    fun `S08_창 동안 close 는 ClientClosed 로 끝내고 같은 generation 의 링크 콜백은 되풀이로 무시한다`() {
        val lost = ConnectionCoreState().manualReady().linkLost().state
        assertFalse(lost.linkLost().accepted)

        val closed = ConnectionCore.step(lost, ConnectionEvent.CloseRequested)
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), closed.state.shown())
        assertNull(closed.state.pendingLoss)
    }

    @Test
    fun `S09_Ready 전 단계의 링크 실패에는 창이 없다`() {
        val configuring = ConnectionCoreState().connecting().linkUp().discovered().state
        val step = configuring.linkLost()
        assertEquals("LINK_FAILED", step.state.failedCode())
        assertNull(step.state.pendingLoss)
    }

    @Test
    fun `S10_창이 닫힌 뒤 새 연결은 창을 물려받지 않는다`() {
        val elapsed = ConnectionCoreState().manualReady().linkLost().state.expire().state
        val next = elapsed.connecting()
        assertNull(next.pendingLoss)
    }
}
