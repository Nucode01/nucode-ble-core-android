package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.harness
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.gatt.GattConnectionLifecycle
import kr.co.nucode.ble.internal.gatt.GattOperationHooks
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 종료 원인은 coordinator 가 generation 당 한 번 고정하고, 공개 Disconnected 이유는 그 값에서만 파생된다. */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCauseLifecycleTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:F2")

    private fun failure(state: ConnectionState?): BleFailure? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure

    @Test
    fun `사용자 해제 후 성공 콜백이면 Requested`() = runTest {
        val h = harness()
        h.connectToReady(id)

        h.disconnect(id)
        h.advancePastAllDeadlines()

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), h.states(id).last())
    }

    @Test
    fun `사용자 해제 후 실패 status 콜백이어도 Requested 이고 실패는 Diagnostic 으로 남는다`() = runTest {
        val h = harness()
        h.connectToReady(id)
        h.fake.onDisconnect = CallbackVariant.Arrive(status = 8)

        h.disconnect(id)
        h.advancePastAllDeadlines()

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), h.states(id).last())
        assertTrue(
            h.events.toString(),
            h.events.any {
                it is BleEvent.Diagnostic && it.failure.code.value == "LINK_FAILED" &&
                    (it.failure.platformDetail as? PlatformDetail.Android)?.statusCode == 8
            },
        )
    }

    @Test
    fun `링크 deadline 후 성공 해제 콜백이면 LINK_TIMEOUT`() = runTest {
        val h = harness()
        h.fake.onConnect = CallbackVariant.Missing
        h.fake.onDisconnect = CallbackVariant.Arrive(status = 0)

        h.connect(id)
        h.advancePastAllDeadlines()

        assertEquals(h.states(id).toString(), "LINK_TIMEOUT", failure(h.states(id).last())?.code?.value)
    }

    @Test
    fun `Ready 중 원인 없는 status 0 해제는 Initial 이 아니라 LINK_FAILED statusCode 0`() = runTest {
        val h = harness()
        h.connectToReady(id)

        h.fake.linkLoss(id, status = 0)
        h.advancePastAllDeadlines()

        val last = h.states(id).last()
        val failure = failure(last)
        assertEquals(h.states(id).toString(), "LINK_FAILED", failure?.code?.value)
        assertEquals(0, (failure?.platformDetail as? PlatformDetail.Android)?.statusCode)
        assertTrue(
            h.events.toString(),
            h.events.any { it is BleEvent.Diagnostic && it.failure == failure },
        )
    }

    @Test
    fun `해제 콜백 누락 시 cleanup deadline 은 고정된 원인으로 끝난다`() = runTest {
        val h = harness(config = BleConfig(disconnectCleanupTimeoutMillis = 50))
        h.fake.onConnect = CallbackVariant.Missing
        h.fake.onDisconnect = CallbackVariant.Missing

        h.connect(id)
        h.advancePastAllDeadlines()

        assertEquals(h.states(id).toString(), "LINK_TIMEOUT", failure(h.states(id).last())?.code?.value)
    }

    private fun testFailure(code: String) = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode(code),
        phase = FailurePhase.Connect,
        retryability = Retryability.TransientMayHelp,
    )

    /** 소진 질의가 항상 [exhausted] 를 돌려주는 lifecycle(Reconnector 없이 선판정 경로만 본다). */
    private fun kotlinx.coroutines.test.TestScope.directLifecycle(
        registry: SessionRegistry,
        events: MutableList<BleEvent>,
        exhausted: BleFailure,
    ) = GattConnectionLifecycle(
        registry = registry,
        emit = { events += it },
        scope = this,
        driver = NoopGattDriver(),
        readinessTimeouts = ConnectionReadinessTimeouts(linkMillis = 100, discoveryMillis = 100, configurationMillis = 100),
        rediscovery = null,
        linkCacheWarm = null,
        operations = object : GattOperationHooks {
            override fun invalidateAll(session: Session, failure: BleFailure) = Unit
            override fun beginGenerationIfPoisoned(session: Session) = Unit
        },
        reconnectExhaustion = { _, _ -> exhausted },
    )

    @Test
    fun `재연결 백오프 중 close 는 Recovering 을 Disconnected(ClientClosed) 로 끝낸다`() = runTest {
        val h = harness(reconnect = BleReconnectPolicy.ExponentialBackoff(2, 100, 200))
        h.connectToReady(id)
        h.fake.linkLoss(id, status = 8)
        runCurrent()
        assertTrue(h.states(id).toString(), h.states(id).last() is ConnectionState.Recovering)
        val retainedBefore = h.fake.calls.count { it == "retainedConnect" }

        h.close()
        h.advancePastAllDeadlines()

        val closed = ConnectionState.Disconnected(BleDisconnectReason.ClientClosed)
        assertEquals(h.states(id).toString(), closed, h.states(id).last())
        // 닫힌 런타임은 이벤트 버스를 닫는다(BleRuntime.emit 의 closed 게이트) — 공개 상태로만 확인한다.
        assertEquals(1, h.states(id).count { it == closed })
        assertEquals(retainedBefore, h.fake.calls.count { it == "retainedConnect" })
        assertEquals(closed, h.core.officialSnapshot().sessions.single().connectionState)
    }

    @Test
    fun `재연결 백오프 중 adapter off 는 Recovering 을 Disconnected(Failed ADAPTER_UNAVAILABLE) 로 끝낸다`() = runTest {
        val h = harness(reconnect = BleReconnectPolicy.ExponentialBackoff(2, 100, 200))
        h.connectToReady(id)
        h.fake.linkLoss(id, status = 8)
        runCurrent()
        assertTrue(h.states(id).toString(), h.states(id).last() is ConnectionState.Recovering)
        val retainedBefore = h.fake.calls.count { it == "retainedConnect" }
        val eventsBefore = h.events.size

        h.fake.setAdapter(AdapterState.PoweredOff)
        h.advancePastAllDeadlines()

        val last = h.states(id).last()
        assertEquals(h.states(id).toString(), "ADAPTER_UNAVAILABLE", failure(last)?.code?.value)
        assertEquals(1, h.states(id).count { failure(it)?.code?.value == "ADAPTER_UNAVAILABLE" })
        assertEquals(retainedBefore, h.fake.calls.count { it == "retainedConnect" })
        assertTrue(h.core.registry.get(id)!!.suppressReconnect)
        assertTrue(
            h.events.drop(eventsBefore).toString(),
            h.events.drop(eventsBefore).any {
                it is BleEvent.Diagnostic && it.failure.code.value == "ADAPTER_UNAVAILABLE"
            },
        )
    }

    @Test
    fun `재시도 없이 이미 실패로 끝난 세션은 close 뒤에도 원래 실패를 유지한다`() = runTest {
        val h = harness()
        h.connectToReady(id)
        h.fake.linkLoss(id, status = 8)
        h.advancePastAllDeadlines()
        val failed = h.states(id).last()
        assertEquals("LINK_FAILED", failure(failed)?.code?.value)

        h.close()

        assertEquals(failed, h.states(id).last())
    }

    @Test
    fun `소진 선판정은 client close 원인을 소진으로 바꾸지 않는다`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(id)
        val events = mutableListOf<BleEvent>()
        val lifecycle = directLifecycle(registry, events, testFailure("RECONNECT_EXHAUSTED"))
        session.connectionCoordinator.beginConnection(this, 100) {}

        lifecycle.close(session)

        assertEquals(TerminalCause(TerminalCleanupReason.ClientClosed, null), session.connectionCoordinator.terminalCause)
        assertTrue(events.toString(), events.none { it is BleEvent.Diagnostic })
    }

    @Test
    fun `operation 무효화 종결은 원인 Diagnostic 을 소진 Diagnostic 보다 먼저 낸다`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(id)
        val events = mutableListOf<BleEvent>()
        val exhausted = testFailure("RECONNECT_EXHAUSTED")
        val lifecycle = directLifecycle(registry, events, exhausted)
        val cause = testFailure("OPERATION_TIMEOUT")
        session.connectionCoordinator.beginConnection(this, 100) {}

        lifecycle.terminateForOperationInvalidation(session, cause, suppressReconnect = false)

        val diagnostics = events.filterIsInstance<BleEvent.Diagnostic>().map { it.failure }
        assertEquals(listOf(cause, exhausted), diagnostics)
        assertEquals(
            TerminalCause(TerminalCleanupReason.OperationInvalidated, exhausted),
            session.connectionCoordinator.terminalCause,
        )
    }
}
