package kr.co.nucode.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.harness.RecordingScanPort
import kr.co.nucode.ble.harness.gapServiceSnapshot
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.model.ConnectOptions
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.charRef
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor
import kr.co.nucode.ble.harness.FakePlatformCharacteristic
import kr.co.nucode.ble.harness.FakePlatformDescriptor

/**
 * plan `platform-harness/v1` task 4 — AsyncInfo route(자원 상태를 끝내지 않는 정보성 실패)의 전달 채널을
 * 공개 경계(BleRuntime)에서 고정한다. 각 테스트는 Event 채널(그 코드의 Diagnostic)을 단언하고,
 * 카탈로그 `current` 에 State 가 있는 route 는 connectionState 도 단언한다.
 * fixture 는 [FailureRouteDeliveryTest] 의 모양을 따른다.
 * [kr.co.nucode.ble.internal.FailureRouteVerificationTest] 가 route → 테스트 메서드 연결을 강제한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailureRouteInfoDeliveryTest {

    private class Driver : NoopGattDriver() {
        var disconnectFailure: Exception? = null
        var closeFailure: Exception? = null
        var mtuStarts: Boolean = true
        var descriptorFailure: Exception? = null
        var descriptorStarts: Boolean = true

        override fun disconnect(deviceId: PeripheralId, gatt: GattHandle?) {
            disconnectFailure?.let { throw it }
        }

        override fun close(deviceId: PeripheralId, gatt: GattHandle?) {
            closeFailure?.let { throw it }
        }

        override fun requestMtu(deviceId: PeripheralId, gatt: GattHandle?, mtu: Int): Boolean = mtuStarts

        override fun writeDescriptor(
            deviceId: PeripheralId,
            gatt: GattHandle?,
            descriptor: PlatformDescriptor,
            value: ByteArray,
        ): Boolean {
            descriptorFailure?.let { throw it }
            return descriptorStarts
        }

        override fun readCharacteristic(
            deviceId: PeripheralId,
            gatt: GattHandle?,
            characteristic: PlatformCharacteristic,
        ): Boolean = true

        fun connection(deviceId: PeripheralId, generation: Long, status: Int, newState: Int) = emit(
            GattDriverEvent.ConnectionStateChanged(deviceId, SOURCE, status, newState, generation),
        )

        fun services(
            deviceId: PeripheralId,
            generation: Long,
            status: Int,
            services: List<GattServiceSnapshot> = listOf(gapServiceSnapshot()),
        ) = emit(GattDriverEvent.ServicesDiscovered(deviceId, SOURCE, status, services, generation))

        fun mtu(deviceId: PeripheralId, generation: Long, status: Int = BluetoothGatt.GATT_SUCCESS) = emit(
            GattDriverEvent.MtuChanged(deviceId, SOURCE, 247, status, generation),
        )

        fun read(deviceId: PeripheralId, ref: CharacteristicRef, status: Int) = emit(
            GattDriverEvent.CharacteristicRead(
                deviceId = deviceId,
                source = SOURCE,
                characteristic = snapshot(ref),
                value = byteArrayOf(1),
                status = status,
            ),
        )

        fun cccd(deviceId: PeripheralId, ref: CharacteristicRef, status: Int) = emit(
            GattDriverEvent.DescriptorWrite(
                deviceId = deviceId,
                source = SOURCE,
                status = status,
                attributeIdentity = GattDescriptorIdentity(GattCharacteristicIdentity.from(ref), CCCD_UUID),
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
        val state: ConnectionState get() = session.connectionState.value

        fun diagnostics(code: String): List<BleFailure> =
            events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().map { it.failure }.filter { it.code.value == code }

        fun diagnosticCodes(): List<String> =
            events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().map { it.failure.code.value }
    }

    private fun TestScope.fixture(rediscovery: PeripheralRediscovery? = null, logger: BleLogger? = null): Fixture {
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
                logger = logger,
            ),
            gattDriver = driver,
            gattOperationTargetResolver = RESOLVER,
            rediscovery = rediscovery,
            adapterState = { AdapterState.Ready },
        )
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()
        return Fixture(core, driver, PeripheralId("AA:BB:CC:DD:EE:D2"), events)
    }

    private suspend fun TestScope.connectToDiscovering(f: Fixture, official: BleConnectOptions? = null) {
        if (official == null) {
            f.core.connect(f.id, ConnectOptions())
        } else {
            f.core.connectOfficial(f.id, official)
        }
        runCurrent()
        f.driver.connection(f.id, f.generation, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        assertEquals(ConnectionState.DiscoveringServices, f.state)
    }

    private suspend fun TestScope.connectToConfiguring(f: Fixture, official: BleConnectOptions? = null) {
        connectToDiscovering(f, official)
        f.driver.services(f.id, f.generation, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertEquals(ConnectionState.Configuring, f.state)
    }

    private suspend fun TestScope.connectToReady(f: Fixture, official: BleConnectOptions? = null) {
        connectToConfiguring(f, official)
        f.driver.mtu(f.id, f.generation)
        runCurrent()
        assertEquals(ConnectionState.Ready, f.state)
    }

    /** unsubscribe 를 background 에서 시작해 CCCD write 가 pending 인 상태로 둔다(unsubscribe 는 throw 하지 않는다). */
    private fun TestScope.startUnsubscribe(f: Fixture, ref: CharacteristicRef = READ_REF) {
        backgroundScope.launch { runCatching { f.core.unsubscribe(f.id, ref) } }
        runCurrent()
    }

    /** cleanup deadline 까지 흘려 Disconnecting 을 Disconnected 로 끝낸다(fake disconnect 는 콜백이 없다). */
    private fun TestScope.finishCleanup() {
        advanceTimeBy(CLEANUP_TIMEOUT + 1)
        runCurrent()
    }

    private fun failedCode(state: ConnectionState): String? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    private fun assertUnsubscribeDiagnostic(f: Fixture, code: String) {
        assertTrue(
            "unsubscribe 로 귀속된 $code Diagnostic 이 없다: ${f.diagnosticCodes()}",
            f.diagnostics(code).any { it.context.operationKind == OperationKind.Unsubscribe },
        )
    }

    // ── unsubscribe 명령(D8 best-effort — throw 없이 Event) ────────────────────────

    @Test
    fun `unsubscribe unresolved target emits CHARACTERISTIC_NOT_FOUND Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)

        f.core.unsubscribe(f.id, MISSING_REF)
        runCurrent()

        assertUnsubscribeDiagnostic(f, "CHARACTERISTIC_NOT_FOUND")
        assertEquals(ConnectionState.Ready, f.state)
    }

    @Test
    fun `unsubscribe CCCD start rejection emits GATT_OPERATION_START_FAILED Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        f.driver.descriptorStarts = false

        f.core.unsubscribe(f.id, READ_REF)
        runCurrent()

        assertUnsubscribeDiagnostic(f, "GATT_OPERATION_START_FAILED")
        assertEquals(ConnectionState.Ready, f.state)
    }

    @Test
    fun `unsubscribe CCCD status failure emits GATT_STATUS_FAILED Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        startUnsubscribe(f)

        f.driver.cccd(f.id, READ_REF, 133)
        runCurrent()

        assertUnsubscribeDiagnostic(f, "GATT_STATUS_FAILED")
        assertEquals(ConnectionState.Ready, f.state)
    }

    @Test
    fun `unsubscribe pending invalidated by disconnect emits OPERATION_INVALIDATED Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        startUnsubscribe(f)

        f.core.disconnect(f.id)
        runCurrent()

        assertUnsubscribeDiagnostic(f, "OPERATION_INVALIDATED")
    }

    @Test
    fun `unsubscribe CCCD ack timeout emits OPERATION_TIMEOUT Diagnostic and Disconnected Failed`() = runTest {
        val f = fixture()
        connectToReady(f)
        startUnsubscribe(f)

        advanceTimeBy(OPERATION_TIMEOUT + 1)
        runCurrent()

        assertUnsubscribeDiagnostic(f, "OPERATION_TIMEOUT")
        assertEquals("OPERATION_TIMEOUT", failedCode(f.state))
    }

    @Test
    fun `unsubscribe CCCD SecurityException emits PERMISSION_DENIED Diagnostic and Disconnected Failed`() = runTest {
        val f = fixture()
        connectToReady(f)
        f.driver.descriptorFailure = SecurityException()

        f.core.unsubscribe(f.id, READ_REF)
        runCurrent()
        finishCleanup()

        assertUnsubscribeDiagnostic(f, "PERMISSION_DENIED")
        assertEquals("PERMISSION_DENIED", failedCode(f.state))
    }

    // ── close / disconnect 정리 ──────────────────────────────────────────────

    @Test
    fun `close cleanup driver failure emits CLEANUP_FAILURE Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        f.driver.closeFailure = IllegalStateException("close failed")

        f.core.close()
        runCurrent()
        f.core.awaitClosed()
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("CLEANUP_FAILURE").isNotEmpty())
    }

    /**
     * close 정리 실패 보고는 진단 디스패처 close 보다 먼저다 — 그래야 CLEANUP_FAILURE 가 호환 스트림뿐 아니라
     * 구성된 logger 에도 닿는다(순서가 뒤집히면 닫힌 디스패처가 보고를 버린다).
     */
    @Test
    fun `close cleanup driver failure reaches configured logger before dispatcher closes`() = runTest {
        val logged = mutableListOf<DiagnosticRecord>()
        val loggerLatch = CountDownLatch(1)
        val f = fixture(
            logger = BleLogger { record ->
                synchronized(logged) { logged += record }
                if (record.failure.code.value == "CLEANUP_FAILURE") loggerLatch.countDown()
            },
        )
        connectToReady(f)
        f.driver.closeFailure = IllegalStateException("close failed")

        f.core.close()
        runCurrent()
        f.core.awaitClosed()
        runCurrent()

        assertTrue(
            "logger 가 CLEANUP_FAILURE 를 받지 못했다: ${synchronized(logged) { logged.map { it.failure.code.value } }}",
            loggerLatch.await(2, TimeUnit.SECONDS),
        )
        val record = synchronized(logged) { logged.first { it.failure.code.value == "CLEANUP_FAILURE" } }
        val exception = (record.values["cleanup_exception"] as DiagnosticValue.Text).value
        assertEquals("IllegalStateException", exception)
        assertFalse("메시지가 진단에 실렸다: $exception", exception.contains("close failed"))
    }

    @Test
    fun `disconnect cleanup deadline emits DISCONNECT_CLEANUP_TIMEOUT Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)

        f.core.disconnect(f.id)
        runCurrent()
        finishCleanup()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("DISCONNECT_CLEANUP_TIMEOUT").isNotEmpty())
    }

    @Test
    fun `disconnect command start failure emits DISCONNECT_START_FAILED Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        f.driver.disconnectFailure = IllegalStateException("disconnect failed")

        f.core.disconnect(f.id)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("DISCONNECT_START_FAILED").isNotEmpty())
    }

    @Test
    fun `internal cleanup start failure emits DISCONNECT_START_FAILED Diagnostic`() = runTest {
        val f = fixture()
        f.driver.disconnectFailure = IllegalStateException("disconnect failed")
        f.core.connect(f.id, ConnectOptions())
        runCurrent()

        advanceTimeBy(STAGE_TIMEOUT + 1)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("DISCONNECT_START_FAILED").isNotEmpty())
        assertEquals("LINK_TIMEOUT", failedCode(f.state))
    }

    // ── link lifecycle 정보성 ─────────────────────────────────────────────────

    @Test
    fun `MTU status failure emits CONFIGURATION_FAILED Diagnostic and falls back to Ready`() = runTest {
        val f = fixture()
        connectToConfiguring(f)

        f.driver.mtu(f.id, f.generation, status = 133)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("CONFIGURATION_FAILED").isNotEmpty())
        assertEquals(ConnectionState.Ready, f.state)
    }

    @Test
    fun `requestMtu start rejection emits GATT_OPERATION_START_FAILED Diagnostic and falls back to Ready`() = runTest {
        val f = fixture()
        f.driver.mtuStarts = false
        connectToDiscovering(f)

        f.driver.services(f.id, f.generation, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("GATT_OPERATION_START_FAILED").isNotEmpty())
        assertEquals(ConnectionState.Ready, f.state)
    }

    @Test
    fun `duplicate characteristic in discovery result emits DUPLICATE_CHARACTERISTIC_REFERENCE Diagnostic`() = runTest {
        val f = fixture()
        connectToDiscovering(f)
        val characteristic = snapshot(READ_REF)
        val service = GattServiceSnapshot(READ_REF.service.uuid.value, listOf(characteristic, characteristic))

        f.driver.services(f.id, f.generation, BluetoothGatt.GATT_SUCCESS, listOf(service))
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("DUPLICATE_CHARACTERISTIC_REFERENCE").isNotEmpty())
        assertEquals(ConnectionState.Configuring, f.state)
    }

    @Test
    fun `late non-terminal link status failure emits LINK_FAILED Diagnostic without changing state`() = runTest {
        val f = fixture()
        connectToDiscovering(f)

        f.driver.connection(f.id, f.generation, 133, BluetoothProfile.STATE_CONNECTED)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("LINK_FAILED").isNotEmpty())
        assertEquals(ConnectionState.DiscoveringServices, f.state)
    }

    @Test
    fun `ended generation link status failure emits LINK_FAILED Warning Diagnostic without changing state`() = runTest {
        val f = fixture()
        connectToReady(f, official = BleConnectOptions(autoReconnect = true))
        f.driver.connection(f.id, f.generation, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        val recovering = f.state
        assertTrue("Recovering 이 아니다: $recovering", recovering is ConnectionState.Recovering)
        val before = f.diagnostics("LINK_FAILED").size

        // 플랫폼 재연결 대기 중(끝난 generation) Android 처럼 generation 없이 온 실패 status — Drop 되지만 Diagnostic 은 남는다.
        f.driver.emit(GattDriverEvent.ConnectionStateChanged(f.id, SOURCE, 133, BluetoothProfile.STATE_DISCONNECTED))
        runCurrent()

        val late = f.events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().filter { it.failure.code.value == "LINK_FAILED" }.drop(before)
        assertEquals(f.diagnosticCodes().toString(), 1, late.size)
        assertEquals(Severity.Warning, late.single().severity)
        assertEquals(recovering, f.state)

        // 성공 status 의 늦은 콜백은 조용히 버린다.
        f.driver.emit(GattDriverEvent.ConnectionStateChanged(f.id, SOURCE, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED))
        runCurrent()
        assertEquals(before + 1, f.diagnostics("LINK_FAILED").size)
        assertEquals(recovering, f.state)
    }

    // ── GATT callback 라우팅 ─────────────────────────────────────────────────

    @Test
    fun `read callback for other characteristic emits GATT_OPERATION_CALLBACK_MISMATCH Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)
        backgroundScope.launch { runCatching { f.core.read(f.id, READ_REF) } }
        runCurrent()

        f.driver.read(f.id, OTHER_REF, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("GATT_OPERATION_CALLBACK_MISMATCH").isNotEmpty())
        assertEquals(ConnectionState.Ready, f.state)
    }

    @Test
    fun `read callback without pending operation emits GATT_OPERATION_DUPLICATE_CALLBACK Diagnostic`() = runTest {
        val f = fixture()
        connectToReady(f)

        f.driver.read(f.id, READ_REF, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("GATT_OPERATION_DUPLICATE_CALLBACK").isNotEmpty())
        assertEquals(ConnectionState.Ready, f.state)
    }

    // ── connect 재발견 스캔 폴백(직접 연결로 진행) ─────────────────────────────────

    private suspend fun TestScope.connectWithRediscovery(rediscovery: PeripheralRediscovery): Fixture {
        val f = fixture(rediscovery = rediscovery)
        f.core.connect(f.id, ConnectOptions())
        runCurrent()
        return f
    }

    @Test
    fun `rediscovery without scanner emits SCANNER_UNAVAILABLE Diagnostic and falls back to connect`() = runTest {
        // 운영 재발견 = Scanner 소비자 → 플랫폼 문 AndroidScanner(스캐너 없음).
        val f = connectWithRediscovery(Scanner({}, backgroundScope, port = AndroidScanner(central = null)))

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("SCANNER_UNAVAILABLE").isNotEmpty())
        assertEquals(ConnectionState.Connecting, f.state)
    }

    /**
     * 아래 세 재발견 실패는 AndroidScanner 가 BluetoothLeScanner(JVM 에서 만들 수 없음)에서 얻는다 —
     * 같은 FailureMapper 값을 rediscovery seam 이 돌려줘 lifecycle 의 폴백 전달만 검증한다.
     */
    @Test
    fun `rediscovery scan permission denial emits PERMISSION_DENIED Diagnostic and falls back to connect`() = runTest {
        val failure = FailureMapper.permissionDenied(phase = FailurePhase.Scan, operation = AndroidApiOperation.StartScan)
        val f = connectWithRediscovery { _, _ -> RediscoveryOutcome.Unavailable(failure) }

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("PERMISSION_DENIED").isNotEmpty())
        assertEquals(ConnectionState.Connecting, f.state)
    }

    @Test
    fun `rediscovery scan runtime failure emits SCAN_RUNTIME_FAILED Diagnostic and falls back to connect`() = runTest {
        val f = connectWithRediscovery { _, _ -> RediscoveryOutcome.Unavailable(FailureMapper.scanRuntimeFailed(2)) }

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("SCAN_RUNTIME_FAILED").isNotEmpty())
        assertEquals(ConnectionState.Connecting, f.state)
    }

    @Test
    fun `rediscovery scan start failure emits SCAN_START_FAILED Diagnostic and falls back to connect`() = runTest {
        val f = connectWithRediscovery { _, _ -> RediscoveryOutcome.Unavailable(FailureMapper.scanStartFailed()) }

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("SCAN_START_FAILED").isNotEmpty())
        assertEquals(ConnectionState.Connecting, f.state)
    }

    @Test
    fun `rediscovery scan start throttled emits SCAN_START_THROTTLED Diagnostic and falls back to connect`() = runTest {
        // 운영 재발견 = Scanner 소비자. 같은 시각에 성공한 시작 5회로 한도를 채운 뒤 재발견 시작은 거부된다.
        val scanner = Scanner({}, backgroundScope, port = RecordingScanPort(), now = { 0L })
        repeat(5) {
            scanner.startScan(ScanRequest(BleScanFilter(), BleScanOptions()))
            scanner.stopScan()
        }
        val f = connectWithRediscovery(scanner)
        runCurrent()

        assertTrue(f.diagnosticCodes().toString(), f.diagnostics("SCAN_START_THROTTLED").isNotEmpty())
        assertEquals(ConnectionState.Connecting, f.state)
    }

    // ── 재연결 시도 알림 ──────────────────────────────────────────────────────

    private companion object {
        const val OPERATION_TIMEOUT = 100L
        const val STAGE_TIMEOUT = 100L
        const val CLEANUP_TIMEOUT = 50L
        val SOURCE = GattCallbackSource { true }
        val SERVICE_UUID: UUID = UUID.randomUUID()
        val READ_REF: CharacteristicRef = charRef(SERVICE_UUID, UUID.randomUUID())
        val OTHER_REF: CharacteristicRef = charRef(SERVICE_UUID, UUID.randomUUID())
        val MISSING_REF: CharacteristicRef = charRef(SERVICE_UUID, UUID.randomUUID())
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RESOLVER = GattOperationTargetResolver { _, ref ->
            if (ref == MISSING_REF) return@GattOperationTargetResolver null
            GattOperationTarget(
                characteristic = FakePlatformCharacteristic(ref.uuid.value),
                identity = GattCharacteristicIdentity.from(ref),
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                cccdDescriptor = FakePlatformDescriptor(CCCD_UUID),
            )
        }

        fun snapshot(ref: CharacteristicRef) = GattCharacteristicSnapshot(
            serviceUuid = ref.service.uuid.value,
            charUuid = ref.uuid.value,
            instanceIndex = ref.instanceIndex,
            propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
        )
    }
}
