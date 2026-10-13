package kr.co.nucode.ble.internal.gatt

import android.bluetooth.BluetoothProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticSeverity
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.Harness
import kr.co.nucode.ble.harness.harness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 첫 직접 연결의 빠른 133 재시도(contract/states.md §2)의 런타임 배선 — Core 가 낸 `RetryLink` 를
 * [LinkEffects] 가 실행해 handle 을 닫고 같은 generation 으로 driver.connect 를 다시 건다. 기본 설정(link 15초)이고
 * 133 은 요청 직후에 온다. 5000ms 경계는 순수 Core 시험(ConnectionRetryTest)이 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectRetryWiringTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:61")

    private fun Harness.connectCalls() = fake.calls.count { it == "connect" }

    /** 첫 Connecting 뒤 공개 상태 기록 — 그 사이에 Disconnected 가 끼면 재시도가 아니라 종결이다. */
    private fun Harness.afterFirstConnecting() = states(id).dropWhile { it != ConnectionState.Connecting }

    private fun Harness.retryDiagnostics() =
        events.filterIsInstance<OfficialBleClientEvent.Diagnostic>()
            .filter { it.severity == Severity.Warning && it.failure.code.value == "LINK_FAILED" }

    @Test
    fun `빠른 133 뒤 handle 을 닫고 다시 걸어 Connecting 만 보인 채 Ready 에 닿는다`() = runTest {
        val h = harness()
        h.fake.connectFailStatuses += 133

        h.connectToReady(id)

        // fake 의 connect 는 null handle 을 돌려주므로 ReleaseStaleHandle 의 driver.close 는 보이지 않는다(닫을 handle 이 없다).
        assertEquals("driver 호출: ${h.fake.calls}", listOf("connect", "connect", "discover"), h.fake.calls.take(3))
        assertEquals(listOf(false, false), h.fake.connectAutoConnect)
        val after = h.afterFirstConnecting()
        assertFalse("중간 Disconnected: ${h.states(id)}", after.any { it is ConnectionState.Disconnected })
        assertEquals("재시도 진단: ${h.events}", 1, h.retryDiagnostics().size)
        h.close()
    }

    @Test
    fun `재시도 진단의 logger 기록은 요청 뒤 경과 ms 를 싣고 공개 이벤트는 그대로다`() = runTest {
        val logged = CopyOnWriteArrayList<DiagnosticRecord>()
        val retryLogged = CountDownLatch(1)
        val h = harness(
            config = BleConfig(
                logger = BleLogger { record ->
                    logged += record
                    if (record.failure.code.value == "LINK_FAILED") retryLogged.countDown()
                },
            ),
        )
        h.fake.connectFailStatuses += 133

        h.connectToReady(id)

        assertTrue("logger 기록: $logged", retryLogged.await(2, TimeUnit.SECONDS))
        val record = logged.single { it.failure.code.value == "LINK_FAILED" }
        assertEquals(DiagnosticSeverity.Warning, record.severity)
        val elapsed = record.values[LinkEffects.LINK_REQUEST_ELAPSED_MS] as DiagnosticValue.Number
        assertTrue("경과: $elapsed", elapsed.value >= 0)
        val public = h.retryDiagnostics().single()
        assertEquals(OfficialBleClientEvent.Diagnostic(Severity.Warning, public.failure), public)
        h.close()
    }

    @Test
    fun `시도 3번이 모두 빠른 133 이면 connect 3번 뒤 LINK_FAILED 로 끝난다`() = runTest {
        val h = harness()
        repeat(3) { h.fake.connectFailStatuses += 133 }

        h.connect(id)
        h.waitUntil("Disconnected") { h.session(id).connectionState.value is ConnectionState.Disconnected }

        assertEquals(3, h.connectCalls())
        val reason = (h.session(id).connectionState.value as ConnectionState.Disconnected).reason
        assertEquals("LINK_FAILED", (reason as BleDisconnectReason.Failed).failure.code.value)
        assertTrue(ConnectionState.Ready !in h.states(id))
        assertEquals("재시도 진단: ${h.events}", 2, h.retryDiagnostics().size)
        h.close()
    }

    @Test
    fun `재시도 중 disconnect 는 Requested 로 끝나고 더 걸지 않는다`() = runTest {
        val h = harness()
        h.fake.onConnect = CallbackVariant.Late(1_000)
        h.fake.connectFailStatuses += 133

        h.connect(id)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("재시도 전제: ${h.fake.calls}", 2, h.connectCalls())
        assertEquals(ConnectionState.Connecting, h.session(id).connectionState.value)

        h.disconnect(id)
        h.advancePastAllDeadlines()

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.Requested), h.session(id).connectionState.value)
        assertEquals(2, h.connectCalls())
        assertTrue(ConnectionState.Ready !in h.states(id))
        h.close()
    }

    @Test
    fun `재시도 connect 가 권한 거절로 던지면 PERMISSION_DENIED 로 끝나고 더 걸지 않는다`() = runTest {
        val h = harness()
        h.fake.onConnect = CallbackVariant.Late(1_000)
        h.fake.connectFailStatuses += 133

        h.connect(id)
        // 첫 시도는 이미 걸렸다. 133 이 오기 전에 걸어 재시도의 connect 만 던지게 한다.
        h.fake.connectThrows = SecurityException("BLUETOOTH_CONNECT")
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals("driver 호출: ${h.fake.calls}", 2, h.connectCalls())
        val reason = (h.session(id).connectionState.value as ConnectionState.Disconnected).reason
        assertEquals("PERMISSION_DENIED", (reason as BleDisconnectReason.Failed).failure.code.value)
        assertTrue(ConnectionState.Ready !in h.states(id))
        h.advancePastAllDeadlines()
        assertEquals(2, h.connectCalls())
        h.close()
    }

    @Test
    fun `재시도 뒤 닫힌 handle 의 늦은 콜백은 상태를 건드리지 않는다`() = runTest {
        val h = harness()
        h.fake.onConnect = CallbackVariant.Late(1_000)
        h.fake.connectFailStatuses += 133
        h.connect(id)
        val firstEpoch = h.fake.epoch
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, h.connectCalls())

        h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 133, fromEpoch = firstEpoch)
        runCurrent()
        assertEquals(ConnectionState.Connecting, h.session(id).connectionState.value)

        h.waitUntil("Ready") { h.session(id).connectionState.value == ConnectionState.Ready }
        assertEquals(2, h.connectCalls())
        h.close()
    }
}
