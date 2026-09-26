package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.model.WriteResult
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DeliverySemantics
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.internal.gatt.GattWriteCommandFailure
import kr.co.nucode.ble.internal.gatt.OperationQueue
import kr.co.nucode.ble.internal.gatt.chunkSizeForMtu
import kr.co.nucode.ble.internal.gatt.splitIntoChunks
import kr.co.nucode.ble.internal.session.SessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.harness.FakePlatformCharacteristic

/**
 * 순수 계산(청킹·chunkSize) + write ack 배선(생성자 [OperationQueue.writeCommand] 람다)을
 * 검증한다. 실제 `gatt.writeCharacteristic(...)` 호출은 GattBridge(이미 배선됨)의 몫이라
 * 여기서는 그 자리를 대신하는 람다로 성공/실패/타임아웃/취소를 흉내 낸다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationQueueTest {

    // ── 청크 분할 ─────────────────────────────────────────────

    @Test
    fun `900바이트를 244로 나누면 4청크, 마지막이 168`() {
        val bytes = ByteArray(900)
        val chunks = splitIntoChunks(bytes, chunkSize = 244)

        assertEquals(4, chunks.size)
        assertEquals(244, chunks[0].size)
        assertEquals(244, chunks[1].size)
        assertEquals(244, chunks[2].size)
        assertEquals(168, chunks[3].size)
    }

    @Test
    fun `페이로드가 chunkSize와 정확히 같으면 1청크`() {
        val bytes = ByteArray(244)
        val chunks = splitIntoChunks(bytes, chunkSize = 244)

        assertEquals(1, chunks.size)
        assertEquals(244, chunks[0].size)
    }

    @Test
    fun `페이로드가 chunkSize보다 1바이트 크면 2청크`() {
        val bytes = ByteArray(245)
        val chunks = splitIntoChunks(bytes, chunkSize = 244)

        assertEquals(2, chunks.size)
        assertEquals(244, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun `빈 페이로드는 청크 0개가 아니라 빈 청크 1개`() {
        // 근거: zero-length write 도 유효한 BLE 신호라 0개로 처리하면 write() 호출이
        // 기기에 아무 영향 없이 조용히 Ok 로 끝난다 — 부분 전달을 숨기지 않는다는
        // 이 파일의 원칙과 같은 이유로, 빈 요청도 숨기지 않고 실제로 한 번 내보낸다.
        val chunks = splitIntoChunks(ByteArray(0), chunkSize = 244)

        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].size)
    }

    // ── chunkSize 계산 ─────────────────────────────────────────

    @Test
    fun `mtu 247이면 chunkSize 244`() {
        assertEquals(244, chunkSizeForMtu(247))
    }

    @Test
    fun `mtu 23이면 chunkSize 20`() {
        assertEquals(20, chunkSizeForMtu(23))
    }

    // ── BleChunking.Disabled ────────────────────────────────────────

    @Test
    fun `BleChunking Disabled이고 페이로드가 chunkSize 이하면 쪼개지 않고 그대로 1청크`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ -> chunks.map { GATT_SUCCESS } }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(244),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Disabled,
        )

        assertEquals(WriteResult.Completed(1), result)
    }

    @Test
    fun `BleChunking Disabled인데 chunkSize를 넘으면 조용히 쪼개지 않고 TooLarge`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
        }

        // TooLarge 분기는 writeCommand 호출 전에 끝난다 — 그래서 discovered 대응표를
        // 채우지 않아도 이 테스트는 그대로 통과한다.
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ -> chunks.map { GATT_SUCCESS } }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef(UUID.randomUUID(), UUID.randomUUID()),
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Disabled,
        )

        assertTrue(result is WriteResult.TooLarge)
        result as WriteResult.TooLarge
        assertEquals(900, result.size)
        assertEquals(244, result.max)
    }

    @Test
    fun `BleChunking Automatic면 chunkSize를 넘어도 쪼개져서 성공한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ -> chunks.map { GATT_SUCCESS } }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Automatic,
        )

        assertEquals(WriteResult.Completed(4), result)
        assertFalse(result is WriteResult.Failed)
    }

    @Test
    fun `BleChunking Fixed는 협상 capability 이내의 지정 크기로 정확히 나눈다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FE")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 20
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }
        val seen = mutableListOf<ByteArray>()
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ ->
            seen += chunks
            chunks.map { GATT_SUCCESS }
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(deviceId, charRef, ByteArray(40), WriteType.WithResponse, BleChunking.Fixed(10))

        assertEquals(WriteResult.Completed(4), result)
        assertEquals(listOf(10, 10, 10, 10), seen.map(ByteArray::size))
    }

    @Test
    fun `BleChunking Fixed가 협상 capability를 넘으면 platform write 전에 TooLarge다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FD")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 20
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }
        var calls = 0
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ ->
            calls += chunks.size
            chunks.map { GATT_SUCCESS }
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(deviceId, charRef, ByteArray(40), WriteType.WithResponse, BleChunking.Fixed(21))

        assertEquals(WriteResult.TooLarge(21, 20), result)
        assertEquals(0, calls)
    }

    @Test
    fun `모르는 PeripheralId면 UnknownDevice를 던진다`() = runTest {
        val registry = SessionRegistry()
        val queue = OperationQueue(registry, emit = { }, writeCommand = { _, _, chunks, _, _ -> chunks.map { GATT_SUCCESS } }, ackTimeoutMillis = 5_000)

        val failure = runCatching { queue.write(
            deviceId = PeripheralId("00:00:00:00:00:00"),
            charRef = charRef(UUID.randomUUID(), UUID.randomUUID()),
            bytes = ByteArray(1),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Automatic,
        ) }.exceptionOrNull() as BleException
        assertEquals("UNKNOWN_DEVICE", failure.failure.code.value)
    }

    @Test
    fun `연결 상태가 Connected가 아니면 NotConnected를 던진다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId) // state 기본값은 Disconnected

        val queue = OperationQueue(registry, emit = { }, writeCommand = { _, _, chunks, _, _ -> chunks.map { GATT_SUCCESS } }, ackTimeoutMillis = 5_000)

        val failure = runCatching { queue.write(
            deviceId = deviceId,
            charRef = charRef(UUID.randomUUID(), UUID.randomUUID()),
            bytes = ByteArray(1),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Automatic,
        ) }.exceptionOrNull() as BleException
        assertEquals("NOT_CONNECTED", failure.failure.code.value)
    }

    @Test
    fun `MTU 협상 전이어도 Connected면 기본 chunkSize 20으로 write가 동작한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            // chunkSize 는 협상 전이라 기본 null
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = { }, writeCommand = { _, _, chunks, _, _ -> chunks.map { GATT_SUCCESS } }, ackTimeoutMillis = 5_000)

        // 기본 chunkSize(20) 이하 페이로드는 그대로 1청크로 성공한다.
        val ok = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(20),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Disabled,
        )
        assertEquals(WriteResult.Completed(1), ok)

        // 기본 chunkSize(20) 을 넘으면 TooLarge 로 그 값이 20 임을 드러낸다.
        val tooLarge = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(21),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Disabled,
        )
        assertTrue(tooLarge is WriteResult.TooLarge)
        tooLarge as WriteResult.TooLarge
        assertEquals(20, tooLarge.max)
    }

    // ── writeCommand ack 배선(task 3) ──────────────────────────────

    @Test
    fun `writeCommand 람다가 모두 0을 반환하면 Ok, 청크 수만큼 전달되고 각 청크 크기가 맞다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val calls = mutableListOf<ByteArray>()
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ ->
            calls += chunks
            chunks.map { GATT_SUCCESS }
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Automatic,
        )

        assertEquals(WriteResult.Completed(4), result)
        assertEquals(4, calls.size)
        assertEquals(244, calls[0].size)
        assertEquals(244, calls[1].size)
        assertEquals(244, calls[2].size)
        assertEquals(168, calls[3].size)
    }

    @Test
    fun `MTU 65에서 600바이트 쓰기는 62바이트 단위 10청크로 완료된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = chunkSizeForMtu(65)
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }
        val chunks = mutableListOf<ByteArray>()
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, batch, _, _ ->
            chunks += batch.map(ByteArray::copyOf)
            batch.map { GATT_SUCCESS }
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId,
            charRef,
            ByteArray(600) { it.toByte() },
            WriteType.WithResponse,
            BleChunking.Automatic,
        )

        assertEquals(WriteResult.Completed(10), result)
        assertEquals(List(9) { 62 } + 42, chunks.map(ByteArray::size))
        assertEquals(ByteArray(600) { it.toByte() }.toList(), chunks.flatMap(ByteArray::toList))
    }

    @Test
    fun `600바이트 쓰기 중 3번째 청크에서 연결이 끊기면 부분 진행률을 남기고 재전송하지 않는다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = chunkSizeForMtu(65)
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }
        var callCount = 0
        val queue = OperationQueue(registry, emit = {}, writeCommand = { session, _, chunks, _, _ ->
            // 엔진은 끊김으로 무효화된 청크를 GattWriteCommandFailure(index) 로 알린다.
            chunks.indices.map { index ->
                callCount += 1
                if (callCount == 3) {
                    throw GattWriteCommandFailure(
                        index,
                        GattFailureFactory.writeInvalidated(session, OperationKind.WriteWithResponse),
                    )
                }
                GATT_SUCCESS
            }
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId,
            charRef,
            ByteArray(600) { it.toByte() },
            WriteType.WithResponse,
            BleChunking.Automatic,
        ) as WriteResult.Failed
        val progress = requireNotNull(result.failure.writeProgress)

        assertEquals("OPERATION_INVALIDATED", result.failure.code.value)
        assertEquals(600, progress.totalBytes)
        assertEquals(10, progress.totalChunks)
        assertEquals(124, progress.bytesCompleted)
        assertEquals(2, progress.chunksCompleted)
        assertEquals(2, progress.failedChunkIndex)
        assertEquals(62, progress.failedChunkBytes)
        assertTrue(progress.failedChunkWasStarted)
        assertEquals(DeliverySemantics.RemoteAcknowledged, progress.delivery)
        assertTrue(progress.currentChunkDeliveryUnknown)
        assertEquals(3, callCount)
    }

    @Test
    fun `3번째 청크에서 133을 반환하면 typed progress가 2번째 index를 보존한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        var callCount = 0
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, chunks, _, _ ->
            // 엔진은 실패 status 를 만난 청크까지만 결과를 돌려준다.
            val statuses = mutableListOf<Int>()
            for (chunk in chunks) {
                callCount += 1
                val status = if (callCount == 3) GATT_FAILURE_133 else GATT_SUCCESS
                statuses += status
                if (status != GATT_SUCCESS) break
            }
            statuses
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900), // 244 chunkSize -> 4청크
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Automatic,
        )

        assertTrue(result is WriteResult.Failed)
        result as WriteResult.Failed
        assertEquals(2, result.failure.writeProgress?.failedChunkIndex)
        assertEquals(4, result.failure.writeProgress?.totalChunks)
        assertTrue(result.failure.writeProgress?.currentChunkDeliveryUnknown == true)
        assertEquals("GATT_STATUS_FAILED", result.failure.code.value)
    }

    @Test
    fun `첫 청크에서 133을 반환하면 typed progress의 completed chunk는 없다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, _, _, _ -> listOf(GATT_FAILURE_133) }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Automatic,
        )

        assertTrue(result is WriteResult.Failed)
        result as WriteResult.Failed
        assertEquals(0, result.failure.writeProgress?.failedChunkIndex)
        assertFalse(result.failure.writeProgress?.chunksCompleted?.let { it > 0 } == true)
    }

    @Test
    fun `writeCommand 람다가 CancellationException을 던지면 Failed로 보고하고 호출자는 살아있다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        // 실제로는 GattBridge가 끊김을 감지해 scheduler completion을 취소하는 상황과 같다.
        // 여기서는 그 결과인 CancellationException을 람다가 직접 던져 흉내낸다.
        val queue = OperationQueue(registry, emit = {}, writeCommand = { _, _, _, _, _ ->
            throw CancellationException("gatt disconnected")
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(20),
            writeType = WriteType.WithResponse,
            chunking = BleChunking.Disabled,
        )

        assertTrue(result is WriteResult.Failed)
        result as WriteResult.Failed
        assertEquals("OPERATION_INVALIDATED", result.failure.code.value)

        // 진짜 코루틴 취소였다면 위 queue.write() 호출에서 이 지점까지 오지 못하고
        // 테스트 코루틴 자체가 조용히 중단됐을 것이다 — 여기 도달했다는 사실 자체가
        // "호출자 코루틴은 살아있다"는 증거다(리뷰 #7 함정 재발 방지).
        assertTrue(true)
    }

    @Test
    fun `production writeCommand seam에는 public write의 모든 청크가 한 번에 전달된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val charRef = charRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 2
        }
        val batches = mutableListOf<List<ByteArray>>()
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            ackTimeoutMillis = 5_000,
            writeCommand = { _, actualRef, chunks, actualType, timeout ->
                assertEquals(charRef, actualRef)
                assertEquals(WriteType.Auto, actualType)
                assertEquals(5_000L, timeout)
                batches += chunks
                chunks.map { GATT_SUCCESS }
            },
        )

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = byteArrayOf(1, 2, 3, 4, 5),
            writeType = WriteType.Auto,
            chunking = BleChunking.Automatic,
        )

        assertEquals(WriteResult.Completed(3), result)
        assertEquals(1, batches.size)
        assertEquals(listOf(2, 2, 1), batches.single().map(ByteArray::size))
    }

    private companion object {
        private const val GATT_SUCCESS = 0
        private const val GATT_FAILURE_133 = 133

        /**
         * 테스트 전용 characteristic 인스턴스. android 단위 테스트 스텁 jar 에서는
         * `BluetoothGattCharacteristic` 의 getter(`getUuid()` 등)를 부르면
         * "not mocked" RuntimeException 이 난다 — 그래서 이 인스턴스는 절대 getter 를
         * 부르지 않고 참조 동일성(`===`, [Session.discovered] 맵의 값)으로만 쓴다.
         * 생성자 자체(필드 대입만 하는 코드)는 스텁 jar 에서도 예외 없이 통과한다.
         */
        private fun fakeCharacteristic(charRef: CharacteristicRef): PlatformCharacteristic =
            FakePlatformCharacteristic(charRef.uuid.value)
    }
}
