package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharRef
import kr.co.nucode.ble.Chunking
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.DeliverySemantics
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.WriteProgress
import kr.co.nucode.ble.WriteResult
import kr.co.nucode.ble.WriteType

/**
 * write 직렬화 + 청킹(설계 §6 구성요소, §5 WriteResult, plan §잠재에러 chunkSize).
 *
 * 청크 하나를 실제로 내보내고 ack 를 기다리는 일은 [writeChunk] 람다가 한다 — BleRuntime
 * 조립부가 GattBridge 의 suspend 함수(`GattBridge.writeChunk`)를 그대로 넘겨준다
 * (plan §접근 ②). 이 파일은 GattBridge 타입을 import 하지 않는다 — 워커 간 직접 참조
 * 금지 원칙(설계 §1 원인②) 때문에, 두 워커를 잇는 건 오직 BleRuntime 조립 라인뿐이다.
 * 이 클래스가 [writeChunk] 에 대해 아는 전부는 "청크 하나를 주면 [ackTimeoutMillis] 안에
 * GATT status (Int) 가 와야 한다"는 계약이다.
 *
 * [ackTimeoutMillis] 는 write 청크 하나의 ack(onCharacteristicWrite) 대기 상한이다(plan
 * blecore-app-tunables 결정③ — 구 상수 `WRITE_ACK_TIMEOUT_MS` 대체). `BleRuntime` 조립부가
 * `BleConfig.operationTimeoutMillis` 를 그대로 넘긴다 — CCCD ack 쪽(GattBridge)과 값은
 * 같아도 워커 간 참조 금지 원칙상 상수를 공유하지 않고 각자 주입받는다.
 */
