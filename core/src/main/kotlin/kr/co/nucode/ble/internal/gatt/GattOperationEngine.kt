package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.port.GattDriverEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.model.GattOperationKind
import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/** Task 3 operation whose ACK clock starts only after the platform start call returns. */
internal interface StartTrackedGattOperation {
    val started: CompletableDeferred<Unit>

    /** 플랫폼 시작부터 센 작업 기한. null 이면 엔진의 기본 기한. */
    val timeoutMillis: Long?
}

/**
 * Read 명령 데이터. 플랫폼 characteristic 해석은 FIFO head 에 오를 때 실행기가 하고, 그 결과(종류·대상)는 큐 Core 항목에 남는다.
 */
internal class QueuedRead(
    override val generation: Long,
    val charRef: CharacteristicRef,
    override val timeoutMillis: Long? = null,
) : GattOperation<ByteArray>, StartTrackedGattOperation {
    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val kind: GattOperationKind = GattOperationKind.Read
    override val attributeIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)
}

/** A public write command reserves every chunk before its first chunk starts. */
internal class QueuedWriteChunk(
    override val generation: Long,
    val charRef: CharacteristicRef,
    val requestedWriteType: WriteType,
    chunk: ByteArray,
    override val timeoutMillis: Long? = null,
) : GattOperation<Unit>, StartTrackedGattOperation {
    private val immutableChunk = chunk.copyOf()

    override val started: CompletableDeferred<Unit> = CompletableDeferred()

    /** 접수 때의 종류. [WriteType.Auto] 는 플랫폼 시작 때 해석된다(해석된 종류는 큐 Core 항목). */
    override val kind: GattOperationKind =
        if (requestedWriteType == WriteType.WithoutResponse) {
            GattOperationKind.WriteWithoutResponse
        } else {
            GattOperationKind.WriteWithResponse
        }
    override val attributeIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)

    /** 플랫폼 write 호출이 받아들여졌을 때 한 번 완료된다(시작 전 거절·줄 무효화와 구분). */
    val platformAccepted: CompletableDeferred<Unit> = CompletableDeferred()

    fun chunkCopy(): ByteArray = immutableChunk.copyOf()
}

/** Subscribe/unsubscribe direction is command intent, not a fact present in Android callback. */
internal class QueuedCccdWrite(
    override val generation: Long,
    val charRef: CharacteristicRef,
    val enabled: Boolean,
    descriptorIdentity: GattDescriptorIdentity,
    override val timeoutMillis: Long? = null,
) : GattOperation<Unit>, StartTrackedGattOperation {
    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val kind: GattOperationKind =
        if (enabled) GattOperationKind.Subscribe else GattOperationKind.Unsubscribe
    override val attributeIdentity: GattDescriptorIdentity = descriptorIdentity
}

/**
 * `GattBridge`의 operation 절반(plan D task 2) — read/write/subscribe/unsubscribe와 common
 * scheduler FIFO(생명주기: 시작·ack·poison)를 소유한다.
 *
 * lifecycle 절반과는 [GattLifecycleHooks]로만 통신한다(op → lifecycle) — `connectionCoordinator`의
 * 쓰기(dispatch·beginCleanup/completeCleanup)와 `driver.connect/disconnect/close`는 여기서
 * 직접 호출하지 않는다. 읽기(generation/stage/accepts)는 허용한다. 반대 방향은
 * [GattOperationHooks]를 이 클래스가 구현해 제공한다.
 */
