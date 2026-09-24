package kr.co.nucode.ble.internal.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.WriteType
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.diagnostics.DiagnosticPolicy
import kr.co.nucode.ble.internal.diagnostics.PayloadDirection
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

/** Task 3 operation whose ACK clock starts only after the platform start call returns. */
private interface StartTrackedGattOperation {
    val started: CompletableDeferred<Unit>
}

/** Read resolves its platform characteristic only when it reaches the FIFO head. */
private class QueuedRead(
    override val generation: Long,
    val charRef: CharacteristicRef,
) : GattOperation<ByteArray>, StartTrackedGattOperation {
    private var resolvedIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val kind: GattOperationKind = GattOperationKind.Read
    override val attributeIdentity: GattCharacteristicIdentity
        get() = resolvedIdentity

    fun prepare(identity: GattCharacteristicIdentity) {
        resolvedIdentity = identity
    }
}

/** A public write command reserves every chunk before its first chunk starts. */
private class QueuedWriteChunk(
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
    override val kind: GattOperationKind
        get() = if (resolvedWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            GattOperationKind.WriteWithoutResponse
        } else {
            GattOperationKind.WriteWithResponse
        }
    override val attributeIdentity: GattCharacteristicIdentity
        get() = resolvedIdentity

    lateinit var commandMembers: List<ScheduledGattOperation<Unit>>

    fun prepare(identity: GattCharacteristicIdentity, writeType: Int) {
        resolvedIdentity = identity
        resolvedWriteType = writeType
    }

    fun chunkCopy(): ByteArray = immutableChunk.copyOf()
}

