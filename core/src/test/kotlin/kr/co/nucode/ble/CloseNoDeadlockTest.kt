package kr.co.nucode.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.ConnectOptions
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.gatt.GattDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `runtime-no-runblocking` task 2(B) — `close()` 가 어떤 디스패처 위에서도 즉시
 * 반환하는지, 정리는 코어 디스패처 위에서 FIFO 로 뒤이어 도는지 검증한다. 변경 전
 * `close()` 는 `onRuntimeBlocking { … }` 로 이 테스트가 주입한 `StandardTestDispatcher`
 * 위에서 `runBlocking` 하는데, 그 스케줄러를 pump 할 스레드가 바로 그 `runBlocking` 을
 * 부른 테스트 스레드 자신이라 데드락한다(`AdapterStateTransitionTest`, `BleCoreWiringTest`
 * D1 전례와 동일한 근거).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseNoDeadlockTest {

    private fun TestScope.newCore(): BleRuntime = BleRuntime(
        config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
        adapter = { null },
    )

    private fun TestScope.newCoreWithFailingClose(driver: GattDriver): BleRuntime = BleRuntime(
        config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
        adapter = { null },
        gattDriver = driver,
    )

    private fun testDeviceId(suffix: String = "FF") = PeripheralId("AA:BB:CC:DD:EE:$suffix")

    @Test(timeout = 5_000)
    fun `close called on the test dispatcher returns and cleanup completes after runCurrent`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }

        // 코어의 officialEvents 스트림이 close() 이후에도 정상 종결되는지(예외 없이) 함께 살펴본다 —
        // close() 의 정리부는(변경 전/후 모두) 이 스트림에 BleEvent.ConnStateChanged 를 emit
        // 하지 않는다(session.connectionState StateFlow만 직접 갱신한다, GattConnectionLifecycle.close
        // 참고) — 그래서 emitted-event 개수는 여기서 단언하지 않는다.
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()

        // close() 는 테스트 코루틴(코어 스레드가 아님) 위에서 직접 부른다 — 즉시 반환해야 한다.
        core.close()
        runCurrent()

        // awaitClosed() 가 이미 완료돼 있어야 한다(정리가 runCurrent() 동안 끝났다는 증거).
        core.awaitClosed()

        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), session.connectionState.value)
        assertNull(core.registry.get(id))

        val snapshot = core.officialSnapshot()
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            snapshot.sessions.single().connectionState,
        )
    }

    /**
     * FIFO 순서를 단언하지 않는다(plan review 정정) — [BleRuntime.officialSnapshot] 은 dispatch
     * 된 블록 안에서 다시 `closed` 를 검사해 이미 gate 가 서 있으면 `closeCompleted.await()` 로
     * 넘어간다(BleRuntime.kt officialSnapshot 참고). 그래서 이 스냅샷 호출이 close() 보다 먼저
     * 코어 디스패처에 admit 됐든(`async` 블록이 한 번 더 dispatch 를 거친 뒤에야 실제 바디가
     * 큐에 들어간다) 나중에 됐든, gate 가 선 **뒤**에 실제로 실행되는 스냅샷은 항상 frozen
     * 결과를 본다는 것만 이 테스트가 고정한다 — "gate 전 admit 순서가 곧 실행 순서"라는
     * FIFO 자체는 이 테스트의 주장이 아니다. 그리고 데드락하지 않는다(변경 전 `runBlocking`
     * 은 여기서 데드락했다).
     */
    @Test(timeout = 5_000)
    fun `snapshot dispatched before close observes the frozen result after the gate`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }

        val before = async { core.officialSnapshot() }
        core.close()
        runCurrent()

        val frozen = core.officialSnapshot()
        assertEquals(frozen, before.await())
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            frozen.sessions.single().connectionState,
        )
    }

    /**
     * plan `runtime-no-runblocking/v2` review MEDIUM — 지연 정리(`runCloseCleanup`) 안에서
     * `gattBridge.close`(driver.close)가 던지면, 변경 전에는 그 예외가 `CoroutineScope(coreDispatcherOnly).launch { … }`
     * 의 기본 `Job`까지 그대로 올라가 uncaught exception handler 를 태운다(Android 라면 프로세스
     * 죽음) — `closeCompleted` 도 영원히 미완료로 남아 `awaitClosed()`/`officialSnapshot()`
     * 모두 멈춘다. 정리는 실패를 삼키고 [BleRuntime.closeFailure] 에 기록하며, `officialSnapshot()`
     * 은 폴백 스냅샷으로 typed 하게 끝나야 한다.
     *
     * plan `v3` Fix 2 갱신 — 세션별 `runCatching`(fault-isolate) 덕분에 `gattBridge.close`가
     * 던져도 그 세션의 `prepareDisconnect`/`completeCleanup`은 이미 반영된 뒤라 스냅샷 구성
     * 자체는(루프 밖, 항상 실행) 이 세션을 frozen 상태로 담아낸다 — v2 의 "실패하면 빈 스냅샷"
     * 폴백은 이제 `registry.all()`이나 스냅샷 구성 자체가 던지는(세션 정리 실패보다 더 앞선)
     * 극단적 케이스 전용이다. 그래서 이 테스트는 더는 `sessions.isEmpty()`를 보지 않는다.
     */
    @Test(timeout = 5_000)
    fun `cleanup failure is recorded and snapshot stays typed`() = runTest {
        val failure = IllegalStateException("gatt gone")
        val core = newCoreWithFailingClose(ThrowingCloseDriver(failure))
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }

        core.close()
        runCurrent()

        core.awaitClosed()

        assertEquals(failure, core.closeFailure)

        // officialSnapshot()이 정리 실패 뒤에도 예외 없이 typed ClientSnapshot을 돌려주는지가 단언이다
        // (호출 자체가 통과하면 스냅샷이 세워졌다는 뜻이다 — requireNotNull 등이 던지면 여기서 실패한다).
        val snapshot = core.officialSnapshot()
        assertEquals(AdapterState.Unsupported, snapshot.adapterState)
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            snapshot.sessions.single().connectionState,
        )
    }

    /**
     * plan `runtime-no-runblocking/v3` review MEDIUM Fix 2 — `runCloseCleanupBody()` 는
     * `sessions.forEach { … gattBridge.close(session) }` 를 한 예외도 안 삼키는 루프로 돈다.
     * 세션 k에서 `gattBridge.close`(driver.close)가 던지면 그 예외가 루프 밖 `runCloseCleanup`
     * 의 catch로 곧장 튀어, k+1..n 세션은 정리되지 않고(GATT 핸들 누수) `unregisterReceiver`·
     * `diagnosticDispatcher.close()`·`coreScope.cancel()` 꼬리도 스킵된다. 세션별로 격리해
     * 첫 예외만 모으고 뒤 세션·항상 실행돼야 할 꼬리를 계속 돌려야 한다.
     */
    @Test(timeout = 5_000)
    fun `cleanup continues past a failing session handle and still closes the rest`() = runTest {
        val failure = IllegalStateException("gatt gone for #2")
        val failingId = testDeviceId("02")
        val driver = PartiallyFailingCloseDriver(failingId, failure)
        val core = newCoreWithFailingClose(driver)
        val id1 = testDeviceId("01")
        val id2 = failingId
        val id3 = testDeviceId("03")
        listOf(id1, id2, id3).forEach { id ->
            core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }
        }

        core.close()
        runCurrent()
        core.awaitClosed()

        assertEquals(1, driver.closeCallsFor(id1))
        assertEquals(1, driver.closeCallsFor(id2))
        assertEquals(1, driver.closeCallsFor(id3))
        assertEquals(failure, core.closeFailure)

        val snapshot = core.officialSnapshot()
        assertEquals(3, snapshot.sessions.size)
        assertTrue(
            "정리 도중 실패해도 스냅샷은 세 세션 모두를 얼려야 한다: $snapshot",
            snapshot.sessions.all { it.connectionState == ConnectionState.Disconnected(BleDisconnectReason.ClientClosed) },
        )

        // adapter receiver 경로/coreScope 도 세션 부분 실패와 무관하게 항상 마무리돼야 한다 —
        // `onCore`(closed 재검사)를 타는 명령이 계속 CLIENT_CLOSED 로 막힌다는 것 자체가
        // 정리 꼬리(coreScope.cancel 포함)가 정상 종결됐다는 관측 가능한 증거다. `disconnect`
        // 는 closed 일 때 조용한 no-op 이라(BleRuntime.disconnect 참고) 이 단언에 못 쓴다 —
        // `connect`는 `onCore`를 그대로 타 CLIENT_CLOSED 를 던진다.
        val postCloseResult = runCatching { core.connect(id1, ConnectOptions()) }
        val postCloseFailure = postCloseResult.exceptionOrNull()
        assertTrue("close 뒤 명령은 BleException(CLIENT_CLOSED) 여야 한다: $postCloseFailure", postCloseFailure is BleException)
        assertEquals("CLIENT_CLOSED", (postCloseFailure as BleException).failure.code.value)
    }

    /** [cleanup continues past a failing session handle and still closes the rest] 전용 — 특정 deviceId 의 close() 만 던지고 나머지는 정상 카운트하는 fake driver. */
    private class PartiallyFailingCloseDriver(
        private val failingId: PeripheralId,
        private val failure: Throwable,
    ) : NoopGattDriver() {
        private val closeCalls = java.util.concurrent.ConcurrentHashMap<PeripheralId, AtomicInteger>()

        fun closeCallsFor(deviceId: PeripheralId): Int = closeCalls[deviceId]?.get() ?: 0

        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) {
            closeCalls.getOrPut(deviceId) { AtomicInteger(0) }.incrementAndGet()
            if (deviceId == failingId) throw failure
        }
    }

    /** [cleanup failure is recorded and snapshot stays typed] 전용 — close() 에서만 던지는 fake driver. */
    private class ThrowingCloseDriver(private val failure: Throwable) : NoopGattDriver() {
        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?): Nothing = throw failure
    }
}
