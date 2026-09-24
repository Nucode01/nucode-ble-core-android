package kr.co.nucode.ble.harness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 하네스 자체가 production 모양 콜백으로 연결·해제·링크 끊김을 끝까지 돌리는지 확인한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessSmokeTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:F1")

    private fun failedCode(state: ConnectionState?): String? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    @Test
    fun `모든 콜백 도착 시 연결 Ready 후 사용자 해제`() = runTest {
        val h = harness()
        assertEquals(ConnectionState.Ready, h.connectToReady(id))

        h.disconnect(id)
        h.advancePastAllDeadlines()

        val states = h.states(id)
        val connecting = states.indexOfFirst { it is ConnectionState.Connecting }
        val ready = states.indexOf(ConnectionState.Ready)
        val disconnecting = states.indexOfFirst { it is ConnectionState.Disconnecting }
        assertTrue(states.toString(), connecting in 0 until ready && ready < disconnecting)
        assertTrue(h.fake.calls.toString(), h.fake.calls.containsAll(listOf("connect", "discover", "mtu", "disconnect")))
        // 알려진 결함: 2단계에서 Requested 로 바뀌어야 함
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Initial), states.last())
    }

    @Test
    fun `해제 콜백 누락 시 cleanup deadline 이 해제를 끝낸다`() = runTest {
        val h = harness(config = BleConfig(disconnectCleanupTimeoutMillis = 50))
        h.connectToReady(id)
        h.fake.onDisconnect = CallbackVariant.Missing

        h.disconnect(id)
        assertTrue(h.session(id).connectionState.value is ConnectionState.Disconnecting)
        h.advancePastAllDeadlines()

        assertTrue(h.states(id).toString(), h.states(id).last() is ConnectionState.Disconnected)
    }

    @Test
    fun `재연결 끔 상태에서 Ready 중 링크 끊김 status 8 은 LINK_FAILED`() = runTest {
        val h = harness()
        h.connectToReady(id)

        h.fake.linkLoss(id, status = 8)
        h.advancePastAllDeadlines()

        assertEquals(h.states(id).toString(), "LINK_FAILED", failedCode(h.states(id).last()))
    }
}
