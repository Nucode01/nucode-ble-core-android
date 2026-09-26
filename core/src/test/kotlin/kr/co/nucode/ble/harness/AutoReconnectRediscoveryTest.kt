package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.internal.model.ScanObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * plan cross-review-fixes task 1 — 운영 배선 재발견(`scannerRediscovery = true`) 위의 autoReconnect 연결 경로.
 * HIGH-1(재발견 NotFound/Unavailable → Diagnostic + OS 대기), MED-1(어댑터 복귀 재연결 시작 실패 Diagnostic),
 * LOW-1(재발견 중 세션 종결 → connect() throw), LOW-4(재발견 중 disconnect → 재발견 즉시 종료).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoReconnectRediscoveryTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:41")

    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private val auto = BleConnectOptions(autoReconnect = true)

    private fun observation() = ScanObservation(id, -50, AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null))

    private fun connectCalls(h: Harness) = h.fake.calls.count { it == "connect" }

    private fun diagnostics(h: Harness, code: String) =
        h.events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().filter { it.failure.code.value == code }

    /** 공개 connect 를 걸고 결과(throw 여부)를 잡는다. */
    private fun TestScope.connectCatching(h: Harness, options: BleConnectOptions): Deferred<Result<Unit>> {
        val session = h.session(id)
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.connect(options) } }
        runCurrent()
        return job
    }

    private fun failureCode(result: Result<Unit>): String? = (result.exceptionOrNull() as? BleException)?.failure?.code?.value

    @Test
    fun `a cold autoReconnect 첫 연결에서 광고가 없으면 PERIPHERAL_NOT_FOUND Diagnostic 뒤 OS 대기로 Connecting 에 남는다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        h.fake.onConnect = CallbackVariant.Missing
        val result = connectCatching(h, auto)
        assertEquals("재발견 스캔이 시작되지 않았다", 1, h.fake.scanStarts.size)
        h.advancePastAllDeadlines()

        val diag = diagnostics(h, "PERIPHERAL_NOT_FOUND")
        assertEquals("PERIPHERAL_NOT_FOUND Diagnostic: ${h.events}", 1, diag.size)
        assertEquals(Severity.Warning, diag.single().severity)
        assertEquals("autoConnect 연결이 나가지 않았다: ${h.fake.calls}", 1, connectCalls(h))
        assertEquals("OS 대기 중 Connecting 이 아니다: ${h.states(id)}", ConnectionState.Connecting, h.session(id).connectionState.value)
        assertTrue("connect() 가 실패했다: ${result.await()}", result.await().isSuccess)

        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("OS 가 붙인 뒤 Ready") { h.session(id).connectionState.value == ConnectionState.Ready }
        h.close()
    }

    @Test
    fun `b 어댑터 복귀 재연결은 재발견을 거치고 광고가 없으면 Diagnostic 뒤 OS 대기로 Connecting 에 남는다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        h.connect(id, auto)
        h.fake.scanStarts.last().result(observation())
        h.waitUntil("첫 연결 Ready") { h.session(id).connectionState.value == ConnectionState.Ready }
        h.fake.linkLoss(id, 8)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: ${h.states(id)}")

        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        h.fake.onConnect = CallbackVariant.Missing
        val scansBefore = h.fake.scanStarts.size
        h.fake.setAdapter(AdapterState.Ready)
        runCurrent()
        assertEquals("복귀 재연결이 재발견을 거치지 않았다", scansBefore + 1, h.fake.scanStarts.size)
        h.advancePastAllDeadlines()

        assertEquals("PERIPHERAL_NOT_FOUND Diagnostic: ${h.events}", 1, diagnostics(h, "PERIPHERAL_NOT_FOUND").size)
        assertEquals("OS 대기 중 Connecting 이 아니다: ${h.states(id)}", ConnectionState.Connecting, h.session(id).connectionState.value)
        h.close()
    }

    @Test
    fun `c cold autoReconnect 첫 연결에서 재발견 스캔을 못 쓰면 그 실패 Diagnostic 뒤 OS 대기로 Connecting 에 남는다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        h.fake.onConnect = CallbackVariant.Missing
        h.fake.scanStartFailure = FailureMapper.scanStartFailed()
        val result = connectCatching(h, auto)
        h.advancePastAllDeadlines()

        assertEquals("SCAN_START_FAILED Diagnostic: ${h.events}", 1, diagnostics(h, "SCAN_START_FAILED").size)
        assertEquals("autoConnect 연결이 나가지 않았다: ${h.fake.calls}", 1, connectCalls(h))
        assertEquals("OS 대기 중 Connecting 이 아니다: ${h.states(id)}", ConnectionState.Connecting, h.session(id).connectionState.value)
        assertTrue("connect() 가 실패했다: ${result.await()}", result.await().isSuccess)
        h.close()
    }

    @Test
    fun `d autoReconnect 가 아닌 cold 연결의 NotFound 는 PERIPHERAL_NOT_FOUND 로 던지고 Disconnected(Failed) 로 끝난다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        val result = connectCatching(h, BleConnectOptions())
        h.advancePastAllDeadlines()

        assertEquals("PERIPHERAL_NOT_FOUND", failureCode(result.await()))
        val reason = (h.session(id).connectionState.value as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed
            ?: fail("Disconnected(Failed) 아님: ${h.states(id)}").let { return@runTest }
        assertEquals("PERIPHERAL_NOT_FOUND", reason.failure.code.value)
        assertEquals("driver connect 가 나갔다: ${h.fake.calls}", 0, connectCalls(h))
        h.close()
    }

    @Test
    fun `e 재발견 대기 중 어댑터 꺼짐이면 connect() 는 ADAPTER_UNAVAILABLE 로 던진다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        val result = connectCatching(h, BleConnectOptions())
        assertEquals(ConnectionState.Connecting, h.session(id).connectionState.value)
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        assertTrue("connect() 가 아직 안 끝났다", result.isCompleted)
        assertEquals("ADAPTER_UNAVAILABLE", failureCode(result.await()))
        assertEquals("driver connect 가 나갔다: ${h.fake.calls}", 0, connectCalls(h))
        h.close()
    }

    @Test
    fun `e2 재발견 대기 중 close 면 connect() 는 CLIENT_CLOSED 로 던진다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        val result = connectCatching(h, auto)
        h.client.close()
        runCurrent()
        assertTrue("connect() 가 아직 안 끝났다", result.isCompleted)
        assertEquals("CLIENT_CLOSED", failureCode(result.await()))
        assertEquals("driver connect 가 나갔다: ${h.fake.calls}", 0, connectCalls(h))
        h.close()
    }

    @Test
    fun `f 재발견 대기 중 disconnect 는 재발견을 즉시 끝내고 Disconnected(Requested) 뒤 새 connect 를 받는다`() = runTest {
        val h = harness(config, scannerRediscovery = true)
        val result = connectCatching(h, BleConnectOptions())
        assertEquals(1, h.fake.scanStarts.size)
        h.disconnect(id)
        // 시간을 흘리지 않는다 — rediscoveryTimeoutMillis 를 기다리면 안 된다.
        runCurrent()
        assertEquals("플랫폼 스캔이 멈추지 않았다", 1, h.fake.scanStops)
        val reason = (h.session(id).connectionState.value as? ConnectionState.Disconnected)?.reason
        assertEquals("Disconnected(Requested) 아님: ${h.states(id)}", BleDisconnectReason.Requested, reason)
        assertTrue("connect() 가 아직 안 끝났다", result.isCompleted)
        assertEquals("OPERATION_INVALIDATED", failureCode(result.await()))

        val again = connectCatching(h, BleConnectOptions())
        assertEquals("새 connect 가 재발견을 시작하지 않았다", 2, h.fake.scanStarts.size)
        assertEquals(ConnectionState.Connecting, h.session(id).connectionState.value)
        assertTrue("새 connect 가 거절됐다: ${if (again.isCompleted) again.await() else "대기"}", !again.isCompleted || again.await().isSuccess)
        h.close()
    }

    @Test
    fun `g 어댑터 복귀 재연결 시작 실패는 LINK_START_FAILED Diagnostic 을 남기고 Disconnected(Failed) 로 끝난다`() = runTest {
        val h = harness(config)
        h.connectToReady(id, auto)
        h.fake.linkLoss(id, 8)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: ${h.states(id)}")
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()

        h.fake.connectThrows = IllegalStateException("connectGatt 거절")
        h.fake.setAdapter(AdapterState.Ready)
        h.advancePastAllDeadlines()

        val reason = (h.session(id).connectionState.value as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed
            ?: fail("Disconnected(Failed) 아님: ${h.states(id)}").let { return@runTest }
        assertEquals("LINK_START_FAILED", reason.failure.code.value)
        assertEquals("LINK_START_FAILED Diagnostic: ${h.events}", 1, diagnostics(h, "LINK_START_FAILED").size)
        h.close()
    }
}
