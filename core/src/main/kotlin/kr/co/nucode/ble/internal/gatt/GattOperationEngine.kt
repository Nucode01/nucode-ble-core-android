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
    var timeoutMillis: Long?
}

/** Read resolves its platform characteristic only when it reaches the FIFO head. */
internal class QueuedRead(
    override val generation: Long,
    val charRef: CharacteristicRef,
) : GattOperation<ByteArray>, StartTrackedGattOperation {
    private var resolvedIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override var timeoutMillis: Long? = null
    override val kind: GattOperationKind = GattOperationKind.Read
    override val attributeIdentity: GattCharacteristicIdentity
        get() = resolvedIdentity

    fun prepare(identity: GattCharacteristicIdentity) {
        resolvedIdentity = identity
    }
}

/** A public write command reserves every chunk before its first chunk starts. */
internal class QueuedWriteChunk(
    override val generation: Long,
    val charRef: CharacteristicRef,
    val requestedWriteType: WriteType,
    val commandIndex: Int,
    chunk: ByteArray,
) : GattOperation<Unit>, StartTrackedGattOperation {
    private val immutableChunk = chunk.copyOf()
    private var resolvedWriteType: Int? = null
    private var resolvedIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override var timeoutMillis: Long? = null
    override val kind: GattOperationKind
        get() = if (resolvedWriteType == GattWriteTypes.NO_RESPONSE) {
            GattOperationKind.WriteWithoutResponse
        } else {
            GattOperationKind.WriteWithResponse
        }
    override val attributeIdentity: GattCharacteristicIdentity
        get() = resolvedIdentity

    /** 플랫폼 write 호출이 받아들여졌는가(시작 전 거절·줄 무효화와 구분). */
    @Volatile var platformStarted: Boolean = false

    lateinit var commandMembers: List<ScheduledGattOperation<Unit>>

    fun prepare(identity: GattCharacteristicIdentity, writeType: Int) {
        resolvedIdentity = identity
        resolvedWriteType = writeType
    }

    fun chunkCopy(): ByteArray = immutableChunk.copyOf()
}

/** Subscribe/unsubscribe direction is command intent, not a fact present in Android callback. */
internal class QueuedCccdWrite(
    override val generation: Long,
    val charRef: CharacteristicRef,
    val enabled: Boolean,
    descriptorIdentity: GattDescriptorIdentity,
) : GattOperation<Unit>, StartTrackedGattOperation {
    private var resolvedIdentity: GattDescriptorIdentity = descriptorIdentity

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override var timeoutMillis: Long? = null
    override val kind: GattOperationKind =
        if (enabled) GattOperationKind.Subscribe else GattOperationKind.Unsubscribe
    override val attributeIdentity: GattDescriptorIdentity
        get() = resolvedIdentity

    fun prepare(identity: GattDescriptorIdentity) {
        resolvedIdentity = identity
    }
}

/**
 * `GattBridge`의 operation 절반(plan D task 2) — read/write/subscribe/unsubscribe와 common
 * scheduler FIFO(생명주기: 시작·ack·poison)를 소유한다.
 *
 * lifecycle 절반과는 [GattLifecycleHooks]로만 통신한다(op → lifecycle) — `connectionCoordinator`의
 * 쓰기(beginCleanup/completeCleanup/beginPlatformRelink)와 `driver.connect/disconnect/close`는 여기서
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

    override fun beginGenerationIfPoisoned(session: Session) {
        if (session.gattOperationScheduler.isPoisoned) session.gattOperationScheduler.beginNextGeneration()
    }

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
        val scheduler = session.gattOperationScheduler
        val scheduled = scheduler.enqueue(
            QueuedRead(
                generation = scheduler.currentGeneration,
                charRef = charRef,
            ),
        )
        executor.startNextOperation(session)
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
        val scheduler = session.gattOperationScheduler
        val scheduled = chunks.mapIndexed { index, chunk ->
            scheduler.enqueue(
                QueuedWriteChunk(
                    generation = scheduler.currentGeneration,
                    charRef = charRef,
                    requestedWriteType = writeType,
                    commandIndex = index,
                    chunk = chunk,
                ).also { it.timeoutMillis = timeoutMillis },
            )
        }
        scheduled.forEach { item ->
            (item.operation as QueuedWriteChunk).commandMembers = scheduled
        }
        executor.startNextOperation(session)

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
        (item.operation as? QueuedWriteChunk)?.platformStarted == true

}
