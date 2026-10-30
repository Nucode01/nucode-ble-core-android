package kr.co.nucode.ble.rssi

import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.FakeAndroidPlatform
import kr.co.nucode.ble.harness.Harness
import kr.co.nucode.ble.harness.harness
import kr.co.nucode.ble.internal.port.GattCallbackSource
import kr.co.nucode.ble.internal.port.GattDriverEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 연결 중 RSSI 읽기(CANDIDATE-34, states.md §8·§10) — sim 시나리오가 못 잡는 것: 두 기기 사이의 차례, 기한, 거절·예외 경로.
 * sim 가짜는 RSSI 요청에 15ms 뒤 -50 으로 답하고, 답하기 전에 온 요청은 버린다(어댑터 슬롯 하나).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssiReadTest {
    private val a = PeripheralId("AA:BB:CC:DD:EE:A1")
    private val b = PeripheralId("AA:BB:CC:DD:EE:B2")
    private val char = CharacteristicRef(
        ServiceRef(BleUuid(UUID.fromString("6e7a0001-5c3a-4b8e-9d1f-0a1b2c3d4e5f"))),
        BleUuid(UUID.fromString("6e7a0002-5c3a-4b8e-9d1f-0a1b2c3d4e5f")),
    )
    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private fun TestScope.ready(first: PeripheralId, second: PeripheralId? = null): Harness =
        harness(config).also { h -> listOfNotNull(first, second).forEach { h.connectToReady(it) } }

    private fun TestScope.readAsync(h: Harness, id: PeripheralId): Deferred<Result<Int>> =
        async { runCatching { h.session(id).readRssi() } }

    private val FakeAndroidPlatform.rssiRequests: Int get() = calls.count { it == "rssi" }

    private fun Deferred<Result<Int>>.value(): Int {
        if (!isCompleted) fail("readRssi 가 끝나지 않음")
        return getCompleted().getOrThrow()
    }

    private fun Deferred<Result<Int>>.failure(): BleFailure {
        if (!isCompleted) fail("readRssi 가 끝나지 않음")
        val error = getCompleted().exceptionOrNull() ?: throw AssertionError("readRssi 가 성공함: ${getCompleted()}")
        return (error as? BleException)?.failure ?: throw AssertionError("BleException 아님", error)
    }

    @Test
    fun `겹친 두 호출은 플랫폼 요청 하나의 결과를 나눠 받는다`() = runTest {
        val h = ready(a)
        val r1 = readAsync(h, a)
        val r2 = readAsync(h, a)
        runCurrent()
        assertEquals(1, h.fake.rssiRequests)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, r1.value())
        assertEquals(-50, r2.value())
        assertEquals(1, h.fake.rssiRequests)
        h.close()
    }

    @Test
    fun `진행 중 write 를 기다리지 않고 먼저 끝난다`() = runTest {
        val h = ready(a)
        h.fake.onWrite = CallbackVariant.Late(40)
        val write = async { runCatching { h.session(a).write(char, byteArrayOf(1)) } }
        runCurrent()
        val rssi = readAsync(h, a)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, rssi.value())
        assertFalse(write.isCompleted)
        advanceTimeBy(30)
        runCurrent()
        assertTrue(write.getCompleted().isSuccess)
        h.close()
    }

    @Test
    fun `취소한 호출의 차례는 앞 응답 전에 다른 기기로 넘어가지 않는다`() = runTest {
        val h = ready(a, b)
        val ra = launch { h.session(a).readRssi() }
        runCurrent()
        ra.cancel()
        runCurrent()
        val rb = readAsync(h, b)
        runCurrent()
        assertEquals(1, h.fake.rssiRequests)
        advanceTimeBy(14)
        runCurrent()
        assertEquals("A 응답 전에 B 요청이 나갔다", 1, h.fake.rssiRequests)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, h.fake.rssiRequests)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, rb.value())
        h.close()
    }

    @Test
    fun `해제와 링크 유실은 OPERATION_INVALIDATED 이고 차례는 앞 응답까지 쥔다`() = runTest {
        val h = ready(a, b)
        val ra = readAsync(h, a)
        runCurrent()
        h.fake.linkLoss(a)
        runCurrent()
        assertEquals("OPERATION_INVALIDATED", ra.failure().code.value)
        assertEquals(OperationKind.ReadRssi, ra.failure().context.operationKind)
        val rb = readAsync(h, b)
        runCurrent()
        assertEquals("A 응답 전에 B 요청이 나갔다", 1, h.fake.rssiRequests)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(2, h.fake.rssiRequests)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, rb.value())

        // 사용자 해제도 같다.
        val rb2 = readAsync(h, b)
        runCurrent()
        h.disconnect(b)
        assertEquals("OPERATION_INVALIDATED", rb2.failure().code.value)
        h.close()
    }

    @Test
    fun `무응답은 기한 뒤 RSSI_READ_TIMEOUT 이고 세션은 Ready 다`() = runTest {
        val h = ready(a)
        h.fake.onRssi = CallbackVariant.Missing
        val r = readAsync(h, a)
        runCurrent()
        advanceTimeBy(49)
        runCurrent()
        assertFalse(r.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        val failure = r.failure()
        assertEquals("RSSI_READ_TIMEOUT", failure.code.value)
        assertEquals("operation", failure.category.value)
        assertEquals(Retryability.TransientMayHelp, failure.retryability)
        assertEquals(OperationKind.ReadRssi, failure.context.operationKind)
        // poison 없음: 연결은 그대로이고 GATT 큐 작업이 계속 된다.
        assertEquals(ConnectionState.Ready, h.session(a).connectionState.value)
        val write = async { runCatching { h.session(a).write(char, byteArrayOf(1)) } }
        runCurrent()
        assertTrue(write.getCompleted().isSuccess)
        h.close()
    }

    @Test
    fun `기한은 차례를 기다린 시간을 넣지 않고 요청을 보낸 때부터 센다`() = runTest {
        val h = ready(a, b)
        h.fake.onRssi = CallbackVariant.Late(40)
        val ra = readAsync(h, a)
        val rb = readAsync(h, b)
        runCurrent()
        advanceTimeBy(41)
        runCurrent()
        assertEquals(-50, ra.value())
        assertFalse(rb.isCompleted)
        // B 는 40ms 를 기다린 뒤 보냈고 40ms 뒤 답을 받는다(호출부터 80ms > 기한 50ms).
        advanceTimeBy(41)
        runCurrent()
        assertEquals(-50, rb.value())
        h.close()
    }

    @Test
    fun `플랫폼이 요청을 거절하면 GATT_OPERATION_START_FAILED 이고 다음 호출은 성공한다`() = runTest {
        val h = ready(a)
        h.fake.rssiStarts = false
        val rejected = readAsync(h, a)
        runCurrent()
        assertEquals("GATT_OPERATION_START_FAILED", rejected.failure().code.value)
        h.fake.rssiStarts = true
        val next = readAsync(h, a)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, next.value())
        h.close()
    }

    @Test
    fun `status 실패는 GATT_STATUS_FAILED 다`() = runTest {
        val h = ready(a)
        h.fake.onRssi = CallbackVariant.Late(15, status = 133)
        val r = readAsync(h, a)
        advanceTimeBy(16)
        runCurrent()
        assertEquals("GATT_STATUS_FAILED", r.failure().code.value)
        assertEquals(OperationKind.ReadRssi, r.failure().context.operationKind)
        h.close()
    }

    @Test
    fun `권한 예외는 PERMISSION_DENIED 이고 세션은 Ready 다`() = runTest {
        val h = ready(a)
        h.fake.rssiThrows = SecurityException("BLUETOOTH_CONNECT")
        val denied = readAsync(h, a)
        runCurrent()
        assertEquals("PERMISSION_DENIED", denied.failure().code.value)
        assertEquals(ConnectionState.Ready, h.session(a).connectionState.value)
        h.fake.rssiThrows = null
        val next = readAsync(h, a)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, next.value())
        h.close()
    }

    @Test
    fun `대기 중 close 는 CLIENT_CLOSED 다`() = runTest {
        val h = ready(a)
        val r = readAsync(h, a)
        runCurrent()
        h.client.close()
        runCurrent()
        assertEquals("CLIENT_CLOSED", r.failure().code.value)
        h.close()
    }

    @Test
    fun `Ready 가 아니면 NOT_READY 이고 모르는 기기는 UNKNOWN_DEVICE 다`() = runTest {
        val h = ready(a)
        val notReady = readAsync(h, b)
        runCurrent()
        assertEquals("NOT_READY", notReady.failure().code.value)
        val unknown = async { runCatching { h.core.readRssi(PeripheralId("11:22:33:44:55:66")) } }
        runCurrent()
        assertEquals("UNKNOWN_DEVICE", unknown.failure().code.value)
        assertEquals(0, h.fake.rssiRequests)
        h.close()
    }

    @Test
    fun `다른 기기의 늦은 응답은 진행 중 요청에 붙지 않는다`() = runTest {
        val h = ready(a, b)
        val r = readAsync(h, a)
        runCurrent()
        // 가짜의 콜백 입구로 B 의 응답을 넣는다(A 요청이 진행 중일 때).
        @Suppress("UNCHECKED_CAST")
        val sink = FakeAndroidPlatform::class.java.getDeclaredField("sink").apply { isAccessible = true }
            .get(h.fake) as (GattDriverEvent) -> Unit
        sink(GattDriverEvent.RemoteRssiRead(b, GattCallbackSource { true }, rssi = -90, status = 0))
        runCurrent()
        assertFalse(r.isCompleted)
        advanceTimeBy(16)
        runCurrent()
        assertEquals(-50, r.value())
        h.close()
    }
}
