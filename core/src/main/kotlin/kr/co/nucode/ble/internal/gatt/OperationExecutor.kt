package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.port.TargetResolution
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.session.SessionOwner
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.diagnostics.DiagnosticPolicy
import kr.co.nucode.ble.internal.diagnostics.PayloadDirection
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor
import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session

/**
 * [GattOperationEngine] 의 실행 칸 — 작업 준비(target·write 타입 해석)·플랫폼 시작·ack 대기·poison·무효화를 소유한다.
 * 상태는 갖지 않는다(작업 큐는 세션의 scheduler 가 소유).
 */
internal class OperationExecutor(
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val clock: ClockPort,
    private val driver: GattDriver,
    private val ackTimeoutMillis: Long,
    private val operationStarter: ((Session, ScheduledGattOperation<*>) -> BleFailure?)?,
    private val operationWriteTypeResolver: ((Session, CharacteristicRef, WriteType) -> Int)?,
    private val operationTargetResolver: GattOperationTargetResolver?,
    private val diagnosticIngress: DiagnosticIngress,
    private val lifecycle: GattLifecycleHooks,
) {

    fun abortRemainingWriteChunks(
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

    // ── 변환 유틸 ────────────────────────────────────────────────────────────

    /** [WriteType.Auto] 를 characteristic 의 실제 properties 로 해석해 플랫폼 write 타입 상수로 바꾼다. */
    fun writeTypeInt(propertyMask: Int, writeType: WriteType): Int =
        when (writeType) {
            WriteType.WithResponse -> GattWriteTypes.DEFAULT
            WriteType.WithoutResponse -> GattWriteTypes.NO_RESPONSE
            WriteType.Auto ->
                if (propertyMask and GattProperties.WRITE != 0) {
                    GattWriteTypes.DEFAULT
                } else {
                    GattWriteTypes.NO_RESPONSE
                }
        }


    /**
     * 호출자는 작업의 완료(정상·실패)만 기다린다. 작업 기한은 큐가 [armDeadline] 로 갖고 있으므로 호출자 취소는
     * 작업에 손대지 않는다 — 대기 작업은 차례가 오면 시작되고, 시작된 작업은 콜백·기한으로 끝난다.
     */
    suspend fun <T> awaitAfterStart(
        session: Session,
        scheduled: ScheduledGattOperation<T>,
        timeoutMillis: Long,
    ): T {
        if (scheduled.operation !is StartTrackedGattOperation) {
            return clock.awaitWithin(timeoutMillis) { awaitScheduled(session, scheduled) }
        }
        return scheduled.completion.await()
    }

    /**
     * 플랫폼 시작이 성공한 순간부터 [StartTrackedGattOperation.timeoutMillis](없으면 [ackTimeoutMillis])를 센다.
     * 기한은 코어 스코프가 갖는다 — 호출자가 떠나도 콜백이 안 오는 작업은 여기서 `OPERATION_TIMEOUT` 으로 poison 된다.
     */
    private fun armDeadline(session: Session, scheduled: ScheduledGattOperation<*>) {
        val tracked = scheduled.operation as? StartTrackedGattOperation ?: return
        val timeoutMillis = tracked.timeoutMillis ?: ackTimeoutMillis
        val timer = scope.launch(SessionOwner(session.deviceId)) {
            clock.sleep(timeoutMillis)
            poisonStartedOperation(session, scheduled)
        }
        scheduled.completion.invokeOnCompletion { timer.cancel() }
    }

    /** 시작된 작업이 기한 안에 끝나지 않았다 — 콜백 귀속을 잃었으니 같은 generation 을 재사용하지 않고 모두 종결한다. */
    fun poisonStartedOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
    ) {
        val scheduler = session.gattOperationScheduler
        if (scheduler.isPoisoned || scheduler.pending !== scheduled) return

        val timeoutFailure = GattFailureFactory.timeoutFailure(session, scheduled)
        val drained = scheduler.poisonCurrentGeneration()
        drained.pending?.completion?.completeExceptionally(BleException(timeoutFailure))
        drained.queued.forEach { queued ->
            queued.completion.completeExceptionally(BleException(GattFailureFactory.operationInvalidated(session, queued)))
        }

        // poison 꼬리(plan §접근·§잠재 에러) — completeCleanup이 driver.disconnect/close보다
        // 먼저다(callback 동기 재진입 대비). 순서는 Core 의 작업 무효화 정책이 보존한다.
        // handle 을 항상 닫으므로 복원 CCCD 도 재연결 대상이 아니다 — 모든 operation 이 suppressReconnect 다.
        lifecycle.onOperationPoisoned(session, timeoutFailure)
    }

    /** Lifecycle 종료가 pending/queued waiter를 callback 없이 유한 종결하는 단일 경로. */
    fun invalidateScheduledOperations(session: Session, failure: BleFailure) {
        val scheduler = session.gattOperationScheduler
        val drained = if (scheduler.isPoisoned) scheduler.drain() else scheduler.poisonCurrentGeneration()
        drained.pending?.let { pending ->
            pending.completion.completeExceptionally(BleException(GattFailureFactory.failureFor(session, pending, failure)))
        }
        drained.queued.forEach { queued ->
            queued.completion.completeExceptionally(BleException(GattFailureFactory.failureFor(session, queued, failure)))
        }
    }

    suspend fun <T> awaitScheduled(
        @Suppress("UNUSED_PARAMETER") session: Session,
        scheduled: ScheduledGattOperation<T>,
    ): T = scheduled.completion.await()

    /** pending이 없을 때 FIFO head 하나를 플랫폼에 시작한다. 시작 실패는 즉시 종결한다. */
    fun startNextOperation(session: Session) {
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
            if (attributedFailure == null) {
                (scheduled.operation as? QueuedWriteChunk)?.platformStarted = true
                armDeadline(session, scheduled)
                return
            }

            val operation = scheduled.operation
            scheduler.takePendingIfMatches(
                generation = operation.generation,
                kind = operation.kind,
                attributeIdentity = operation.attributeIdentity,
            )
            scheduled.completion.completeExceptionally(BleException(attributedFailure))
            abortRemainingWriteChunks(session, scheduled, attributedFailure)
            if (attributedFailure.code.value == "PERMISSION_DENIED") {
                // 권한 상실은 세션 자원 실패다 — State(정리 시작) 와 함께 Event 로도 알린다.
                emitDiagnostic(Severity.Error, attributedFailure)
                lifecycle.onOperationPermissionLost(session, attributedFailure)
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
            WriteType.WithoutResponse -> GattWriteTypes.NO_RESPONSE
            WriteType.WithResponse,
            WriteType.Auto,
            -> GattWriteTypes.DEFAULT
        }

    private fun startPlatformOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
    ): BleFailure? {
        val operation = scheduled.operation
        if (operation is QueuedRead) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, OperationKind.Read)
            if (target.propertyMask and GattProperties.READ == 0) {
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
        val resolved = try {
            resolvePlatformTarget(session, characteristic, charRef)
        } catch (e: BleException) {
            return e.failure
        }
        val currentIdentity = GattCharacteristicIdentity.from(charRef, charRef.service.instanceIndex)
        if (currentIdentity != identity) return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(operation.kind))

        return when (operation) {
            is GattOperation.WriteWithResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = GattWriteTypes.DEFAULT,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else GattFailureFactory.operationStartFailure(session, operation)
            is GattOperation.WriteWithoutResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = GattWriteTypes.NO_RESPONSE,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else GattFailureFactory.operationStartFailure(session, operation)
            is GattOperation.Subscribe -> startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = characteristic,
                charRef = charRef,
                enabled = true,
                descriptor = resolved.cccdDescriptor,
                propertyMask = resolved.propertyMask,
            )
            is GattOperation.Unsubscribe -> startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = characteristic,
                charRef = charRef,
                enabled = false,
                descriptor = resolved.cccdDescriptor,
                propertyMask = resolved.propertyMask,
            )
            else -> GattFailureFactory.operationStartFailure(session, operation)
        }
    }

    private fun startCccdOperation(
        session: Session,
        gatt: GattHandle?,
        characteristic: PlatformCharacteristic,
        charRef: CharacteristicRef,
        enabled: Boolean,
        descriptor: PlatformDescriptor?,
        propertyMask: Int,
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
            propertyMask and GattProperties.NOTIFY != 0 -> byteArrayOf(0x01, 0x00)
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

    fun resolveOperationTarget(session: Session, charRef: CharacteristicRef): GattOperationTarget? {
        operationTargetResolver?.let { resolver ->
            return resolver.resolve(session, charRef)?.takeIf { it.identity.toCharacteristicRef() == charRef }
        }
        val characteristic = session.resolve(charRef) ?: return null
        val resolved = resolvePlatformTarget(session, characteristic, charRef)
        return GattOperationTarget(
            characteristic = characteristic,
            identity = GattCharacteristicIdentity.from(charRef, charRef.service.instanceIndex),
            propertyMask = resolved.propertyMask,
            cccdDescriptor = resolved.cccdDescriptor,
        )
    }

    fun exactIdentity(
        session: Session,
        characteristic: PlatformCharacteristic,
        charRef: CharacteristicRef,
    ): GattCharacteristicIdentity {
        resolvePlatformTarget(session, characteristic, charRef)
        return GattCharacteristicIdentity.from(charRef, charRef.service.instanceIndex)
    }

    /** 현재 handle 의 서비스 그래프에서 [characteristic] 이 [charRef] 자리의 바로 그 객체인지 driver 에 묻는다. */
    private fun resolvePlatformTarget(
        session: Session,
        characteristic: PlatformCharacteristic,
        charRef: CharacteristicRef,
    ): TargetResolution.Resolved {
        val gatt = session.gatt ?: throw BleException(GattFailureFactory.notConnected(session))
        return when (val resolution = driver.resolveTarget(session.deviceId, gatt, charRef, characteristic)) {
            is TargetResolution.Resolved -> resolution
            TargetResolution.NotConnected -> throw BleException(GattFailureFactory.notConnected(session))
            TargetResolution.NotFound -> throw BleException(GattFailureFactory.characteristicNotFound(session))
        }
    }

    fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }

    /** Logger-only (not projected to events); payload is copied only if the logger admits a record, and then only by policy. */
    fun emitPayloadObservation(
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


    companion object {
        /** CCCD(00002902-...) 표준 UUID. BLE 스펙 고정값 — 기기마다 다르지 않다. */
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** 콜백 identity 를 공개 [CharacteristicRef] 로 되돌린다. */
internal fun GattCharacteristicIdentity.toCharacteristicRef(): CharacteristicRef = CharacteristicRef(
    service = ServiceRef(BleUuid(serviceUuid), serviceInstanceIndex),
    uuid = BleUuid(characteristicUuid),
    instanceIndex = characteristicInstanceIndex,
)
