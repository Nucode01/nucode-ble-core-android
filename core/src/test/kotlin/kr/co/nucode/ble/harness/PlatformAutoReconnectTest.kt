package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `BleConnectOptions(autoReconnect = true)` — 플랫폼 재연결 대기(파생 Recovering)와 그 대기의 조합
 * 시나리오(plan autoconnect-reconnect v2 task 1). fake 는 OS 재연결을 같은 handle(epoch)의
 * STATE_CONNECTED 로 흉내 낸다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformAutoReconnectTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:F3")
    private val serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val ref = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))

    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private val auto = BleConnectOptions(autoReconnect = true)

    private fun serviceSnapshot() = GattServiceSnapshot(
        serviceUuid = serviceUuid,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                instanceIndex = 0,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            ),
        ),
    )

    private fun history(h: Harness, userDisconnected: Boolean = false, pastAllDeadlines: Boolean = false, closedAt: Int? = null) =
        ConnectionHistory(
            states = h.states(id),
            actives = h.actives(id),
            timeline = h.timeline(id),
            connEvents = h.connEvents(id),
            userDisconnected = userDisconnected,
            pastAllDeadlines = pastAllDeadlines,
            autoReconnect = true,
            platformWaiting = h.core.registry.get(id)?.awaitingPlatformRelink ?: false,
            closedAt = closedAt,
        )

    private fun assertNoViolations(h: ConnectionHistory) {
        val violations = checkConnectionInvariants(h)
        if (violations.isNotEmpty()) fail("불변식 위반: $violations states=${h.states}")
    }

    /** autoReconnect 로 Ready → 링크 끊김(8) → Recovering 까지. 각 단계를 전제로 단언한다. */
    private fun TestScope.readyThenLinkLoss(h: Harness, subscribe: Boolean = false) {
        if (subscribe) h.fake.services = listOf(serviceSnapshot())
        h.connectToReady(id, auto)
        if (subscribe) {
            val result = h.subscribe(id, ref)
            if (result == null || result.isFailure) fail("전제 실패: subscribe 가 성공하지 않음 ($result)")
            if (h.session(id).activeSubscriptions.value.isEmpty()) fail("전제 실패: activeSubscriptions 비어 있음")
        }
        h.fake.linkLoss(id, 8)
        runCurrent()
        val state = h.session(id).connectionState.value
        if (state !is ConnectionState.Recovering) fail("전제 실패: 링크 끊김 뒤 Recovering 아님 ${h.states(id)}")
    }

    private fun connectCalls(h: Harness) = h.fake.calls.count { it == "connect" }

    @Test
    fun `a 링크 끊김은 Recovering(LINK_FAILED)이고 handle 을 닫지 않는다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        val state = h.session(id).connectionState.value as ConnectionState.Recovering
        assertEquals("LINK_FAILED", state.lastFailure.code.value)
        assertTrue("OS 대기용 handle 을 닫았다: ${h.fake.calls}", "close" !in h.fake.calls)
        // 라이브러리 시간표는 없다 — 대기는 deadline 으로 끝나지 않는다.
        h.advancePastAllDeadlines()
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("deadline 뒤 Recovering 이 끝났다 ${h.states(id)}")
        assertEquals("재연결 시작이 나갔다: ${h.fake.calls}", 1, connectCalls(h))
        assertNoViolations(history(h))
        h.close()
    }

    @Test
    fun `a2 플랫폼이 다시 붙이지 않으면 모든 deadline 뒤에도 Recovering 으로 남고 I6 를 지킨다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        h.advancePastAllDeadlines()
        val last = h.states(id).last()
        assertTrue("최종 상태가 Recovering 아님: ${h.states(id)}", last is ConnectionState.Recovering)
        assertEquals("재연결 시작이 나갔다: ${h.fake.calls}", 1, connectCalls(h))
        assertEquals(null, checkI6(history(h, pastAllDeadlines = true)))
        // 대조: autoReconnect 세션이 아닌 기록의 최종 Recovering 은 여전히 멈춤이다.
        assertEquals("I6", checkI6(history(h, pastAllDeadlines = true).copy(autoReconnect = false))?.id)
        assertNoViolations(history(h, pastAllDeadlines = true))
        h.close()
    }

    @Test
    fun `b 같은 handle 의 플랫폼 재연결은 Ready 로 가고 구독을 복원한다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h, subscribe = true)
        if (h.session(id).activeSubscriptions.value.isNotEmpty()) fail("전제 실패: Recovering 인데 activeSubscriptions 가 남음")
        val descriptorsBefore = h.fake.calls.count { it == "descriptor" }
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("플랫폼 재연결이 Ready 에 도달") { h.session(id).connectionState.value == ConnectionState.Ready }
        runCurrent()
        assertTrue("재연결 뒤 CCCD write 없음 ${h.fake.calls}", h.fake.calls.count { it == "descriptor" } > descriptorsBefore)
        assertTrue("activeSubscriptions 에 $ref 없음", ref in h.session(id).activeSubscriptions.value)
        assertEquals("새 connectGatt 가 나갔다: ${h.fake.calls}", 1, connectCalls(h))
        assertNoViolations(history(h))
        h.close()
    }

    @Test
    fun `c Recovering 중 disconnect 는 Requested 로 끝나고 대기를 취소한다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        val callsBefore = h.fake.calls.size
        val connectingBefore = h.states(id).count { it == ConnectionState.Connecting }
        h.disconnect(id)
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.Requested),
            h.session(id).connectionState.value,
        )
        val after = h.fake.calls.drop(callsBefore)
        assertTrue("OS 대기 취소(disconnect/close) 없음: $after", "disconnect" in after && "close" in after)
        // 닫힌 handle 의 늦은 재연결은 받지 않는다.
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0, fromEpoch = h.fake.epoch - 1)
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.advancePastAllDeadlines()
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.Requested),
            h.session(id).connectionState.value,
        )
        assertEquals("해제 뒤 generation 이 열렸다: ${h.states(id)}", connectingBefore, h.states(id).count { it == ConnectionState.Connecting })
        assertNoViolations(history(h, userDisconnected = true, pastAllDeadlines = true))
        h.close()
    }

    @Test
    fun `d Recovering 중 client close 는 ClientClosed 로 끝난다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        val at = h.states(id).size
        h.close()
        // 닫힌 클라이언트에서는 session() 을 부를 수 없다 — 기록의 마지막 게시를 본다.
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            h.states(id).last(),
        )
        assertTrue("handle 을 닫지 않았다: ${h.fake.calls}", "close" in h.fake.calls)
        assertEquals("handle 을 두 번 닫았다: ${h.fake.calls}", 1, h.fake.calls.count { it == "close" })
        assertNoViolations(history(h, closedAt = at))
    }

    @Test
    fun `e Recovering 중 어댑터 꺼짐은 AdapterUnavailable 로 끝나고 복귀 시 한 번 다시 건다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        val off = h.session(id).connectionState.value
        val reason = (off as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed
            ?: fail("어댑터 꺼짐 뒤 Disconnected(Failed) 아님: ${h.states(id)}").let { return@runTest }
        assertEquals("ADAPTER_UNAVAILABLE", reason.failure.code.value)
        assertTrue("OS 대기 handle 을 닫지 않았다: ${h.fake.calls}", "close" in h.fake.calls)

        val connectsBefore = connectCalls(h)
        h.fake.setAdapter(AdapterState.Ready)
        h.waitUntil("어댑터 복귀 재연결이 Ready 에 도달") { h.session(id).connectionState.value == ConnectionState.Ready }
        h.advancePastAllDeadlines()
        assertEquals("어댑터 복귀 connect 횟수: ${h.fake.calls}", 1, connectCalls(h) - connectsBefore)
        assertEquals(ConnectionState.Ready, h.session(id).connectionState.value)
        assertNoViolations(history(h, pastAllDeadlines = true))
        h.close()
    }

    @Test
    fun `e2 어댑터 꺼짐 뒤 사용자가 해제한 autoReconnect 세션은 복귀 때 다시 걸지 않는다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Disconnected) fail("전제 실패: ${h.states(id)}")
        h.disconnect(id)
        val connectsBefore = connectCalls(h)
        h.fake.setAdapter(AdapterState.Ready)
        h.advancePastAllDeadlines()
        assertEquals("사용자 해제 세션을 다시 걸었다: ${h.fake.calls}", 0, connectCalls(h) - connectsBefore)
        h.close()
    }

    /**
     * docs/v1 connection-lifecycle "duplicate connect": 플랫폼이 autoConnect 를 소유하는 동안의 connect() 는
     * 같은 handle 위에 두 번째 연결을 시작하지 않고 기존 대기를 유지한다(같은 옵션이면 no-op).
     */
    @Test
    fun `f Recovering 중 같은 옵션 connect 는 대기를 유지하고 두 번째 연결을 걸지 않는다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        val connectsBefore = connectCalls(h)
        val callsBefore = h.fake.calls.size
        h.connect(id, auto)
        runCurrent()
        assertEquals("두 번째 connectGatt: ${h.fake.calls}", connectsBefore, connectCalls(h))
        assertTrue("대기 handle 을 건드렸다: ${h.fake.calls.drop(callsBefore)}", h.fake.calls.drop(callsBefore).isEmpty())
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("대기가 끝났다 ${h.states(id)}")
        // 대기는 그대로라 플랫폼 재연결을 받는다.
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("플랫폼 재연결이 Ready 에 도달") { h.session(id).connectionState.value == ConnectionState.Ready }
        assertNoViolations(history(h))
        h.close()
    }

    @Test
    fun `g autoReconnect 첫 연결에는 link deadline 이 없고 disconnect 로 끝난다`() = runTest {
        val h = harness(config)
        h.fake.onConnect = CallbackVariant.Missing
        val first = h.connect(id, auto)
        if (first != ConnectionState.Connecting) fail("전제 실패: Connecting 아님 $first")
        advanceTimeBy(config.linkTimeoutMillis * 5)
        runCurrent()
        assertEquals("link deadline 이 발화했다: ${h.states(id)}", ConnectionState.Connecting, h.session(id).connectionState.value)
        h.disconnect(id)
        h.advancePastAllDeadlines()
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.Requested),
            h.session(id).connectionState.value,
        )
        assertNoViolations(history(h, userDisconnected = true, pastAllDeadlines = true))
        h.close()
    }

    @Test
    fun `h Recovering 중 늦은 STATE_DISCONNECTED 는 generation 을 열지 않는다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        val connectingBefore = h.states(id).count { it == ConnectionState.Connecting }
        val eventsBefore = h.connEvents(id).size
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 8)
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0)
        runCurrent()
        assertEquals(connectingBefore, h.states(id).count { it == ConnectionState.Connecting })
        assertEquals("늦은 콜백이 상태를 게시했다: ${h.connEvents(id).drop(eventsBefore)}", eventsBefore, h.connEvents(id).size)
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("대기가 끝났다 ${h.states(id)}")
        assertNoViolations(history(h))
        h.close()
    }

    /**
     * review H1 — 플랫폼 재연결 뒤 복원 CCCD 가 타임아웃이면 operation invalidation 이 handle 을 닫는다.
     * 닫힌 handle 로는 OS 대기가 없으므로 Recovering 이 아니라 Disconnected(Failed(고정 원인)) 다 —
     * 먼저 고정된 원인은 복원 write 의 operation 타임아웃(OperationInvalidated, first wins)이다.
     * 뒤이은 disconnect() 도 Recovering 을 되살리지 않고 원인도 바꾸지 않는다(종결된 generation, first wins).
     */
    @Test
    fun `i 재연결 뒤 복원 CCCD 타임아웃은 handle 을 닫고 Disconnected(Failed) 로 끝난다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h, subscribe = true)
        h.fake.onDescriptorWrite = CallbackVariant.Missing
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.advancePastAllDeadlines()
        val ended = h.session(id).connectionState.value
        val reason = (ended as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed
            ?: fail("복원 타임아웃 뒤 Disconnected(Failed) 아님: ${h.states(id)}").let { return@runTest }
        assertEquals("OPERATION_TIMEOUT", reason.failure.code.value)
        assertTrue("handle 을 닫지 않았다: ${h.fake.calls}", "close" in h.fake.calls)
        // 이미 종결된(대기 없는) generation 의 disconnect() 는 원인을 바꾸지 않는다(first wins) — Requested 가 아니라
        // 고정된 Failed 가 남고, Recovering 으로 돌아가지도 않는다.
        h.disconnect(id)
        h.advancePastAllDeadlines()
        assertTrue("disconnect 뒤 Recovering: ${h.states(id)}", h.states(id).last() !is ConnectionState.Recovering)
        assertEquals(ended, h.session(id).connectionState.value)
        assertNoViolations(history(h, pastAllDeadlines = true))
        h.close()
    }

    /**
     * review H2 — 사용자 해제(Disconnecting, 콜백 유실) 중 어댑터 꺼짐은 먼저 고정된 원인(UserRequested)으로
     * 끝난다. 최종 원인이 AdapterUnavailable 이 아니므로 어댑터 복귀 때 다시 걸지 않는다.
     */
    @Test
    fun `j 사용자 해제 중 어댑터 꺼짐은 복귀 때 다시 걸지 않는다`() = runTest {
        val h = harness(config)
        h.connectToReady(id, auto)
        h.fake.onDisconnect = CallbackVariant.Missing
        h.disconnect(id)
        if (h.session(id).connectionState.value != ConnectionState.Disconnecting) fail("전제 실패: Disconnecting 아님 ${h.states(id)}")
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.Requested),
            h.session(id).connectionState.value,
        )
        val connectsBefore = connectCalls(h)
        h.fake.setAdapter(AdapterState.Ready)
        runCurrent()
        runCurrent()
        assertEquals("사용자 해제 세션을 어댑터 복귀 때 다시 걸었다: ${h.fake.calls}", 0, connectCalls(h) - connectsBefore)
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.Requested),
            h.session(id).connectionState.value,
        )
        h.close()
    }

    /**
     * review H3(결정 A) — 플랫폼 재연결 generation 이 Ready 전에 실패하면(탐색 status 133) 첫 연결 실패와
     * 같은 규칙으로 handle 을 닫고 Disconnected(Failed) 로 끝난다. 대기로 돌아가지 않으므로 뒤이은
     * STATE_CONNECTED 는 새 generation 을 열지 않는다.
     */
    @Test
    fun `k 재연결 뒤 탐색 실패는 handle 을 닫고 Disconnected(Failed) 로 끝난다`() = runTest {
        val h = harness(config)
        readyThenLinkLoss(h)
        h.fake.onDiscover = CallbackVariant.Arrive(status = 133)
        val closesBefore = h.fake.calls.count { it == "close" }
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.advancePastAllDeadlines()
        val ended = h.session(id).connectionState.value
        if ((ended as? ConnectionState.Disconnected)?.reason !is BleDisconnectReason.Failed) {
            fail("재연결 탐색 실패 뒤 Disconnected(Failed) 아님: ${h.states(id)}")
        }
        assertEquals("handle close 횟수: ${h.fake.calls}", 1, h.fake.calls.count { it == "close" } - closesBefore)
        val connectingBefore = h.states(id).count { it == ConnectionState.Connecting }
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.advancePastAllDeadlines()
        assertEquals("실패 뒤 generation 이 열렸다: ${h.states(id)}", connectingBefore, h.states(id).count { it == ConnectionState.Connecting })
        assertEquals(ended, h.session(id).connectionState.value)
        assertNoViolations(history(h, pastAllDeadlines = true))
        h.close()
    }
}
