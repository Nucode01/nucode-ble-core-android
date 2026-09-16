package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kr.co.nucode.ble.BleException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleCore
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.CharRef
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATT lifecycle characterization and Slice 2 readiness callback routing regression tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattLifecycleCharacterizationTest {

    @Test
    fun `connect starts one coordinator generation and passes it to the driver fake`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID)
        val driver = FakeGattDriver(this)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(15_000, 10_000, 10_000),
        )

        bridge.connect(DEVICE_ID, kr.co.nucode.ble.ConnectOptions(timeoutMillis = null))

        assertEquals(1L, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(1L, driver.connectionGeneration)
        assertEquals(listOf("connect:false"), driver.commands)
    }

    @Test
    fun `discovery permission exception enters bounded terminal cleanup`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply { state = ConnState.DiscoveringServices }
        val driver = FakeGattDriver(this).apply { discoverFailure = SecurityException() }
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(15_000, 10_000, 10_000, 20),
        )
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            timeoutMillis = 10_000,
        )

        bridge.discoverServices(DEVICE_ID)

        assertEquals("PERMISSION_DENIED", session.terminalLifecycleCause?.code?.value)
        assertEquals(ConnectionStage.Disconnecting, session.connectionCoordinator.stage)
        assertEquals(listOf("discover", "disconnect"), driver.commands)

        advanceTimeBy(20)
        runCurrent()
        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertTrue(session.state is ConnState.Disconnected)
    }

    @Test
    fun `operation permission exception is typed and enters bounded terminal cleanup`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply { state = ConnState.Ready }
        val driver = FakeGattDriver(this)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(15_000, 10_000, 10_000, 20),
            operationStarter = { _, _ -> throw SecurityException() },
        )
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            timeoutMillis = 10_000,
        )
        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            timeoutMillis = 10_000,
        )
        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )

        val thrown = runCatching {
            bridge.read(DEVICE_ID, CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
        }.exceptionOrNull()

        assertEquals("PERMISSION_DENIED", (thrown as? BleException)?.failure?.code?.value)
        assertEquals("PERMISSION_DENIED", session.terminalLifecycleCause?.code?.value)
        assertEquals(ConnectionStage.Disconnecting, session.connectionCoordinator.stage)
        assertEquals(listOf("disconnect"), driver.commands)
    }

    @Test
    fun `delayed callbacks progress readiness through discovery configuration and Ready`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Connecting
            requestedMtu = 185
        }
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(observed) }
        runCurrent()

        driver.emitAfter(
            10,
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
                connectionGeneration = generation,
            ),
        )
        advanceTimeBy(9)
        runCurrent()
        assertTrue(observed.isEmpty())
        assertTrue(driver.commands.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConnState.DiscoveringServices, session.state)
        assertEquals(ConnectionStage.DiscoveringServices, session.connectionCoordinator.stage)
        assertEquals(listOf("discover"), driver.commands)

        driver.emitAfter(
            20,
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = emptyList(),
                connectionGeneration = generation,
            ),
        )
        advanceTimeBy(19)
        runCurrent()
        assertEquals(listOf("discover"), driver.commands)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConnState.Configuring, session.state)
        assertEquals(ConnectionStage.Configuring, session.connectionCoordinator.stage)
        assertEquals(listOf("discover", "mtu:185"), driver.commands)

        driver.emitAfter(
            30,
            GattDriverEvent.MtuChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                mtu = 185,
                status = BluetoothGatt.GATT_SUCCESS,
                connectionGeneration = generation,
            ),
        )
        advanceTimeBy(29)
        runCurrent()
        assertEquals(ConnState.Configuring, session.state)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(ConnState.Ready, session.state)
        assertEquals(ConnectionStage.Ready, session.connectionCoordinator.stage)
        assertEquals(185, session.mtu)
        assertEquals(182, session.chunkSize)
        assertEquals(
            listOf(
                BleEvent.ConnStateChanged(DEVICE_ID, ConnState.DiscoveringServices),
                BleEvent.ServicesDiscovered(DEVICE_ID, emptyList()),
                BleEvent.ConnStateChanged(DEVICE_ID, ConnState.Configuring),
                BleEvent.MtuChanged(DEVICE_ID, 185, 182),
                BleEvent.ConnStateChanged(DEVICE_ID, ConnState.Ready),
            ),
            observed,
        )
    }

    @Test
    fun `retained reconnect keeps MTU completion Configuring for Core restore orchestration`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Connecting
            suppressReconnect = false
            requestedMtu = 185
        }
        val driver = FakeGattDriver(this)
        val observed = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = observed::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(40, 40, 40, 25),
        )

        val generation = bridge.beginRetainedReconnect(DEVICE_ID)

        assertEquals(1L, generation)
        assertEquals(ConnectionAttemptOrigin.Reconnect, session.connectionCoordinator.attemptOrigin)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(40L, session.connectionCoordinator.activeDeadline?.timeoutMillis)

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            ),
        )
        bridge.discoverServices(DEVICE_ID)
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = emptyList(),
            ),
        )
        bridge.requestMtu(DEVICE_ID, 185)
        driver.emit(
            GattDriverEvent.MtuChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                mtu = 185,
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )

        assertEquals(ConnectionStage.Configuring, session.connectionCoordinator.stage)
        assertEquals(40L, session.connectionCoordinator.activeDeadline?.timeoutMillis)
        assertEquals(185, session.mtu)
        assertEquals(182, session.chunkSize)
        assertEquals(listOf("discover", "mtu:185"), driver.commands)
    }

    @Test
    fun `retained reconnect missing link callback uses coordinator deadline and remains retryable`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Connecting
            suppressReconnect = false
        }
        val driver = FakeGattDriver(this)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(40, 40, 40, 25),
        )

        bridge.beginRetainedReconnect(DEVICE_ID)
        advanceTimeBy(65)
        runCurrent()

        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertTrue(session.state is ConnState.Disconnected)
        assertFalse(session.suppressReconnect)
        assertEquals(listOf("disconnect"), driver.commands)
    }

    @Test
    fun `terminal disconnect arms existing reconnect job completion into a fresh lifecycle`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Ready
            suppressReconnect = false
        }
        val firstGeneration = session.connectionCoordinator.beginConnection(this, 40) {}
        session.connectionCoordinator.transition(
            firstGeneration,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            firstGeneration,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            firstGeneration,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )
        val driver = FakeGattDriver(this)
        var reconnectStarted = false
        val bridge = GattBridge(
            registry = registry,
            emit = { event ->
                if (
                    !reconnectStarted &&
                    event is BleEvent.ConnStateChanged &&
                    event.state is ConnState.Disconnected
                ) {
                    reconnectStarted = true
                    session.reconnectJob = launch {
                        delay(10)
                        session.state = ConnState.Connecting
                    }
                }
            },
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(40, 40, 40, 25),
        )

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_DISCONNECTED,
            ),
        )
        advanceTimeBy(10)
        runCurrent()

        assertEquals(firstGeneration + 1, session.connectionCoordinator.generation)
        assertEquals(ConnectionAttemptOrigin.Reconnect, session.connectionCoordinator.attemptOrigin)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(40L, session.connectionCoordinator.activeDeadline?.timeoutMillis)
        session.connectionCoordinator.completeCleanup(
            session.connectionCoordinator.generation,
            TerminalCleanupReason.ClientClosed,
        )
    }

    @Test
    fun `started operation timeout and cancellation terminalize lifecycle before the next attempt`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Ready
            suppressReconnect = false
        }
        val firstGeneration = session.connectionCoordinator.beginConnection(this, 40) {}
        session.connectionCoordinator.transition(
            firstGeneration,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            firstGeneration,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            firstGeneration,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )
        val driver = FakeGattDriver(this)
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 25,
            readinessTimeouts = ConnectionReadinessTimeouts(40, 40, 40, 25),
            operationStarter = { _, _ -> null },
        )

        val read = async {
            runCatching { bridge.read(
                DEVICE_ID,
                kr.co.nucode.ble.CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
            ) }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(25)
        runCurrent()

        assertEquals("OPERATION_TIMEOUT", (read.await() as? BleException)?.failure?.code?.value)
        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertEquals(
            TerminalCleanupReason.OperationInvalidated,
            session.connectionCoordinator.terminalCleanupReason,
        )
        assertTrue(session.state is ConnState.Disconnected)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect", "close"), driver.commands)

        // BleCore.connect가 public state gate를 통과한 뒤 실행하는 실제 bridge path를 재현한다.
        session.state = ConnState.Connecting
        bridge.connect(DEVICE_ID, kr.co.nucode.ble.ConnectOptions())

        assertEquals(firstGeneration + 1, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(firstGeneration + 1, driver.connectionGeneration)
        assertFalse(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect", "close", "connect:false"), driver.commands)

        val secondGeneration = session.connectionCoordinator.generation
        session.connectionCoordinator.transition(
            secondGeneration,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            secondGeneration,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            secondGeneration,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )
        session.state = ConnState.Ready
        val cancelledRead = async {
            bridge.read(
                DEVICE_ID,
                kr.co.nucode.ble.CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
            )
        }
        runCurrent()

        cancelledRead.cancelAndJoin()
        runCurrent()

        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertEquals(
            TerminalCleanupReason.OperationInvalidated,
            session.connectionCoordinator.terminalCleanupReason,
        )
        assertTrue(session.state is ConnState.Disconnected)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertEquals(
            listOf("disconnect", "close", "connect:false", "disconnect", "close"),
            driver.commands,
        )
    }

    @Test
    fun `stale generation and wrong stage readiness callbacks cannot mutate lifecycle snapshots`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply { state = ConnState.Connecting }
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(observed) }
        runCurrent()

        driver.emit(connectedEvent(generation - 1))
        runCurrent()

        assertEquals(ConnState.Connecting, session.state)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertTrue(driver.commands.isEmpty())
        assertTrue(observed.isEmpty())

        driver.emit(connectedEvent(generation))
        runCurrent()
        assertEquals(ConnState.DiscoveringServices, session.state)
        assertEquals(listOf("discover"), driver.commands)
        driver.commands.clear()
        observed.clear()

        val serviceUuid = java.util.UUID.randomUUID()
        val charUuid = java.util.UUID.randomUUID()
        val charRef = kr.co.nucode.ble.CharRef(serviceUuid, charUuid)
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                deviceId = DEVICE_ID,
                source = CURRENT_SOURCE,
                status = BluetoothGatt.GATT_SUCCESS,
                services = listOf(
                    GattServiceSnapshot(
                        serviceUuid = serviceUuid,
                        characteristics = listOf(
                            GattCharacteristicSnapshot(
                                serviceUuid = serviceUuid,
                                charUuid = charUuid,
                                instanceIndex = 0,
                                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
                                platformCharacteristic = BluetoothGattCharacteristic(charUuid, 0, 0),
                            ),
                        ),
                    ),
                ),
                connectionGeneration = generation - 1,
            ),
        )
        driver.emit(
            GattDriverEvent.MtuChanged(
                deviceId = DEVICE_ID,
                source = CURRENT_SOURCE,
                mtu = 247,
                status = BluetoothGatt.GATT_SUCCESS,
                connectionGeneration = generation,
            ),
        )
        runCurrent()

        assertEquals(ConnState.DiscoveringServices, session.state)
        assertEquals(ConnectionStage.DiscoveringServices, session.connectionCoordinator.stage)
        assertNull(session.resolve(charRef))
        assertNull(session.mtu)
        assertNull(session.chunkSize)
        assertTrue(driver.commands.isEmpty())
        assertTrue(observed.isEmpty())
    }

    @Test
    fun `same-generation late non-terminal failures cannot tear down readiness stages`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.DiscoveringServices
            suppressReconnect = false
        }
        val generation = session.connectionCoordinator.beginConnection(this, 40) {}
        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            40,
        )
        val driver = FakeGattDriver(this)
        val observed = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = observed::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(40, 40, 40, 25),
        )
        val discoveryDeadline = session.connectionCoordinator.activeDeadline

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                status = 133,
                newState = BluetoothProfile.STATE_CONNECTED,
                connectionGeneration = generation,
            ),
        )

        assertEquals(ConnectionStage.DiscoveringServices, session.connectionCoordinator.stage)
        assertTrue(session.state is ConnState.DiscoveringServices)
        assertSame(discoveryDeadline, session.connectionCoordinator.activeDeadline)
        assertNull(session.connectionCoordinator.terminalCleanupReason)
        assertNull(session.terminalLifecycleCause)
        assertFalse(session.suppressReconnect)
        assertTrue(driver.commands.isEmpty())

        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
        session.state = ConnState.Configuring
        val configurationDeadline = session.connectionCoordinator.activeDeadline
        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                status = 133,
                newState = BluetoothProfile.STATE_CONNECTING,
                connectionGeneration = generation,
            ),
        )

        assertEquals(ConnectionStage.Configuring, session.connectionCoordinator.stage)
        assertTrue(session.state is ConnState.Configuring)
        assertSame(configurationDeadline, session.connectionCoordinator.activeDeadline)
        assertNull(session.connectionCoordinator.terminalCleanupReason)
        assertNull(session.terminalLifecycleCause)
        assertFalse(session.suppressReconnect)
        assertTrue(driver.commands.isEmpty())

        session.connectionCoordinator.transition(
            generation,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )
        session.state = ConnState.Ready
        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                status = 133,
                newState = BluetoothProfile.STATE_DISCONNECTING,
                connectionGeneration = generation,
            ),
        )

        assertEquals(ConnectionStage.Ready, session.connectionCoordinator.stage)
        assertTrue(session.state is ConnState.Ready)
        assertNull(session.connectionCoordinator.activeDeadline)
        assertNull(session.connectionCoordinator.terminalCleanupReason)
        assertNull(session.terminalLifecycleCause)
        assertFalse(session.suppressReconnect)
        assertTrue(driver.commands.isEmpty())
        assertEquals(3, observed.filterIsInstance<BleEvent.Diagnostic>().size)
        session.connectionCoordinator.completeCleanup(generation, TerminalCleanupReason.ClientClosed)
    }

    @Test
    fun `discovery status failure emits Diagnostic without publishing Ready`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply { state = ConnState.Connecting }
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(observed) }
        runCurrent()

        driver.emit(connectedEvent(generation))
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                status = 133,
                services = emptyList(),
                connectionGeneration = generation,
            ),
        )
        runCurrent()

        val diagnostic = observed.filterIsInstance<BleEvent.Diagnostic>().single()
        assertEquals("SERVICE_DISCOVERY_FAILED", diagnostic.failure.code.value)
        assertEquals(133, (diagnostic.failure.platformDetail as? kr.co.nucode.ble.PlatformDetail.Android)?.statusCode)
        assertEquals(session.failureSessionId, diagnostic.failure.context.sessionId)
        assertFalse(session.state is ConnState.Ready)
        assertFalse(session.connectionCoordinator.stage == ConnectionStage.Ready)
        assertEquals(1, driver.commands.count { it == "discover" })
        assertTrue(observed.none { it is BleEvent.ServicesDiscovered })
        assertTrue(
            observed.none {
                it is BleEvent.ConnStateChanged && it.state is ConnState.Ready
            },
        )
    }

    @Test
    fun `missing discovery callback expires its deadline without publishing Ready`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply { state = ConnState.Connecting }
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(observed) }
        runCurrent()

        driver.emit(connectedEvent(generation))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertFalse(session.state is ConnState.Ready)
        assertFalse(session.connectionCoordinator.stage == ConnectionStage.Ready)
        assertNull(session.connectionCoordinator.activeDeadline)
        assertEquals(1, driver.commands.count { it == "discover" })
        assertTrue(
            observed.none {
                it is BleEvent.ConnStateChanged && it.state is ConnState.Ready
            },
        )
    }

    @Test
    fun `duplicate callbacks do not repeat readiness or revive disconnected lifecycle`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Connecting
            suppressReconnect = true
        }
        val generation = session.connectionCoordinator.beginConnection(this, 15_000) {}
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(observed) }
        runCurrent()

        driver.emitAfter(10, connectedEvent(generation))
        driver.emitAfter(10, connectedEvent(generation))
        advanceTimeBy(10)
        runCurrent()

        val disconnected = GattDriverEvent.ConnectionStateChanged(
            DEVICE_ID,
            CURRENT_SOURCE,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        driver.emit(disconnected)
        driver.emit(disconnected)
        runCurrent()

        assertTrue(session.state is ConnState.Disconnected)
        assertEquals(1, driver.commands.count { it == "discover" })
        assertEquals(
            1,
            observed.count {
                it is BleEvent.ConnStateChanged && it.state is ConnState.DiscoveringServices
            },
        )
        val firstDisconnected = observed.indexOfFirst {
            it is BleEvent.ConnStateChanged && it.state is ConnState.Disconnected
        }
        assertTrue(firstDisconnected >= 0)
        assertTrue(
            observed.drop(firstDisconnected + 1).none {
                it is BleEvent.ConnStateChanged && (
                    it.state is ConnState.DiscoveringServices ||
                        it.state is ConnState.Configuring ||
                        it.state is ConnState.Ready
                    )
            },
        )
    }

    @Test
    fun `close currently cancels pending work closes the driver and removes the Session`() {
        val driver = FakeGattDriver(CoroutineScope(Dispatchers.Unconfined))
        val core = BleCore(
            config = BleConfig(dispatcher = Dispatchers.Unconfined),
            adapter = { null },
            gattDriver = driver,
        )
        val pendingWrite = CompletableDeferred<Int>()
        val pendingCccd = CompletableDeferred<Int>()
        val resubscribeJob = Job()
        val reconnectJob = Job()
        val timeoutJob = Job()
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Connected
            this.pendingWrite = pendingWrite
            this.pendingCccd = pendingCccd
            this.resubscribeJob = resubscribeJob
            this.reconnectJob = reconnectJob
            this.timeoutJob = timeoutJob
        }
        val identity = GattCharacteristicIdentity.from(
            kr.co.nucode.ble.CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
        )
        val scheduledPending = session.gattOperationScheduler.enqueue(
            GattOperation.Read(session.gattOperationScheduler.currentGeneration, identity),
        )
        val scheduledQueued = session.gattOperationScheduler.enqueue(
            GattOperation.WriteWithResponse(
                session.gattOperationScheduler.currentGeneration,
                identity,
                byteArrayOf(1),
            ),
        )
        session.gattOperationScheduler.startNext()

        core.close()

        assertTrue(pendingWrite.isCancelled)
        assertTrue(pendingCccd.isCancelled)
        assertTrue(resubscribeJob.isCancelled)
        assertTrue(reconnectJob.isCancelled)
        assertTrue(timeoutJob.isCancelled)
        assertTrue(scheduledPending.completion.isCompleted)
        assertTrue(scheduledQueued.completion.isCompleted)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertNull(session.gatt)
        assertNull(core.registry.get(DEVICE_ID))
        assertEquals(listOf("close"), driver.commands)

        core.close()
        assertEquals("close is currently idempotent", listOf("close"), driver.commands)
    }

    @Test
    fun `stale callbacks currently cannot mutate Session emit events or complete pending callback`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val pendingWrite = CompletableDeferred<Int>()
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            state = ConnState.Connected
            mtu = 185
            chunkSize = 182
            this.pendingWrite = pendingWrite
        }
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(observed) }
        runCurrent()

        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                STALE_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_DISCONNECTED,
            ),
        )
        driver.emit(
            GattDriverEvent.MtuChanged(
                DEVICE_ID,
                STALE_SOURCE,
                mtu = 247,
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )
        driver.emit(
            GattDriverEvent.CharacteristicWrite(
                DEVICE_ID,
                STALE_SOURCE,
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )
        runCurrent()

        assertEquals(ConnState.Connected, session.state)
        assertEquals(185, session.mtu)
        assertEquals(182, session.chunkSize)
        assertSame(pendingWrite, session.pendingWrite)
        assertFalse(pendingWrite.isCompleted)
        assertTrue(observed.isEmpty())
        assertTrue(driver.commands.isEmpty())
    }

    private fun TestScope.newCore(driver: GattDriver): BleCore = BleCore(
        config = BleConfig(
            dispatcher = StandardTestDispatcher(testScheduler),
            reconnectPolicy = ReconnectPolicy(maxAttempts = 0),
        ),
        adapter = { null },
        gattDriver = driver,
    )

    private fun connectedEvent(generation: Long? = null) = GattDriverEvent.ConnectionStateChanged(
        DEVICE_ID,
        CURRENT_SOURCE,
        BluetoothGatt.GATT_SUCCESS,
        BluetoothProfile.STATE_CONNECTED,
        connectionGeneration = generation,
    )

    private class FakeGattDriver(
        private val callbackScope: CoroutineScope,
    ) : GattDriver {
        val commands = mutableListOf<String>()
        var connectionGeneration: Long? = null
        var discoverFailure: SecurityException? = null
        private var sink: (GattDriverEvent) -> Unit = {}

        override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
            this.sink = sink
        }

        override fun connect(deviceId: DeviceId, autoConnect: Boolean): BluetoothGatt? {
            commands += "connect:$autoConnect"
            return null
        }

        override fun connect(
            deviceId: DeviceId,
            autoConnect: Boolean,
            connectionGeneration: Long,
        ): BluetoothGatt? {
            this.connectionGeneration = connectionGeneration
            return connect(deviceId, autoConnect)
        }

        override fun disconnect(deviceId: DeviceId, gatt: BluetoothGatt?) {
            commands += "disconnect"
        }

        override fun close(deviceId: DeviceId, gatt: BluetoothGatt?) {
            commands += "close"
        }

        override fun discoverServices(deviceId: DeviceId, gatt: BluetoothGatt?): Boolean {
            commands += "discover"
            discoverFailure?.let { throw it }
            return true
        }

        override fun requestMtu(deviceId: DeviceId, gatt: BluetoothGatt?, mtu: Int): Boolean {
            commands += "mtu:$mtu"
            return true
        }

        override fun setCharacteristicNotification(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            enabled: Boolean,
        ): Boolean = true

        override fun writeDescriptor(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
        ): Boolean = true

        override fun writeCharacteristic(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean = true

        fun emit(event: GattDriverEvent) {
            sink(event)
        }

        fun emitAfter(delayMillis: Long, event: GattDriverEvent): Job = callbackScope.launch {
            delay(delayMillis)
            sink(event)
        }
    }

    private companion object {
        val DEVICE_ID = DeviceId("AA:BB:CC:DD:EE:FF")
        val CURRENT_SOURCE = GattCallbackSource { true }
        val STALE_SOURCE = GattCallbackSource { false }
    }
}