internal class GattOperationEngine(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    scope: CoroutineScope,
    private val driver: GattDriver,
    private val ackTimeoutMillis: Long,
    private val operationStarter: ((Session, ScheduledGattOperation<*>) -> BleFailure?)?,
    private val operationWriteTypeResolver: ((Session, CharacteristicRef, WriteType) -> Int)?,
    private val operationTargetResolver: GattOperationTargetResolver?,
    private val diagnosticIngress: DiagnosticIngress,
    private val lifecycle: GattLifecycleHooks,
) : GattOperationHooks {


    private val executor = OperationExecutor(
        emit, scope, registry.clock, driver, ackTimeoutMillis, operationStarter, operationWriteTypeResolver,
        operationTargetResolver, diagnosticIngress, lifecycle,
    )
    private val callbacks = OperationCallbacks(registry, emit, executor)
    private val subscriptions = SubscriptionCommands(registry, ackTimeoutMillis, operationStarter, operationTargetResolver, executor)

    override fun invalidateAll(session: Session, failure: BleFailure) =
        executor.invalidateScheduledOperations(session, failure)

    override fun beginGenerationIfPoisoned(session: Session) =
        executor.dispatch(session, GattQueueEvent.BeginGenerationIfPoisoned)

    internal fun handleCharacteristicChanged(event: GattDriverEvent.CharacteristicChanged) = callbacks.handleCharacteristicChanged(event)
    internal fun handleCharacteristicRead(event: GattDriverEvent.CharacteristicRead) = callbacks.handleCharacteristicRead(event)
    internal fun handleCharacteristicWrite(event: GattDriverEvent.CharacteristicWrite) = callbacks.handleCharacteristicWrite(event)
    internal fun handleDescriptorWrite(event: GattDriverEvent.DescriptorWrite) = callbacks.handleDescriptorWrite(event)

    // ── 진입 함수(실제 플랫폼 호출) ──────────────────────────────────────────

    /**
     * characteristic read를 common scheduler FIFO에 예약하고, platform start 뒤부터
     * [ackTimeoutMillis] 동안 정확히 일치하는 read callback을 기다린다.
     */
    suspend fun read(deviceId: PeripheralId, charRef: CharacteristicRef): ByteArray {
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        if (!session.canStartPublicGattOperation()) throw BleException(GattFailureFactory.notConnected(session))
        val scheduled = executor.submit(
            session,
            QueuedRead(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
            ),
        )
        return executor.awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }


    suspend fun subscribe(deviceId: PeripheralId, charRef: CharacteristicRef) =
        subscriptions.subscribe(deviceId, charRef, recordIntent = true)

    suspend fun restoreSubscription(deviceId: PeripheralId, charRef: CharacteristicRef) = subscriptions.restoreSubscription(deviceId, charRef)

    suspend fun unsubscribe(deviceId: PeripheralId, charRef: CharacteristicRef) = subscriptions.unsubscribe(deviceId, charRef)

    /**
     * 한 public write의 모든 청크를 FIFO에 연속 예약한다. 각 청크는 실제 start 시점에
     * characteristic을 다시 resolve하고 [WriteType.Auto]를 다시 판정한다.
     */
    suspend fun writeCommand(
        session: Session,
        charRef: CharacteristicRef,
        chunks: List<ByteArray>,
        writeType: WriteType,
        timeoutMillis: Long,
    ): List<Int> {
        val generation = session.gattOperationScheduler.currentGeneration
        val scheduled = executor.submitCommand(
            session,
            chunks.map { chunk ->
                QueuedWriteChunk(
                    generation = generation,
                    charRef = charRef,
                    requestedWriteType = writeType,
                    chunk = chunk,
                    timeoutMillis = timeoutMillis,
                )
            },
        )

        val statuses = mutableListOf<Int>()
        for ((index, item) in scheduled.withIndex()) {
            val status = try {
                executor.awaitAfterStart(session, item, timeoutMillis)
                GattStatus.SUCCESS
            } catch (e: BleException) {
                // A status integer cannot carry callback domain/API or the admission token.
                throw GattWriteCommandFailure(index, e.failure, chunkStarted(item))
            } catch (e: CancellationException) {
                // 호출자 취소는 그대로 전파한다 — 남은 조각은 큐가 끝까지 내보낸다. 호출자가 살아 있는데
                // 취소로 끝난 완료는 회수(무효화)다.
                kotlin.coroutines.coroutineContext.ensureActive()
                throw GattWriteCommandFailure(index, GattFailureFactory.operationInvalidated(session, item), chunkStarted(item))
            } catch (_: Exception) {
                throw GattWriteCommandFailure(index, GattFailureFactory.operationInvalidated(session, item), chunkStarted(item))
            }
            statuses += status
            if (status != GattStatus.SUCCESS) break
        }
        return statuses
    }

    private fun chunkStarted(item: ScheduledGattOperation<*>): Boolean =
        (item.operation as? QueuedWriteChunk)?.platformAccepted?.isCompleted == true

}
