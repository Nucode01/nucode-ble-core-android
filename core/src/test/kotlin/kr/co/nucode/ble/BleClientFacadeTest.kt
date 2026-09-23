package kr.co.nucode.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.DefaultBleSession
import kr.co.nucode.ble.internal.NoopGattDriver
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleClientFacadeTest {
    @Test
    fun `official write preserves chunking mode driver calls and receipt semantics`() = runBlocking {
        val peripheralId = PeripheralId("AA:BB:CC:DD:EE:08")
        val characteristic = CharacteristicRef(
            service = ServiceRef(BleUuid(UUID.randomUUID())),
            uuid = BleUuid(UUID.randomUUID()),
        )
        val identity = GattCharacteristicIdentity.from(characteristic)
        val driver = RecordingWriteDriver(identity)
        val resolver = GattOperationTargetResolver { _, _ ->
            GattOperationTarget(
                characteristic = BluetoothGattCharacteristic(characteristic.uuid.value, 0, 0),
                identity = identity,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            )
        }
        val core = BleRuntime(BleConfig(), adapter = { null }, gattDriver = driver, gattOperationTargetResolver = resolver, adapterState = { AdapterState.Ready })
        val view = DefaultBleClient(core).session(peripheralId)
        core.registry.get(PeripheralId(peripheralId.value))!!.apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 20
        }

        val automatic = view.write(characteristic, ByteArray(40))
        assertEquals(listOf(20, 20), driver.calls.map { it.size })
        assertTrue(driver.calls.all { it.type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT })
        assertEquals(WriteReceipt(40, 2, DeliverySemantics.RemoteAcknowledged), automatic)

        driver.calls.clear()
        val fixed = view.write(characteristic, ByteArray(40), BleWriteOptions(chunking = BleChunking.Fixed(10)))
        assertEquals(listOf(10, 10, 10, 10), driver.calls.map { it.size })
        assertEquals(WriteReceipt(40, 4, DeliverySemantics.RemoteAcknowledged), fixed)

        driver.calls.clear()
        val withoutResponse = view.write(
            characteristic,
            byteArrayOf(1),
            BleWriteOptions(mode = BleWriteMode.WithoutResponse),
        )
        assertEquals(listOf(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE), driver.calls.map { it.type })
        assertEquals(WriteReceipt(1, 1, DeliverySemantics.SubmittedToPlatform), withoutResponse)
    }
    @Test
    fun `same peripheral views observe the Session-owned state`() {
        val core = BleRuntime(config = BleConfig(), adapter = { null })
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:01")

        val first = client.session(id)
        val second = client.session(id)

        assertEquals(first.connectionState.value, second.connectionState.value)
        assertEquals(1, core.registry.all().size)
        client.close()
    }

    @Test
    fun `close terminalizes existing view and gates new view`() {
        val core = BleRuntime(config = BleConfig(), adapter = { null })
        val client = DefaultBleClient(core)
        val existing = client.session(PeripheralId("AA:BB:CC:DD:EE:02"))

        client.close()
        // close()는 더 이상 정리 완료까지 블록하지 않는다(plan `runtime-no-runblocking`
        // task 2(B)) — 정리부가 세션 상태를 갱신할 시간을 준다.
        runBlocking { core.awaitClosed() }

        assertEquals(
            ConnectionState.Disconnected(BleDisconnectReason.ClientClosed),
            existing.connectionState.value,
        )
        val error = runCatching { client.session(PeripheralId("AA:BB:CC:DD:EE:03")) }
            .exceptionOrNull() as? BleException
        assertEquals("CLIENT_CLOSED", requireNotNull(error).failure.code.value)
    }

    @Test
    fun `runtime owns the live graph and facades own no registry or jobs`() {
        assertFalse(
            BleRuntime::class.java.declaredFields.any { it.type == DefaultBleClient::class.java },
        )
        val forbidden = setOf(SessionRegistry::class.java, kotlinx.coroutines.Job::class.java)
        listOf(DefaultBleClient::class.java, DefaultBleSession::class.java).forEach { facade ->
            assertFalse(
                "facade/view must not own registry or coroutine work: ${facade.name}",
                facade.declaredFields.any { field -> forbidden.any { it.isAssignableFrom(field.type) } },
            )
        }
    }

    @Test
    fun `official exponential reconnect policy is accepted rather than rejected`() = kotlinx.coroutines.test.runTest {
        val client = DefaultBleClient(BleRuntime(config = BleConfig(), adapter = { null }))

        val error = runCatching {
            client.connect(
                PeripheralId("AA:BB:CC:DD:EE:04"),
                BleConnectOptions(BleReconnectPolicy.ExponentialBackoff(1, 10, 10)),
            )
        }.exceptionOrNull() as? BleException

        // This JVM seam has no adapter, so connect itself may fail; the policy must not be
        // rejected before the normal platform admission boundary.
        assertFalse(requireNotNull(error).failure.code.value == "UNSUPPORTED_RECONNECT_POLICY")
        client.close()
    }

    @Test
    fun `closed client retains frozen snapshot and permits repeated cleanup`() = runBlocking {
        val core = BleRuntime(config = BleConfig(), adapter = { null })
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:05")
        val view = client.session(id)

        client.close()
        val frozen = client.snapshot()
        client.stopScan()
        view.disconnect()
        client.close()

        assertEquals(frozen, client.snapshot())
        assertFalse(frozen.isScanning)
        assertEquals(listOf(id), frozen.sessions.map { it.peripheralId })
        assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), view.connectionState.value)
        assertEquals(view.connectionState.value, frozen.sessions.single().connectionState)
        assertTrue(core.registry.all().isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `official edges retain ingress state when collector runs after later transitions`() = kotlinx.coroutines.test.runTest {
        val core = BleRuntime(config = BleConfig(), adapter = { null })
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:07")
        client.session(id)
        val backing = requireNotNull(core.registry.get(PeripheralId(id.value)))
        val edges = mutableListOf<BleClientEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { edges += it }
        }
        try {
            backing.setStateForTest(ConnectionState.Connecting)
            core.emit(BleEvent.ConnStateChanged(backing.deviceId, ConnectionState.Connecting))
            backing.setStateForTest(ConnectionState.Disconnecting)
            core.emit(BleEvent.ConnStateChanged(backing.deviceId, ConnectionState.Disconnecting))
            runCurrent()

            assertEquals(
                listOf(ConnectionState.Connecting, ConnectionState.Disconnecting),
                edges.filterIsInstance<OfficialBleClientEvent.Connection>().map { it.state },
            )
        } finally {
            collector.cancel()
            client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `state collector can close inline on runtime without self deadlock`() = runBlocking {
        val core = BleRuntime(config = BleConfig(), adapter = { null })
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:06")
        val view = client.session(id)
        core.registry.get(PeripheralId(id.value))!!.setStateForTest(ConnectionState.Ready)
        val states = mutableListOf<ConnectionState>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            view.connectionState.collect { state ->
                states += state
                if (state == ConnectionState.Disconnecting) client.close()
            }
        }
        try {
            view.disconnect()
            assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), states.last())
            assertTrue(core.registry.all().isEmpty())
            assertEquals(states.last(), client.snapshot().sessions.single().connectionState)
        } finally {
            collector.cancel()
            client.close()
        }
    }

    @Test(timeout = 5_000)
    fun `concurrent close prevents session insertion after cleanup`() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(20) { iteration ->
                val core = BleRuntime(config = BleConfig(), adapter = { null })
                val client = DefaultBleClient(core)
                val start = java.util.concurrent.CountDownLatch(1)
                val view = executor.submit<BleSession?> {
                    start.await()
                    try {
                        client.session(PeripheralId("race-$iteration"))
                    } catch (failure: BleException) {
                        assertEquals("CLIENT_CLOSED", failure.failure.code.value)
                        null
                    }
                }
                val closing = executor.submit {
                    start.await()
                    client.close()
                }
                start.countDown()
                closing.get(2, java.util.concurrent.TimeUnit.SECONDS)
                // close()는 더 이상 정리 완료까지 블록하지 않는다(plan `runtime-no-runblocking`
                // task 2(B)) — registry 비워짐을 단언하기 전에 정리 완료를 기다린다.
                runBlocking { core.awaitClosed() }
                val created = view.get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(core.registry.all().isEmpty())
                created?.let {
                    assertEquals(ConnectionState.Disconnected(BleDisconnectReason.ClientClosed), it.connectionState.value)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private class RecordingWriteDriver(
        private val identity: GattCharacteristicIdentity,
    ) : NoopGattDriver() {
        data class Call(val size: Int, val type: Int)

        val calls = mutableListOf<Call>()


        override fun writeCharacteristic(
            deviceId: PeripheralId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean {
            calls += Call(value.size, writeType)
            emit(
                GattDriverEvent.CharacteristicWrite(
                    deviceId = deviceId,
                    source = GattCallbackSource { true },
                    status = BluetoothGatt.GATT_SUCCESS,
                    attributeIdentity = identity,
                ),
            )
            return true
        }
    }
}
