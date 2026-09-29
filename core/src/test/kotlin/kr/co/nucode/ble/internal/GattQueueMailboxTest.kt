package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.harness.FakePlatformCharacteristic
import kr.co.nucode.ble.harness.FakePlatformDescriptor
import kr.co.nucode.ble.harness.gapServiceSnapshot
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.model.ConnectOptions
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.port.GattCallbackSource
import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattDriverEvent
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 우편함 규칙(§10): 기한 타이머가 깨어나도 판정은 우편함 끝으로 한 번 미룬다. 그 사이 우편함에 먼저 들어와 있던
 * 완료 콜백이 기한을 이기고, 판정 뒤에 도착한 콜백은 늦다. 같은 가상 시각 안에서 항목 순서를 직접 세운다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattQueueMailboxTest {

    private class Driver : NoopGattDriver() {
        override fun readCharacteristic(deviceId: PeripheralId, gatt: GattHandle?, characteristic: PlatformCharacteristic) = true

        fun connection(id: PeripheralId, generation: Long) = emit(
            GattDriverEvent.ConnectionStateChanged(
                id, SOURCE, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED, generation,
            ),
        )

        fun services(id: PeripheralId, generation: Long) = emit(
            GattDriverEvent.ServicesDiscovered(id, SOURCE, BluetoothGatt.GATT_SUCCESS, listOf(gapServiceSnapshot()), generation),
        )

        fun mtu(id: PeripheralId, generation: Long) = emit(
            GattDriverEvent.MtuChanged(id, SOURCE, 247, BluetoothGatt.GATT_SUCCESS, generation),
        )

        fun read(id: PeripheralId, ref: CharacteristicRef) = emit(
            GattDriverEvent.CharacteristicRead(
                deviceId = id,
                source = SOURCE,
                characteristic = GattCharacteristicSnapshot(
                    serviceUuid = ref.service.uuid.value,
                    charUuid = ref.uuid.value,
                    instanceIndex = ref.instanceIndex,
                    propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
                ),
                value = byteArrayOf(7),
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )
    }

    private class Fixture(
        val core: BleRuntime,
        val driver: Driver,
        val dispatcher: CoroutineDispatcher,
        val events: MutableList<BleClientEvent>,
    ) {
        val id = PeripheralId("AA:BB:CC:DD:EE:E1")
        val session: Session get() = core.registry.get(id)!!
        fun diagnosticCodes(): List<String> =
            events.filterIsInstance<OfficialBleClientEvent.Diagnostic>().map { it.failure.code.value }
    }

    private suspend fun TestScope.readyFixture(): Fixture {
        val driver = Driver()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val core = BleRuntime(
            config = BleConfig(
                dispatcher = dispatcher,
                operationTimeoutMillis = OPERATION_TIMEOUT,
                linkTimeoutMillis = STAGE_TIMEOUT,
                discoveryTimeoutMillis = STAGE_TIMEOUT,
                configurationTimeoutMillis = STAGE_TIMEOUT,
                disconnectCleanupTimeoutMillis = STAGE_TIMEOUT,
                rediscoveryTimeoutMillis = STAGE_TIMEOUT,
            ),
            gattDriver = driver,
            gattOperationTargetResolver = RESOLVER,
            adapterState = { kr.co.nucode.ble.AdapterState.Ready },
        )
        val events = mutableListOf<BleClientEvent>()
        backgroundScope.launch { core.officialEvents.toList(events) }
        runCurrent()
        val f = Fixture(core, driver, dispatcher, events)
        core.connect(f.id, ConnectOptions())
        runCurrent()
        driver.connection(f.id, f.session.connectionCoordinator.generation)
        runCurrent()
        driver.services(f.id, f.session.connectionCoordinator.generation)
        runCurrent()
        driver.mtu(f.id, f.session.connectionCoordinator.generation)
        runCurrent()
        assertEquals(ConnectionState.Ready, f.session.connectionState.value)
        return f
    }

    @Test
    fun `completion callback queued behind the deadline timer item wins over the deadline`() = runTest {
        val f = readyFixture()
        val read = async { runCatching { f.core.read(f.id, READ_REF) } }
        runCurrent()

        // 타이머가 깨어나는 시각까지 흘리되 그 시각의 항목은 아직 돌리지 않는다.
        advanceTimeBy(OPERATION_TIMEOUT)
        // 타이머 깨움 항목(D)이 돌면 타이머 코루틴 항목(E)이 우편함에 선다. 이 도우미는 D 뒤·E 앞에서 돌아
        // 완료 콜백을 E 뒤에 세운다. E 가 판정을 우편함 끝(콜백 뒤)으로 미룬다.
        f.dispatcher.let { d -> backgroundScope.launch(d) { f.driver.read(f.id, READ_REF) } }
        runCurrent()

        val result = read.await()
        assertTrue("read 는 성공해야 한다: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(ConnectionState.Ready, f.session.connectionState.value)
        assertTrue(f.diagnosticCodes().toString(), "OPERATION_TIMEOUT" !in f.diagnosticCodes())
    }

    @Test
    fun `completion callback arriving after the judgment gets OPERATION_TIMEOUT and ends the connection`() = runTest {
        val f = readyFixture()
        val read = async { runCatching { f.core.read(f.id, READ_REF) } }
        runCurrent()

        advanceTimeBy(OPERATION_TIMEOUT + 1)
        runCurrent()
        // 판정이 끝난 뒤 도착한 콜백은 늦다.
        f.driver.read(f.id, READ_REF)
        runCurrent()

        val failure = (read.await().exceptionOrNull() as? BleException)?.failure
        assertNotNull("result=${read.await()}", failure)
        assertEquals("OPERATION_TIMEOUT", failure!!.code.value)
        val state = f.session.connectionState.value
        assertEquals(
            "OPERATION_TIMEOUT",
            ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value,
        )
    }

    @Test
    fun `public read arriving inside the judgment window is refused like a poisoned connection`() = runTest {
        val f = readyFixture()
        val read = async { runCatching { f.core.read(f.id, READ_REF) } }
        runCurrent()

        advanceTimeBy(OPERATION_TIMEOUT)
        val late = CompletableDeferred<Result<ByteArray>>()
        backgroundScope.launch(f.dispatcher) { late.complete(runCatching { f.core.read(f.id, READ_REF) }) }
        runCurrent()

        assertEquals("NOT_READY", (late.await().exceptionOrNull() as? BleException)?.failure?.code?.value)
        assertEquals("OPERATION_TIMEOUT", (read.await().exceptionOrNull() as? BleException)?.failure?.code?.value)
    }

    @Test
    fun `link loss inside the judgment window still ends the pending read with OPERATION_TIMEOUT`() = runTest {
        val f = readyFixture()
        val read = async { runCatching { f.core.read(f.id, READ_REF) } }
        runCurrent()

        advanceTimeBy(OPERATION_TIMEOUT)
        backgroundScope.launch(f.dispatcher) {
            f.driver.emit(
                GattDriverEvent.ConnectionStateChanged(
                    f.id, SOURCE, 8, BluetoothProfile.STATE_DISCONNECTED, f.session.connectionCoordinator.generation,
                ),
            )
        }
        runCurrent()

        assertEquals("OPERATION_TIMEOUT", (read.await().exceptionOrNull() as? BleException)?.failure?.code?.value)
    }

    private companion object {
        const val OPERATION_TIMEOUT = 100L
        const val STAGE_TIMEOUT = 100L
        val SOURCE = GattCallbackSource { true }
        val READ_REF: CharacteristicRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RESOLVER = GattOperationTargetResolver { _, ref ->
            GattOperationTarget(
                characteristic = FakePlatformCharacteristic(ref.uuid.value),
                identity = GattCharacteristicIdentity.from(ref),
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
                cccdDescriptor = FakePlatformDescriptor(CCCD_UUID),
            )
        }
    }
}
