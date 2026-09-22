package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.ConnectOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan cold-cache-rediscovery v4 task 8 — [GattBridge.connect] 의 cold-cache 재발견
 * 선행 분기 회귀 테스트. 다루는 케이스: cold 선행 · warm 미호출 · rediscovery null 경로 보존 ·
 * NotFound fail-fast · Unavailable 폴백 · disconnect 중 중단 · 호출자 취소 정리.
 *
 * "재발견 중 중복 connect"는 `BleRuntime.connect` 의 기존 상태 가드 영역이라 JVM 에서
 * 재현 불가하다(adapter seam 이 `{ null }` 이면 `ensureAdapterReady` 가 `Unsupported` 로
 * 즉시 실패해 `gattBridge.connect` 자체에 도달하지 못한다) — 실기기 오라클(task 6)이 다룬다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattBridgeRediscoveryTest {

    /** [GattBridge]가 요구하는 최소 driver — connect 호출을 [connectCalls]에 기록만 한다. */
    private class RecordingGattDriver(private val log: MutableList<String>? = null) : GattDriver {
        val connectCalls = mutableListOf<Pair<PeripheralId, Long>>()
        val disconnectCalls = mutableListOf<PeripheralId>()
        private var sink: (GattDriverEvent) -> Unit = {}

        override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
            this.sink = sink
        }

        override fun connect(deviceId: PeripheralId, autoConnect: Boolean): BluetoothGatt? = null

        override fun connect(deviceId: PeripheralId, autoConnect: Boolean, connectionGeneration: Long): BluetoothGatt? {
            connectCalls += deviceId to connectionGeneration
            log?.add("connect:${deviceId.value}")
            return null
        }

        override fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?) {
            disconnectCalls += deviceId
        }

        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

        override fun discoverServices(deviceId: PeripheralId, gatt: BluetoothGatt?): Boolean = true

        override fun requestMtu(deviceId: PeripheralId, gatt: BluetoothGatt?, mtu: Int): Boolean = true

        override fun setCharacteristicNotification(
            deviceId: PeripheralId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            enabled: Boolean,
        ): Boolean = true

        override fun writeDescriptor(
            deviceId: PeripheralId,
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
        ): Boolean = true

        override fun readCharacteristic(
            deviceId: PeripheralId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
        ): Boolean = true

        override fun writeCharacteristic(
            deviceId: PeripheralId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean = true
    }

    /** 고정 outcome 또는 [deferred] 로 지연되는 [PeripheralRediscovery] fake. */
    private class FakeRediscovery(
        private val outcome: RediscoveryOutcome? = RediscoveryOutcome.Found,
        private val deferred: CompletableDeferred<RediscoveryOutcome>? = null,
        private val log: MutableList<String>? = null,
    ) : PeripheralRediscovery {
        val calls = mutableListOf<Pair<PeripheralId, Long>>()

        override suspend fun rediscover(deviceId: PeripheralId, timeoutMillis: Long): RediscoveryOutcome {
            calls += deviceId to timeoutMillis
            log?.add("rediscover:${deviceId.value}")
            return deferred?.await() ?: requireNotNull(outcome) {
                "outcome 과 deferred 둘 다 없다"
            }
        }
    }

    private val rediscoveryTimeouts = ConnectionReadinessTimeouts(
        linkMillis = 15_000,
        discoveryMillis = 10_000,
        configurationMillis = 10_000,
        rediscoveryMillis = 4_000,
    )

    private fun assertFailureCode(expected: String, actual: Throwable?) {
        assertEquals(expected, (actual as? BleException)?.failure?.code?.value)
    }

    @Test
    fun `cold 주소는 driver connect 전에 rediscovery 를 1회 부르고 그 timeout 은 설정값과 같다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:01")
        registry.getOrCreate(deviceId)
        val log = mutableListOf<String>()
        val driver = RecordingGattDriver(log)
        val rediscovery = FakeRediscovery(outcome = RediscoveryOutcome.Found, log = log)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            rediscovery = rediscovery,
            linkCacheWarm = { false },
        )

        bridge.connect(deviceId, ConnectOptions())
        runCurrent()

        assertEquals(1, rediscovery.calls.size)
        assertEquals(deviceId, rediscovery.calls.single().first)
        assertEquals(rediscoveryTimeouts.rediscoveryMillis, rediscovery.calls.single().second)
        assertEquals(1, driver.connectCalls.size)
        // 순서 — rediscovery 반환 뒤에만 driver.connect 가 온다.
        assertEquals(listOf("rediscover:${deviceId.value}", "connect:${deviceId.value}"), log)
    }

    @Test
    fun `warm 주소는 rediscovery 를 부르지 않고 driver connect 만 1회 부른다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:02")
        registry.getOrCreate(deviceId)
        val warm = setOf(deviceId)
        val driver = RecordingGattDriver()
        val rediscovery = FakeRediscovery(outcome = RediscoveryOutcome.Found)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            rediscovery = rediscovery,
            linkCacheWarm = { it in warm },
        )

        bridge.connect(deviceId, ConnectOptions())
        runCurrent()

        assertTrue(rediscovery.calls.isEmpty())
        assertEquals(1, driver.connectCalls.size)
    }

    @Test
    fun `rediscovery 가 null 이면 기존 경로대로 driver connect 만 1회 부른다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:03")
        registry.getOrCreate(deviceId)
        val driver = RecordingGattDriver()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            // rediscovery 인자 생략 — 기본값 null
            linkCacheWarm = { false },
        )

        bridge.connect(deviceId, ConnectOptions())
        runCurrent()

        assertEquals(1, driver.connectCalls.size)
    }

    @Test
    fun `NotFound 는 fail-fast 로 PERIPHERAL_NOT_FOUND 를 던지고 driver connect 를 부르지 않는다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:04")
        val session = registry.getOrCreate(deviceId)
        val driver = RecordingGattDriver()
        val rediscovery = FakeRediscovery(outcome = RediscoveryOutcome.NotFound)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            rediscovery = rediscovery,
            linkCacheWarm = { false },
        )

        val failure = runCatching { bridge.connect(deviceId, ConnectOptions()) }.exceptionOrNull()
        runCurrent()

        assertFailureCode("PERIPHERAL_NOT_FOUND", failure)
        val bleFailure = (failure as BleException).failure
        assertEquals(FailurePhase.Connect, bleFailure.phase)
        assertEquals(Retryability.TransientMayHelp, bleFailure.retryability)
        assertTrue(driver.connectCalls.isEmpty())
        assertTrue(session.state is ConnState.Disconnected)
        assertTrue(session.suppressReconnect)
        val disconnectedReason = (session.state as ConnState.Disconnected).reason
        assertTrue(disconnectedReason is DisconnectReason.Failed)
        assertEquals("PERIPHERAL_NOT_FOUND", (disconnectedReason as DisconnectReason.Failed).failure.code.value)
    }

    @Test
    fun `Unavailable 은 직접 연결로 폴백하고 Diagnostic 을 1개 emit 한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:05")
        registry.getOrCreate(deviceId)
        val driver = RecordingGattDriver()
        val unavailableFailure = BleFailure(
            category = FailureCategory("scan"),
            code = FailureCode("SCAN_UNAVAILABLE"),
            phase = FailurePhase.Scan,
            retryability = Retryability.TransientMayHelp,
        )
        val rediscovery = FakeRediscovery(outcome = RediscoveryOutcome.Unavailable(unavailableFailure))
        val diagnostics = mutableListOf<BleEvent.Diagnostic>()
        val bridge = GattBridge(
            registry = registry,
            emit = { e -> if (e is BleEvent.Diagnostic) diagnostics += e },
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            rediscovery = rediscovery,
            linkCacheWarm = { false },
        )

        bridge.connect(deviceId, ConnectOptions())
        runCurrent()

        assertEquals(1, driver.connectCalls.size)
        assertEquals(1, diagnostics.size)
        assertEquals(Severity.Warning, diagnostics.single().severity)
        assertEquals(unavailableFailure, diagnostics.single().failure)
    }

    @Test
    fun `재발견 대기 중 disconnect 가 generation 을 끝내면 driver connect 는 불리지 않고 예외도 없다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:06")
        registry.getOrCreate(deviceId)
        val driver = RecordingGattDriver()
        val deferred = CompletableDeferred<RediscoveryOutcome>()
        val rediscovery = FakeRediscovery(outcome = null, deferred = deferred)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            rediscovery = rediscovery,
            linkCacheWarm = { false },
        )

        val connectJob = async { bridge.connect(deviceId, ConnectOptions()) }
        runCurrent()
        assertEquals(1, rediscovery.calls.size)
        assertTrue(driver.connectCalls.isEmpty())

        bridge.disconnect(deviceId)
        runCurrent()

        val session = registry.get(deviceId)!!
        // review MEDIUM 1 — gatt 가 아직 없어 driver.disconnect() 가 no-op 이다. 좁은 대안(narrow
        // alternative)은 disconnect 호출 즉시가 아니라 재발견 resume 시점에 cleanup 을 끝낸다 —
        // 그 전까지는 Disconnecting 에 머문다.
        assertTrue(session.state is ConnState.Disconnecting)

        deferred.complete(RediscoveryOutcome.Found)
        runCurrent()
        connectJob.await()

        assertTrue(driver.connectCalls.isEmpty())
        assertFalse(deferred.isCancelled)
        assertTrue(session.state is ConnState.Disconnected)
    }

    @Test
    fun `호출자 취소는 재발견 대기 중 열린 generation 을 정리하고 Disconnected 를 1회 emit 한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:07")
        val session = registry.getOrCreate(deviceId)
        val driver = RecordingGattDriver()
        val deferred = CompletableDeferred<RediscoveryOutcome>()
        val rediscovery = FakeRediscovery(outcome = null, deferred = deferred)
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = { events += it },
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = rediscoveryTimeouts,
            rediscovery = rediscovery,
            linkCacheWarm = { false },
        )

        val job = async { bridge.connect(deviceId, ConnectOptions()) }
        runCurrent()
        assertEquals(1, rediscovery.calls.size)
        val connStateChangesBeforeCancel = events.count { it is BleEvent.ConnStateChanged }

        job.cancel()
        runCurrent()

        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertTrue(session.state is ConnState.Disconnected)
        assertTrue(driver.connectCalls.isEmpty())
        val connStateChangesAfterCancel = events.count { it is BleEvent.ConnStateChanged } - connStateChangesBeforeCancel
        assertEquals(1, connStateChangesAfterCancel)
        val lastConnStateChanged = events.last { it is BleEvent.ConnStateChanged } as BleEvent.ConnStateChanged
        assertTrue(lastConnStateChanged.state is ConnState.Disconnected)
        assertTrue(job.isCancelled)
    }
}
