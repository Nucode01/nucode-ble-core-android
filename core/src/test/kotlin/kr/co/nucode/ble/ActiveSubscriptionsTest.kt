package kr.co.nucode.ble

import kr.co.nucode.ble.internal.port.GattCallbackSource
import kr.co.nucode.ble.internal.port.GattDriverEvent
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.DefaultBleSession
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.TestStageAuthority
import kr.co.nucode.ble.internal.charRef
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.core.connection.ConnectionCore
import kr.co.nucode.ble.internal.core.connection.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor
import kr.co.nucode.ble.harness.FakePlatformCharacteristic
import kr.co.nucode.ble.harness.FakePlatformDescriptor

/**
 * plan failure-delivery-fixes v1 task 3 — `BleSession.activeSubscriptions` 가 현재 연결에서
 * CCCD 가 켜진 집합만 싣는지, 그리고 불변식 ② `activeSubscriptions ⊆ snapshot().subscriptions`
 * 가 매 단계에서 지켜지는지 공개 경계(DefaultBleClient/DefaultBleSession)로 확인한다.
 * 연결 절차 자체는 [BleCoreWiringTest] 의 Slice5 와 같은 runtime seam 으로 Configuring 까지 세운다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSubscriptionsTest {

    @Test
    fun `subscribe on Ready adds ref to active and intent`() = runTest {
        val f = fixture()
        f.connectToReady(this)

        f.subscribe(this, f.ref)

        assertEquals(setOf(f.ref), f.bleSession.activeSubscriptions.value)
        assertEquals(setOf(f.ref), f.intent())
        f.assertInvariant()
    }

    @Test
    fun `link loss empties active while intent is kept`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)

        f.driver.emitLinkLoss(f.deviceId, f.session.connectionCoordinator.generation)
        runCurrent()

        // Ready 링크 유실은 어댑터 정착 창(states.md §3)이 지난 뒤 Disconnected 로 확정된다.

        advanceTimeBy(ConnectionCore.ADAPTER_SETTLE_MILLIS)

        runCurrent()

        assertTrue(f.session.connectionState.value !is ConnectionState.Ready)
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(setOf(f.ref), f.intent())
        f.assertInvariant()
    }

    @Test
    fun `user disconnect empties active and clears intent`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)

        f.bleSession.disconnect()
        runCurrent()

        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(emptySet<CharacteristicRef>(), f.intent())
        f.assertInvariant()
    }

    @Test
    fun `user disconnect then reconnect writes no CCCD and restores nothing`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)
        f.bleSession.disconnect()
        runCurrent()
        f.driver.emitLinkLoss(f.deviceId, f.session.connectionCoordinator.generation)
        runCurrent()
        // Ready 링크 유실은 어댑터 정착 창(states.md §3)이 지난 뒤 Disconnected 로 확정된다.
        advanceTimeBy(ConnectionCore.ADAPTER_SETTLE_MILLIS)
        runCurrent()
        assertTrue(f.bleSession.connectionState.value is ConnectionState.Disconnected)
        val before = f.driver.descriptorWrites

        f.reachConfiguring(this)
        f.settleMtu(this)

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertEquals(before, f.driver.descriptorWrites)
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(emptySet<CharacteristicRef>(), f.intent())
    }

    @Test
    fun `link loss then reconnect restores intent with CCCD write`() = runTest {
        val f = fixture()
        f.reachRestore(this)
        val before = f.driver.descriptorWrites

        f.settleMtu(this)
        f.driver.emitDescriptor(f.deviceId, f.ref)
        runCurrent()

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertEquals(before + 1, f.driver.descriptorWrites)
        assertEquals(setOf(f.ref), f.bleSession.activeSubscriptions.value)
        assertEquals(setOf(f.ref), f.intent())
    }

    @Test
    fun `late CCCD ack after user disconnect does not mark active`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        val job = async { runCatching { f.bleSession.subscribe(f.ref) } }
        runCurrent()

        f.bleSession.disconnect()
        runCurrent()
        f.driver.emitDescriptor(f.deviceId, f.ref)
        runCurrent()
        val outcome = job.await()

        assertEquals("OPERATION_INVALIDATED", (outcome.exceptionOrNull() as? BleException)?.failure?.code?.value)
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(emptySet<CharacteristicRef>(), f.intent())
        f.assertInvariant()
    }

    /** ack 가 subscribe 재개보다 먼저 처리되고, 재개 전에 사용자 disconnect 블록이 도는 순서. */
    @Test
    fun `user disconnect between CCCD ack and subscribe resume does not restore intent`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        val job = async { runCatching { f.bleSession.subscribe(f.ref) } }
        runCurrent()

        // ack 는 sink 로 동기 처리돼 subscribe 재개가 코어 큐에 들어간다 — 그보다 먼저 disconnect 블록을 돌린다.
        f.driver.emitDescriptor(f.deviceId, f.ref)
        launch(f.coreDispatcher, start = CoroutineStart.UNDISPATCHED) { f.bleSession.disconnect() }
        runCurrent()
        val outcome = job.await()

        assertEquals("OPERATION_INVALIDATED", (outcome.exceptionOrNull() as? BleException)?.failure?.code?.value)
        assertEquals(emptySet<CharacteristicRef>(), f.intent())
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        f.assertInvariant()
    }

    @Test
    fun `successful restore puts ref back in active at Ready`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)
        f.driver.emitLinkLoss(f.deviceId, f.session.connectionCoordinator.generation)
        runCurrent()
        // Ready 링크 유실은 어댑터 정착 창(states.md §3)이 지난 뒤 Disconnected 로 확정된다.
        advanceTimeBy(ConnectionCore.ADAPTER_SETTLE_MILLIS)
        runCurrent()
        f.assertInvariant()

        f.reachConfiguring(this)
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        f.assertInvariant()

        var activeAtReady: Set<CharacteristicRef>? = null
        backgroundScope.launch {
            f.core.officialEvents.collect { event ->
                if (event == OfficialBleClientEvent.Connection(f.deviceId, ConnectionState.Ready)) {
                    activeAtReady = f.bleSession.activeSubscriptions.value
                }
            }
        }
        runCurrent()
        f.settleMtu(this)
        f.driver.emitDescriptor(f.deviceId, f.ref)
        runCurrent()

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertEquals(setOf(f.ref), activeAtReady)
        assertEquals(setOf(f.ref), f.bleSession.activeSubscriptions.value)
        f.assertInvariant()
    }

    @Test
    fun `failed CCCD restore reaches Ready without ref in active`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)
        f.driver.emitLinkLoss(f.deviceId, f.session.connectionCoordinator.generation)
        runCurrent()
        // Ready 링크 유실은 어댑터 정착 창(states.md §3)이 지난 뒤 Disconnected 로 확정된다.
        advanceTimeBy(ConnectionCore.ADAPTER_SETTLE_MILLIS)
        runCurrent()
        f.assertInvariant()

        f.reachConfiguring(this)
        f.driver.descriptorStarts = false
        f.settleMtu(this)
        runCurrent()

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "GATT_OPERATION_START_FAILED" in f.restoreFailureEventCodes())
        f.assertInvariant()
    }

    @Test
    fun `unsubscribe removes ref from active and intent`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)

        val unsubscribe = async { f.bleSession.unsubscribe(f.ref) }
        runCurrent()
        f.driver.emitDescriptor(f.deviceId, f.ref)
        runCurrent()
        unsubscribe.await()

        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(emptySet<CharacteristicRef>(), f.intent())
        f.assertInvariant()
    }

    // ── 복원 실패 route: State(activeSubscriptions 또는 connectionState) + Event ──

    @Test
    fun `restore CCCD status failure GATT_STATUS_FAILED keeps ref out of active with event`() = runTest {
        val f = fixture()
        f.reachRestore(this)

        f.settleMtu(this)
        f.driver.emitDescriptor(f.deviceId, f.ref, status = 133)
        runCurrent()

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "GATT_STATUS_FAILED" in f.restoreFailureEventCodes())
        f.assertInvariant()
    }

    @Test
    fun `restore unresolved target CHARACTERISTIC_NOT_FOUND keeps ref out of active with event`() = runTest {
        val f = fixture()
        f.reachRestore(this)
        f.driver.resolvable = false

        f.settleMtu(this)

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "CHARACTERISTIC_NOT_FOUND" in f.restoreFailureEventCodes())
        f.assertInvariant()
    }

    @Test
    fun `restore non-BleException INTERNAL_TRANSPORT_ERROR keeps ref out of active with event`() = runTest {
        val f = fixture()
        f.reachRestore(this)
        f.driver.resolveFailure = IllegalStateException("resolver broke")

        f.settleMtu(this)

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "INTERNAL_TRANSPORT_ERROR" in f.restoreFailureEventCodes())
        f.assertInvariant()
    }

    /** resolver 없는 조립에서 discovered target 은 있으나 gatt 핸들이 없으면 복원이 NOT_CONNECTED 로 끝난다. */
    @Test
    fun `restore without platform handle NOT_CONNECTED keeps ref out of active with event`() = runTest {
        val f = fixture(resolver = false)
        f.session.updateDiscovered(mapOf(f.ref to FakePlatformCharacteristic(f.ref.uuid.value)))
        f.session.subscriptionState.addDesired(f.ref)
        f.reachConfiguring(this)

        f.settleMtu(this)

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "NOT_CONNECTED" in f.restoreFailureEventCodes())
        f.assertInvariant()
    }

    @Test
    fun `restore CCCD ack timeout OPERATION_TIMEOUT publishes Disconnected Failed with event`() = runTest {
        val f = fixture()
        f.reachRestore(this)

        f.settleMtu(this)
        advanceTimeBy(BleConfig().operationTimeoutMillis + 1)
        runCurrent()

        val states = f.officialEvents.filterIsInstance<OfficialBleClientEvent.Connection>().map { it.state }
        assertTrue(states.toString(), states.any { failedCode(it) == "OPERATION_TIMEOUT" })
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "OPERATION_TIMEOUT" in f.restoreFailureEventCodes())
    }

    @Test
    fun `restore CCCD SecurityException PERMISSION_DENIED publishes Disconnected Failed with event`() = runTest {
        val f = fixture()
        f.reachRestore(this)
        f.driver.descriptorFailure = SecurityException()

        f.settleMtu(this)
        advanceTimeBy(BleConfig().disconnectCleanupTimeoutMillis + 1) // cleanup ack 대기를 끝낸다
        runCurrent()

        val states = f.officialEvents.filterIsInstance<OfficialBleClientEvent.Connection>().map { it.state }
        assertTrue(states.toString(), states.any { failedCode(it) == "PERMISSION_DENIED" })
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
        assertTrue(f.restoreFailureEventCodes().toString(), "PERMISSION_DENIED" in f.restoreFailureEventCodes())
    }

    private fun failedCode(state: ConnectionState): String? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    private fun TestScope.fixture(resolver: Boolean = true): Fixture {
        val driver = FakeDriver()
        val targetResolver = GattOperationTargetResolver { _, ref ->
            driver.resolveFailure?.let { throw it }
            if (!driver.resolvable) return@GattOperationTargetResolver null
            GattOperationTarget(
                characteristic = FakePlatformCharacteristic(ref.uuid.value),
                identity = GattCharacteristicIdentity.from(ref),
                propertyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                cccdDescriptor = FakePlatformDescriptor(CCCD_UUID),
            )
        }
        val coreDispatcher = StandardTestDispatcher(testScheduler)
        val core = BleRuntime(
            config = BleConfig(dispatcher = coreDispatcher),
            gattDriver = driver,
            gattOperationTargetResolver = if (resolver) targetResolver else null,
            adapterState = { AdapterState.Ready },
        )
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:A5")
        val session = core.registry.getOrCreate(deviceId).apply { chunkSize = 20 }
        val client = DefaultBleClient(core)
        val bleSession = client.session(deviceId)
        val officialEvents = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(officialEvents) }
        runCurrent()
        return Fixture(
            core, client, bleSession, session, driver, deviceId, charRef(UUID.randomUUID(), UUID.randomUUID()),
            officialEvents, coreDispatcher,
        )
    }

    private class Fixture(
        val core: BleRuntime,
        val client: DefaultBleClient,
        val bleSession: DefaultBleSession,
        val session: Session,
        val driver: FakeDriver,
        val deviceId: PeripheralId,
        val ref: CharacteristicRef,
        val officialEvents: List<BleClientEvent>,
        /** 코어 디스패처 — 경합 테스트가 코어 큐 순서에 끼어들 때 쓴다. */
        val coreDispatcher: CoroutineDispatcher,
) {
        /** 복원 실패의 Event 채널 — Diagnostic 또는 SubscriptionRestoreFailed 로 나간 코드. */
        fun restoreFailureEventCodes(): List<String> =
            officialEvents.filterIsInstance<OfficialBleClientEvent.Diagnostic>().map { it.failure.code.value } +
                officialEvents.filterIsInstance<OfficialBleClientEvent.SubscriptionRestoreFailed>()
                    .map { it.failure.code.value }

        /** Ready 에서 구독한 뒤 링크를 잃고 새 generation 의 Configuring 까지 세운다 — 복원 직전 상태. */
        suspend fun reachRestore(scope: TestScope) {
            connectToReady(scope)
            subscribe(scope, ref)
            driver.emitLinkLoss(deviceId, session.connectionCoordinator.generation)
            scope.runCurrent()
            // Ready 링크 유실은 어댑터 정착 창(states.md §3)이 지난 뒤 Disconnected 로 확정된다.
            scope.advanceTimeBy(ConnectionCore.ADAPTER_SETTLE_MILLIS)
            scope.runCurrent()
            reachConfiguring(scope)
            assertEquals(emptySet<CharacteristicRef>(), bleSession.activeSubscriptions.value)
        }
        suspend fun intent(): Set<CharacteristicRef> =
            client.snapshot().sessions.single { it.peripheralId == deviceId }.subscriptions

        /** 불변식 ②: activeSubscriptions ⊆ snapshot().subscriptions. */
        suspend fun assertInvariant() {
            val active = bleSession.activeSubscriptions.value
            val intent = intent()
            assertTrue("active $active must be subset of intent $intent", intent.containsAll(active))
        }

        /** Slice5 와 같은 seam — 새 generation 을 열고 Configuring 까지 세운다. */
        fun reachConfiguring(scope: TestScope) {
            val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, scope, 100) {}
            session.connectionCoordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = scope,
                timeoutMillis = 100,
            )
            session.connectionCoordinator.transition(
                TestStageAuthority,
                callbackGeneration = generation,
                expectedStage = ConnectionStage.DiscoveringServices,
                nextStage = ConnectionStage.Configuring,
                scope = scope,
                timeoutMillis = 100,
            )
            session.subscriptionState.beginGeneration(generation)
            session.gattOperationScheduler.beginNextGeneration()
        }

        /** lifecycle 의 MTU 확정 지점을 흉내 낸다 — 세션 mtu/chunkSize 반영 후 `onMtuSettled`(재구독 → Ready). */
        fun settleMtu(scope: TestScope) {
            val session = core.registry.get(deviceId)!!
            session.mtu = 247
            session.chunkSize = 244
            core.linkSetup.onMtuSettled(session, session.connectionCoordinator.generation)
            scope.runCurrent()
        }

        suspend fun connectToReady(scope: TestScope) {
            reachConfiguring(scope)
            settleMtu(scope)
            assertEquals(ConnectionState.Ready, bleSession.connectionState.value)
            assertInvariant()
        }

        suspend fun subscribe(scope: TestScope, ref: CharacteristicRef) {
            val job = scope.async { bleSession.subscribe(ref) }
            scope.runCurrent()
            assertInvariant()
            driver.emitDescriptor(deviceId, ref)
            scope.runCurrent()
            job.await()
        }
    }

    private class FakeDriver : NoopGattDriver() {
        var descriptorStarts: Boolean = true
        var resolvable: Boolean = true
        var resolveFailure: Exception? = null
        var descriptorFailure: Exception? = null
        var descriptorWrites: Int = 0

        override fun disconnect(deviceId: PeripheralId, gatt: GattHandle?) = Unit

        override fun close(deviceId: PeripheralId, gatt: GattHandle?) = Unit

        override fun setCharacteristicNotification(
            deviceId: PeripheralId,
            gatt: GattHandle?,
            characteristic: PlatformCharacteristic,
            enabled: Boolean,
        ): Boolean = true

        override fun writeDescriptor(
            deviceId: PeripheralId,
            gatt: GattHandle?,
            descriptor: PlatformDescriptor,
            value: ByteArray,
        ): Boolean {
            descriptorWrites++
            descriptorFailure?.let { throw it }
            return descriptorStarts
        }

        fun emitLinkLoss(deviceId: PeripheralId, generation: Long) {
            emit(
                GattDriverEvent.ConnectionStateChanged(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    status = 8,
                    newState = BluetoothProfile.STATE_DISCONNECTED,
                    connectionGeneration = generation,
                ),
            )
        }

        fun emitDescriptor(deviceId: PeripheralId, ref: CharacteristicRef, status: Int = BluetoothGatt.GATT_SUCCESS) {
            emit(
                GattDriverEvent.DescriptorWrite(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    status = status,
                    attributeIdentity = GattDescriptorIdentity(
                        characteristic = GattCharacteristicIdentity.from(ref),
                        descriptorUuid = CCCD_UUID,
                    ),
                ),
            )
        }
    }

    private companion object {
        val CURRENT_SOURCE = GattCallbackSource { true }
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
