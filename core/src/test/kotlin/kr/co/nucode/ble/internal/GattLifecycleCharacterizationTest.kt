package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.model.ConnectOptions
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.harness.gapServiceSnapshot
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.harness.FakePlatformCharacteristic

/**
 * GATT lifecycle characterization and Slice 2 readiness callback routing regression tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattLifecycleCharacterizationTest {

    @Test
    fun `stale cleanup generation cannot replace current disconnect reason projection`() = runTest {
        val session = Session(DEVICE_ID)
        val firstGeneration = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        assertTrue(
            session.connectionCoordinator.completeCleanup(
                TestStageAuthority,
                firstGeneration,
                TerminalCause(TerminalCleanupReason.UserRequested, null),
            ),
        )
        val secondGeneration = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        assertTrue(
            session.connectionCoordinator.completeCleanup(
                TestStageAuthority,
                secondGeneration,
                TerminalCause(TerminalCleanupReason.ClientClosed, null),
            ),
        )

        val rejected = session.connectionCoordinator.completeCleanup(
            TestStageAuthority,
            firstGeneration,
            TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
        )

        assertFalse(rejected)
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            session.connectionState.value,
        )
        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            session.connectionState.value,
        )
    }

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

        bridge.connect(DEVICE_ID, kr.co.nucode.ble.internal.model.ConnectOptions(timeoutMillis = null))

        assertEquals(1L, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(1L, driver.connectionGeneration)
        assertEquals(listOf("connect:false"), driver.commands)
    }

    @Test
    fun `disconnect start failure completes cleanup immediately with one diagnostic`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID)
        val driver = FakeGattDriver(this).apply { disconnectFailure = IllegalStateException("gatt gone") }
        val events = mutableListOf<BleEvent>()
        val bridge = GattBridge(
            registry = registry,
            emit = { events += it },
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(15_000, 10_000, 10_000, 20),
        )
        session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}

        bridge.disconnect(DEVICE_ID)

        // 시작 실패 → 즉시 completeLifecycleCleanup: deadline 없이 close까지 동기로 끝난다.
        assertEquals(listOf("disconnect", "close"), driver.commands)
        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertEquals(
            listOf("DISCONNECT_START_FAILED"),
            events.filterIsInstance<BleEvent.Diagnostic>().map { it.failure.code.value },
        )
    }

    @Test
    fun `discovery permission exception enters bounded terminal cleanup`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID)
        val driver = FakeGattDriver(this).apply { discoverFailure = SecurityException() }
        val bridge = GattBridge(
            registry = registry,
            emit = {},
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(15_000, 10_000, 10_000, 20),
        )
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        session.connectionCoordinator.transition(
            TestStageAuthority,
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
        assertTrue(session.connectionState.value is ConnectionState.Disconnected)
    }

    @Test
    fun `operation permission exception is typed and enters bounded terminal cleanup`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID)
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
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        session.connectionCoordinator.transition(
            TestStageAuthority,
            generation,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            timeoutMillis = 10_000,
        )
        session.connectionCoordinator.transition(
            TestStageAuthority,
            generation,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            timeoutMillis = 10_000,
        )
        session.connectionCoordinator.transition(
            TestStageAuthority,
            generation,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )

        val thrown = runCatching {
            bridge.read(DEVICE_ID, charRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
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
            requestedMtu = 185
        }
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        val observed = mutableListOf<BleClientEvent>()
        // MtuChanged 는 공개 흐름에 없다 — Ready 게시 순간의 MTU 를 찍어 "MTU 반영 뒤 Ready" 를 본다.
        val mtuAtReady = mutableListOf<Pair<Int?, Int?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            core.officialEvents.collect {
                observed += it
                if (it == OfficialBleClientEvent.Connection(DEVICE_ID, ConnectionState.Ready)) {
                    mtuAtReady += session.mtu to session.chunkSize
                }
            }
        }
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
        assertEquals(ConnectionState.DiscoveringServices, session.connectionState.value)
        assertEquals(ConnectionStage.DiscoveringServices, session.connectionCoordinator.stage)
        assertEquals(listOf("discover"), driver.commands)

        driver.emitAfter(
            20,
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = listOf(gapServiceSnapshot()),
                connectionGeneration = generation,
            ),
        )
        advanceTimeBy(19)
        runCurrent()
        assertEquals(listOf("discover"), driver.commands)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConnectionState.Configuring, session.connectionState.value)
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
        assertEquals(ConnectionState.Configuring, session.connectionState.value)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(ConnectionState.Ready, session.connectionState.value)
        assertEquals(ConnectionStage.Ready, session.connectionCoordinator.stage)
        assertEquals(185, session.mtu)
        assertEquals(182, session.chunkSize)
        val discovered = observed.filterIsInstance<OfficialBleClientEvent.Services>().single()
        assertEquals(
            listOf("00001800-0000-1000-8000-00805f9b34fb"),
            discovered.services.map { it.ref.uuid.value.toString() },
        )
        assertEquals(
            listOf(
                OfficialBleClientEvent.Connection(DEVICE_ID, ConnectionState.DiscoveringServices),
                OfficialBleClientEvent.Services(DEVICE_ID, discovered.services),
                OfficialBleClientEvent.Connection(DEVICE_ID, ConnectionState.Configuring),
                OfficialBleClientEvent.Connection(DEVICE_ID, ConnectionState.Ready),
            ),
            observed,
        )
        // Ready 는 MTU(185/182) 가 반영된 뒤에 나간다.
        assertEquals(listOf<Pair<Int?, Int?>>(185 to 182), mtuAtReady)
    }

    @Test
    fun `platform relink keeps MTU completion Configuring for Core restore orchestration`() = runTest {
        val registry = SessionRegistry()
        // 플랫폼 재연결 대기 — autoConnect handle 이 열린 채 generation 1 이 끝났다.
        val session = registry.getOrCreate(DEVICE_ID).apply {
            autoConnect = true
            platformHandleOpen = true
            requestedMtu = 185
        }
        val ended = session.connectionCoordinator.beginConnection(TestStageAuthority, this, null) {}
        session.beginPlatformWait {
            session.connectionCoordinator.completeCleanup(TestStageAuthority, ended, TerminalCause(TerminalCleanupReason.PlatformDisconnected, null))
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

        // OS 가 같은 handle 로 다시 붙였다 — generation 없는 STATE_CONNECTED 가 Bootstrap 으로 새 generation 을 연다.
        driver.emit(
            GattDriverEvent.ConnectionStateChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            ),
        )
        assertEquals(2L, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.DiscoveringServices, session.connectionCoordinator.stage)
        bridge.discoverServices(DEVICE_ID)
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = listOf(gapServiceSnapshot()),
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
    fun `started operation timeout terminalizes lifecycle before the next attempt and caller cancellation only detaches the caller`() = runTest {
        val registry = SessionRegistry()
        val session = registry.getOrCreate(DEVICE_ID).apply {
            suppressReconnect = false
        }
        val firstGeneration = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 40) {}
        session.connectionCoordinator.transition(
            TestStageAuthority,
            firstGeneration,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            TestStageAuthority,
            firstGeneration,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            TestStageAuthority,
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
                charRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
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
        assertTrue(session.connectionState.value is ConnectionState.Disconnected)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect", "close"), driver.commands)

        // BleCore.connect가 public state gate를 통과한 뒤 실행하는 실제 bridge path를 재현한다.
        bridge.connect(DEVICE_ID, kr.co.nucode.ble.internal.model.ConnectOptions())

        assertEquals(firstGeneration + 1, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(firstGeneration + 1, driver.connectionGeneration)
        assertFalse(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect", "close", "connect:false"), driver.commands)

        val secondGeneration = session.connectionCoordinator.generation
        session.connectionCoordinator.transition(
            TestStageAuthority,
            secondGeneration,
            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            TestStageAuthority,
            secondGeneration,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
        session.connectionCoordinator.transition(
            TestStageAuthority,
            secondGeneration,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )
        val cancelledRead = async {
            bridge.read(
                DEVICE_ID,
                charRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
            )
        }
        runCurrent()

        cancelledRead.cancelAndJoin()
        runCurrent()

        // 호출자 취소는 호출자만 뗀다 — 세션은 Ready 로 남고 정리도 시작되지 않는다.
        assertEquals(ConnectionStage.Ready, session.connectionCoordinator.stage)
        assertFalse(session.gattOperationScheduler.isPoisoned)
        assertEquals(listOf("disconnect", "close", "connect:false"), driver.commands)

        // 콜백이 없으면 큐가 든 기한(플랫폼 시작부터 25ms)에서만 poison 된다.
        advanceTimeBy(25)
        runCurrent()

        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertEquals(
            TerminalCleanupReason.OperationInvalidated,
            session.connectionCoordinator.terminalCleanupReason,
        )
        assertTrue(session.connectionState.value is ConnectionState.Disconnected)
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
        val session = core.registry.getOrCreate(DEVICE_ID)
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        val observed = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(observed) }
        runCurrent()

        driver.emit(connectedEvent(generation - 1))
        runCurrent()

        assertEquals(ConnectionState.Connecting, session.connectionState.value)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertTrue(driver.commands.isEmpty())
        assertTrue(observed.isEmpty())

        driver.emit(connectedEvent(generation))
        runCurrent()
        assertEquals(ConnectionState.DiscoveringServices, session.connectionState.value)
        assertEquals(listOf("discover"), driver.commands)
        driver.commands.clear()
        observed.clear()

        val serviceUuid = java.util.UUID.randomUUID()
        val charUuid = java.util.UUID.randomUUID()
        val charRef = charRef(serviceUuid, charUuid)
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
                                platformCharacteristic = FakePlatformCharacteristic(charUuid),
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

        assertEquals(ConnectionState.DiscoveringServices, session.connectionState.value)
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
            suppressReconnect = false
        }
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 40) {}
        session.connectionCoordinator.transition(
            TestStageAuthority,
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
        assertTrue(session.connectionState.value is ConnectionState.DiscoveringServices)
        assertSame(discoveryDeadline, session.connectionCoordinator.activeDeadline)
        assertNull(session.connectionCoordinator.terminalCleanupReason)
        assertNull(session.terminalLifecycleCause)
        assertFalse(session.suppressReconnect)
        assertTrue(driver.commands.isEmpty())

        session.connectionCoordinator.transition(
            TestStageAuthority,
            generation,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            this,
            40,
        )
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
        assertTrue(session.connectionState.value is ConnectionState.Configuring)
        assertSame(configurationDeadline, session.connectionCoordinator.activeDeadline)
        assertNull(session.connectionCoordinator.terminalCleanupReason)
        assertNull(session.terminalLifecycleCause)
        assertFalse(session.suppressReconnect)
        assertTrue(driver.commands.isEmpty())

        session.connectionCoordinator.transition(
            TestStageAuthority,
            generation,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            this,
        )
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
        assertTrue(session.connectionState.value is ConnectionState.Ready)
        assertNull(session.connectionCoordinator.activeDeadline)
        assertNull(session.connectionCoordinator.terminalCleanupReason)
        assertNull(session.terminalLifecycleCause)
        assertFalse(session.suppressReconnect)
        assertTrue(driver.commands.isEmpty())
        assertEquals(3, observed.filterIsInstance<BleEvent.Diagnostic>().size)
        session.connectionCoordinator.completeCleanup(TestStageAuthority, generation, TerminalCause(TerminalCleanupReason.ClientClosed, null))
    }

    @Test
    fun `discovery status failure emits Diagnostic without publishing Ready`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID)
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        val observed = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(observed) }
        runCurrent()

        driver.emit(connectedEvent(generation))
        driver.emit(
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                status = 133,
                services = listOf(gapServiceSnapshot()),
                connectionGeneration = generation,
            ),
        )
        runCurrent()

        val diagnostic = observed.filterIsInstance<OfficialBleClientEvent.Diagnostic>().single()
        assertEquals("SERVICE_DISCOVERY_FAILED", diagnostic.failure.code.value)
        assertEquals(133, (diagnostic.failure.platformDetail as? kr.co.nucode.ble.PlatformDetail.Android)?.statusCode)
        assertEquals(session.failureSessionId, diagnostic.failure.context.sessionId)
        assertFalse(session.connectionState.value is ConnectionState.Ready)
        assertFalse(session.connectionCoordinator.stage == ConnectionStage.Ready)
        assertEquals(1, driver.commands.count { it == "discover" })
        assertTrue(observed.none { it is OfficialBleClientEvent.Services })
        assertTrue(
            observed.none {
                it is OfficialBleClientEvent.Connection && it.state is ConnectionState.Ready
            },
        )
    }

    @Test
    fun `missing discovery callback expires its deadline without publishing Ready`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID)
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        val observed = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(observed) }
        runCurrent()

        driver.emit(connectedEvent(generation))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertFalse(session.connectionState.value is ConnectionState.Ready)
        assertFalse(session.connectionCoordinator.stage == ConnectionStage.Ready)
        assertNull(session.connectionCoordinator.activeDeadline)
        assertEquals(1, driver.commands.count { it == "discover" })
        assertTrue(
            observed.none {
                it is OfficialBleClientEvent.Connection && it.state is ConnectionState.Ready
            },
        )
    }

    @Test
    fun `duplicate callbacks do not repeat readiness or revive disconnected lifecycle`() = runTest {
        val driver = FakeGattDriver(this)
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            suppressReconnect = true
        }
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        val observed = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(observed) }
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

        assertTrue(session.connectionState.value is ConnectionState.Disconnected)
        assertEquals(1, driver.commands.count { it == "discover" })
        assertEquals(
            1,
            observed.count {
                it is OfficialBleClientEvent.Connection && it.state is ConnectionState.DiscoveringServices
            },
        )
        val firstDisconnected = observed.indexOfFirst {
            it is OfficialBleClientEvent.Connection && it.state is ConnectionState.Disconnected
        }
        assertTrue(firstDisconnected >= 0)
        assertTrue(
            observed.drop(firstDisconnected + 1).none {
                it is OfficialBleClientEvent.Connection && (
                    it.state is ConnectionState.DiscoveringServices ||
                        it.state is ConnectionState.Configuring ||
                        it.state is ConnectionState.Ready
                    )
            },
        )
    }

    @Test
    fun `close currently cancels pending work closes the driver and removes the Session`() {
        val driver = FakeGattDriver(CoroutineScope(Dispatchers.Unconfined))
        val core = BleRuntime(
            config = BleConfig(dispatcher = Dispatchers.Unconfined),
            gattDriver = driver,
        )
        // 복원 job 취소는 SetupPhaseMatrixTest 가 실제 복원(onDescriptorWrite 유실)으로 본다.
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            setStateForTest(ConnectionState.Ready)
        }
        val identity = GattCharacteristicIdentity.from(
            charRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
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
        val session = core.registry.getOrCreate(DEVICE_ID).apply {
            setStateForTest(ConnectionState.Ready)
            mtu = 185
            chunkSize = 182
        }
        val identity = GattCharacteristicIdentity.from(
            charRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
        )
        val scheduledWrite = session.gattOperationScheduler.enqueue(
            GattOperation.WriteWithResponse(0, identity, byteArrayOf(1)),
        )
        session.gattOperationScheduler.startNext()
        val observed = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(observed) }
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

        assertEquals(ConnectionState.Ready, session.connectionState.value)
        assertEquals(185, session.mtu)
        assertEquals(182, session.chunkSize)
        assertSame(scheduledWrite, session.gattOperationScheduler.pending)
        assertFalse(scheduledWrite.completion.isCompleted)
        assertTrue(observed.isEmpty())
        assertTrue(driver.commands.isEmpty())
    }

    /** MTU 협상 status 실패 — 폴백 MtuChanged(23) 발행 뒤 onMtuSettled 로 Ready 까지 간다. */
    @Test(timeout = 10_000)
    fun `MTU status failure falls back to default MTU and still reaches Ready`() = runTest {
        val driver = FakeGattDriver(this)
        val (session, mtuAtReady) = driveToMtuRequest(driver)

        driver.emitAfter(
            1,
            GattDriverEvent.MtuChanged(
                DEVICE_ID,
                CURRENT_SOURCE,
                mtu = 185,
                status = BluetoothGatt.GATT_FAILURE,
                connectionGeneration = session.connectionCoordinator.generation,
            ),
        )
        advanceTimeBy(1)
        runCurrent()

        assertFallbackThenReady(session, mtuAtReady)
    }

    /** requestMtu 시작 실패(false) — 콜백이 없으므로 폴백 MtuChanged(23) 뒤 onMtuSettled 로 Ready 까지 간다. */
    @Test(timeout = 10_000)
    fun `MTU request start failure falls back to default MTU and still reaches Ready`() = runTest {
        val driver = FakeGattDriver(this).apply { mtuStartResult = false }
        val (session, mtuAtReady) = driveToMtuRequest(driver)

        assertFallbackThenReady(session, mtuAtReady)
    }

    private fun TestScope.driveToMtuRequest(
        driver: FakeGattDriver,
    ): Pair<Session, List<Pair<Int?, Int?>>> {
        val core = newCore(driver)
        val session = core.registry.getOrCreate(DEVICE_ID).apply { requestedMtu = 185 }
        val generation = session.connectionCoordinator.beginConnection(TestStageAuthority, this, 15_000) {}
        val observed = mutableListOf<BleClientEvent>()
        val mtuAtReady = mutableListOf<Pair<Int?, Int?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            core.officialEvents.collect {
                observed += it
                if (it == OfficialBleClientEvent.Connection(DEVICE_ID, ConnectionState.Ready)) {
                    mtuAtReady += session.mtu to session.chunkSize
                }
            }
        }
        runCurrent()

        driver.emitAfter(1, connectedEvent(generation))
        advanceTimeBy(1)
        runCurrent()
        driver.emitAfter(
            1,
            GattDriverEvent.ServicesDiscovered(
                DEVICE_ID,
                CURRENT_SOURCE,
                BluetoothGatt.GATT_SUCCESS,
                services = listOf(gapServiceSnapshot()),
                connectionGeneration = generation,
            ),
        )
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("discover", "mtu:185"), driver.commands)
        return session to mtuAtReady
    }

    /** [mtuAtReady] — Ready 게시 순간의 (mtu, chunkSize). MtuChanged 는 공개 흐름에 없어 이것으로 순서를 본다. */
    private fun assertFallbackThenReady(session: Session, mtuAtReady: List<Pair<Int?, Int?>>) {
        assertEquals(ConnectionState.Ready, session.connectionState.value)
        assertEquals(ConnectionStage.Ready, session.connectionCoordinator.stage)
        assertEquals(23, session.mtu)
        assertEquals(20, session.chunkSize)
        assertEquals("Ready 가 폴백 MTU 반영 뒤에 와야 한다", listOf<Pair<Int?, Int?>>(23 to 20), mtuAtReady)
    }

    private fun TestScope.newCore(driver: GattDriver): BleRuntime = BleRuntime(
        config = BleConfig(
            dispatcher = StandardTestDispatcher(testScheduler),
        ),
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
    ) : NoopGattDriver() {
        val commands = mutableListOf<String>()
        var connectionGeneration: Long? = null
        var discoverFailure: SecurityException? = null
        var disconnectFailure: Exception? = null
        var mtuStartResult: Boolean = true

        override fun connect(deviceId: PeripheralId, autoConnect: Boolean): GattHandle? {
            commands += "connect:$autoConnect"
            return null
        }

        override fun connect(
            deviceId: PeripheralId,
            autoConnect: Boolean,
            connectionGeneration: Long,
        ): GattHandle? {
            this.connectionGeneration = connectionGeneration
            return connect(deviceId, autoConnect)
        }

        override fun disconnect(deviceId: PeripheralId, gatt: GattHandle?) {
            commands += "disconnect"
            disconnectFailure?.let { throw it }
        }

        override fun close(deviceId: PeripheralId, gatt: GattHandle?) {
            commands += "close"
        }

        override fun discoverServices(deviceId: PeripheralId, gatt: GattHandle?): Boolean {
            commands += "discover"
            discoverFailure?.let { throw it }
            return true
        }

        override fun requestMtu(deviceId: PeripheralId, gatt: GattHandle?, mtu: Int): Boolean {
            commands += "mtu:$mtu"
            return mtuStartResult
        }

        fun emitAfter(delayMillis: Long, event: GattDriverEvent): Job = callbackScope.launch {
            delay(delayMillis)
            emit(event)
        }
    }

    private companion object {
        val DEVICE_ID = PeripheralId("AA:BB:CC:DD:EE:FF")
        val CURRENT_SOURCE = GattCallbackSource { true }
        val STALE_SOURCE = GattCallbackSource { false }
    }
}
