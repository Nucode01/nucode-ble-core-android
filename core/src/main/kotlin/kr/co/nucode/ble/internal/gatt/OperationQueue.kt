package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.DeliverySemantics
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.WriteProgress
import kr.co.nucode.ble.internal.model.WriteResult
import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * write 직렬화 + 청킹(설계 §6 구성요소, §5 WriteResult, plan §잠재에러 chunkSize).
 *
 * 청크 묶음을 실제로 내보내고 ack 를 기다리는 일은 [writeCommand] 람다가 한다 — BleRuntime
 * 조립부가 GattBridge 의 suspend 함수(`GattBridge.writeCommand`)를 그대로 넘겨준다
 * (plan §접근 ②). 이 파일은 GattBridge 타입을 import 하지 않는다 — 워커 간 직접 참조
 * 금지 원칙(설계 §1 원인②) 때문에, 두 워커를 잇는 건 오직 BleRuntime 조립 라인뿐이다.
 * 이 클래스가 [writeCommand] 에 대해 아는 전부는 "청크 목록을 주면 청크마다
 * [ackTimeoutMillis] 안에 GATT status (Int) 가 와야 하고, 실패 청크는
 * [GattWriteCommandFailure] 로 알린다"는 계약이다.
 *
 * [ackTimeoutMillis] 는 write 청크 하나의 ack(onCharacteristicWrite) 대기 상한이다(plan
 * blecore-app-tunables 결정③ — 구 상수 `WRITE_ACK_TIMEOUT_MS` 대체). `BleRuntime` 조립부가
 * `BleConfig.operationTimeoutMillis` 를 그대로 넘긴다 — CCCD ack 쪽(GattBridge)과 값은
 * 같아도 워커 간 참조 금지 원칙상 상수를 공유하지 않고 각자 주입받는다.
 */
internal class OperationQueue(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val ackTimeoutMillis: Long,
    private val writeCommand: suspend (
        Session,
        CharacteristicRef,
        List<ByteArray>,
        WriteType,
        Long,
    ) -> List<Int>,
) {

    /**
     * Public write의 모든 청크를 scheduler에 중단 없이 한 batch로 예약한다. 같은 기기의
     * 동시 호출과 read/CCCD는 scheduler FIFO에서 command batch 뒤에 직렬화된다.
     *
     * 연결 여부는 [Session.canStartPublicGattOperation]으로 판정한다. Slice 2 lifecycle
     * generation은 [ConnectionStage.Ready]에서만 write를 시작한다.
     * `NOT_CONNECTED` typed failure를 낸다. chunkSize 는 Session 이 들고 있는 협상된 값을 그대로 쓰되,
     * 아직 협상 전(null)이면 [DEFAULT_CHUNK_SIZE](BLE 기본 ATT_MTU 23 기준)로 대체한다 —
     * MTU 협상은 연결 이후에 일어나는 별개의 단계라, 협상 전이라고 해서 연결 자체가
     * 안 된 것처럼 NotConnected 를 던지면 의미가 틀린다. chunkSize 를 코어 밖에서
     * 다시 계산하지는 않는다(설계 §2, Session 이 사실의 소유자).
     */
    suspend fun write(
        deviceId: PeripheralId,
        charRef: CharacteristicRef,
        bytes: ByteArray,
        writeType: WriteType,
        chunking: BleChunking,
    ): WriteResult {
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())

        return run {
            if (!session.canStartPublicGattOperation()) {
                throw BleException(GattFailureFactory.notConnected(session))
            }
            val chunkSize = session.chunkSize ?: DEFAULT_CHUNK_SIZE
            val chunks = when (chunking) {
                BleChunking.Disabled -> {
                    if (bytes.size > chunkSize) {
                        return@run WriteResult.TooLarge(size = bytes.size, max = chunkSize)
                    }
                    listOf(bytes)
                }
                BleChunking.Automatic -> splitIntoChunks(bytes, chunkSize)
                is BleChunking.Fixed -> {
                    if (chunking.maxBytes > chunkSize) {
                        return@run WriteResult.TooLarge(size = chunking.maxBytes, max = chunkSize)
                    }
                    splitIntoChunks(bytes, chunking.maxBytes)
                }
            }
            val total = chunks.size

            // public write의 모든 청크를 한 번에 scheduler에 예약한다. 청크마다
            // enqueue/await를 반복하면 첫 청크 callback 직후 이미 대기 중인 CCCD가
            // 두 번째 청크보다 먼저 시작해 한 command가 중간에서 갈라진다.
            val statuses = try {
                writeCommand(session, charRef, chunks, writeType, ackTimeoutMillis)
            } catch (e: GattWriteCommandFailure) {
                return@run failedWrite(e.failure, writeType, chunks, e.chunkIndex)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return@run failedWrite(
                    GattFailureFactory.writeInvalidated(session, writeKind(writeType)),
                    writeType, chunks, 0,
                )
            }

            for ((index, status) in statuses.withIndex()) {
                if (status != GattStatus.SUCCESS) {
                    return@run failedWrite(
                        GattFailureFactory.writeStatusFailed(session, status, writeKind(writeType)),
                        writeType, chunks, index,
                    )
                }
            }
            check(statuses.size == total) {
                "write command 결과 수(${statuses.size})가 청크 수($total)와 다르다"
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

/**
 * write 청크 하나의 [WriteType]으로부터 [OperationKind]를 고른다 — `characteristicNotFound`/
 * `operationTimeout`/`operationInvalidated`/`gattStatusFailure`가 각자 만들던
 * `writeContext(...)`를 대체한다(구 helper는 [GattFailureFactory]로 흡수됐다).
 */
private fun writeKind(writeType: WriteType): OperationKind =
    if (writeType == WriteType.WithoutResponse) {
        OperationKind.WriteWithoutResponse
    } else {
        OperationKind.WriteWithResponse
    }

private fun failedWrite(
    base: BleFailure,
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
                operationKind = writeKind(writeType),
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