/** Subscribe/unsubscribe direction is command intent, not a fact present in Android callback. */
private class QueuedCccdWrite(
    override val generation: Long,
    val charRef: CharacteristicRef,
    val enabled: Boolean,
    val restore: Boolean = false,
    descriptorIdentity: GattDescriptorIdentity,
) : GattOperation<Unit>, StartTrackedGattOperation {
    private var resolvedIdentity: GattDescriptorIdentity = descriptorIdentity

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
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
    private val driver: GattDriver,
    private val ackTimeoutMillis: Long,
    private val operationStarter: ((Session, ScheduledGattOperation<*>) -> BleFailure?)?,
    private val operationWriteTypeResolver: ((Session, CharacteristicRef, WriteType) -> Int)?,
    private val operationTargetResolver: GattOperationTargetResolver?,
    private val diagnosticIngress: DiagnosticIngress,
    private val lifecycle: GattLifecycleHooks,
) : GattOperationHooks {

    override fun invalidateAll(session: Session, failure: BleFailure) =
        invalidateScheduledOperations(session, failure)

    override fun beginGenerationIfPoisoned(session: Session) {
        if (session.gattOperationScheduler.isPoisoned) session.gattOperationScheduler.beginNextGeneration()
    }

    /**
     * [CallbackAttribution] 판정을 operation 핸들러가 쓸 세션으로 바꾼다. null 이면 콜백을 버린다.
     * 끝난 generation 의 ack([ackKind] != null)는 cleanup drain 뒤 pending 이 없을 때와 같은
     * duplicate 진단을 남긴다. notify([ackKind] == null)는 조용히 버린다.
     */
    private fun attributedSession(event: GattDriverEvent, ackKind: String?, ackStatus: Int): Session? {
        val session = registry.get(event.deviceId) ?: return null
        return when (val attribution = CallbackAttribution.attribute(session, event)) {
            is Attribution.Current -> session
            // Bootstrap 은 STATE_CONNECTED 링크 콜백에만 나온다 — operation 이벤트에선 불가능하다.
            Attribution.Bootstrap -> null
            is Attribution.Drop -> {
                if (attribution.reason != DropReason.StaleHandle && ackKind != null) {
                    emitDuplicateCallback(session, ackStatus, ackKind)
                }
                null
            }
        }
    }

    /** notify/indicate 수신 → CharValue emit. Session 갱신은 없다(구독 목록은 subscribe()가 관리). */
    internal fun handleCharacteristicChanged(event: GattDriverEvent.CharacteristicChanged) {
        val session = attributedSession(event, ackKind = null, ackStatus = 0) ?: return
        val characteristic = event.characteristic ?: return
        emitPayloadObservation(session, PayloadDirection.Rx, characteristic.toCharacteristicRef(), event.value)
        emit(BleEvent.CharValue(event.deviceId, characteristic.toCharacteristicRef(), event.value))
    }

    /** Read callback은 현재 generation의 동일 characteristic read만 완료한다. */
    internal fun handleCharacteristicRead(event: GattDriverEvent.CharacteristicRead) {
        val session = attributedSession(event, ackKind = "CharacteristicRead", ackStatus = event.status) ?: return
        val scheduler = session.gattOperationScheduler
        if (scheduler.pending == null) {
            emitDuplicateCallback(session, event.status, "CharacteristicRead")
            return
        }
        val characteristic = event.characteristic
        if (characteristic == null) {
            emitCallbackMismatch(session, event.status, "CharacteristicRead")
            return
        }
        val scheduled = scheduler.takePendingIfMatches(
            generation = scheduler.currentGeneration,
            kind = GattOperationKind.Read,
            attributeIdentity = characteristic.toIdentity(),
        )
        if (scheduled == null) {
            emitCallbackMismatch(session, event.status, "CharacteristicRead")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val completion = scheduled.completion as CompletableDeferred<ByteArray>
        if (event.status == BluetoothGatt.GATT_SUCCESS) {
            emitPayloadObservation(session, PayloadDirection.Rx, characteristic.toCharacteristicRef(), event.value, scheduled)
            completion.complete(event.value.copyOf())
        } else {
            completion.completeExceptionally(BleException(GattFailureFactory.operationStatusFailure(session, scheduled, event.status)))
        }
        startNextOperation(session)
    }

    /** 동일 generation/kind/characteristic write callback만 완료한다. */
    internal fun handleCharacteristicWrite(event: GattDriverEvent.CharacteristicWrite) {
        val session = attributedSession(event, ackKind = "CharacteristicWrite", ackStatus = event.status) ?: return
        val scheduler = session.gattOperationScheduler
        if (scheduler.pending == null) {
            emitDuplicateCallback(session, event.status, "CharacteristicWrite")
            return
        }
        val identity = event.attributeIdentity
        if (identity == null) {
            emitCallbackMismatch(session, event.status, "CharacteristicWrite")
            return
        }
        // Android callback은 characteristic-write category만 제공한다. 현재 pending의 subtype을
        // 그대로 callback kind로 복사하지 않고, category가 허용하는 두 operation kind를
        // 독립적으로 대조한다. mode는 시작 시 driver에 준 값이지 callback 사실이 아니다.
        val scheduled = scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.WriteWithResponse,
            identity,
        ) ?: scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.WriteWithoutResponse,
            identity,
        )
        if (scheduled == null) {
            emitCallbackMismatch(session, event.status, "CharacteristicWrite")
            return
        }

        completeUnitOperation(session, scheduled, event.status)
        if (event.status != BluetoothGatt.GATT_SUCCESS) {
            abortRemainingWriteChunks(session, scheduled, GattFailureFactory.operationStatusFailure(session, scheduled, event.status))
        }
        startNextOperation(session)
    }

    /** 동일 generation/kind/descriptor callback만 CCCD operation을 완료한다. */
    internal fun handleDescriptorWrite(event: GattDriverEvent.DescriptorWrite) {
        val session = attributedSession(event, ackKind = "DescriptorWrite", ackStatus = event.status) ?: return
        val scheduler = session.gattOperationScheduler
        if (scheduler.pending == null) {
            emitDuplicateCallback(session, event.status, "DescriptorWrite")
            return
        }
        val identity = event.attributeIdentity
        if (identity == null) {
            emitCallbackMismatch(session, event.status, "DescriptorWrite")
            return
        }
        // Android callback은 descriptor-write category만 제공하고 subscribe 방향은 제공하지
        // 않는다. 두 방향을 callback category의 허용 집합으로 대조한다.
        val scheduled = scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.Subscribe,
            identity,
        ) ?: scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.Unsubscribe,
            identity,
        )
        if (scheduled == null) {
            emitCallbackMismatch(session, event.status, "DescriptorWrite")
            return
        }

        completeUnitOperation(session, scheduled, event.status)
        startNextOperation(session)
    }

    private fun abortRemainingWriteChunks(
        session: Session,
        failed: ScheduledGattOperation<*>,
        failure: BleFailure,
    ) {
        val operation = failed.operation as? QueuedWriteChunk ?: return
        operation.commandMembers
            .drop(operation.commandIndex + 1)
            .forEach { queued ->
                if (session.gattOperationScheduler.removeQueued(queued)) {
                    queued.completion.completeExceptionally(BleException(GattFailureFactory.failureFor(session, queued, failure)))
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun completeUnitOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        status: Int,
    ) {
        val completion = scheduled.completion as CompletableDeferred<Unit>
        if (status == BluetoothGatt.GATT_SUCCESS) {
            completion.complete(Unit)
        } else {
            completion.completeExceptionally(BleException(GattFailureFactory.operationStatusFailure(session, scheduled, status)))
        }
    }

    private fun emitCallbackMismatch(session: Session, status: Int, callbackKind: String) {
        emitDiagnostic(Severity.Warning, GattFailureFactory.callbackMismatch(session, status, callbackKind))
    }

    private fun emitDuplicateCallback(session: Session, status: Int, callbackKind: String) {
        emitDiagnostic(Severity.Warning, GattFailureFactory.duplicateCallback(session, status, callbackKind))
    }

    // ── 변환 유틸 ────────────────────────────────────────────────────────────

    /** [WriteType.Auto] 를 characteristic 의 실제 properties 로 해석해 플랫폼 write 타입 상수로 바꾼다. */
    private fun writeTypeInt(propertyMask: Int, writeType: WriteType): Int =
        when (writeType) {
            WriteType.WithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteType.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            WriteType.Auto ->
                if (propertyMask and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
        }

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
        startNextOperation(session)
        return awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    /**
     * notify/indicate 구독. CCCD write 의 `onDescriptorWrite` ack 를 기다린 뒤에만
     * 반환한다(plan §접근 ③, review #18) — ack 전에 반환하면 기기가 아직 통지를
     * 시작하지 않았을 수 있는데도 호출자는 구독이 끝났다고 믿게 된다.
     *
     * write와 같은 [GattOperationScheduler] FIFO에 들어간다. 플랫폼 호출 직전에 resolve해
     * queue 대기 중 서비스 재탐색으로 바뀐 characteristic handle도 따라간다.
     *
     * 반환값은 없다 — 실패는 전부 BleException으로 throw 한다. subscribe
     * (켜는 방향)는 실패가 소비자에게 보여야 한다 — 못 켰는데 조용하면 notify 를 기다리다
     * 하염없다. [unsubscribe](끄는 방향, D8 best-effort)와 비대칭인 이유가 이것이다.
     */
    suspend fun subscribe(deviceId: PeripheralId, charRef: CharacteristicRef) {
        subscribe(deviceId, charRef, restore = false)
    }

    /** Internal lifecycle path. It shares the FIFO but its timeout may recover via reconnect. */
    suspend fun restoreSubscription(deviceId: PeripheralId, charRef: CharacteristicRef) {
        subscribe(deviceId, charRef, restore = true)
    }

    private suspend fun subscribe(deviceId: PeripheralId, charRef: CharacteristicRef, restore: Boolean) {
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        val characteristicIdentity = when {
            operationStarter != null -> GattCharacteristicIdentity.from(charRef)
            operationTargetResolver != null -> resolveOperationTarget(session, charRef)?.identity
                ?: throw BleException(GattFailureFactory.characteristicNotFound(session, OperationKind.Subscribe))
            else -> {
                val characteristic = session.resolve(charRef)
                    ?: throw BleException(GattFailureFactory.characteristicNotFound(session, OperationKind.Subscribe))
                exactIdentity(session, characteristic, charRef)
            }
        }
        val identity = GattDescriptorIdentity(
            characteristic = characteristicIdentity,
            descriptorUuid = CLIENT_CHARACTERISTIC_CONFIG_UUID,
        )
        val scheduled = session.gattOperationScheduler.enqueue(
            QueuedCccdWrite(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
                enabled = true,
                restore = restore,
                descriptorIdentity = identity,
            ),
        )
        startNextOperation(session)
        awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    /**
     * 구독 해제 — D8 best-effort(plan design decisions v1, review L-1). `subscribe` 와
     * 비대칭이다: 소비자 의도는 "이벤트 그만"이고 로컬 라우팅만 꺼도 달성된다 — CCCD
     * write 실패로 throw 하면 소비자가 불필요한 재시도 상태 기계를 만든다(규칙 1 유도).
     * 그래서 이 함수는 **throw 하지 않는다** — 실패는 전부 [Diagnostic][BleEvent.Diagnostic]
     * 으로만 알린다. [Session.subscriptions] 정리는 호출자([BleRuntime.unsubscribe])
     * 가 이 함수 성패와 무관하게 항상 수행한다.
     *
     * subscribe/write와 동일한 scheduler FIFO를 사용한다.
     */
    suspend fun unsubscribe(deviceId: PeripheralId, charRef: CharacteristicRef) {
        val session = registry.get(deviceId) ?: return
        // 링크가 없으면 끄는 방향은 로컬 해제만으로 달성된다(BleRuntime.unsubscribe KDoc).
        if (session.connectionCoordinator.stage == ConnectionStage.Disconnected ||
            session.gattOperationScheduler.isPoisoned
        ) return
        val characteristicIdentity = when {
            operationStarter != null -> GattCharacteristicIdentity.from(charRef)
            operationTargetResolver != null -> resolveOperationTarget(session, charRef)?.identity
                ?: run {
                    emitUnsubscribeFailure(
                        session,
                        charRef,
                        GattFailureFactory.characteristicNotFound(session, OperationKind.Unsubscribe),
                    )
                    return
                }
            else -> {
            val characteristic = session.resolve(charRef)
            if (characteristic == null) {
                emitUnsubscribeFailure(session, charRef, GattFailureFactory.characteristicNotFound(session, OperationKind.Unsubscribe))
                return
            }
            if (session.gatt == null) return
            try {
                exactIdentity(session, characteristic, charRef)
            } catch (e: BleException) {
                emitUnsubscribeFailure(session, charRef, e.failure)
                return
            }
            }
        }
        val identity = GattDescriptorIdentity(
            characteristic = characteristicIdentity,
            descriptorUuid = CLIENT_CHARACTERISTIC_CONFIG_UUID,
        )
        val scheduled = session.gattOperationScheduler.enqueue(
            QueuedCccdWrite(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
                enabled = false,
                descriptorIdentity = identity,
            ),
        )
        startNextOperation(session)

        try {
            awaitAfterStart(session, scheduled, ackTimeoutMillis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlin.coroutines.coroutineContext.ensureActive()
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.operationInvalidated(session, scheduled),
            )
        } catch (e: BleException) {
            emitUnsubscribeFailure(session, charRef, e.failure)
        }
    }

    private fun emitUnsubscribeFailure(session: Session, charRef: CharacteristicRef, failure: BleFailure) {
        emitDiagnostic(
            Severity.Warning,
            failure.copy(
                context = failure.context.copy(operationKind = OperationKind.Unsubscribe),
            ),
        )
    }

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
                ),
            )
        }
        scheduled.forEach { item ->
            (item.operation as QueuedWriteChunk).commandMembers = scheduled
        }
        startNextOperation(session)

        val statuses = mutableListOf<Int>()
        for ((index, item) in scheduled.withIndex()) {
            val status = try {
                awaitAfterStart(session, item, timeoutMillis)
                BluetoothGatt.GATT_SUCCESS
            } catch (e: BleException) {
                // A status integer cannot carry callback domain/API or the admission token.
                throw GattWriteCommandFailure(index, e.failure)
            } catch (e: CancellationException) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val failure = if (e is TimeoutCancellationException) {
                    GattFailureFactory.timeoutFailure(session, item)
                } else {
                    GattFailureFactory.operationInvalidated(session, item)
                }
                throw GattWriteCommandFailure(index, failure)
            } catch (_: Exception) {
                throw GattWriteCommandFailure(index, GattFailureFactory.operationInvalidated(session, item))
            }
            statuses += status
            if (status != BluetoothGatt.GATT_SUCCESS) break
        }
        return statuses
    }

    private suspend fun <T> awaitAfterStart(
        session: Session,
        scheduled: ScheduledGattOperation<T>,
        timeoutMillis: Long,
    ): T {
        val startTracked = scheduled.operation as? StartTrackedGattOperation
            ?: return withTimeout(timeoutMillis) { awaitScheduled(session, scheduled) }
        return try {
            select {
                startTracked.started.onAwait {
                    withTimeout(timeoutMillis) { awaitScheduled(session, scheduled) }
                }
                // Task 4 poison/lifecycle drain can complete or cancel a queued item before it
                // ever starts. Observe that terminal signal as well, otherwise waiting only on
                // `started` would strand the caller forever.
                scheduled.completion.onAwait { it }
            }
        } catch (e: TimeoutCancellationException) {
            if (session.gattOperationScheduler.removeQueued(scheduled)) {
                scheduled.completion.cancel(e)
                abortRemainingWriteChunks(session, scheduled, GattFailureFactory.operationInvalidated(session, scheduled))
            } else if (session.gattOperationScheduler.pending === scheduled) {
                poisonStartedOperation(session, scheduled, e)
            }
            throw BleException(GattFailureFactory.timeoutFailure(session, scheduled))
        } catch (e: CancellationException) {
            if (session.gattOperationScheduler.removeQueued(scheduled)) {
                scheduled.completion.cancel(e)
                abortRemainingWriteChunks(session, scheduled, GattFailureFactory.operationInvalidated(session, scheduled))
            } else if (session.gattOperationScheduler.pending === scheduled) {
                // Platform start 뒤에는 callback operation ID를 다시 받을 방법이 없다.
                // timeout/caller cancellation이 pending의 수신자를 없애면 같은 generation을
                // 재사용하지 않고 모든 waiter와 handle을 한 번에 종결한다.
                poisonStartedOperation(session, scheduled, e)
            }
            throw e
        }
    }

    private fun poisonStartedOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        failure: CancellationException,
    ) {
        val scheduler = session.gattOperationScheduler
        if (scheduler.isPoisoned || scheduler.pending !== scheduled) return

        val drained = scheduler.poisonCurrentGeneration()
        drained.pending?.completion?.completeExceptionally(failure)
        drained.queued.forEach { queued ->
            queued.completion.completeExceptionally(BleException(GattFailureFactory.operationInvalidated(session, queued)))
        }

        val invalidationCause = if (failure is TimeoutCancellationException) {
            GattFailureFactory.timeoutFailure(session, scheduled)
        } else {
            GattFailureFactory.operationInvalidated(session, scheduled)
        }
        val suppressReconnect = (scheduled.operation as? QueuedCccdWrite)?.restore != true
        // poison 꼬리(plan §접근·§잠재 에러) — completeCleanup이 driver.disconnect/close보다
        // 먼저다(callback 동기 재진입 대비). 순서는 lifecycle.terminateForOperationInvalidation이 보존한다.
        lifecycle.terminateForOperationInvalidation(session, invalidationCause, suppressReconnect)
    }

    /** Lifecycle 종료가 pending/queued waiter를 callback 없이 유한 종결하는 단일 경로. */
    private fun invalidateScheduledOperations(session: Session, failure: BleFailure) {
        val scheduler = session.gattOperationScheduler
        val drained = if (scheduler.isPoisoned) scheduler.drain() else scheduler.poisonCurrentGeneration()
        drained.pending?.let { pending ->
            pending.completion.completeExceptionally(BleException(GattFailureFactory.failureFor(session, pending, failure)))
        }
        drained.queued.forEach { queued ->
            queued.completion.completeExceptionally(BleException(GattFailureFactory.failureFor(session, queued, failure)))
        }
    }

    private suspend fun <T> awaitScheduled(
        session: Session,
        scheduled: ScheduledGattOperation<T>,
    ): T = try {
        scheduled.completion.await()
    } catch (e: kotlinx.coroutines.CancellationException) {
        val scheduler = session.gattOperationScheduler
        if (scheduler.removeQueued(scheduled)) {
            scheduled.completion.cancel(e)
        }
        throw e
    }

    /** pending이 없을 때 FIFO head 하나를 플랫폼에 시작한다. 시작 실패는 즉시 종결한다. */
    private fun startNextOperation(session: Session) {
        val scheduler = session.gattOperationScheduler
        while (true) {
            val scheduled = scheduler.startNext() ?: return
            val failure = try {
                if (operationStarter != null) {
                    prepareTestOperation(session, scheduled.operation)
                    operationStarter.invoke(session, scheduled)
                } else {
                    startPlatformOperation(session, scheduled)
                }
            } catch (e: BleException) {
                e.failure
            } catch (_: SecurityException) {
                GattFailureFactory.permissionDenied(
                    session,
                    FailurePhase.Operation,
                    GattFailureFactory.androidOperation(scheduled.operation.kind, callback = false),
                    kind = GattFailureFactory.operationKind(scheduled.operation.kind),
                )
            } catch (_: Exception) {
                GattFailureFactory.operationStartFailure(session, scheduled.operation)
            }
            val attributedFailure = failure?.let { GattFailureFactory.failureFor(session, scheduled, it) }
            (scheduled.operation as? StartTrackedGattOperation)?.started?.complete(Unit)
            if (attributedFailure == null) return

            val operation = scheduled.operation
            scheduler.takePendingIfMatches(
                generation = operation.generation,
                kind = operation.kind,
                attributeIdentity = operation.attributeIdentity,
            )
            scheduled.completion.completeExceptionally(BleException(attributedFailure))
            abortRemainingWriteChunks(session, scheduled, attributedFailure)
            if (attributedFailure.code.value == "PERMISSION_DENIED") {
                // 권한 상실은 세션 자원 실패다 — State(beginCleanup) 와 함께 Event 로도 알린다.
                emitDiagnostic(Severity.Error, attributedFailure)
                lifecycle.beginCleanup(
                    session = session,
                    reason = TerminalCleanupReason.PermissionDenied,
                    cause = attributedFailure,
                    suppressReconnect = true,
                )
                return
            }
        }
    }

    private fun prepareTestOperation(session: Session, operation: GattOperation<*>) {
        when (operation) {
            is QueuedRead -> operation.prepare(GattCharacteristicIdentity.from(operation.charRef))
            is QueuedWriteChunk -> {
                val resolved = testWriteType(session, operation.charRef, operation.requestedWriteType)
                operation.prepare(GattCharacteristicIdentity.from(operation.charRef), resolved)
            }
            is QueuedCccdWrite -> operation.prepare(
                GattDescriptorIdentity(
                    GattCharacteristicIdentity.from(operation.charRef),
                    CLIENT_CHARACTERISTIC_CONFIG_UUID,
                ),
            )
            else -> Unit
        }
    }

    private fun testWriteType(session: Session, charRef: CharacteristicRef, writeType: WriteType): Int =
        operationWriteTypeResolver?.invoke(session, charRef, writeType) ?: when (writeType) {
            WriteType.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            WriteType.WithResponse,
            WriteType.Auto,
            -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

    private fun startPlatformOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
    ): BleFailure? {
        val operation = scheduled.operation
        if (operation is QueuedRead) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, OperationKind.Read)
            if (target.propertyMask and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                return GattFailureFactory.operationStartFailure(session, operation)
            }
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            operation.prepare(target.identity)
            return if (driver.readCharacteristic(
                    deviceId = session.deviceId,
                    gatt = gatt,
                    characteristic = target.characteristic,
                )
            ) null else GattFailureFactory.operationStartFailure(session, operation)
        }
        if (operation is QueuedWriteChunk) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(operation.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            val resolvedType = writeTypeInt(target.propertyMask, operation.requestedWriteType)
            operation.prepare(target.identity, resolvedType)
            return if (driver.writeCharacteristic(
                    deviceId = session.deviceId,
                    gatt = gatt,
                    characteristic = target.characteristic,
                value = operation.chunkCopy(),
                writeType = resolvedType,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, operation.charRef, operation.chunkCopy(), scheduled)
            }
            ) null else GattFailureFactory.operationStartFailure(session, operation)
        }
        if (operation is QueuedCccdWrite) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(operation.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            operation.prepare(GattDescriptorIdentity(target.identity, CLIENT_CHARACTERISTIC_CONFIG_UUID))
            return startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = target.characteristic,
                charRef = operation.charRef,
                enabled = operation.enabled,
                descriptor = target.cccdDescriptor,
                propertyMask = target.propertyMask,
            )
        }
        val identity = operation.attributeIdentity as? GattCharacteristicIdentity
            ?: (operation.attributeIdentity as? GattDescriptorIdentity)?.characteristic
            ?: return GattFailureFactory.operationStartFailure(session, operation)
        val charRef = identity.toCharacteristicRef()
        val characteristic = session.resolve(charRef)
            ?: return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(operation.kind))
        val gatt = session.gatt ?: return GattFailureFactory.notConnected(session)
        val currentIdentity = try {
            exactIdentity(session, characteristic, charRef)
        } catch (e: BleException) {
            return e.failure
        }
        if (currentIdentity != identity) return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(operation.kind))

        return when (operation) {
            is GattOperation.WriteWithResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else GattFailureFactory.operationStartFailure(session, operation)
            is GattOperation.WriteWithoutResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else GattFailureFactory.operationStartFailure(session, operation)
            is GattOperation.Subscribe -> startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = characteristic,
                charRef = charRef,
                enabled = true,
            )
            is GattOperation.Unsubscribe -> startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = characteristic,
                charRef = charRef,
                enabled = false,
            )
            else -> GattFailureFactory.operationStartFailure(session, operation)
        }
    }

    private fun startCccdOperation(
        session: Session,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        charRef: CharacteristicRef,
        enabled: Boolean,
        descriptor: BluetoothGattDescriptor? = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID),
        propertyMask: Int = characteristic.properties,
    ): BleFailure? {
        val routingStarted = driver.setCharacteristicNotification(
            session.deviceId,
            gatt,
            characteristic,
            enabled,
        )
        if (enabled && !routingStarted) {
            return GattFailureFactory.startRejected(
                session,
                FailurePhase.Operation,
                AndroidApiOperation.WriteDescriptor,
                kind = if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe,
            )
        }

        val resolvedDescriptor = descriptor
            ?: return GattFailureFactory.characteristicNotFound(session, if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe)
        val value = when {
            !enabled -> byteArrayOf(0x00, 0x00)
            propertyMask and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> byteArrayOf(0x01, 0x00)
            else -> byteArrayOf(0x02, 0x00)
        }
        return if (driver.writeDescriptor(session.deviceId, gatt, resolvedDescriptor, value)) {
            null
        } else {
            GattFailureFactory.startRejected(
                session,
                FailurePhase.Operation,
                AndroidApiOperation.WriteDescriptor,
                kind = if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe,
            )
        }
    }

    private fun resolveOperationTarget(session: Session, charRef: CharacteristicRef): GattOperationTarget? {
        operationTargetResolver?.let { resolver ->
            return resolver.resolve(session, charRef)?.takeIf { it.identity.toCharacteristicRef() == charRef }
        }
        val characteristic = session.resolve(charRef) ?: return null
        return GattOperationTarget(
            characteristic = characteristic,
            identity = exactIdentity(session, characteristic, charRef),
            propertyMask = characteristic.properties,
            cccdDescriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID),
        )
    }

    private fun GattCharacteristicIdentity.toCharacteristicRef(): CharacteristicRef = CharacteristicRef(
        service = ServiceRef(BleUuid(serviceUuid), serviceInstanceIndex),
        uuid = BleUuid(characteristicUuid),
        instanceIndex = characteristicInstanceIndex,
    )

    private fun exactIdentity(
        session: Session,
        characteristic: BluetoothGattCharacteristic,
        charRef: CharacteristicRef,
    ): GattCharacteristicIdentity {
        val gatt = session.gatt ?: throw BleException(GattFailureFactory.notConnected(session))
        val matchingServices = gatt.services.filter { it.uuid == charRef.service.uuid.value }
        val service = matchingServices.getOrNull(charRef.service.instanceIndex)
            ?: throw BleException(GattFailureFactory.characteristicNotFound(session))
        val matchingCharacteristics = service.characteristics.filter { it.uuid == charRef.uuid.value }
        if (matchingCharacteristics.getOrNull(charRef.instanceIndex) !== characteristic) {
            throw BleException(GattFailureFactory.characteristicNotFound(session))
        }
        return GattCharacteristicIdentity.from(charRef, charRef.service.instanceIndex)
    }

    private fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }

    /** Logger-only (not projected to events); payload is copied only if the logger admits a record, and then only by policy. */
    private fun emitPayloadObservation(
        session: Session,
        direction: PayloadDirection,
        characteristic: CharacteristicRef,
        payload: ByteArray,
        scheduled: ScheduledGattOperation<*>? = null,
    ) {
        val observation = GattFailureFactory.payloadObserved(session, scheduled)
        diagnosticIngress.observe(Severity.Info, observation) { options ->
            DiagnosticPolicy().payloadValues(options, direction, characteristic, payload)
        }
    }

    private companion object {
        /** CCCD(00002902-...) 표준 UUID. BLE 스펙 고정값 — 기기마다 다르지 않다. */
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
