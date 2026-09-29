package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.harness
import kr.co.nucode.ble.internal.gatt.GattConnectionLifecycle
import kr.co.nucode.ble.internal.gatt.GattOperationHooks
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.core.connection.TerminalCause
import kr.co.nucode.ble.internal.core.connection.TerminalCleanupReason
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
                it is OfficialBleClientEvent.Diagnostic && it.failure.code.value == "LINK_FAILED" &&
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
            h.events.any { it is OfficialBleClientEvent.Diagnostic && it.failure == failure },
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

    @Test
    fun `autoReconnect 대기 중 close 는 Recovering 을 Disconnected(ClientClosed) 로 끝낸다`() = runTest {
        val h = harness(options = BleConnectOptions(autoReconnect = true))
        h.connectToReady(id)
        h.fake.linkLoss(id, status = 8)
        runCurrent()
        assertTrue(h.states(id).toString(), h.states(id).last() is ConnectionState.Recovering)
        val connectsBefore = h.fake.calls.count { it == "connect" }

        h.close()
        h.advancePastAllDeadlines()

        val closed = ConnectionState.Disconnected(BleDisconnectReason.ClientClosed)
        assertEquals(h.states(id).toString(), closed, h.states(id).last())
        // 닫힌 런타임은 이벤트 버스를 닫는다(BleRuntime.emit 의 closed 게이트) — 공개 상태로만 확인한다.
        assertEquals(1, h.states(id).count { it == closed })
        assertEquals(connectsBefore, h.fake.calls.count { it == "connect" })
        assertEquals(closed, h.core.officialSnapshot().sessions.single().connectionState)
    }

    @Test
    fun `autoReconnect 대기 중 adapter off 는 Recovering 을 Disconnected(Failed ADAPTER_UNAVAILABLE) 로 끝낸다`() = runTest {
        val h = harness(options = BleConnectOptions(autoReconnect = true))
        h.connectToReady(id)
        h.fake.linkLoss(id, status = 8)
        runCurrent()
        assertTrue(h.states(id).toString(), h.states(id).last() is ConnectionState.Recovering)
        val connectsBefore = h.fake.calls.count { it == "connect" }
        val eventsBefore = h.events.size

        h.fake.setAdapter(AdapterState.PoweredOff)
        h.advancePastAllDeadlines()

        val last = h.states(id).last()
        assertEquals(h.states(id).toString(), "ADAPTER_UNAVAILABLE", failure(last)?.code?.value)
        assertEquals(1, h.states(id).count { failure(it)?.code?.value == "ADAPTER_UNAVAILABLE" })
        assertEquals(connectsBefore, h.fake.calls.count { it == "connect" })
        assertTrue(h.core.registry.get(id)!!.suppressReconnect)
        assertTrue(
            h.events.drop(eventsBefore).toString(),
            h.events.drop(eventsBefore).any {
                it is OfficialBleClientEvent.Diagnostic && it.failure.code.value == "ADAPTER_UNAVAILABLE"
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

}
