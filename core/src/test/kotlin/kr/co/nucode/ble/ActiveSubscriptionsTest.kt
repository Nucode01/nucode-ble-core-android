package kr.co.nucode.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.DefaultBleSession
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.charRef
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.gatt.GattDescriptorIdentity
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertTrue(f.session.connectionState.value !is ConnectionState.Ready)
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(setOf(f.ref), f.intent())
        f.assertInvariant()
    }

    @Test
    fun `user disconnect empties active while intent is kept`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)

        f.bleSession.disconnect()
        runCurrent()

        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        assertEquals(setOf(f.ref), f.intent())
        f.assertInvariant()
    }

    @Test
    fun `successful restore puts ref back in active at Ready`() = runTest {
        val f = fixture()
        f.connectToReady(this)
        f.subscribe(this, f.ref)
        f.driver.emitLinkLoss(f.deviceId, f.session.connectionCoordinator.generation)
        runCurrent()
        f.assertInvariant()

        f.reachConfiguring(this)
        assertEquals(emptySet<CharacteristicRef>(), f.bleSession.activeSubscriptions.value)
        f.assertInvariant()

        var activeAtReady: Set<CharacteristicRef>? = null
        backgroundScope.launch {
            f.core.events.collect { event ->
                if (event == BleEvent.ConnStateChanged(f.deviceId, ConnectionState.Ready)) {
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
        f.assertInvariant()

        f.reachConfiguring(this)
        f.driver.descriptorStarts = false
        f.settleMtu(this)
        runCurrent()

        assertEquals(ConnectionState.Ready, f.bleSession.connectionState.value)
        assertTrue(f.ref !in f.bleSession.activeSubscriptions.value)
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

    private fun TestScope.fixture(): Fixture {
        val driver = FakeDriver()
        val resolver = GattOperationTargetResolver { _, ref ->
            GattOperationTarget(
                characteristic = BluetoothGattCharacteristic(ref.uuid.value, 0, 0),
                identity = GattCharacteristicIdentity.from(ref),
                propertyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                cccdDescriptor = BluetoothGattDescriptor(CCCD_UUID, 0),
            )
        }
        val core = BleRuntime(
            config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
            adapter = { null },
            gattDriver = driver,
            gattOperationTargetResolver = resolver,
            adapterState = { AdapterState.Ready },
        )
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:A5")
        val session = core.registry.getOrCreate(deviceId).apply { chunkSize = 20 }
        val client = DefaultBleClient(core)
        val bleSession = client.session(deviceId)
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        return Fixture(core, client, bleSession, session, driver, deviceId, charRef(UUID.randomUUID(), UUID.randomUUID()))
    }

    private class Fixture(
        val core: BleRuntime,
        val client: DefaultBleClient,
        val bleSession: DefaultBleSession,
        val session: Session,
        val driver: FakeDriver,
        val deviceId: PeripheralId,
        val ref: CharacteristicRef,
    ) {
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
            val generation = session.connectionCoordinator.beginConnection(scope, 100) {}
            session.connectionCoordinator.transition(
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Connecting,
                nextStage = ConnectionStage.DiscoveringServices,
                scope = scope,
                timeoutMillis = 100,
            )
            session.connectionCoordinator.transition(
                callbackGeneration = generation,
                expectedStage = ConnectionStage.DiscoveringServices,
                nextStage = ConnectionStage.Configuring,
                scope = scope,
                timeoutMillis = 100,
            )
            session.subscriptionState.beginGeneration(generation)
            session.gattOperationScheduler.beginNextGeneration()
        }

        /** lifecycle 의 MTU 발행 지점을 흉내 낸다 — 발행 후 `onMtuSettled`(재구독 → Ready). */
        fun settleMtu(scope: TestScope) {
            core.emit(BleEvent.MtuChanged(deviceId, 247, 244))
            core.connectSequence.onMtuSettled(deviceId)
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

        override fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

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
        ): Boolean = descriptorStarts

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

        fun emitDescriptor(deviceId: PeripheralId, ref: CharacteristicRef) {
            emit(
                GattDriverEvent.DescriptorWrite(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    status = BluetoothGatt.GATT_SUCCESS,
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