internal class OperationQueue(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val writeChunk: suspend (
        Session,
        BluetoothGattCharacteristic,
        CharRef,
        ByteArray,
        WriteType,
    ) -> Int,
    private val ackTimeoutMillis: Long,
    private val writeCommand: (suspend (
        Session,
        CharRef,
        List<ByteArray>,
        WriteType,
        Long,
    ) -> List<Int>)? = null,
) {

    /**
     * 기기 하나에 write 하나를 직렬화해 실행한다. 같은 기기에 대해 동시에 두 번 호출되면
     * 뒤의 호출은 앞이 끝날 때까지 [Session.gattOpLock] 에서 기다린다(직렬화 구조,
     * plan §담당범위 4). 락을 Session 별 DeviceId 맵으로 따로 두지 않는 이유는
     * Session.kt KDoc 참고 — 정리 누락(락 누수)이 구조적으로 불가능해진다(설계 §1 원인②).
     *
     * 연결 여부는 [Session.canStartPublicGattOperation]으로 판정한다. Slice 2 lifecycle
     * generation은 [ConnState.Ready]에서만 write를 시작하며, generation 0의 legacy
     * [ConnState.Connected]는 기존 direct seam 호환 경로로만 유지한다.
     * `NOT_CONNECTED` typed failure를 낸다. chunkSize 는 Session 이 들고 있는 협상된 값을 그대로 쓰되,
     * 아직 협상 전(null)이면 [DEFAULT_CHUNK_SIZE](BLE 기본 ATT_MTU 23 기준)로 대체한다 —
     * MTU 협상은 연결 이후에 일어나는 별개의 단계라, 협상 전이라고 해서 연결 자체가
     * 안 된 것처럼 NotConnected 를 던지면 의미가 틀린다. chunkSize 를 코어 밖에서
     * 다시 계산하지는 않는다(설계 §2, Session 이 사실의 소유자).
     */
    suspend fun write(
        deviceId: DeviceId,
        charRef: CharRef,
        bytes: ByteArray,
        writeType: WriteType,
        chunking: Chunking,
    ): WriteResult {
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)

        return session.gattOpLock.withLock {
            // 락 획득 **후에** 읽는다. withLock 대기는 suspend 지점이라 그 사이 끊김·재연결로
            // state/chunkSize 가 바뀔 수 있다 — 락 밖에서 읽은 244 로 MTU 23 링크에 쓰면
            // 스택이 초과분을 조용히 잘라 기기가 손상된 스트림을 받고도 Ok 가 반환된다.
            if (!session.canStartPublicGattOperation()) throw notConnected(deviceId)
            val chunkSize = session.chunkSize ?: DEFAULT_CHUNK_SIZE
            val chunks = when (chunking) {
                Chunking.None -> {
                    if (bytes.size > chunkSize) {
                        return@withLock WriteResult.TooLarge(size = bytes.size, max = chunkSize)
                    }
                    listOf(bytes)
                }
                Chunking.Auto -> splitIntoChunks(bytes, chunkSize)
                is Chunking.Fixed -> {
                    if (chunking.maxBytes > chunkSize) {
                        return@withLock WriteResult.TooLarge(size = chunking.maxBytes, max = chunkSize)
                    }
                    splitIntoChunks(bytes, chunking.maxBytes)
                }
            }
            val total = chunks.size

            // Production GATT 경로는 public write의 모든 청크를 한 번에 scheduler에 예약한다.
            // 청크마다 enqueue/await를 반복하면 첫 청크 callback 직후 이미 대기 중인 CCCD가
            // 두 번째 청크보다 먼저 시작해 한 command가 중간에서 갈라진다. 테스트용 legacy
            // writeChunk seam은 순수 청킹/결과 매핑 테스트를 위해 그대로 둔다.
            writeCommand?.let { execute ->
                val statuses = try {
                    execute(session, charRef, chunks, writeType, ackTimeoutMillis)
                } catch (e: GattWriteCommandFailure) {
                    return@withLock failedWrite(e.failure, deviceId, charRef, writeType, chunks, e.chunkIndex)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return@withLock failedWrite(
                        operationInvalidated(deviceId, charRef, writeType),
                        deviceId, charRef, writeType, chunks, 0,
                    )
                }

                for ((index, status) in statuses.withIndex()) {
                    if (status != GATT_SUCCESS) {
                        return@withLock failedWrite(
                            gattStatusFailure(status, deviceId, charRef, writeType),
                            deviceId, charRef, writeType, chunks, index,
                        )
                    }
                }
                check(statuses.size == total) {
                    "write command 결과 수(${statuses.size})가 청크 수($total)와 다르다"
                }
                return@withLock WriteResult.Completed(total)
            }

            for ((index, chunk) in chunks.withIndex()) {
                // 매 청크마다 다시 resolve 한다 — 루프 밖에서 한 번만 resolve 하지 않는다.
                // 서비스 재탐색(discoverServices)이 오가면 BluetoothGattCharacteristic
                // 인스턴스 자체가 갈리므로, 앞 청크에서 얻은 인스턴스를 뒤 청크에 그대로
                // 쓰면 낡은 인스턴스에 write 하게 된다.
                val char = session.resolve(charRef)
                    ?: return@withLock failedWrite(
                        characteristicNotFound(deviceId, charRef, writeType),
                        deviceId, charRef, writeType, chunks, index,
                    )

                val status = try {
                    withTimeout(ackTimeoutMillis) {
                        writeChunk(
                            session,
                            char,
                            charRef,
                            chunk,
                            writeType,
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    // GattBridge가 scheduler의 queued/pending 항목을 회수한 뒤 이 예외를
                    // 다시 던진다. generation poison은 후속 lifecycle task의 책임이다.
                    return@withLock failedWrite(
                        operationTimeout(deviceId, charRef, writeType),
                        deviceId, charRef, writeType, chunks, index,
                    )
                } catch (e: CancellationException) {
                    // lifecycle 정리가 scheduler completion을 취소한 경우 await()가
                    // CancellationException을 던진다. 이걸 그대로 위로 흘리면 write()를 부른
                    // 코루틴 자체가 취소된 것처럼 조용히 죽는다. ensureActive()로
                    // "내 코루틴이 취소된 것"과 "기기가 끊긴 것"을 가른다 — 전자면 그대로
                    // 재던져 정상적인 취소 전파를 지키고, 후자만 Failed 로 보고한다.
                    currentCoroutineContext().ensureActive()
                    return@withLock failedWrite(
                        operationInvalidated(deviceId, charRef, writeType),
                        deviceId, charRef, writeType, chunks, index,
                    )
                }

                if (status != GATT_SUCCESS) {
                    // 청크 0 을 보내기 전에 실패했으면 false, 하나라도 나갔으면 true.
                    // 설계 §5: 부분 전달을 로그로 흘리면 기기가 불완전한 명령을 받은 상태를
                    // 아무도 모르게 된다 — 이 값이 이 파일의 존재 이유다.
                    return@withLock failedWrite(
                        gattStatusFailure(status, deviceId, charRef, writeType),
                        deviceId, charRef, writeType, chunks, index,
                    )
                }
            }
            WriteResult.Completed(total)
        }
    }
}

/** Scheduler 기반 batch write가 실패한 정확한 청크와 원인을 OperationQueue에 전달한다. */
internal class GattWriteCommandFailure(
    val chunkIndex: Int,
    val failure: BleFailure,
) : Exception(failure.message ?: failure.code.value)

private fun unknownDevice(deviceId: DeviceId): kr.co.nucode.ble.BleException =
    kr.co.nucode.ble.BleException(
        BleFailure(
            category = FailureCategory("validation"),
            code = FailureCode("UNKNOWN_DEVICE"),
            phase = FailurePhase.Validation,
            retryability = Retryability.Never,
            context = FailureContext(),
        ),
    )

private fun notConnected(deviceId: DeviceId): kr.co.nucode.ble.BleException =
    kr.co.nucode.ble.BleException(
        BleFailure(
            category = FailureCategory("connection"),
            code = FailureCode("NOT_CONNECTED"),
            phase = FailurePhase.Operation,
            retryability = Retryability.NewConnectionMayHelp,
            context = FailureContext(),
        ),
    )

private fun characteristicNotFound(deviceId: DeviceId, charRef: CharRef, writeType: WriteType): BleFailure =
    BleFailure(
        category = FailureCategory("validation"),
        code = FailureCode("CHARACTERISTIC_NOT_FOUND"),
        phase = FailurePhase.Operation,
        retryability = Retryability.Never,
        context = writeContext(deviceId, charRef, writeType),
    )

private fun operationTimeout(deviceId: DeviceId, charRef: CharRef, writeType: WriteType): BleFailure =
    BleFailure(
        category = FailureCategory("operation"),
        code = FailureCode("OPERATION_TIMEOUT"),
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = writeContext(deviceId, charRef, writeType),
    )

private fun operationInvalidated(deviceId: DeviceId, charRef: CharRef, writeType: WriteType): BleFailure =
    BleFailure(
        category = FailureCategory("operation"),
        code = FailureCode("OPERATION_INVALIDATED"),
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = writeContext(deviceId, charRef, writeType),
    )

private fun gattStatusFailure(status: Int, deviceId: DeviceId, charRef: CharRef, writeType: WriteType): BleFailure =
    FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.Operation,
        operation = AndroidApiOperation.OnCharacteristicWrite,
        context = writeContext(deviceId, charRef, writeType),
    )

