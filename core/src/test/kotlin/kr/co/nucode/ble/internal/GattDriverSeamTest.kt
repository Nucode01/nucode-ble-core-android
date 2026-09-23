package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.CharacteristicProperty
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.ConnectOptions
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.WriteResult
import kr.co.nucode.ble.WriteType
import kr.co.nucode.ble.internal.platform.android.AndroidGattCallbackRouter
import kr.co.nucode.ble.internal.platform.android.characteristicChangedEvent
import kr.co.nucode.ble.internal.platform.android.characteristicReadEvent
import kr.co.nucode.ble.internal.platform.android.characteristicWriteEvent
import kr.co.nucode.ble.internal.platform.android.descriptorWriteEvent
import kr.co.nucode.ble.internal.platform.android.identityIndexOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattDriverSeamTest {
    @Test
    fun `read command starts once and returns a defensive callback copy`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).setStateForTest(ConnState.Ready)
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val identity = GattCharacteristicIdentity.from(charRef)
        val callbackValue = byteArrayOf(1, 2, 3)

        val read = async { bridge.read(deviceId, charRef) }
        runCurrent()

        assertEquals(listOf(GattOperationKind.Read), starts)
        driver.emitRead(
            deviceId,
            GattCharacteristicSnapshot(
                serviceUuid = identity.serviceUuid,
                charUuid = identity.characteristicUuid,
                instanceIndex = identity.characteristicInstanceIndex,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
            ),
            callbackValue,
        )
        callbackValue[0] = 9
        runCurrent()

        assertEquals(listOf<Byte>(1, 2, 3), read.await().toList())
    }

    @Test
    fun `read synchronous start rejection fails only that operation without poison`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = FakeGattDriver(),
            ackTimeoutMillis = 5_000,
            operationStarter = { session, scheduled -> gattFailure(session, scheduled, 133) },
        )

        val failure = runCatching {
            bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID()))
        }.exceptionOrNull()

        assertFailureCode("GATT_STATUS_FAILED", failure)
        assertFalse(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
    }

    @Test
    fun `started read timeout poisons generation drains queue and closes handle`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 100,
            operationStarter = { _, _ -> null },
        )
        val firstRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val first = async { runCatching { bridge.read(deviceId, firstRef) }.exceptionOrNull() }
        runCurrent()
        val queued = async {
            runCatching { bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }
                .exceptionOrNull()
        }
        runCurrent()

        advanceTimeBy(100)
        runCurrent()

        assertFailureCode("OPERATION_TIMEOUT", first.await())
        assertFailureCode("OPERATION_INVALIDATED", queued.await())
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
        assertTrue(session.state is ConnState.Disconnected)
        assertEquals(listOf("disconnect", "close"), driver.commands)

        driver.emitRead(
            deviceId,
            GattCharacteristicSnapshot(
                serviceUuid = firstRef.service.uuid.value,
                charUuid = firstRef.uuid.value,
                instanceIndex = firstRef.instanceIndex,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
            ),
            byteArrayOf(9),
        )
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertTrue(
            events.filterIsInstance<BleEvent.Diagnostic>()
                .any { it.code == "GATT_OPERATION_DUPLICATE_CALLBACK" },
        )
    }

    @Test
    fun `poison tail keeps its cause when disconnect re-enters DISCONNECTED synchronously`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 100,
            operationStarter = { _, _ -> null },
        )
        driver.onDisconnect = { id ->
            driver.emit(
                GattDriverEvent.ConnectionStateChanged(
                    id,
                    CURRENT_SOURCE,
                    BluetoothGatt.GATT_SUCCESS,
                    BluetoothProfile.STATE_DISCONNECTED,
                ),
            )
        }

        val read = async { runCatching { bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }.exceptionOrNull() }
        runCurrent()

        advanceTimeBy(100)
        runCurrent()

        assertFailureCode("OPERATION_TIMEOUT", read.await())
        assertEquals("OPERATION_TIMEOUT", session.terminalLifecycleCause?.code?.value)
        val connStateChanges = events.filterIsInstance<BleEvent.ConnStateChanged>()
        assertEquals(1, connStateChanges.size)
        val disconnected = connStateChanges.single().state as ConnState.Disconnected
        val failedReason = disconnected.reason as DisconnectReason.Failed
        assertEquals("OPERATION_TIMEOUT", failedReason.failure.code.value)
        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertEquals(listOf("disconnect", "close"), driver.commands)
    }

    @Test
    fun `started caller cancellation propagates and poisons generation`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, _ -> null },
        )
        val first = async { bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }
        runCurrent()
        val queued = async {
            runCatching { bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }
                .exceptionOrNull()
        }
        runCurrent()

        first.cancelAndJoin()
        runCurrent()

        assertTrue(first.isCancelled)
        assertFailureCode("OPERATION_INVALIDATED", queued.await())
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect", "close"), driver.commands)
    }

    @Test
    fun `started write timeout through OperationQueue poisons generation and returns partial-safe failure`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 20
        }
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 100,
            operationStarter = { _, _ -> null },
        )
        val queue = OperationQueue(
            registry = registry,
            emit = events::add,
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 100,
            writeCommand = bridge::writeCommand,
        )

        val write = async {
            queue.write(
                deviceId,
                charRef(UUID.randomUUID(), UUID.randomUUID()),
                byteArrayOf(1),
                WriteType.WithResponse,
                BleChunking.Disabled,
            )
        }
        runCurrent()
        assertEquals(GattOperationKind.WriteWithResponse, session.gattOperationScheduler.pending?.operation?.kind)

        advanceTimeBy(100)
        runCurrent()

        val result = write.await() as WriteResult.Failed
        assertEquals(0, result.failure.writeProgress?.failedChunkIndex)
        assertEquals("OPERATION_TIMEOUT", result.failure.code.value)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
        assertTrue(session.state is ConnState.Disconnected)
        assertEquals(listOf("disconnect", "close"), driver.commands)
        assertTrue(
            events.filterIsInstance<BleEvent.Diagnostic>()
                .any { it.code == "OPERATION_TIMEOUT" },
        )
    }

    @Test
    fun `started write caller cancellation through OperationQueue propagates and poisons generation`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 20
        }
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, _ -> null },
        )
        val queue = OperationQueue(
            registry = registry,
            emit = events::add,
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 5_000,
            writeCommand = bridge::writeCommand,
        )

        val write = async {
            queue.write(
                deviceId,
                charRef(UUID.randomUUID(), UUID.randomUUID()),
                byteArrayOf(1),
                WriteType.WithResponse,
                BleChunking.Disabled,
            )
        }
        runCurrent()
        assertEquals(GattOperationKind.WriteWithResponse, session.gattOperationScheduler.pending?.operation?.kind)

        write.cancelAndJoin()
        runCurrent()

        assertTrue(write.isCancelled)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
        assertEquals(listOf("disconnect", "close"), driver.commands)
        assertTrue(
            events.filterIsInstance<BleEvent.Diagnostic>()
                .any { it.code == "OPERATION_INVALIDATED" },
        )
    }

    @Test
    fun `started CCCD timeout through subscribe poisons generation`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 100,
            operationStarter = { _, _ -> null },
        )

        val subscribe = async {
            runCatching { bridge.subscribe(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }.exceptionOrNull()
        }
        runCurrent()
        assertEquals(GattOperationKind.Subscribe, session.gattOperationScheduler.pending?.operation?.kind)

        advanceTimeBy(100)
        runCurrent()

        assertFailureCode("OPERATION_TIMEOUT", subscribe.await())
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
        assertEquals(listOf("disconnect", "close"), driver.commands)
        assertTrue(
            events.filterIsInstance<BleEvent.Diagnostic>()
                .any { it.code == "OPERATION_TIMEOUT" },
        )
    }

    @Test
    fun `started CCCD caller cancellation through subscribe propagates and poisons generation`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, _ -> null },
        )

        val subscribe = async {
            bridge.subscribe(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID()))
        }
        runCurrent()
        assertEquals(GattOperationKind.Subscribe, session.gattOperationScheduler.pending?.operation?.kind)

        subscribe.cancelAndJoin()
        runCurrent()

        assertTrue(subscribe.isCancelled)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
        assertEquals(listOf("disconnect", "close"), driver.commands)
        assertTrue(
            events.filterIsInstance<BleEvent.Diagnostic>()
                .any { it.code == "OPERATION_INVALIDATED" },
        )
    }

    @Test
    fun `disconnect drains scheduler waiters before platform callback`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, _ -> null },
        )
        val pending = async {
            runCatching { bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }
                .exceptionOrNull()
        }
        runCurrent()
        val queued = async {
            runCatching { bridge.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID())) }
                .exceptionOrNull()
        }
        runCurrent()

        bridge.disconnect(deviceId)
        runCurrent()

        assertFailureCode("OPERATION_INVALIDATED", pending.await())
        assertFailureCode("OPERATION_INVALIDATED", queued.await())
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect"), driver.commands)
    }

    @Test
    fun `same-UUID service instances keep their own instance index through discovery projection`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId)
        val events = mutableListOf<BleEvent>()
        val driver = FakeGattDriver()
        val bridge = GattBridge(registry, events::add, this, driver, ackTimeoutMillis = 5_000)
        bridge.connect(deviceId, ConnectOptions(autoConnect = true, timeoutMillis = null, requestedMtu = 185))
        driver.emit(
            GattDriverEvent.ConnectionStateChanged(deviceId, CURRENT_SOURCE, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED),
        )

        val serviceUuid = UUID.randomUUID()
        val charUuid = UUID.randomUUID()
        fun service(instance: Int) = GattServiceSnapshot(
            serviceUuid,
            listOf(
                GattCharacteristicSnapshot(
                    serviceUuid = serviceUuid,
                    charUuid = charUuid,
                    instanceIndex = 0,
                    propertyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                ),
            ),
            instanceIndex = instance,
        )
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                deviceId,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = listOf(service(0), service(1)),
            ),
        )

        val discovered = events.filterIsInstance<BleEvent.ServicesDiscovered>().single().services
        assertEquals(listOf(0, 1), discovered.map { it.ref.instanceIndex })
        // characteristic ref: service instance index comes from the enclosing service, char instance stays 0
        assertEquals(
            listOf(charRef(serviceUuid, charUuid, instanceIndex = 0, serviceInstanceIndex = 0), charRef(serviceUuid, charUuid, instanceIndex = 0, serviceInstanceIndex = 1)),
            discovered.map { it.characteristics.single().ref },
        )
        assertEquals(
            GattCharacteristicIdentity(serviceUuid, 1, charUuid, 0),
            GattCharacteristicIdentity.from(discovered[1].characteristics.single().ref),
        )
    }

    @Test
    fun `fake driver injects commands and callbacks without BluetoothGatt`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId)
        val events = mutableListOf<BleEvent>()
        val driver = FakeGattDriver()
        val bridge = GattBridge(registry, events::add, this, driver, ackTimeoutMillis = 5_000)

        bridge.connect(deviceId, ConnectOptions(autoConnect = true, timeoutMillis = null, requestedMtu = 185))
        bridge.discoverServices(deviceId)
        bridge.requestMtu(deviceId, 185)

        assertEquals(listOf("connect:true", "discover", "mtu:185"), driver.commands)
        assertEquals(185, session.requestedMtu)

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                deviceId,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            ),
        )
        assertEquals(ConnState.DiscoveringServices, session.state)

        val serviceUuid = UUID.randomUUID()
        val charUuid = UUID.randomUUID()
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                deviceId,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = listOf(
                    GattServiceSnapshot(
                        serviceUuid,
                        listOf(
                            GattCharacteristicSnapshot(
                                serviceUuid = serviceUuid,
                                charUuid = charUuid,
                                instanceIndex = 0,
                                propertyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val discovered = events.filterIsInstance<BleEvent.ServicesDiscovered>().single()
        assertEquals(charRef(serviceUuid, charUuid), discovered.services.single().characteristics.single().ref)
        assertEquals(
            setOf(CharacteristicProperty.Notify),
            discovered.services.single().characteristics.single().properties,
        )

        driver.emit(GattDriverEvent.MtuChanged(deviceId, CURRENT_SOURCE, 185, BluetoothGatt.GATT_SUCCESS))
        assertEquals(185, session.mtu)
        assertEquals(182, session.chunkSize)

        val bytes = byteArrayOf(1, 2, 3)
        driver.emit(
            GattDriverEvent.CharacteristicChanged(
                deviceId,
                CURRENT_SOURCE,
                GattCharacteristicSnapshot(serviceUuid, charUuid, 0, 0),
                bytes,
            ),
        )
        assertEquals(BleEvent.CharValue(deviceId, charRef(serviceUuid, charUuid), bytes), events.last())

        val characteristicIdentity = GattCharacteristicIdentity.from(charRef(serviceUuid, charUuid))
        val scheduledWrite = session.gattOperationScheduler.enqueue(
            GattOperation.WriteWithResponse(
                session.gattOperationScheduler.currentGeneration,
                characteristicIdentity,
                byteArrayOf(1),
            ),
        )
        session.gattOperationScheduler.startNext()
        driver.emitCharacteristicWrite(characteristicIdentity, status = 133)
        assertFailureCode("GATT_STATUS_FAILED", runCatching { scheduledWrite.completion.await() }.exceptionOrNull())
        assertNull(session.gattOperationScheduler.pending)

        val descriptorIdentity = GattDescriptorIdentity(
            characteristicIdentity,
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )
        val scheduledCccd = session.gattOperationScheduler.enqueue(
            GattOperation.Subscribe(session.gattOperationScheduler.currentGeneration, descriptorIdentity),
        )
        session.gattOperationScheduler.startNext()
        driver.emitDescriptorWrite(descriptorIdentity)
        assertEquals(Unit, scheduledCccd.completion.await())
        assertNull(session.gattOperationScheduler.pending)

        bridge.disconnect(deviceId)
        assertEquals(listOf("connect:true", "discover", "mtu:185", "disconnect"), driver.commands)
        assertNull(session.gatt)

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                deviceId,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_DISCONNECTED,
            ),
        )
        assertTrue(session.state is ConnState.Disconnected)
        assertEquals("close", driver.commands.last())
    }

    @Test
    fun `stale callback source cannot mutate the current Session`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId)
        session.setStateForTest(ConnState.Connecting)
        val events = mutableListOf<BleEvent>()
        val driver = FakeGattDriver()
        GattBridge(registry, events::add, this, driver, ackTimeoutMillis = 5_000)

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                deviceId,
                GattCallbackSource { false },
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            ),
        )

        assertEquals(ConnState.Connecting, session.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `fake read success completes only the matching pending read`() = runTest {
        val fixture = readFixture(this)
        val bytes = byteArrayOf(1, 2, 3)

        fixture.driver.emitRead(fixture.deviceId, fixture.snapshot, bytes)

        assertTrue(fixture.scheduled.completion.isCompleted)
        assertEquals(bytes.toList(), fixture.scheduled.completion.await().toList())
        assertNull(fixture.session.gattOperationScheduler.pending)
    }

    @Test
    fun `fake read status failure completes pending read with typed GATT failure`() = runTest {
        val fixture = readFixture(this)

        fixture.driver.emitRead(fixture.deviceId, fixture.snapshot, byteArrayOf(), status = 133)

        val failure = runCatching { fixture.scheduled.completion.await() }.exceptionOrNull()
        assertFailureCode("GATT_STATUS_FAILED", failure)
        failure as BleException
        assertEquals(fixture.scheduled.operationId, failure.failure.context.operationId)
        assertEquals(fixture.session.failureSessionId, failure.failure.context.sessionId)
        assertNull(fixture.session.gattOperationScheduler.pending)
    }

    @Test
    fun `fake read wrong target leaves pending read untouched`() = runTest {
        val fixture = readFixture(this)
        val wrongTarget = fixture.snapshot.copy(charUuid = UUID.randomUUID())

        fixture.driver.emitRead(fixture.deviceId, wrongTarget, byteArrayOf(9))

        assertFalse(fixture.scheduled.completion.isCompleted)
        assertSame(fixture.scheduled, fixture.session.gattOperationScheduler.pending)

        fixture.driver.emitRead(fixture.deviceId, fixture.snapshot, byteArrayOf(1))
        assertEquals(listOf<Byte>(1), fixture.scheduled.completion.await().toList())
    }

    @Test
    fun `write와 CCCD는 common FIFO에서 exact target callback으로만 차례로 완료된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val characteristicIdentity = GattCharacteristicIdentity.from(charRef)
        val descriptorIdentity = GattDescriptorIdentity(
            characteristicIdentity,
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )
        val characteristic = BluetoothGattCharacteristic(charRef.uuid.value, 0, 0)

        val write = async {
            bridge.writeChunk(
                session,
                characteristic,
                charRef,
                byteArrayOf(1),
                kr.co.nucode.ble.WriteType.WithResponse,
            )
        }
        runCurrent()
        val subscribe = async { bridge.subscribe(deviceId, charRef) }
        runCurrent()

        assertEquals(listOf(GattOperationKind.WriteWithResponse), starts)
        driver.emitDescriptorWrite(descriptorIdentity)
        driver.emitCharacteristicWrite(
            characteristicIdentity.copy(characteristicInstanceIndex = 1),
        )
        assertFalse(write.isCompleted)
        assertFalse(subscribe.isCompleted)
        assertEquals(listOf(GattOperationKind.WriteWithResponse), starts)
        assertEquals(
            2,
            events.filterIsInstance<BleEvent.Diagnostic>()
                .count { it.code == "GATT_OPERATION_CALLBACK_MISMATCH" },
        )

        driver.emitCharacteristicWrite(characteristicIdentity)
        runCurrent()
        assertEquals(
            listOf(GattOperationKind.WriteWithResponse, GattOperationKind.Subscribe),
            starts,
        )
        assertEquals(BluetoothGatt.GATT_SUCCESS, write.await())
        assertFalse(subscribe.isCompleted)

        driver.emitDescriptorWrite(descriptorIdentity)
        runCurrent()
        subscribe.await()
        assertNull(session.gattOperationScheduler.pending)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
    }

    @Test
    fun `pending 없는 characteristic callback은 duplicate diagnostic으로 기록된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId)
        val driver = FakeGattDriver()
        val events = mutableListOf<BleEvent>()
        GattBridge(registry, events::add, this, driver, ackTimeoutMillis = 5_000)

        driver.emitCharacteristicWrite(characteristicIdentity())

        assertEquals(
            "GATT_OPERATION_DUPLICATE_CALLBACK",
            events.filterIsInstance<BleEvent.Diagnostic>().single().code,
        )
    }

    @Test
    fun `pre-start queue time은 write ACK timeout을 소비하지 않는다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 20
        }
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 100,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val characteristicIdentity = GattCharacteristicIdentity.from(charRef)
        val blocker = async {
            bridge.writeChunk(
                session,
                BluetoothGattCharacteristic(charRef.uuid.value, 0, 0),
                charRef,
                byteArrayOf(0),
                WriteType.WithResponse,
            )
        }
        runCurrent()
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 100,
            writeCommand = bridge::writeCommand,
        )
        val write = async {
            queue.write(deviceId, charRef, byteArrayOf(1), WriteType.WithResponse, BleChunking.Disabled)
        }
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()
        assertFalse("queue 대기 시간이 ACK timeout으로 계산됐다", write.isCompleted)
        assertEquals(listOf(GattOperationKind.WriteWithResponse), starts)

        driver.emitCharacteristicWrite(characteristicIdentity)
        runCurrent()
        blocker.await()
        assertEquals(
            listOf(GattOperationKind.WriteWithResponse, GattOperationKind.WriteWithResponse),
            starts,
        )

        advanceTimeBy(99)
        runCurrent()
        assertFalse(write.isCompleted)
        driver.emitCharacteristicWrite(characteristicIdentity)
        runCurrent()
        assertEquals(WriteResult.Completed(1), write.await())
    }

    @Test
    fun `pre-start public write 취소는 예약한 모든 청크를 제거한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 2
        }
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val identity = GattCharacteristicIdentity.from(charRef)
        val blocker = async {
            bridge.writeChunk(
                session,
                BluetoothGattCharacteristic(charRef.uuid.value, 0, 0),
                charRef,
                byteArrayOf(0),
                WriteType.WithResponse,
            )
        }
        runCurrent()
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 5_000,
            writeCommand = bridge::writeCommand,
        )
        val queuedWrite = async {
            queue.write(deviceId, charRef, byteArrayOf(1, 2, 3, 4), WriteType.WithResponse, BleChunking.Automatic)
        }
        runCurrent()
        assertEquals(2, session.gattOperationScheduler.queuedCount)

        queuedWrite.cancelAndJoin()
        assertEquals(0, session.gattOperationScheduler.queuedCount)

        driver.emitCharacteristicWrite(identity)
        runCurrent()
        blocker.await()
        assertEquals(listOf(GattOperationKind.WriteWithResponse), starts)
    }

    @Test
    fun `Task4 drain이 pre-start completion을 취소하면 started 대기 caller도 종결된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
        val driver = FakeGattDriver()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, _ -> null },
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val identity = GattCharacteristicIdentity.from(charRef)
        val blocker = async {
            bridge.writeChunk(
                session,
                BluetoothGattCharacteristic(charRef.uuid.value, 0, 0),
                charRef,
                byteArrayOf(0),
                WriteType.WithResponse,
            )
        }
        runCurrent()
        val subscribe = async { bridge.subscribe(deviceId, charRef) }
        runCurrent()

        val drained = session.gattOperationScheduler.drain()
        assertEquals(1, drained.queued.size)
        drained.queued.single().completion.cancel()
        runCurrent()

        assertTrue(subscribe.isCompleted)
        assertTrue(runCatching { subscribe.await() }.exceptionOrNull() is CancellationException)

        // drain은 pending blocker도 회수했으므로 직접 종결해 테스트 코루틴을 남기지 않는다.
        drained.pending!!.completion.cancel()
        runCurrent()
        assertEquals(BluetoothGatt.GATT_FAILURE, blocker.await())
        driver.emitCharacteristicWrite(identity)
    }

    @Test
    fun `multi-chunk public write 전체가 뒤에 enqueue된 CCCD보다 먼저 시작된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 2
        }
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
        )
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 5_000,
            writeCommand = bridge::writeCommand,
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val characteristicIdentity = GattCharacteristicIdentity.from(charRef)
        val descriptorIdentity = GattDescriptorIdentity(
            characteristicIdentity,
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )

        val write = async {
            queue.write(deviceId, charRef, byteArrayOf(1, 2, 3, 4), WriteType.WithResponse, BleChunking.Automatic)
        }
        runCurrent()
        val subscribe = async { bridge.subscribe(deviceId, charRef) }
        runCurrent()

        assertEquals(listOf(GattOperationKind.WriteWithResponse), starts)
        driver.emitCharacteristicWrite(characteristicIdentity)
        runCurrent()
        assertEquals(
            listOf(GattOperationKind.WriteWithResponse, GattOperationKind.WriteWithResponse),
            starts,
        )
        driver.emitCharacteristicWrite(characteristicIdentity)
        runCurrent()
        assertEquals(
            listOf(
                GattOperationKind.WriteWithResponse,
                GattOperationKind.WriteWithResponse,
                GattOperationKind.Subscribe,
            ),
            starts,
        )
        assertEquals(WriteResult.Completed(2), write.await())

        driver.emitDescriptorWrite(descriptorIdentity)
        runCurrent()
        subscribe.await()
    }

    @Test
    fun `동시에 들어온 multi-chunk write 두 개는 command 단위 FIFO로 실행된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 2
        }
        val firstRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val secondRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val firstIdentity = GattCharacteristicIdentity.from(firstRef)
        val secondIdentity = GattCharacteristicIdentity.from(secondRef)
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattCharacteristicIdentity>()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.attributeIdentity as GattCharacteristicIdentity
                null
            },
        )
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 5_000,
            writeCommand = bridge::writeCommand,
        )

        val first = async {
            queue.write(deviceId, firstRef, byteArrayOf(1, 2, 3, 4), WriteType.WithResponse, BleChunking.Automatic)
        }
        runCurrent()
        val second = async {
            queue.write(deviceId, secondRef, byteArrayOf(5, 6, 7, 8), WriteType.WithResponse, BleChunking.Automatic)
        }
        runCurrent()

        assertEquals(listOf(firstIdentity), starts)
        driver.emitCharacteristicWrite(firstIdentity)
        runCurrent()
        assertEquals(listOf(firstIdentity, firstIdentity), starts)
        driver.emitCharacteristicWrite(firstIdentity)
        runCurrent()
        assertEquals(listOf(firstIdentity, firstIdentity, secondIdentity), starts)
        assertEquals(WriteResult.Completed(2), first.await())

        driver.emitCharacteristicWrite(secondIdentity)
        runCurrent()
        assertEquals(listOf(firstIdentity, firstIdentity, secondIdentity, secondIdentity), starts)
        driver.emitCharacteristicWrite(secondIdentity)
        runCurrent()

        assertEquals(WriteResult.Completed(2), second.await())
    }

    @Test
    fun `multi-chunk write 실패 뒤 남은 청크는 시작하지 않고 다음 command로 진행한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 2
        }
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
        )
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 5_000,
            writeCommand = bridge::writeCommand,
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val characteristicIdentity = GattCharacteristicIdentity.from(charRef)
        val descriptorIdentity = GattDescriptorIdentity(
            characteristicIdentity,
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )
        val write = async {
            queue.write(deviceId, charRef, byteArrayOf(1, 2, 3, 4), WriteType.WithResponse, BleChunking.Automatic)
        }
        runCurrent()
        val subscribe = async { bridge.subscribe(deviceId, charRef) }
        runCurrent()

        driver.emitCharacteristicWrite(characteristicIdentity, status = 133)
        runCurrent()

        assertEquals(
            listOf(GattOperationKind.WriteWithResponse, GattOperationKind.Subscribe),
            starts,
        )
        val result = write.await() as WriteResult.Failed
        assertEquals(0, result.failure.writeProgress?.failedChunkIndex)
        assertEquals("GATT_STATUS_FAILED", result.failure.code.value)
        assertEquals(133, (result.failure.platformDetail as PlatformDetail.Android).statusCode)
        assertEquals("onCharacteristicWrite", (result.failure.platformDetail as PlatformDetail.Android).apiOperation)
        assertTrue(result.failure.context.operationId?.startsWith("gatt-") == true)

        driver.emitDescriptorWrite(descriptorIdentity)
        runCurrent()
        subscribe.await()
    }

    @Test
    fun `WriteType Auto는 queue 대기 뒤 실제 start에서 다시 판정한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnState.Ready)
            chunkSize = 20
        }
        val driver = FakeGattDriver()
        val starts = mutableListOf<GattOperationKind>()
        var resolvedType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            operationStarter = { _, scheduled ->
                starts += scheduled.operation.kind
                null
            },
            operationWriteTypeResolver = { _, _, _ -> resolvedType },
        )
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = bridge::writeChunk,
            ackTimeoutMillis = 5_000,
            writeCommand = bridge::writeCommand,
        )
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val characteristicIdentity = GattCharacteristicIdentity.from(charRef)
        val descriptorIdentity = GattDescriptorIdentity(
            characteristicIdentity,
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )
        val blocker = async { bridge.subscribe(deviceId, charRef) }
        runCurrent()
        val write = async {
            queue.write(deviceId, charRef, byteArrayOf(1), WriteType.Auto, BleChunking.Disabled)
        }
        runCurrent()

        resolvedType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        driver.emitDescriptorWrite(descriptorIdentity)
        runCurrent()
        blocker.await()

        assertEquals(
            listOf(GattOperationKind.Subscribe, GattOperationKind.WriteWithoutResponse),
            starts,
        )
        driver.emitCharacteristicWrite(characteristicIdentity)
        runCurrent()
        assertEquals(WriteResult.Completed(1), write.await())
    }

    @Test
    fun `fake characteristic write callback preserves wrong target identity`() {
        val driver = FakeGattDriver()
        val expected = characteristicIdentity()
        val wrongTarget = expected.copy(characteristicInstanceIndex = expected.characteristicInstanceIndex + 1)
        var forwarded: GattDriverEvent? = null
        driver.setEventSink { forwarded = it }

        driver.emitCharacteristicWrite(wrongTarget)

        val event = forwarded as GattDriverEvent.CharacteristicWrite
        assertNotEquals(expected, event.attributeIdentity)
        assertEquals(wrongTarget, event.attributeIdentity)
    }

    @Test
    fun `fake descriptor write callback preserves characteristic and descriptor wrong target identity`() {
        val driver = FakeGattDriver()
        val expectedCharacteristic = characteristicIdentity()
        val expected = GattDescriptorIdentity(expectedCharacteristic, UUID.randomUUID())
        val wrongTarget = GattDescriptorIdentity(
            characteristic = expectedCharacteristic.copy(
                serviceInstanceIndex = expectedCharacteristic.serviceInstanceIndex + 1,
            ),
            descriptorUuid = UUID.randomUUID(),
        )
        var forwarded: GattDriverEvent? = null
        driver.setEventSink { forwarded = it }

        driver.emitDescriptorWrite(wrongTarget)

        val event = forwarded as GattDriverEvent.DescriptorWrite
        assertNotEquals(expected, event.attributeIdentity)
        assertEquals(wrongTarget.characteristic, event.attributeIdentity?.characteristic)
        assertEquals(wrongTarget.descriptorUuid, event.attributeIdentity?.descriptorUuid)
    }

    @Test
    fun `Android callback의 stale platform 객체에는 instance index 0을 위조하지 않는다`() {
        val current = Any()
        val stale = Any()

        assertEquals(0, identityIndexOrNull(listOf(current), current))
        assertNull(identityIndexOrNull(listOf(current), stale))
    }

    @Test
    fun `Android callback router forwards callbacks through the supplied core scope`() = runTest {
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val router = AndroidGattCallbackRouter(scope = this)
        var delivered = false
        router.setEventSink { delivered = true }

        router.forward(
            GattDriverEvent.CharacteristicRead(
                deviceId,
                CURRENT_SOURCE,
                characteristic = null,
                value = byteArrayOf(1),
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )

        assertFalse(delivered)
        runCurrent()
        assertTrue(delivered)
    }

    @Test
    fun `Android callback event conversion snapshots bytes and exact attribute identity`() {
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val source = CURRENT_SOURCE
        val identity = characteristicIdentity()
        val characteristic = GattCharacteristicSnapshot(
            serviceUuid = identity.serviceUuid,
            charUuid = identity.characteristicUuid,
            instanceIndex = identity.characteristicInstanceIndex,
            propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
            serviceInstanceIndex = identity.serviceInstanceIndex,
        )
        val changedBytes = byteArrayOf(1, 2)
        val readBytes = byteArrayOf(3, 4)

        val changed = characteristicChangedEvent(deviceId, source, characteristic, changedBytes)
        val read = characteristicReadEvent(
            deviceId,
            source,
            characteristic,
            readBytes,
            BluetoothGatt.GATT_SUCCESS,
        )
        val write = characteristicWriteEvent(deviceId, source, BluetoothGatt.GATT_SUCCESS, characteristic)
        val descriptorUuid = UUID.randomUUID()
        val descriptor = descriptorWriteEvent(
            deviceId,
            source,
            BluetoothGatt.GATT_SUCCESS,
            characteristic,
            descriptorUuid,
        )
        changedBytes[0] = 9
        readBytes[0] = 9

        assertTrue(changed.value.contentEquals(byteArrayOf(1, 2)))
        assertTrue(read.value.contentEquals(byteArrayOf(3, 4)))
        assertEquals(identity, write.attributeIdentity)
        assertEquals(identity, descriptor.attributeIdentity?.characteristic)
        assertEquals(descriptorUuid, descriptor.attributeIdentity?.descriptorUuid)
    }

    @Test
    fun `Android callback overload contract emits legacy values only before API 33`() {
        val source = java.io.File(
            "src/main/kotlin/kr/co/nucode/ble/internal/platform/android/AndroidGattCallbackRouter.kt",
        ).readText()

        assertEquals(2, Regex("override fun onCharacteristicChanged\\(").findAll(source).count())
        assertEquals(2, Regex("override fun onCharacteristicRead\\(").findAll(source).count())
        assertEquals(
            2,
            Regex("Build\\.VERSION\\.SDK_INT < Build\\.VERSION_CODES\\.TIRAMISU")
                .findAll(source)
                .count(),
        )
        assertEquals(2, Regex("characteristicChangedEvent\\(").findAll(source).count() - 1)
        assertEquals(2, Regex("characteristicReadEvent\\(").findAll(source).count() - 1)
    }

    private fun readFixture(scope: CoroutineScope): ReadFixture {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId)
        val driver = FakeGattDriver()
        GattBridge(registry, {}, scope, driver, ackTimeoutMillis = 5_000)
        val target = characteristicIdentity()
        val scheduler = session.gattOperationScheduler
        val generation = scheduler.beginNextGeneration()
        val scheduled = scheduler.enqueue(GattOperation.Read(generation, target))
        scheduler.startNext()
        val snapshot = GattCharacteristicSnapshot(
            serviceUuid = target.serviceUuid,
            charUuid = target.characteristicUuid,
            instanceIndex = target.characteristicInstanceIndex,
            propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
            serviceInstanceIndex = target.serviceInstanceIndex,
        )
        return ReadFixture(deviceId, session, driver, scheduled, snapshot)
    }

    private fun characteristicIdentity(): GattCharacteristicIdentity = GattCharacteristicIdentity(
        serviceUuid = UUID.randomUUID(),
        serviceInstanceIndex = 1,
        characteristicUuid = UUID.randomUUID(),
        characteristicInstanceIndex = 2,
    )

    private data class ReadFixture(
        val deviceId: PeripheralId,
        val session: Session,
        val driver: FakeGattDriver,
        val scheduled: ScheduledGattOperation<ByteArray>,
        val snapshot: GattCharacteristicSnapshot,
    )

    private class FakeGattDriver : NoopGattDriver() {
        val commands = mutableListOf<String>()
        var onDisconnect: ((PeripheralId) -> Unit)? = null

        override fun connect(deviceId: PeripheralId, autoConnect: Boolean): BluetoothGatt? {
            commands += "connect:$autoConnect"
            return null
        }

        override fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?) {
            commands += "disconnect"
            onDisconnect?.invoke(deviceId)
        }

        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) {
            commands += "close"
        }

        override fun discoverServices(deviceId: PeripheralId, gatt: BluetoothGatt?): Boolean {
            commands += "discover"
            return true
        }

        override fun requestMtu(deviceId: PeripheralId, gatt: BluetoothGatt?, mtu: Int): Boolean {
            commands += "mtu:$mtu"
            return true
        }

        fun emitRead(
            deviceId: PeripheralId,
            characteristic: GattCharacteristicSnapshot,
            value: ByteArray,
            status: Int = BluetoothGatt.GATT_SUCCESS,
        ) {
            emit(
                GattDriverEvent.CharacteristicRead(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    characteristic = characteristic,
                    value = value,
                    status = status,
                ),
            )
        }

        fun emitCharacteristicWrite(
            attributeIdentity: GattCharacteristicIdentity,
            status: Int = BluetoothGatt.GATT_SUCCESS,
        ) {
            emit(
                GattDriverEvent.CharacteristicWrite(
                    deviceId = PeripheralId("AA:BB:CC:DD:EE:FF"),
                    source = CURRENT_SOURCE,
                    status = status,
                    attributeIdentity = attributeIdentity,
                ),
            )
        }

        fun emitDescriptorWrite(
            attributeIdentity: GattDescriptorIdentity,
            status: Int = BluetoothGatt.GATT_SUCCESS,
        ) {
            emit(
                GattDriverEvent.DescriptorWrite(
                    deviceId = PeripheralId("AA:BB:CC:DD:EE:FF"),
                    source = CURRENT_SOURCE,
                    status = status,
                    attributeIdentity = attributeIdentity,
                ),
            )
        }
    }

    private companion object {
        val CURRENT_SOURCE = GattCallbackSource { true }
    }
}

private val BleEvent.Diagnostic.code: String
    get() = failure.code.value

private fun assertFailureCode(expected: String, throwable: Throwable?) {
    val failure = (throwable as? BleException)?.failure
    assertEquals(expected, failure?.code?.value)
}

private fun gattFailure(
    session: Session,
    scheduled: ScheduledGattOperation<*>,
    status: Int,
): BleFailure = BleFailure(
    category = FailureCategory("operation"),
    code = FailureCode("GATT_STATUS_FAILED"),
    phase = FailurePhase.Operation,
    retryability = Retryability.Unknown,
    context = FailureContext(
        sessionId = session.failureSessionId,
        connectionGeneration = session.gattOperationScheduler.currentGeneration,
    ),
    platformDetail = PlatformDetail.Android(
        domain = "android.bluetooth.gatt.status",
        statusCode = status,
        apiOperation = scheduled.operation.kind.name,
    ),
)
