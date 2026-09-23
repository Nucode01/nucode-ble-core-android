package kr.co.nucode.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.NoopGattDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `runtime-no-runblocking/v1` task 1(A) — `BleRuntime.sessionForView`(→
 * `onRuntimeBlocking` → `runBlocking(coreDispatcherOnly)`) 가 `runTest` +
 * `StandardTestDispatcher(testScheduler)` 조합에서 테스트 스레드를 막는지 회귀한다
 * (plan §원인·배경 ①). `AdapterStateTransitionTest.newCore` 전례와 동일한 fixture다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewNoDeadlockTest {

    private fun TestScope.newCore(): BleRuntime = BleRuntime(
        config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
        adapter = { null },
    )

    private fun testDeviceId() = PeripheralId("AA:BB:CC:DD:EE:FF")

    @Test(timeout = 5_000)
    fun `session view is created without blocking the test dispatcher`() = runTest {
        val core = newCore()
        val id = testDeviceId()

        val session = DefaultBleClient(core).session(id)

        assertTrue(
            "새로 만든 view의 connectionState는 Disconnected여야 한다: ${session.connectionState.value}",
            session.connectionState.value is ConnectionState.Disconnected,
        )
        assertNotNull("registry에 세션이 등록돼 있어야 한다", core.registry.get(id))
    }

    @Test(timeout = 5_000)
    fun `connect through the facade reaches the adapter gate inside runTest`() = runTest {
        val core = newCore()
        val id = testDeviceId()

        val result = runCatching { DefaultBleClient(core).connect(id, BleConnectOptions()) }

        val failure = result.exceptionOrNull()
        assertTrue("BleException이어야 한다: $failure", failure is BleException)
        val code = (failure as BleException).failure.code.value
        assertTrue(
            "adapter=null 경로는 BLE_UNSUPPORTED 또는 ADAPTER_UNAVAILABLE이어야 한다: $code",
            code == "BLE_UNSUPPORTED" || code == "ADAPTER_UNAVAILABLE",
        )
    }

    /**
     * plan `runtime-no-runblocking/v2` review HIGH — v1 `sessionForView` 의 무조건
     * `registry.remove` 는 `checkOpen()` 과 `getOrCreate` 사이에 gate 가 서면 **기존
     * 라이브 Session** 을 registry 에서 걷어내 `close()` 의 지연 정리(`runCloseCleanup`,
     * `registry.all()` 스냅샷)가 그 세션을 보지 못하게 만들었다 — `gattBridge.close`(driver.close)
     * 가 영원히 안 불려 GATT 핸들이 샌다. 이 경합은 `checkOpen()`↔`getOrCreate` 사이에 정확히
     * 주입할 수 없어(테스트에서 그 지점을 잡을 훅이 없다) brute force 로 반복한다 — 매 반복마다
     * 새 runtime + 라이브 세션을 만들고 두 스레드(뷰 조회/close)를 `CountDownLatch` 로 동시에
     * 풀어 창을 최대한 좁힌다. 기본 config(단일 스레드 확정 디스패처, 실제 스레드 — StandardTestDispatcher
     * 아님)를 쓴다: close() 의 정리 코루틴이 이 디스패처를 얻는 데 스케줄링 지연이 있는 반면
     * `sessionForView` 를 부르는 스레드는 즉시 실행되므로, v1 코드에서는 이 스레드의 무조건
     * remove 가 정리 코루틴의 `registry.all()` 스냅샷보다 먼저 도착하는 경우가 실제로 재현된다.
     */
    @Test(timeout = 60_000)
    fun `close racing a view lookup never drops a live session from cleanup`() {
        val id = testDeviceId()
        val iterations = 200

        repeat(iterations) {
            val driver = CountingCloseDriver()
            val core = BleRuntime(
                config = BleConfig(),
                adapter = { null },
                gattDriver = driver,
            )
            val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }

            val startBarrier = CountDownLatch(2)
            val threadA = Thread {
                startBarrier.countDown()
                startBarrier.await()
                runCatching { core.sessionForView(id) }
            }
            val threadB = Thread {
                startBarrier.countDown()
                startBarrier.await()
                core.close()
            }
            threadA.start()
            threadB.start()
            threadA.join()
            threadB.join()

            runBlocking { core.awaitClosed() }

            assertEquals(
                "driver.close 는 정확히 1회여야 한다(누락 = GATT 핸들 누수): ${driver.closeCalls.get()}",
                1,
                driver.closeCalls.get(),
            )
            assertEquals(
                "라이브 세션은 close 뒤 Disconnected(ClientClosed) 여야 한다: ${session.connectionState.value}",
                ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
                session.connectionState.value,
            )
        }
    }

    /**
     * plan `runtime-no-runblocking/v3` review HIGH round-2 재발 — v2 의 조건부 `remove`
     * ("이 호출이 만든 것만 지운다")는 [existing]을 `checkOpen()` 과 `getOrCreate` 사이의
     * 경합 이전에 캡처해둔다는 전제가 깨진다. registry 가 애초에 비어 있고 두 스레드가
     * *동시에* 같은 [deviceId] 로 만드는 경우 — A(`sessionForView`)의 `registry.get`은
     * B(`registry.getOrCreate` 직접 호출)가 아직 등록하기 전이라 null 을 본다(`existing == null`).
     * 그런데 A의 뒤이은 `getOrCreate`가 실행되는 시점엔 B가 이미 등록을 끝내 같은 인스턴스
     * S 를 그대로 받아온다(registry 는 `@Synchronized` — 같은 키에 두 인스턴스가 생기지
     * 않는다) — A는 S 를 "만든 적"이 없는데도 [existing] 지역변수는 null 이다. B가 곧이어
     * `close()` 를 부르면, v2 코드는 `existing == null` 분기를 타 S 를 지워버린다 — S 는
     * B 가 등록한 살아있는 Session 인데도. `close()`의 지연 정리(`registry.all()` 스냅샷)가
     * 이 삭제보다 늦으면 S 를 영원히 못 보고 `driver.close`가 안 불린다(GATT 핸들 누수).
     *
     * 정확한 인터리빙을 훅으로 못 잡으므로(checkOpen↔getOrCreate 사이에 개입할 지점이
     * 없다) brute force 로 반복하며 `CountDownLatch` 시작 게이트로 창을 최대한 좁힌다.
     * B는 항상 `getOrCreate` → `close()` 순서로 부르므로(리스트업 순서가 아니라 코드
     * 순서) B의 `getOrCreate`가 `close()`보다 먼저 완료된다는 것만은 보장된다 — 그래서
     * 매 반복 `driver.closeCalls.get() == 1`을 무조건 단언한다(등록된 세션은 반드시
     * 정확히 한 번 정리돼야 하고, 조용히 사라지면 안 된다는 불변식).
     */
    @Test(timeout = 120_000)
    fun `close racing a first-time view creation still lets cleanup close the session`() {
        val id = testDeviceId()
        val iterations = 2_000

        repeat(iterations) { iteration ->
            val driver = CountingCloseDriver()
            val core = BleRuntime(
                config = BleConfig(),
                adapter = { null },
                gattDriver = driver,
            )

            val startBarrier = CountDownLatch(2)
            val threadA = Thread {
                startBarrier.countDown()
                startBarrier.await()
                runCatching { core.sessionForView(id) }
            }
            val threadB = Thread {
                startBarrier.countDown()
                startBarrier.await()
                core.registry.getOrCreate(id)
                core.close()
            }
            threadA.start()
            threadB.start()
            threadA.join()
            threadB.join()

            runBlocking { core.awaitClosed() }

            assertEquals(
                "빈 registry 에서 동시에 만들어진 세션도 정확히 1회 정리돼야 한다(누락 = GATT 핸들 누수, iteration=$iteration): ${driver.closeCalls.get()}",
                1,
                driver.closeCalls.get(),
            )
        }
    }

    /** [close racing a view lookup never drops a live session from cleanup] 전용 최소 fake — close() 호출 횟수만 센다. */
    private class CountingCloseDriver : NoopGattDriver() {
        val closeCalls = AtomicInteger(0)

        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) {
            closeCalls.incrementAndGet()
        }
    }
}