private fun writeContext(
    @Suppress("UNUSED_PARAMETER") deviceId: DeviceId,
    @Suppress("UNUSED_PARAMETER") charRef: CharRef,
    writeType: WriteType,
): FailureContext =
    FailureContext(
        operationKind = if (writeType == WriteType.WithoutResponse) {
            OperationKind.WriteWithoutResponse
        } else {
            OperationKind.WriteWithResponse
        },
    )

private fun failedWrite(
    base: BleFailure,
    deviceId: DeviceId,
    charRef: CharRef,
    writeType: WriteType,
    chunks: List<ByteArray>,
    failedChunkIndex: Int,
): WriteResult.Failed {
    val chunksCompleted = failedChunkIndex.coerceIn(0, chunks.size)
    val bytesCompleted = chunks.take(chunksCompleted).sumOf(ByteArray::size)
    val failedChunk = chunks.getOrNull(failedChunkIndex)
    return WriteResult.Failed(
        base.copy(
            context = base.context.copy(
                operationKind = writeContext(deviceId, charRef, writeType).operationKind,
            ),
            writeProgress = WriteProgress(
                totalBytes = chunks.sumOf(ByteArray::size),
                totalChunks = chunks.size,
                bytesCompleted = bytesCompleted,
                chunksCompleted = chunksCompleted,
                failedChunkIndex = failedChunkIndex,
                failedChunkBytes = failedChunk?.size,
                failedChunkWasStarted = true,
                delivery = if (writeType == WriteType.WithoutResponse) {
                    DeliverySemantics.SubmittedToPlatform
                } else {
                    DeliverySemantics.RemoteAcknowledged
                },
                currentChunkDeliveryUnknown = true,
            ),
        ),
    )
}

    /** ATT opcode(1바이트) + handle(2바이트). 설계 §6: chunkSize = mtu - 3. */
    private const val ATT_HEADER_OVERHEAD = 3

    /** GATT 성공 status 코드. `android.bluetooth.BluetoothGatt.GATT_SUCCESS` 와 같은 값(0)이다. */
    private const val GATT_SUCCESS = 0

    /**
     * MTU 협상 전에 쓰는 기본 청크 크기. BLE 기본 ATT_MTU 23 에서 온 값
     * (`chunkSizeForMtu(23)` = 20) — 협상 전에도 write 자체는 유효한 동작이다.
     */
    internal val DEFAULT_CHUNK_SIZE = chunkSizeForMtu(23)

    /**
     * MTU 로부터 청크 크기를 계산한다. mtu 247 → 244, mtu 23(협상 실패 시 기본값) → 20.
     * `internal` 이라 같은 모듈 테스트에서 바로 보인다.
     */
    internal fun chunkSizeForMtu(mtu: Int): Int =
        (mtu - ATT_HEADER_OVERHEAD).coerceAtLeast(1)

    /**
     * 페이로드를 chunkSize 단위로 자른다. 900 / 244 → [244][244][244][168].
     *
     * `bytes.toList().chunked(n).map { it.toByteArray() }` 대신 `copyOfRange` 로 직접
     * 자른다(review #17) — `toList()` 는 ByteArray 의 원시 byte 를 전부 Byte 박싱 객체로
     * 바꾼 뒤에야 다시 배열로 되돌리는데, write 페이로드는 수백~수천 바이트가 흔해
     * 매 write 마다 그만큼의 불필요한 힙 박싱이 쌓인다. 동작은 동일하다 — 이 계약을
     * 검증하는 게 바로 아래 청킹 테스트 5개다.
     *
     * 빈 페이로드(`bytes.isEmpty()`)는 청크 0개가 아니라 **빈 청크 1개**로 취급한다.
     * zero-length write 도 유효한 BLE 동작(신호용 트리거)이라, 0개로 처리하면
     * write() 호출 자체가 기기에 아무 영향도 주지 않은 채 조용히 Ok 를 반환해
     * 호출 의도가 사라진다 — 이 파일의 존재 이유(부분 전달을 조용히 넘기지 않는다)와
     * 같은 원칙이다.
     */
    internal fun splitIntoChunks(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0) { "chunkSize 는 양수여야 한다: $chunkSize" }
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            chunks += bytes.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }
