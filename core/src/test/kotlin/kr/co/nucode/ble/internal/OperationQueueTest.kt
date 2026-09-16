package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.CharRef
import kr.co.nucode.ble.Chunking
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.WriteResult
import kr.co.nucode.ble.WriteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 순수 계산(청킹·chunkSize) + write ack 배선(생성자 [OperationQueue.writeChunk] 람다)을
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

    // ── Chunking.None ────────────────────────────────────────

    @Test
    fun `Chunking None이고 페이로드가 chunkSize 이하면 쪼개지 않고 그대로 1청크`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ -> GATT_SUCCESS }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(244),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
        )

        assertEquals(WriteResult.Completed(1), result)
    }

    @Test
    fun `Chunking None인데 chunkSize를 넘으면 조용히 쪼개지 않고 TooLarge`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
        }

        // resolve() 는 TooLarge 분기(청크 루프 진입 전) 이후엔 아예 불리지 않는다 —
        // 그래서 discovered 대응표를 채우지 않아도 이 테스트는 그대로 통과한다.
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ -> GATT_SUCCESS }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = CharRef(UUID.randomUUID(), UUID.randomUUID()),
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
        )

        assertTrue(result is WriteResult.TooLarge)
        result as WriteResult.TooLarge
        assertEquals(900, result.size)
        assertEquals(244, result.max)
    }

    @Test
    fun `Chunking Auto면 chunkSize를 넘어도 쪼개져서 성공한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ -> GATT_SUCCESS }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
        )

        assertEquals(WriteResult.Completed(4), result)
        assertFalse(result is WriteResult.Failed)
    }

    @Test
    fun `Chunking Fixed는 협상 capability 이내의 지정 크기로 정확히 나눈다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FE")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 20
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }
        val seen = mutableListOf<ByteArray>()
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, chunk, _ ->
            seen += chunk
            GATT_SUCCESS
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(deviceId, charRef, ByteArray(40), WriteType.WithResponse, Chunking.Fixed(10))

        assertEquals(WriteResult.Completed(4), result)
        assertEquals(listOf(10, 10, 10, 10), seen.map(ByteArray::size))
    }

    @Test
    fun `Chunking Fixed가 협상 capability를 넘으면 platform write 전에 TooLarge다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FD")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 20
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }
        var calls = 0
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ ->
            calls += 1
            GATT_SUCCESS
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(deviceId, charRef, ByteArray(40), WriteType.WithResponse, Chunking.Fixed(21))

        assertEquals(WriteResult.TooLarge(21, 20), result)
        assertEquals(0, calls)
    }

    @Test
    fun `모르는 DeviceId면 UnknownDevice를 던진다`() = runTest {
        val registry = SessionRegistry()
        val queue = OperationQueue(registry, emit = { }, writeChunk = { _, _, _, _, _ -> GATT_SUCCESS }, ackTimeoutMillis = 5_000)

        val failure = runCatching { queue.write(
            deviceId = DeviceId("00:00:00:00:00:00"),
            charRef = CharRef(UUID.randomUUID(), UUID.randomUUID()),
            bytes = ByteArray(1),
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
        ) }.exceptionOrNull() as BleException
        assertEquals("UNKNOWN_DEVICE", failure.failure.code.value)
    }

    @Test
    fun `연결 상태가 Connected가 아니면 NotConnected를 던진다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId) // state 기본값은 Disconnected

        val queue = OperationQueue(registry, emit = { }, writeChunk = { _, _, _, _, _ -> GATT_SUCCESS }, ackTimeoutMillis = 5_000)

        val failure = runCatching { queue.write(
            deviceId = deviceId,
            charRef = CharRef(UUID.randomUUID(), UUID.randomUUID()),
            bytes = ByteArray(1),
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
        ) }.exceptionOrNull() as BleException
        assertEquals("NOT_CONNECTED", failure.failure.code.value)
    }

    @Test
    fun `MTU 협상 전이어도 Connected면 기본 chunkSize 20으로 write가 동작한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            // chunkSize 는 협상 전이라 기본 null
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = { }, writeChunk = { _, _, _, _, _ -> GATT_SUCCESS }, ackTimeoutMillis = 5_000)

        // 기본 chunkSize(20) 이하 페이로드는 그대로 1청크로 성공한다.
        val ok = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(20),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
        )
        assertEquals(WriteResult.Completed(1), ok)

        // 기본 chunkSize(20) 을 넘으면 TooLarge 로 그 값이 20 임을 드러낸다.
        val tooLarge = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(21),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
        )
        assertTrue(tooLarge is WriteResult.TooLarge)
        tooLarge as WriteResult.TooLarge
        assertEquals(20, tooLarge.max)
    }

    // ── writeChunk ack 배선(task 3) ──────────────────────────────

    @Test
    fun `writeChunk 람다가 매번 0을 반환하면 Ok, 청크 수만큼 호출되고 각 청크 크기가 맞다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val calls = mutableListOf<ByteArray>()
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, chunk, _ ->
            calls += chunk
            GATT_SUCCESS
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
        )

        assertEquals(WriteResult.Completed(4), result)
        assertEquals(4, calls.size)
        assertEquals(244, calls[0].size)
        assertEquals(244, calls[1].size)
        assertEquals(244, calls[2].size)
        assertEquals(168, calls[3].size)
    }

    @Test
    fun `3번째 청크에서 133을 반환하면 typed progress가 2번째 index를 보존한다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        var callCount = 0
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ ->
            callCount += 1
            if (callCount == 3) GATT_FAILURE_133 else GATT_SUCCESS
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900), // 244 chunkSize -> 4청크
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
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
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ -> GATT_FAILURE_133 }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900),
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
        )

        assertTrue(result is WriteResult.Failed)
        result as WriteResult.Failed
        assertEquals(0, result.failure.writeProgress?.failedChunkIndex)
        assertFalse(result.failure.writeProgress?.chunksCompleted?.let { it > 0 } == true)
    }

    @Test
    fun `writeChunk 람다가 5초 안에 응답하지 않으면 타임아웃으로 Failed`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        // 응답 없이 영원히 suspend — runTest 가 이 코루틴이 그것 말고는 할 일이 없다고
        // 판단하면 가상 시계를 5초까지 앞당겨 withTimeout 이 스스로 취소시킨다.
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ -> awaitCancellation() }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(20),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
        )

        assertTrue(result is WriteResult.Failed)
        result as WriteResult.Failed
        assertEquals(0, result.failure.writeProgress?.failedChunkIndex)
        assertEquals("OPERATION_TIMEOUT", result.failure.code.value)
    }

    /**
     * plan blecore-app-tunables 결정③ 배선 검증 — 생성자로 받은 [ackTimeoutMillis] 가
     * 실제로 `withTimeout` 에 쓰이는지. 1_000 을 주입하고 가상 시계가 정확히 1초에서
     * 멈추는지(`currentTime`) 확인한다 — 옛 상수(5_000, 구 `WRITE_ACK_TIMEOUT_MS`)가 아직
     * 남아 있었다면 이 값이 5_000 이었을 것이다.
     */
    @Test
    fun `주입된 ackTimeoutMillis 1000으로 응답 없으면 1초 만에 타임아웃되고 5초가 아니다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ -> awaitCancellation() }, ackTimeoutMillis = 1_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(20),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
        )

        assertTrue(result is WriteResult.Failed)
        result as WriteResult.Failed
        assertEquals("OPERATION_TIMEOUT", result.failure.code.value)
        assertEquals("주입값 1000이 아니라 다른 타임아웃이 걸렸다(생성자 주입 실패 의심)", 1_000L, currentTime)
    }

    @Test
    fun `writeChunk 람다가 CancellationException을 던지면 Failed로 보고하고 호출자는 살아있다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to fakeCharacteristic(charRef)))
        }

        // 실제로는 GattBridge 가 끊김을 감지해 session.pendingWrite.cancel() 을 부르는
        // 상황과 같다 — 여기서는 그 결과인 CancellationException 을 람다가 직접 던져 흉내낸다.
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, _, _, _, _ ->
            throw CancellationException("gatt disconnected")
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(20),
            writeType = WriteType.WithResponse,
            chunking = Chunking.None,
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
    fun `write 도중 updateDiscovered로 characteristic이 갈리면 다음 청크는 새 인스턴스로 나간다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        val charA = fakeCharacteristic(charRef)
        val charB = fakeCharacteristic(charRef)

        val session = registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 244
            updateDiscovered(mapOf(charRef to charA))
        }

        val seen = mutableListOf<BluetoothGattCharacteristic>()
        val queue = OperationQueue(registry, emit = {}, writeChunk = { _, characteristic, _, _, _ ->
            seen += characteristic
            if (seen.size == 2) {
                // 두 번째 청크가 나간 직후 재탐색이 일어난 것처럼 대응표를 갈아 끼운다.
                session.updateDiscovered(mapOf(charRef to charB))
            }
            GATT_SUCCESS
        }, ackTimeoutMillis = 5_000)

        val result = queue.write(
            deviceId = deviceId,
            charRef = charRef,
            bytes = ByteArray(900), // chunkSize 244 -> 4청크
            writeType = WriteType.WithResponse,
            chunking = Chunking.Auto,
        )

        assertEquals(WriteResult.Completed(4), result)
        assertEquals(4, seen.size)
        assertSame(charA, seen[0])
        assertSame(charA, seen[1])
        assertSame(charB, seen[2])
        assertSame(charB, seen[3])
    }

    @Test
    fun `production writeCommand seam에는 public write의 모든 청크가 한 번에 전달된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = DeviceId("AA:BB:CC:DD:EE:FF")
        val charRef = CharRef(UUID.randomUUID(), UUID.randomUUID())
        registry.getOrCreate(deviceId).apply {
            state = ConnState.Connected
            chunkSize = 2
        }
        var legacyChunkCalls = 0
        val batches = mutableListOf<List<ByteArray>>()
        val queue = OperationQueue(
            registry = registry,
            emit = {},
            writeChunk = { _, _, _, _, _ ->
                legacyChunkCalls += 1
                GATT_SUCCESS
            },
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
            chunking = Chunking.Auto,
        )

        assertEquals(WriteResult.Completed(3), result)
        assertEquals(0, legacyChunkCalls)
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
        private fun fakeCharacteristic(charRef: CharRef): BluetoothGattCharacteristic =
            BluetoothGattCharacteristic(charRef.charUuid, 0, 0)
    }
}
