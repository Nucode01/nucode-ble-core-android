package kr.co.nucode.ble.descriptor

import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.Harness
import kr.co.nucode.ble.internal.gatt.DescriptorCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android CCCD write 규칙(요청서 B.4 규칙 1~5)과 구독 변경 진단. */
@OptIn(ExperimentalCoroutinesApi::class)
class CccdWriteTest {

    private val auto = BleConnectOptions(autoReconnect = true)

    private fun Harness.active() = session(DEVICE).activeSubscriptions.value
    private fun Harness.intent() = core.registry.get(DEVICE)!!.subscriptionState.desiredSnapshot
    private fun Harness.cccdValues() = fake.descriptorWrites.filter { it.first?.descriptorUuid == CCCD_UUID }.map { it.second.toList() }

    /** 링크를 잃고 같은 handle 로 다시 붙어 Ready 까지(autoReconnect). 그 사이 쓴 CCCD 값 목록을 돌려준다. */
    private fun TestScope.relink(h: Harness): List<List<Byte>> {
        val before = h.cccdValues().size
        h.fake.linkLoss(DEVICE)
        runCurrent()
        h.fake.emitConnectionState(DEVICE, BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("재연결 Ready") { h.session(DEVICE).connectionState.value == ConnectionState.Ready }
        return h.cccdValues().drop(before)
    }

    private fun le(vararg bytes: Int) = bytes.map { it.toByte() }

    @Test
    fun `규칙 1 - 0x0003·0x0005·3바이트 값도 바꾸지 않고 그대로 쓴다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        for (value in listOf(byteArrayOf(3, 0), byteArrayOf(5, 0), byteArrayOf(1, 2, 3))) {
            assertEquals("ok", call { h.session(DEVICE).writeDescriptor(CCCD, value) }.failureCode())
        }
        assertEquals(listOf(le(3, 0), le(5, 0), le(1, 2, 3)), h.cccdValues())
        h.close()
    }

    @Test
    fun `규칙 2 - 0 이 아니면 로컬 알림 수신을 켜고 0 이면 끈다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.notificationRouting.clear()
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(0, 0)) }
        assertEquals(listOf(true, false), h.fake.notificationRouting)
        h.close()
    }

    @Test
    fun `규칙 3 - 성공 응답 뒤에만 활성·의도를 고치고 실패면 그대로다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.descriptorWriteStatus = { _, _ -> 3 }
        assertEquals("GATT_STATUS_FAILED", call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }.failureCode())
        assertTrue(h.active().isEmpty() && h.intent().isEmpty())

        h.fake.descriptorWriteStatus = null
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }
        assertEquals(setOf(CHAR), h.active())
        assertEquals(setOf(CHAR), h.intent())

        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(0, 0)) }
        assertTrue(h.active().isEmpty() && h.intent().isEmpty())
        h.close()
    }

    @Test
    fun `규칙 4 - 재연결 복원은 마지막으로 쓴 값을 그대로 다시 쓴다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE, auto)
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(3, 0)) }

        assertEquals(listOf(le(3, 0)), relink(h))
        assertEquals(setOf(CHAR), h.active())
        h.close()
    }

    @Test
    fun `규칙 4 - subscribe 뒤 0 을 쓰면 복원하지 않는다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE, auto)
        h.subscribe(DEVICE, CHAR)
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(0, 0)) }

        assertEquals(emptyList<List<Byte>>(), relink(h))
        assertTrue(h.active().isEmpty())
        h.close()
    }

    @Test
    fun `규칙 5 - 0 을 쓰는 중에 접수한 subscribe 가 나중이라 이긴다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.subscribe(DEVICE, CHAR)
        h.fake.onDescriptorWrite = CallbackVariant.Late(10)
        async { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(0, 0)) }
        runCurrent()
        async { h.session(DEVICE).subscribe(CHAR) }
        advanceTimeBy(30)
        runCurrent()

        assertEquals(setOf(CHAR), h.active())
        assertEquals(setOf(CHAR), h.intent())
        h.close()
    }

    @Test
    fun `규칙 5 - 0100 을 쓰는 중에 부른 unsubscribe 가 이긴다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.onDescriptorWrite = CallbackVariant.Late(10)
        async { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }
        runCurrent()
        async { h.session(DEVICE).unsubscribe(CHAR) }
        advanceTimeBy(30)
        runCurrent()

        assertTrue(h.active().isEmpty() && h.intent().isEmpty())
        assertEquals(le(0, 0), h.cccdValues().last())
        h.close()
    }

    @Test
    fun `규칙 5 - subscribe 다음에 접수한 0x0003 이 복원 값이 된다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE, auto)
        h.fake.onDescriptorWrite = CallbackVariant.Late(10)
        async { h.session(DEVICE).subscribe(CHAR) }
        runCurrent()
        async { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(3, 0)) }
        advanceTimeBy(30)
        runCurrent()
        h.fake.onDescriptorWrite = CallbackVariant.Arrive()

        assertEquals(listOf(le(3, 0)), relink(h))
        h.close()
    }

    @Test
    fun `호출자를 취소해도 성공한 CCCD write 는 활성·의도에 남는다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.onDescriptorWrite = CallbackVariant.Late(20)
        val job = async { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }
        runCurrent()
        job.cancel()
        advanceTimeBy(30)
        runCurrent()

        assertEquals(setOf(CHAR), h.active())
        assertEquals(setOf(CHAR), h.intent())
        h.close()
    }

    @Test
    fun `내부 subscribe CCCD 는 payload 관측이 없고 앱 CCCD write 는 있다`() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        val h = descriptorHarness(TEST_CONFIG.copy(logger = BleLogger { synchronized(records) { records += it } }))
        h.connectToReady(DEVICE)
        h.subscribe(DEVICE, CHAR)
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }

        val tx = awaitRecord(records) { it.failure.context.descriptor == CCCD && it.values["payload_direction"] != null }
        assertEquals(DiagnosticValue.Text("tx"), tx.values["payload_direction"])
        val observed = synchronized(records) { records.filter { it.failure.code.value == "GATT_PAYLOAD_OBSERVED" } }
        assertFalse(observed.any { it.failure.context.operationKind == OperationKind.Subscribe && "payload_direction" in it.values })
        h.close()
    }

    @Test
    fun `활성 구독이 바뀔 때만 subscription_change 를 남긴다`() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        val h = descriptorHarness(TEST_CONFIG.copy(logger = BleLogger { synchronized(records) { records += it } }))
        h.connectToReady(DEVICE)
        val key = DescriptorCommands.SUBSCRIPTION_CHANGE_KEY

        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(1, 0)) }
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(3, 0)) }
        call { h.session(DEVICE).writeDescriptor(CCCD, byteArrayOf(0, 0)) }

        val disabled = awaitRecord(records) { it.values[key] == DiagnosticValue.Text("disabled") }
        assertEquals(OperationKind.Unsubscribe, disabled.failure.context.operationKind)
        assertEquals(CCCD, disabled.failure.context.descriptor)
        val changes = synchronized(records) { records.mapNotNull { it.values[key] } }
        assertEquals(listOf(DiagnosticValue.Text("enabled"), DiagnosticValue.Text("disabled")), changes)
        h.close()
    }
}
