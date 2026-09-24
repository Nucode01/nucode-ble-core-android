package kr.co.nucode.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.ReconnectPolicy
import kr.co.nucode.ble.internal.connection.Reconnector
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.session.ConnectionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan failure-delivery-fixes v1 task 4 — 재연결 포기는 공개 상태의 종착(Disconnected(Failed))이어야
 * 하고, disconnect() 는 취소된 호출자에서도 정리를 시작해야 한다.
 *
 * plan terminal-cause v2 이후 소진은 lifecycle 종결 **전에** 판정돼 coordinator 가 소진 실패를 원인으로
 * 고정하고 Disconnected 를 한 번만 게시한다(이전의 "원 실패 게시 → give-up 재게시" 이중 게시는 없다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectStateContractTest {

    private fun TestScope.newCore(
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        gattDriver: NoopGattDriver? = null,
        linkTimeoutMillis: Long = BleConfig().linkTimeoutMillis,
    ): BleRuntime = BleRuntime(
        config = BleConfig(
            dispatcher = StandardTestDispatcher(testScheduler),
            linkTimeoutMillis = linkTimeoutMillis,
        ),
        adapter = { null },
        reconnectPolicy = reconnectPolicy,
        gattDriver = gattDriver,
    )

    private fun linkFailure(): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = 133,
        phase = FailurePhase.Link,
        operation = AndroidApiOperation.OnConnectionStateChange,
        context = FailureContext(),
    )

    private fun failedCode(state: ConnectionState?): String? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    /**
     * 재연결 generation 의 링크가 [status] 로 끊겼다는 플랫폼 콜백 — 소진 선판정은 lifecycle 종결에서
     * 일어나므로 합성 Disconnected 발행이 아니라 실제 종결 경로로 들어간다.
     */
    private fun TestScope.dropReconnectLink(
        driver: NoopGattDriver,
        session: kr.co.nucode.ble.internal.session.Session,
        status: Int = 133,
    ) {
        val generation = session.connectionCoordinator.beginReconnect(backgroundScope, 120_000) {}
        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                deviceId = session.deviceId,
                source = GattCallbackSource { true },
                status = status,
                newState = android.bluetooth.BluetoothProfile.STATE_DISCONNECTED,
                connectionGeneration = generation,
            ),
        )
    }

    @Test
    fun `공식 ExponentialBackoff 소진 시 최종 상태는 Disconnected Failed RECONNECT_EXHAUSTED다`() = runTest {
        val driver = NoopGattDriver()
        val core = newCore(gattDriver = driver)
        val id = PeripheralId("AA:BB:CC:DD:EE:A1")
        val session = core.registry.getOrCreate(id).apply {
            effectiveReconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                maxAttempts = 1,
                initialDelayMillis = 25,
                maxDelayMillis = 25,
            )
            retryCount = 1
            suppressReconnect = false
        }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        dropReconnectLink(driver, session)
        advanceUntilIdle()
        runCurrent() // backgroundScope 수집자가 마지막 방출까지 받게 한다

        assertEquals("RECONNECT_EXHAUSTED", failedCode(session.connectionState.value))
        val exhausted = acc.filterIsInstance<BleEvent.Diagnostic>().single { it.failure.code.value == "RECONNECT_EXHAUSTED" }
        // 상태의 failure 와 Diagnostic 의 failure 는 같은 사실이다(원인 platformDetail 보존).
        assertEquals(exhausted.failure, (session.connectionState.value as ConnectionState.Disconnected).let {
            (it.reason as BleDisconnectReason.Failed).failure
        })
        val states = acc.filterIsInstance<BleEvent.ConnStateChanged>().map { it.state }
        val giveUpIndex = states.indexOfFirst { failedCode(it) == "RECONNECT_EXHAUSTED" }
        assertTrue("포기 상태가 발행되지 않았다: $states", giveUpIndex >= 0)
        assertTrue(
            "포기 이후 Recovering 이 나왔다: $states",
            states.drop(giveUpIndex + 1).none { it is ConnectionState.Recovering },
        )
    }

    @Test
    fun `legacy 정책 포기 시 최종 상태는 Disconnected Failed RECONNECT_GIVE_UP이다`() = runTest {
        val driver = NoopGattDriver()
        val core = newCore(reconnectPolicy = ReconnectPolicy(maxAttempts = 0), gattDriver = driver)
        val id = PeripheralId("AA:BB:CC:DD:EE:A2")
        val session = core.registry.getOrCreate(id).apply { suppressReconnect = false }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        dropReconnectLink(driver, session)
        advanceUntilIdle()
        runCurrent() // backgroundScope 수집자가 마지막 방출까지 받게 한다

        assertEquals("RECONNECT_GIVE_UP", failedCode(session.connectionState.value))
        assertTrue(acc.filterIsInstance<BleEvent.Diagnostic>().any { it.failure.code.value == "RECONNECT_GIVE_UP" })
        assertTrue(acc.none { it is BleEvent.ConnStateChanged && it.state is ConnectionState.Connecting })
    }

    @Test
    fun `소진은 종결 게시 전에 판정돼 Disconnected를 한 번만 게시하고 retryability는 Never다`() = runTest {
        val driver = NoopGattDriver()
        val core = newCore(gattDriver = driver)
        val id = PeripheralId("AA:BB:CC:DD:EE:A4")
        val session = core.registry.getOrCreate(id).apply {
            effectiveReconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                maxAttempts = 1,
                initialDelayMillis = 25,
                maxDelayMillis = 25,
            )
            retryCount = 1
            suppressReconnect = false
        }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        dropReconnectLink(driver, session, status = 133)
        advanceUntilIdle()
        runCurrent()

        val disconnected = acc.filterIsInstance<BleEvent.ConnStateChanged>()
            .map { it.state }
            .filterIsInstance<ConnectionState.Disconnected>()
        assertEquals("종결 게시는 한 번이어야 한다: $disconnected", 1, disconnected.size)
        val failure = (disconnected.single().reason as BleDisconnectReason.Failed).failure
        assertEquals("RECONNECT_EXHAUSTED", failure.code.value)
        assertEquals(Retryability.Never, failure.retryability)
        // 원 원인(링크 status 133)의 platformDetail 을 감싼다.
        assertEquals(133, (failure.platformDetail as PlatformDetail.Android).statusCode)
        assertTrue(acc.none { it is BleEvent.ConnStateChanged && it.state is ConnectionState.Recovering })
    }

    @Test
    fun `취소된 코루틴에서 disconnect해도 정리는 시작되고 호출자는 CancellationException을 본다`() = runTest {
        val driver = object : NoopGattDriver() {
            val commands = mutableListOf<String>()
            override fun disconnect(deviceId: PeripheralId, gatt: android.bluetooth.BluetoothGatt?) {
                commands += "disconnect"
            }
        }
        val core = newCore(gattDriver = driver)
        val id = PeripheralId("AA:BB:CC:DD:EE:A3")
        val session = core.registry.getOrCreate(id)
        session.connectionCoordinator.beginConnection(this, 100) {}

        var thrown: Throwable? = null
        val job = launch {
            coroutineContext.cancel()
            try {
                core.disconnect(id)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        runCurrent()
        job.join()

        assertTrue("호출자에게 취소가 전파되지 않았다: $thrown", thrown is CancellationException)
        assertEquals(listOf("disconnect"), driver.commands)
        assertTrue(
            "정리가 시작되지 않았다: ${session.connectionState.value}",
            session.stage == ConnectionStage.Disconnecting || session.stage == ConnectionStage.Disconnected,
        )
    }

    // ── 4b: 재연결 시도 timeout ────────────────────────────────────────────────

    /**
     * JVM 테스트는 실제 BluetoothGatt 를 만들 수 없어 기본 `reconnect` 는 늘 거부된다. driver 의
     * retained connect 를 "시작 성공"으로 재정의해 production 경로(GattBridge → driver)로 link
     * deadline 에 닿게 한다.
     */
    private class AcceptingReconnectDriver : NoopGattDriver() {
        override fun reconnect(deviceId: PeripheralId, gatt: android.bluetooth.BluetoothGatt?): Boolean = true
    }

    private class TimeoutFixture(
        val core: BleRuntime,
        val driver: NoopGattDriver,
        val session: kr.co.nucode.ble.internal.session.Session,
        val acc: MutableList<BleEvent>,
    ) {
        val states: List<ConnectionState>
            get() = acc.filterIsInstance<BleEvent.ConnStateChanged>().map { it.state }
    }

    /** 재연결 시도의 유일한 상한은 그 generation 의 link deadline 이다(plan callback-ingress 목표 4). */
    private fun TestScope.timeoutFixture(id: PeripheralId, maxAttempts: Int): TimeoutFixture {
        val driver = AcceptingReconnectDriver()
        val core = newCore(gattDriver = driver, linkTimeoutMillis = LINK_DEADLINE_MILLIS)
        val session = core.registry.getOrCreate(id).apply {
            effectiveReconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                maxAttempts = maxAttempts,
                initialDelayMillis = 10,
                maxDelayMillis = 10,
            )
            suppressReconnect = false
        }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()
        session.setStateForTest(ConnectionState.Disconnected(BleDisconnectReason.Failed(linkFailure())))
        core.publishState(session)
        return TimeoutFixture(core, driver, session, acc)
    }

    private companion object {
        const val LINK_DEADLINE_MILLIS = 5_000L

        /** link deadline 뒤 Disconnecting — Noop driver 는 해제 콜백을 내지 않아 cleanup 상한까지 가야 종결된다. */
        val ATTEMPT_END_MILLIS = LINK_DEADLINE_MILLIS + BleConfig().disconnectCleanupTimeoutMillis
    }

    @Test
    fun `재연결 시도가 link deadline을 넘기면 Disconnected Failed LINK_TIMEOUT을 내고 다음 Recovering이 그 원인을 싣는다`() = runTest {
        val f = timeoutFixture(PeripheralId("AA:BB:CC:DD:EE:B1"), maxAttempts = 2)
        advanceTimeBy(10 + 1)
        runCurrent()
        assertEquals(ConnectionStage.Connecting, f.session.stage)

        advanceTimeBy(ATTEMPT_END_MILLIS)
        runCurrent()

        val states = f.states
        val timeoutIndex = states.indexOfFirst { failedCode(it) == "LINK_TIMEOUT" }
        assertTrue("시도 timeout 이 Disconnected(Failed) 로 발행되지 않았다: $states", timeoutIndex >= 0)
        assertTrue(
            "timeout 이 Initial 로 발행됐다: $states",
            states.none { it == ConnectionState.Disconnected(BleDisconnectReason.Initial) },
        )
        val timeoutFailure = ((states[timeoutIndex] as ConnectionState.Disconnected).reason as BleDisconnectReason.Failed).failure
        val nextRecovering = states.drop(timeoutIndex + 1).filterIsInstance<ConnectionState.Recovering>().firstOrNull()
        assertTrue("timeout 뒤 Recovering 이 없다: $states", nextRecovering != null)
        // Recovering.lastFailure 코드는 RECONNECT_ATTEMPT 이고, 원인(timeout)의 context 를 그대로 잇는다.
        assertEquals(2, nextRecovering!!.attempt)
        assertEquals("RECONNECT_ATTEMPT", nextRecovering.lastFailure.code.value)
        assertEquals(timeoutFailure.context, nextRecovering.lastFailure.context)
        assertTrue(
            "link deadline Diagnostic 이 없다",
            f.acc.filterIsInstance<BleEvent.Diagnostic>().any { it.failure.code.value == "LINK_TIMEOUT" },
        )
    }

    @Test
    fun `재연결 시도가 link deadline으로 소진하면 최종 상태는 Disconnected Failed RECONNECT_EXHAUSTED다`() = runTest {
        val f = timeoutFixture(PeripheralId("AA:BB:CC:DD:EE:B2"), maxAttempts = 2)
        advanceUntilIdle()
        runCurrent()

        assertEquals("RECONNECT_EXHAUSTED", failedCode(f.session.connectionState.value))
        // 두 시도 모두 link deadline 이지만, 마지막 시도의 종결은 게시 전에 소진으로 판정돼 한 번만
        // (RECONNECT_EXHAUSTED 로) 게시된다 — LINK_TIMEOUT 사실은 Diagnostic 에 두 번 남는다.
        assertEquals(
            2,
            f.acc.filterIsInstance<BleEvent.Diagnostic>().count { it.failure.code.value == "LINK_TIMEOUT" },
        )
        assertEquals(1, f.states.count { failedCode(it) == "LINK_TIMEOUT" })
        assertEquals(1, f.states.count { failedCode(it) == "RECONNECT_EXHAUSTED" })
        assertEquals(ConnectionStage.Disconnected, f.session.stage)
    }

    @Test
    fun `timeout된 시도의 늦은 연결 콜백은 상태를 바꾸지 않는다`() = runTest {
        val f = timeoutFixture(PeripheralId("AA:BB:CC:DD:EE:B3"), maxAttempts = 2)
        advanceTimeBy(10 + 1)
        runCurrent()
        val timedOutGeneration = f.session.connectionCoordinator.generation
        advanceTimeBy(ATTEMPT_END_MILLIS)
        runCurrent()
        assertTrue(
            "시도 timeout 이 발행되지 않았다: ${f.states}",
            f.states.any { failedCode(it) == "LINK_TIMEOUT" },
        )

        fun late(newState: Int, status: Int) = f.driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                deviceId = f.session.deviceId,
                source = GattCallbackSource { true },
                status = status,
                newState = newState,
                connectionGeneration = timedOutGeneration,
            ),
        )

        // (1) 백오프 대기 중 — 같은 generation 이지만 stage 는 Disconnected.
        var before = f.session.connectionState.value
        var count = f.states.size
        late(android.bluetooth.BluetoothProfile.STATE_CONNECTED, android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        late(android.bluetooth.BluetoothProfile.STATE_DISCONNECTED, android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertEquals(before, f.session.connectionState.value)
        assertEquals(count, f.states.size)

        // (2) 다음 시도 진행 중 — generation 이 바뀌었다.
        advanceTimeBy(10 + 1)
        runCurrent()
        assertEquals(ConnectionStage.Connecting, f.session.stage)
        assertTrue(f.session.connectionCoordinator.generation != timedOutGeneration)
        before = f.session.connectionState.value
        count = f.states.size
        late(android.bluetooth.BluetoothProfile.STATE_CONNECTED, android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        late(android.bluetooth.BluetoothProfile.STATE_DISCONNECTED, 8)
        runCurrent()
        assertEquals(before, f.session.connectionState.value)
        assertEquals(count, f.states.size)
        assertEquals(ConnectionStage.Connecting, f.session.stage)
    }
}
