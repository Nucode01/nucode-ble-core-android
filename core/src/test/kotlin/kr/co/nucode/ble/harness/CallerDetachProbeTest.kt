package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2단계 프로브(전달 계획 §3 2단계, §4 "호출자 취소는 호출자만 떼어 냄"): 호출자 취소를 "호출자만 떼어 내기"로 바꿔도
 * 안전한가. main 코드는 바꾸지 않고, 떼어 내기를 테스트에서 모델링한다 — 작업은 호출자와 무관한 scope(backgroundScope)가
 * 소유해 내부 timeout 까지 그대로 기다리고, 호출자는 그 결과를 기다리던 코루틴만 취소된다.
 * 결과는 `PROBE|caller-detach|…` 줄로 표준 출력에 남긴다(테스트 리포트 system-out).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallerDetachProbeTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:D1")
    private val serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val ref = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))

    private val config = BleConfig(
        operationTimeoutMillis = 500,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private fun serviceSnapshot() = GattServiceSnapshot(
        serviceUuid = serviceUuid,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                instanceIndex = 0,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            ),
        ),
    )

    private fun TestScope.ready(): Harness {
        val h = harness(config = config)
        h.fake.services = listOf(gapServiceSnapshot(), serviceSnapshot())
        h.connectToReady(id)
        return h
    }

    private fun probe(case: String, vararg values: Pair<String, Any?>) {
        println("PROBE|caller-detach|case=$case|" + values.joinToString("|") { (k, v) -> "$k=$v" })
    }

    /** 호출자와 무관한 작업 소유 scope. 실패가 부모로 번지지 않게 supervisor 로 둔다(결과는 Deferred 가 가진다). */
    private fun TestScope.owner() =
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))

    /** 떼어 내기 모델: [owner] 가 작업을 소유하고, 호출자는 await 만 한다. 호출자 Job 을 돌려준다. */
    private fun <T> TestScope.detachedCaller(owner: Deferred<T>) = launch { owner.await() }

    private fun <T> Deferred<T>.outcome(): String = when {
        !isCompleted -> "pending"
        else -> when (val e = getCompletionExceptionOrNull()) {
            null -> getCompleted().let { v -> if (v is ByteArray) "ok(${v.toList()})" else "ok" }
            is BleException -> "failed(${e.failure.code.value})"
            else -> "threw(${e::class.simpleName})"
        }
    }

    @Test
    fun `계약 - 시작된 read 의 호출자 취소 뒤에도 세션은 Ready 로 남고 늦은 콜백이 떠난 작업을 끝낸다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Late(300)
        val caller = launch { h.session(id).read(ref) }
        runCurrent()
        advanceTimeBy(100); runCurrent()
        caller.cancel(); runCurrent()
        val stateAfterCancel = h.session(id).connectionState.value
        advanceTimeBy(250); runCurrent() // 늦은 콜백(300ms) 도착
        val last = h.states(id).last()
        probe("cancel-started-read", "afterCancel" to stateAfterCancel, "finalState" to last, "closeCalls" to h.fake.calls.count { it == "close" })
        assertEquals(ConnectionState.Ready, stateAfterCancel)
        assertEquals(ConnectionState.Ready, last)
        assertEquals(0, h.fake.calls.count { it == "close" })
        h.close()
    }

    @Test
    fun `계약 - 호출자가 떠난 read 가 콜백을 못 받으면 큐 기한이 OPERATION_TIMEOUT 으로 세션을 끝낸다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Missing
        val caller = launch { h.session(id).read(ref) }
        runCurrent()
        caller.cancel(); runCurrent()
        assertEquals(ConnectionState.Ready, h.session(id).connectionState.value)
        h.advancePastAllDeadlines()
        val last = h.states(id).last()
        probe("cancel-missing-callback", "finalState" to last)
        val failed = (last as? ConnectionState.Disconnected)?.reason as? kr.co.nucode.ble.BleDisconnectReason.Failed
        assertEquals("OPERATION_TIMEOUT", failed?.failure?.code?.value)
        h.close()
    }

    @Test
    fun `계약 - 대기 중이던 subscribe 를 호출자가 취소해도 완료되어 활성 구독과 의도에 남는다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Late(200)
        h.fake.onDescriptorWrite = CallbackVariant.Late(100)
        val reader = launch { h.session(id).read(ref) }
        runCurrent()
        val subscriber = launch { h.session(id).subscribe(ref) } // read 뒤에 대기
        runCurrent()
        subscriber.cancel(); runCurrent()
        advanceTimeBy(400); runCurrent()
        val active = ref in h.session(id).activeSubscriptions.value
        probe("cancel-queued-subscribe", "active" to active, "state" to h.session(id).connectionState.value)
        assertTrue("대기 중 취소된 subscribe 도 시작·완료되어 활성으로 남는다", active)
        assertEquals(ConnectionState.Ready, h.session(id).connectionState.value)
        reader.cancel()
        h.close()
    }

    @Test
    fun `계약 - 취소된 subscribe 를 unsubscribe 로 되돌리면 의도도 활성도 남지 않는다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Late(200)
        h.fake.onDescriptorWrite = CallbackVariant.Late(100)
        val reader = launch { h.session(id).read(ref) }
        runCurrent()
        val subscriber = launch { h.session(id).subscribe(ref) } // read 뒤에 대기
        runCurrent()
        subscriber.cancel(); runCurrent()
        val undo = launch { h.session(id).unsubscribe(ref) }
        runCurrent()
        advanceTimeBy(600); runCurrent()
        undo.join()
        assertTrue("되돌린 구독은 활성에 남지 않는다", ref !in h.session(id).activeSubscriptions.value)
        reader.cancel()
        h.close()
    }

    @Test
    fun `떼어 내기 - 늦은 콜백은 떠난 작업에 붙고 다음 작업은 자기 값을 받는다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Late(300)
        h.fake.readValue = byteArrayOf(1)
        val first = owner().async { h.session(id).read(ref) }
        val caller = detachedCaller(first)
        runCurrent()
        advanceTimeBy(100); runCurrent()
        caller.cancel(); runCurrent()
        // 떠난 read 가 아직 pending 인 동안 다른 호출자가 read 를 건다 — 큐 뒤에 선다.
        val second = owner().async { h.session(id).read(ref) }
        runCurrent()
        val readsWhileOrphanPending = h.fake.calls.count { it == "read" }
        h.fake.readValue = byteArrayOf(2) // 두 번째 read 가 시작될 때의 값
        advanceTimeBy(250); runCurrent() // 첫 콜백(300ms) 도착
        advanceTimeBy(400); runCurrent() // 두 번째 콜백
        val state = h.session(id).connectionState.value
        probe(
            "detach-late-callback",
            "first" to first.outcome(), "second" to second.outcome(),
            "readStartsWhileOrphanPending" to readsWhileOrphanPending,
            "state" to state, "closeCalls" to h.fake.calls.count { it == "close" },
        )
        assertEquals("두 번째 read 는 첫 콜백 전에 시작되지 않는다", 1, readsWhileOrphanPending)
        assertEquals("ok([1])", first.outcome())
        assertEquals("ok([2])", second.outcome())
        assertEquals(ConnectionState.Ready, state)
        h.close()
    }

    @Test
    fun `떼어 내기 - 콜백이 오지 않으면 내부 timeout 이 끝내고 뒤 작업도 유한하게 끝난다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Missing
        val first = owner().async { h.session(id).read(ref) }
        val caller = detachedCaller(first)
        runCurrent()
        caller.cancel(); runCurrent()
        val second = owner().async { h.session(id).read(ref) }
        runCurrent()
        advanceTimeBy(config.operationTimeoutMillis + 10); runCurrent()
        h.advancePastAllDeadlines()
        probe(
            "detach-missing-callback",
            "first" to first.outcome(), "second" to second.outcome(),
            "finalState" to h.states(id).last(),
        )
        assertTrue(first.outcome(), first.outcome().startsWith("failed"))
        assertTrue(second.outcome(), second.outcome().startsWith("failed"))
        h.close()
    }

    @Test
    fun `떼어 내기 - 떠난 작업이 pending 일 때 사용자 해제도 유한하게 끝난다`() = runTest {
        val h = ready()
        h.fake.onRead = CallbackVariant.Missing
        val first = owner().async { h.session(id).read(ref) }
        val caller = detachedCaller(first)
        runCurrent()
        caller.cancel(); runCurrent()
        val done = CompletableDeferred<Unit>()
        owner().launch { h.session(id).disconnect(); done.complete(Unit) }
        runCurrent()
        h.advancePastAllDeadlines()
        probe(
            "detach-then-disconnect",
            "first" to first.outcome(), "disconnectReturned" to done.isCompleted,
            "finalState" to h.states(id).last(),
        )
        assertTrue(done.isCompleted)
        assertTrue(first.outcome(), first.outcome().startsWith("failed"))
        h.close()
    }

    @Test
    fun `떼어 내기 - 여러 조각 write 는 호출자가 떠나도 남은 조각을 계속 보낸다`() = runTest {
        val h = harness(config = config)
        h.fake.services = listOf(gapServiceSnapshot(), serviceSnapshot())
        h.fake.mtuResult = 23
        h.connectToReady(id)
        h.fake.onWrite = CallbackVariant.Late(100)
        val payload = ByteArray(200) { it.toByte() } // 기본 MTU 23 → 20바이트 조각 10개
        val owner = owner().async {
            h.session(id).write(ref, payload, kr.co.nucode.ble.BleWriteOptions(chunking = kr.co.nucode.ble.BleChunking.Automatic))
        }
        val caller = detachedCaller(owner)
        runCurrent()
        advanceTimeBy(150); runCurrent()
        val writesAtCancel = h.fake.calls.count { it == "write" }
        caller.cancel(); runCurrent()
        h.advancePastAllDeadlines()
        val writesTotal = h.fake.calls.count { it == "write" }
        probe(
            "detach-chunked-write",
            "writesAtCancel" to writesAtCancel, "writesTotal" to writesTotal,
            "owner" to owner.outcome(), "state" to h.session(id).connectionState.value,
        )
        assertTrue("취소 뒤에도 조각이 나간다 $writesAtCancel→$writesTotal", writesTotal > writesAtCancel)
        h.close()
    }

    @Test
    fun `떼어 내기 - 떠난 subscribe 는 완료되어 활성 구독에 남는다`() = runTest {
        val h = ready()
        h.fake.onDescriptorWrite = CallbackVariant.Late(200)
        val owner = owner().async { h.session(id).subscribe(ref) }
        val caller = detachedCaller(owner)
        runCurrent()
        caller.cancel(); runCurrent()
        advanceTimeBy(300); runCurrent()
        val active = ref in h.session(id).activeSubscriptions.value
        probe("detach-subscribe", "owner" to owner.outcome(), "activeAfter" to active)
        assertTrue("떠난 subscribe 가 활성 구독으로 남는다", active)
        h.close()
    }
}
