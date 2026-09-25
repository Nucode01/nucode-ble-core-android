package kr.co.nucode.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.harness.gapServiceSnapshot
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.ConnectOptions
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.charRef
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `failure-delivery-guards/v1` task 2 — 기존 테스트가 단언하지 않던 FailureCatalog route 의
 * 전달 채널(Throw: 던진 코드, State: connectionState, Event: Diagnostic)을 공개 경계(BleRuntime)에서 고정한다.
 * [kr.co.nucode.ble.internal.FailureRouteVerificationTest] 가 route → 테스트 메서드 연결을 강제한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailureRouteDeliveryTest {

    private class Driver : NoopGattDriver() {
        var connectFailure: Exception? = null
        var discoverFailure: Exception? = null
        var discoverStarts: Boolean = true
        var readFailure: Exception? = null
        var readStarts: Boolean = true

        override fun connect(deviceId: PeripheralId, autoConnect: Boolean): BluetoothGatt? {
            connectFailure?.let { throw it }
            return null
        }

        override fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

        override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) = Unit

        override fun discoverServices(deviceId: PeripheralId, gatt: BluetoothGatt?): Boolean {
            discoverFailure?.let { throw it }
            return discoverStarts
        }

        override fun readCharacteristic(
            deviceId: PeripheralId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
        ): Boolean {
            readFailure?.let { throw it }
            return readStarts
        }

        fun connection(deviceId: PeripheralId, generation: Long, status: Int, newState: Int) = emit(
            GattDriverEvent.ConnectionStateChanged(deviceId, SOURCE, status, newState, generation),
        )

        fun services(deviceId: PeripheralId, generation: Long, status: Int) = emit(
            GattDriverEvent.ServicesDiscovered(deviceId, SOURCE, status, listOf(gapServiceSnapshot()), generation),
        )

        fun mtu(deviceId: PeripheralId, generation: Long) = emit(
            GattDriverEvent.MtuChanged(deviceId, SOURCE, 247, BluetoothGatt.GATT_SUCCESS, generation),
        )

        fun read(deviceId: PeripheralId, ref: CharacteristicRef, status: Int) = emit(
            GattDriverEvent.CharacteristicRead(
                deviceId = deviceId,
                source = SOURCE,
                characteristic = GattCharacteristicSnapshot(
                    serviceUuid = ref.service.uuid.value,
                    charUuid = ref.uuid.value,
                    instanceIndex = ref.instanceIndex,
                    propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
                ),
                value = byteArrayOf(1),
                status = status,
            ),
        )
    }

    private class Fixture(
        val core: BleRuntime,
        val driver: Driver,
        val id: PeripheralId,
        val events: MutableList<BleClientEvent>,
    ) {
        val session: Session get() = core.registry.get(id)!!
        val generation: Long get() = session.connectionCoordinator.generation

        fun diagnosticCodes(): List<String> =
            events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().map { it.failure.code.value }
    }

    private fun TestScope.fixture(
        gattDriver: GattDriver? = null,
        resolver: Boolean = true,
        adapterState: () -> AdapterState = { AdapterState.Ready },
    ): Fixture {
        val driver = Driver()
        val core = BleRuntime(
            config = BleConfig(
                dispatcher = StandardTestDispatcher(testScheduler),
                operationTimeoutMillis = OPERATION_TIMEOUT,
                linkTimeoutMillis = STAGE_TIMEOUT,
                discoveryTimeoutMillis = STAGE_TIMEOUT,
                configurationTimeoutMillis = STAGE_TIMEOUT,
                disconnectCleanupTimeoutMillis = CLEANUP_TIMEOUT,
                rediscoveryTimeoutMillis = STAGE_TIMEOUT,
            ),
            adapter = { null },
            gattDriver = gattDriver ?: driver,
            gattOperationTargetResolver = if (resolver) RESOLVER else null,
            adapterState = adapterState,
        )
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()
        return Fixture(core, driver, PeripheralId("AA:BB:CC:DD:EE:D1"), events)
    }

    private suspend fun Fixture.connectThrown(): Throwable? =
        runCatching { core.connect(id, ConnectOptions()) }.exceptionOrNull()

    private suspend fun TestScope.connectToDiscovering(f: Fixture) {
        f.core.connect(f.id, ConnectOptions())
        runCurrent()
        f.driver.connection(f.id, f.generation, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        assertEquals(ConnectionState.DiscoveringServices, f.session.connectionState.value)
    }

    private suspend fun TestScope.connectToConfiguring(f: Fixture) {
        connectToDiscovering(f)
        f.driver.services(f.id, f.generation, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertEquals(ConnectionState.Configuring, f.session.connectionState.value)
    }

    private suspend fun TestScope.connectToReady(f: Fixture) {
        connectToConfiguring(f)
        f.driver.mtu(f.id, f.generation)
        runCurrent()
        assertEquals(ConnectionState.Ready, f.session.connectionState.value)
    }

    /** cleanup deadline 까지 흘려 Disconnecting 을 Disconnected 로 끝낸다(fake disconnect 는 콜백이 없다). */
    private fun TestScope.finishCleanup() {
        advanceTimeBy(CLEANUP_TIMEOUT + 1)
        runCurrent()
    }

    private fun code(thrown: Throwable?): String? = (thrown as? BleException)?.failure?.code?.value

    private fun failedCode(state: ConnectionState): String? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    // ── connect 동기 실패: Throw + State(Disconnected(Failed)) ─────────────────────

    @Test
    fun `connect driver start ADAPTER_UNAVAILABLE throws and terminalizes state`() = runTest {
        val f = fixture(gattDriver = AndroidGattDriver(android.content.ContextWrapper(null), { null }, backgroundScope))

        val thrown = f.connectThrown()

        assertEquals("ADAPTER_UNAVAILABLE", code(thrown))
        assertEquals("ADAPTER_UNAVAILABLE", failedCode(f.session.connectionState.value))
    }

    @Test
    fun `connect driver start BLE_UNSUPPORTED throws and terminalizes state`() = runTest {
        val f = fixture(gattDriver = AndroidGattDriver(null, { null }, backgroundScope))

        val thrown = f.connectThrown()

        assertEquals("BLE_UNSUPPORTED", code(thrown))
        assertEquals("BLE_UNSUPPORTED", failedCode(f.session.connectionState.value))
    }

    /** AndroidGattDriver 의 잘못된 주소 변환(getRemoteDevice IAE)은 JVM 에서 만들 수 없어 같은 typed failure 를 driver 가 던진다. */
    @Test
    fun `connect invalid address UNKNOWN_DEVICE throws and terminalizes state`() = runTest {
        val f = fixture()
        f.driver.connectFailure = BleException(
            BleFailure(
                category = FailureCategory("validation"),
                code = FailureCode("UNKNOWN_DEVICE"),
                phase = FailurePhase.Validation,
                retryability = Retryability.Never,
            ),
        )

        val thrown = f.connectThrown()

        assertEquals("UNKNOWN_DEVICE", code(thrown))
        assertEquals("UNKNOWN_DEVICE", failedCode(f.session.connectionState.value))
    }

    @Test
    fun `connect SecurityException PERMISSION_DENIED throws and terminalizes state`() = runTest {
        val f = fixture()
        f.driver.connectFailure = SecurityException()

        val thrown = f.connectThrown()

        assertEquals("PERMISSION_DENIED", code(thrown))
        assertEquals("PERMISSION_DENIED", failedCode(f.session.connectionState.value))
    }

    @Test
    fun `connect start rejection LINK_START_FAILED throws and terminalizes state`() = runTest {
        val f = fixture()
        f.driver.connectFailure = IllegalStateException("connectGatt rejected")

        val thrown = f.connectThrown()

        assertEquals("LINK_START_FAILED", code(thrown))
        assertEquals("LINK_START_FAILED", failedCode(f.session.connectionState.value))
    }

    @Test
    fun `session command with unregistered id throws UNKNOWN_DEVICE`() = runTest {
        val f = fixture()

        val thrown = runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull()

        assertEquals("UNKNOWN_DEVICE", code(thrown))
    }

    // ── link lifecycle 비동기 실패: State(Disconnected(Failed)) + Event(Diagnostic) ──

    @Test
    fun `link status callback LINK_FAILED publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        f.core.connect(f.id, ConnectOptions())
        runCurrent()

        f.driver.connection(f.id, f.generation, 133, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()

        assertEquals("LINK_FAILED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "LINK_FAILED" in f.diagnosticCodes())
    }

    @Test
    fun `link deadline LINK_TIMEOUT publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        f.core.connect(f.id, ConnectOptions())
        runCurrent()

        advanceTimeBy(STAGE_TIMEOUT + 1)
        runCurrent()
        finishCleanup()

        assertEquals("LINK_TIMEOUT", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "LINK_TIMEOUT" in f.diagnosticCodes())
    }

    @Test
    fun `discovery start rejection SERVICE_DISCOVERY_START_FAILED publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        f.driver.discoverStarts = false
        f.core.connect(f.id, ConnectOptions())
        runCurrent()

        f.driver.connection(f.id, f.generation, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        finishCleanup()

        assertEquals("SERVICE_DISCOVERY_START_FAILED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "SERVICE_DISCOVERY_START_FAILED" in f.diagnosticCodes())
    }

    @Test
    fun `discovery start SecurityException PERMISSION_DENIED publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        f.driver.discoverFailure = SecurityException()
        f.core.connect(f.id, ConnectOptions())
        runCurrent()

        f.driver.connection(f.id, f.generation, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        finishCleanup()

        assertEquals("PERMISSION_DENIED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "PERMISSION_DENIED" in f.diagnosticCodes())
    }

    @Test
    fun `discovery status SERVICE_DISCOVERY_FAILED publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        connectToDiscovering(f)

        f.driver.services(f.id, f.generation, 133)
        runCurrent()
        finishCleanup()

        assertEquals("SERVICE_DISCOVERY_FAILED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "SERVICE_DISCOVERY_FAILED" in f.diagnosticCodes())
    }

    @Test
    fun `discovery deadline SERVICE_DISCOVERY_TIMEOUT publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        connectToDiscovering(f)

        advanceTimeBy(STAGE_TIMEOUT + 1)
        runCurrent()
        finishCleanup()

        assertEquals("SERVICE_DISCOVERY_TIMEOUT", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "SERVICE_DISCOVERY_TIMEOUT" in f.diagnosticCodes())
    }

    @Test
    fun `configuration deadline CONFIGURATION_TIMEOUT publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        connectToConfiguring(f)

        advanceTimeBy(STAGE_TIMEOUT + 1)
        runCurrent()
        finishCleanup()

        assertEquals("CONFIGURATION_TIMEOUT", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "CONFIGURATION_TIMEOUT" in f.diagnosticCodes())
    }

    // ── read/write/subscribe 명령 ─────────────────────────────────────────────

    @Test
    fun `read start rejection throws GATT_OPERATION_START_FAILED`() = runTest {
        val f = fixture()
        connectToReady(f)
        f.driver.readStarts = false

        val thrown = runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull()

        assertEquals("GATT_OPERATION_START_FAILED", code(thrown))
    }

    @Test
    fun `read status callback failure throws GATT_STATUS_FAILED`() = runTest {
        val f = fixture()
        connectToReady(f)

        val read = async { runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull() }
        runCurrent()
        f.driver.read(f.id, READ_REF, 133)
        runCurrent()

        assertEquals("GATT_STATUS_FAILED", code(read.await()))
    }

    /** resolver 없이 discovered target 은 있으나 gatt 핸들이 없으면 시작 단계에서 NOT_CONNECTED 다. */
    @Test
    fun `read without platform handle throws NOT_CONNECTED`() = runTest {
        val f = fixture(resolver = false)
        connectToReady(f)
        f.session.updateDiscovered(mapOf(READ_REF to BluetoothGattCharacteristic(READ_REF.uuid.value, 0, 0)))

        val thrown = runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull()

        assertEquals("NOT_CONNECTED", code(thrown))
    }

    @Test
    fun `read SecurityException throws PERMISSION_DENIED terminalizes state and Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        f.driver.readFailure = SecurityException()

        val thrown = runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull()
        runCurrent()
        finishCleanup()

        assertEquals("PERMISSION_DENIED", code(thrown))
        assertEquals("PERMISSION_DENIED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "PERMISSION_DENIED" in f.diagnosticCodes())
    }

    @Test
    fun `started read timeout throws OPERATION_TIMEOUT publishes Disconnected Failed and Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)

        val read = async { runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(OPERATION_TIMEOUT + 1)
        runCurrent()

        assertEquals("OPERATION_TIMEOUT", code(read.await()))
        assertEquals("OPERATION_TIMEOUT", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "OPERATION_TIMEOUT" in f.diagnosticCodes())
    }

    /**
     * 공개 명령은 platform start 뒤에만 ACK timeout 을 건다 — 대기열에 있는 동안 timeout 이 나는 경로는
     * 호출자 쪽 `withTimeout` 이 대기 중인 명령을 끊을 때다.
     */
    @Test
    fun `queued read cut by caller timeout throws OPERATION_TIMEOUT`() = runTest {
        val f = fixture()
        connectToReady(f)
        val pending = async { runCatching { f.core.read(f.id, READ_REF) }.exceptionOrNull() }
        runCurrent()

        val queued = async {
            runCatching { withTimeout(OPERATION_TIMEOUT / 2) { f.core.read(f.id, QUEUED_REF) } }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(OPERATION_TIMEOUT / 2 + 1)
        runCurrent()

        assertEquals("OPERATION_TIMEOUT", code(queued.await()))
        pending.cancel()
    }

    @Test
    fun `started read caller cancellation publishes Disconnected Failed OPERATION_INVALIDATED and Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)

        val read = async { f.core.read(f.id, READ_REF) }
        runCurrent()
        read.cancelAndJoin()
        runCurrent()

        assertEquals("OPERATION_INVALIDATED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "OPERATION_INVALIDATED" in f.diagnosticCodes())
    }

    // ── adapter sweep ─────────────────────────────────────────────────────────

    @Test
    fun `permission revoked sweep publishes Disconnected Failed PERMISSION_DENIED and Diagnostic`() = runTest {
        var adapter: AdapterState = AdapterState.Ready
        val f = fixture(adapterState = { adapter })
        connectToReady(f)

        adapter = AdapterState.Unauthorized(canRequest = true)
        f.core.refreshAdapterState()
        runCurrent()

        assertEquals("PERMISSION_DENIED", failedCode(f.session.connectionState.value))
        assertTrue(f.diagnosticCodes().toString(), "PERMISSION_DENIED" in f.diagnosticCodes())
    }

    private companion object {
        const val OPERATION_TIMEOUT = 100L
        const val STAGE_TIMEOUT = 100L
        const val CLEANUP_TIMEOUT = 50L
        val SOURCE = GattCallbackSource { true }
        val READ_REF: CharacteristicRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val QUEUED_REF: CharacteristicRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RESOLVER = GattOperationTargetResolver { _, ref ->
            GattOperationTarget(
                characteristic = BluetoothGattCharacteristic(ref.uuid.value, 0, 0),
                identity = GattCharacteristicIdentity.from(ref),
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                cccdDescriptor = BluetoothGattDescriptor(CCCD_UUID, 0),
            )
        }
    }
}
