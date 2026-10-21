package kr.co.nucode.ble.android

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DescriptorRef
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.WriteLimits
import kr.co.nucode.ble.WriteReceipt
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.Harness
import kr.co.nucode.ble.harness.gapServiceSnapshot
import kr.co.nucode.ble.harness.harness
import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattServiceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 요청 MTU(CANDIDATE-33)와 write 한도·협상 MTU 조회(CANDIDATE-5). */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMtuTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:M1")
    private val serviceUuid: UUID = UUID.fromString("6e7a0001-5c3a-4b8e-9d1f-0a1b2c3d4e5f")
    private val charUuid: UUID = UUID.fromString("6e7a0003-5c3a-4b8e-9d1f-0a1b2c3d4e5f")
    private val rx = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))
    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    /** write 를 받는 characteristic 하나가 있는 하네스. [maxMtu] 는 가짜 기기의 최대 MTU(null 이면 요청 그대로). */
    private fun TestScope.mtuHarness(maxMtu: Int? = null): Harness {
        val h = harness(config)
        h.fake.mtuResult = maxMtu
        h.fake.services = listOf(
            gapServiceSnapshot(),
            GattServiceSnapshot(
                serviceUuid = serviceUuid,
                characteristics = listOf(
                    GattCharacteristicSnapshot(
                        serviceUuid = serviceUuid,
                        charUuid = charUuid,
                        instanceIndex = 0,
                        propertyMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    ),
                ),
            ),
        )
        return h
    }

    private fun TestScope.write(h: Harness, size: Int, chunking: BleChunking): Result<WriteReceipt> {
        val job = async { runCatching { h.session(id).write(rx, ByteArray(size), BleWriteOptions(chunking = chunking)) } }
        h.waitUntil("write 완료") { job.isCompleted }
        return job.getCompleted()
    }

    private fun Result<*>.code(): String = ((exceptionOrNull() as? BleException)?.failure?.code?.value) ?: "ok"

    // ── 옵션 범위·기본값 ──────────────────────────────────────────────────────

    @Test
    fun `requestedMtu 는 23 에서 517 까지이고 밖이면 생성 때 IllegalArgumentException 이다`() {
        assertThrows(IllegalArgumentException::class.java) { BleConnectOptions(requestedMtu = 22) }
        assertThrows(IllegalArgumentException::class.java) { BleConnectOptions(requestedMtu = 518) }
        assertEquals(23, BleConnectOptions(requestedMtu = 23).requestedMtu)
        assertEquals(517, BleConnectOptions(requestedMtu = 517).requestedMtu)
        assertNull(BleConnectOptions().requestedMtu)
        assertNull(BleConnectOptions(autoReconnect = true).requestedMtu)
    }

    @Test
    fun `요청 값이 협상에 쓰이고 없으면 247 을 요청한다`() = runTest {
        val h = mtuHarness()
        h.connectToReady(id, BleConnectOptions())
        assertEquals(247, h.session(id).androidMtu.value)
        h.disconnect(id)
        h.waitUntil("Disconnected") { h.session(id).connectionState.value is ConnectionState.Disconnected }
        h.connectToReady(id, BleConnectOptions(requestedMtu = 23))
        assertEquals(23, h.session(id).androidMtu.value)
        h.close()
    }

    // ── 게시 ────────────────────────────────────────────────────────────────

    @Test
    fun `Ready 에서 write 한도는 협상 MTU 빼기 3 이고 연결 전에는 null 이다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        assertNull(h.session(id).writeLimits.value)
        assertNull(h.session(id).androidMtu.value)
        h.connectToReady(id)
        assertEquals(WriteLimits(62, 62), h.session(id).writeLimits.value)
        assertEquals(65, h.session(id).androidMtu.value)
        h.close()
    }

    @Test
    fun `요청 23 이면 한도 20 이다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.connectToReady(id, BleConnectOptions(requestedMtu = 23))
        assertEquals(WriteLimits(20, 20), h.session(id).writeLimits.value)
        assertEquals(23, h.session(id).androidMtu.value)
        h.close()
    }

    @Test
    fun `Ready 를 본 관찰자는 이미 write 한도를 읽는다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        val session = h.session(id)
        val seenAtReady = mutableListOf<WriteLimits?>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            session.connectionState.collect { if (it == ConnectionState.Ready) seenAtReady += session.writeLimits.value }
        }
        h.connectToReady(id)
        runCurrent()
        assertEquals(listOf<WriteLimits?>(WriteLimits(62, 62)), seenAtReady)
        watcher.cancel()
        h.close()
    }

    @Test
    fun `설정 단계에는 아직 null 이다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.fake.onMtu = CallbackVariant.Late(30)
        h.connect(id)
        h.waitUntil("Configuring") { h.session(id).connectionState.value == ConnectionState.Configuring }
        assertNull(h.session(id).writeLimits.value)
        assertNull(h.session(id).androidMtu.value)
        h.close()
    }

    @Test
    fun `협상이 실패하면 23 과 한도 20 으로 Ready 다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.fake.onMtu = CallbackVariant.Arrive(status = 133)
        h.connectToReady(id)
        assertEquals(WriteLimits(20, 20), h.session(id).writeLimits.value)
        assertEquals(23, h.session(id).androidMtu.value)
        h.close()
    }

    @Test
    fun `disconnect 뒤 null 이다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.connectToReady(id)
        h.disconnect(id)
        assertNull(h.session(id).writeLimits.value)
        h.waitUntil("Disconnected") { h.session(id).connectionState.value is ConnectionState.Disconnected }
        assertNull(h.session(id).writeLimits.value)
        assertNull(h.session(id).androidMtu.value)
        h.close()
    }

    @Test
    fun `링크를 잃은 Recovering 동안 null 이고 다시 Ready 면 값이 생긴다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.connectToReady(id, BleConnectOptions(autoReconnect = true))
        h.fake.linkLoss(id, 8)
        runCurrent()
        assertTrue(h.session(id).connectionState.value is ConnectionState.Recovering)
        assertNull(h.session(id).writeLimits.value)
        assertNull(h.session(id).androidMtu.value)
        h.fake.emitConnectionState(id, android.bluetooth.BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("재연결 Ready") { h.session(id).connectionState.value == ConnectionState.Ready }
        assertEquals(WriteLimits(62, 62), h.session(id).writeLimits.value)
        assertEquals(65, h.session(id).androidMtu.value)
        h.close()
    }

    // ── 조각 크기 = write 한도 ─────────────────────────────────────────────────

    @Test
    fun `자동 분할 조각 크기와 Disabled 한도는 writeLimits 와 같다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.connectToReady(id)
        val limits = h.session(id).writeLimits.value
        assertNotNull(limits)
        val limit = limits!!.withResponse
        assertEquals(62, limit)

        val before = h.fake.writes.size
        assertEquals("ok", write(h, 200, BleChunking.Automatic).code())
        val sizes = h.fake.writes.drop(before).map { it.value.size }
        assertEquals(listOf(limit, limit, limit, 200 - 3 * limit), sizes)

        assertEquals("PAYLOAD_TOO_LARGE", write(h, limit + 1, BleChunking.Disabled).code())
        assertEquals("ok", write(h, limit, BleChunking.Disabled).code())
        h.close()
    }

    // ── 같은 기기 다른 옵션 ────────────────────────────────────────────────────

    private fun TestScope.connectAgain(h: Harness, options: BleConnectOptions): String {
        val job = async { runCatching { h.session(id).connect(options) } }
        runCurrent()
        return job.getCompleted().code()
    }

    @Test
    fun `다른 requestedMtu 로 connect 하면 CONNECTION_OPTIONS_CONFLICT 이고 null 과 247 은 같다`() = runTest {
        val h = mtuHarness(maxMtu = 65)
        h.connectToReady(id, BleConnectOptions(requestedMtu = 23))
        assertEquals("CONNECTION_OPTIONS_CONFLICT", connectAgain(h, BleConnectOptions(requestedMtu = 65)))
        assertEquals("CONNECTION_OPTIONS_CONFLICT", connectAgain(h, BleConnectOptions()))
        assertEquals("ok", connectAgain(h, BleConnectOptions(requestedMtu = 23)))
        h.disconnect(id)
        h.waitUntil("Disconnected") { h.session(id).connectionState.value is ConnectionState.Disconnected }

        h.connectToReady(id, BleConnectOptions())
        assertEquals("ok", connectAgain(h, BleConnectOptions(requestedMtu = 247)))
        h.disconnect(id)
        h.waitUntil("Disconnected") { h.session(id).connectionState.value is ConnectionState.Disconnected }

        h.connectToReady(id, BleConnectOptions(requestedMtu = 247))
        assertEquals("ok", connectAgain(h, BleConnectOptions()))
        assertEquals(ConnectionState.Ready, h.session(id).connectionState.value)
        h.close()
    }

    // ── 다른 구현 ─────────────────────────────────────────────────────────────

    @Test
    fun `이 라이브러리가 만들지 않은 세션의 androidMtu 는 null 이다`() {
        val foreign = object : BleSession {
            override val peripheralId = id
            override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Ready)
            override val services: StateFlow<List<GattService>> = MutableStateFlow(emptyList())
            override val activeSubscriptions: StateFlow<Set<CharacteristicRef>> = MutableStateFlow(emptySet())
            override val writeLimits: StateFlow<WriteLimits?> = MutableStateFlow(WriteLimits(1, 1))
            override suspend fun connect(options: BleConnectOptions) = Unit
            override suspend fun disconnect() = Unit
            override suspend fun read(characteristic: CharacteristicRef) = ByteArray(0)
            override suspend fun write(characteristic: CharacteristicRef, value: ByteArray, options: BleWriteOptions) =
                error("unused")
            override suspend fun subscribe(characteristic: CharacteristicRef) = Unit
            override fun notifications(characteristic: CharacteristicRef): Flow<ByteArray> = error("unused")
            override suspend fun unsubscribe(characteristic: CharacteristicRef) = Unit
            override suspend fun readDescriptor(descriptor: DescriptorRef) = ByteArray(0)
            override suspend fun writeDescriptor(descriptor: DescriptorRef, value: ByteArray) = Unit
        }
        assertNull(foreign.androidMtu.value)
    }
}
