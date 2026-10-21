package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.port.TargetResolution
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.session.SessionOwner
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.DescriptorRef
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent
import kr.co.nucode.ble.internal.core.gatt.GattQueueOutcome
import kr.co.nucode.ble.internal.core.gatt.GattQueueSpec
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.diagnostics.DiagnosticPolicy
import kr.co.nucode.ble.internal.diagnostics.PayloadDirection
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.GattAttributeIdentity
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.model.GattOperationKind
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session

/** read 콜백이 실어 온 값. 큐 Core 가 read 를 성공으로 끝낼 때 호출자에게 넘긴다. descriptor read 면 [descriptor] 가 있다. */
internal class ReadResponse(val characteristic: CharacteristicRef, val value: ByteArray, val descriptor: DescriptorRef? = null)

/**
 * [GattOperationEngine] 의 실행 칸 — 큐 Core 의 할 일 실행기다. 사건을 세션 scheduler 셸에 넣고, 셸이 낸 할 일을
 * 목록 순서대로 실행한다: 시작(대상·write 타입 해석, 플랫폼 호출, 결과를 사건으로), 완료, 진단, 권한 상실, poison.
 * ack 대기·작업 기한 타이머·무효화도 여기 있다. 상태는 갖지 않는다(작업 큐는 세션의 scheduler 가 소유).
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

    /** 사건을 세션의 큐 셸에 넣고 낸 할 일을 실행한다. [response] 는 read 콜백의 값(성공 완료 때 호출자에게 간다). */
    fun dispatch(session: Session, event: GattQueueEvent, response: ReadResponse? = null) {
        session.gattOperationScheduler.apply(event) { effect -> execute(session, effect, response) }
    }

    /** 명령 하나를 접수한다. [beforeStart] 는 handle 이 표에 오른 뒤 첫 시작 전에 부른다. */
    fun <T> submit(
        session: Session,
        operation: GattOperation<T>,
        beforeStart: (ScheduledGattOperation<T>) -> Unit = {},
    ): ScheduledGattOperation<T> =
        submitAll(session, listOf(operation), asCommand = false) { beforeStart(it.single()) }.single()

    /** write 한 호출의 조각 전부를 한 사건으로 접수한다(다른 호출이 끼어들지 못한다, §8). */
    fun submitCommand(session: Session, chunks: List<QueuedWriteChunk>): List<ScheduledGattOperation<Unit>> =
        submitAll(session, chunks, asCommand = true)

    private fun <T> submitAll(
        session: Session,
        operations: List<GattOperation<T>>,
        asCommand: Boolean,
        beforeStart: (List<ScheduledGattOperation<T>>) -> Unit = {},
    ): List<ScheduledGattOperation<T>> {
        val specs = operations.map {
            GattQueueSpec(
                generation = it.generation,
                kind = it.kind,
                identity = it.attributeIdentity,
                tracked = it is StartTrackedGattOperation,
                timeoutMillis = (it as? StartTrackedGattOperation)?.let { tracked -> tracked.timeoutMillis ?: ackTimeoutMillis },
            )
        }
        return session.gattOperationScheduler.submit(
            specs, operations, asCommand, beforeStart,
        ) { effect -> execute(session, effect, response = null) }
    }

    private fun execute(session: Session, effect: GattQueueEffect, response: ReadResponse?) {
        when (effect) {
            is GattQueueEffect.Start -> startOperation(session, effect.sequence)
            is GattQueueEffect.Complete -> complete(session, effect.sequence, effect.outcome, response)
            is GattQueueEffect.Diagnostic -> emitDiagnostic(effect.severity, effect.failure)
            is GattQueueEffect.PermissionLost -> lifecycle.onOperationPermissionLost(session, effect.failure)
            is GattQueueEffect.Poison -> lifecycle.onOperationPoisoned(session, effect.failure)
            is GattQueueEffect.ArmDeadline -> armDeadline(session, effect)
            is GattQueueEffect.CancelDeadline -> session.gattOperationScheduler.cancelDeadlineTimer(effect.token)
            is GattQueueEffect.JudgeLater -> judgeLater(session, effect.sequence)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun complete(session: Session, sequence: Long, outcome: GattQueueOutcome, response: ReadResponse?) {
        val scheduled = session.gattOperationScheduler.release(sequence) ?: return
        when (outcome) {
            GattQueueOutcome.Success ->
                if (response != null && scheduled.operation.kind in READ_KINDS) {
                    emitPayloadObservation(
                        session, PayloadDirection.Rx, response.characteristic, response.value, scheduled, response.descriptor,
                    )
                    (scheduled.completion as CompletableDeferred<ByteArray>).complete(response.value.copyOf())
                } else {
                    (scheduled.completion as CompletableDeferred<Unit>).complete(Unit)
                }
            is GattQueueOutcome.Failed -> scheduled.completion.completeExceptionally(BleException(outcome.failure))
        }
    }

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
     * 호출자는 작업의 완료(정상·실패)만 기다린다. 작업 기한은 큐 Core 가 정하고 타이머는 코어 스코프가 갖고 있으므로 호출자 취소는
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

    /** 판정을 우편함 끝으로 미룬다 — 그 사이 먼저 들어온 완료 콜백이 먼저 step 된다. */
    private fun judgeLater(session: Session, sequence: Long) {
        scope.launch(SessionOwner(session.deviceId)) {
            dispatch(session, GattQueueEvent.DeadlineJudged(sequence, session.lifecycleFailureContext()))
        }
    }

    /**
     * 큐 Core 가 낸 기한 — 플랫폼 시작이 성공한 순간부터 센다. 타이머는 코어 스코프가 갖는다: 호출자가 떠나도
     * 콜백이 안 오는 작업은 기한 사건으로 Core 에 들어가 판정된다(우편함 규칙은 [GattQueueCore] 를 본다).
     */
    private fun armDeadline(session: Session, effect: GattQueueEffect.ArmDeadline) {
        val scheduler = session.gattOperationScheduler
        val timer = scope.launch(SessionOwner(session.deviceId)) {
            clock.sleep(effect.millis)
            scheduler.releaseDeadlineTimer(effect.token)
            dispatch(session, GattQueueEvent.DeadlineFired(effect.token))
        }
        scheduler.holdDeadlineTimer(effect.token, timer)
    }

    /** Lifecycle 종료가 pending/queued waiter를 callback 없이 유한 종결하는 단일 경로. */
    fun invalidateScheduledOperations(session: Session, failure: BleFailure) {
        dispatch(session, GattQueueEvent.Invalidate(failure, session.lifecycleFailureContext()))
    }

    suspend fun <T> awaitScheduled(
        @Suppress("UNUSED_PARAMETER") session: Session,
        scheduled: ScheduledGattOperation<T>,
    ): T = scheduled.completion.await()

    /** 큐 Core 가 차례를 준 항목 하나를 플랫폼에 시작하고, 결과(해석된 종류·대상, 거절)를 사건으로 돌려준다. */
    private fun startOperation(session: Session, sequence: Long) {
        val scheduled = session.gattOperationScheduler.handleOf(sequence) ?: return
        val resolution = StartResolution(scheduled.operation.kind, scheduled.operation.attributeIdentity)
        val failure = try {
            if (operationStarter != null) {
                prepareTestOperation(session, scheduled.operation, resolution)
                operationStarter.invoke(session, scheduled.resolvedAs(resolution.kind, resolution.identity))
            } else {
                startPlatformOperation(session, scheduled, resolution)
            }
        } catch (e: BleException) {
            e.failure
        } catch (_: SecurityException) {
            GattFailureFactory.permissionDenied(
                session,
                FailurePhase.Operation,
                GattFailureFactory.androidOperation(resolution.kind, callback = false),
                kind = GattFailureFactory.operationKind(resolution.kind),
            )
        } catch (_: Exception) {
            GattFailureFactory.operationStartFailure(session, scheduled.resolvedAs(resolution.kind, resolution.identity).operation)
        }
        (scheduled.operation as? StartTrackedGattOperation)?.started?.complete(Unit)
        if (failure == null) {
            (scheduled.operation as? QueuedWriteChunk)?.platformAccepted?.complete(Unit)
        }
        dispatch(
            session,
            GattQueueEvent.StartFinished(
                sequence = sequence,
                kind = resolution.kind,
                identity = resolution.identity,
                failure = failure,
                context = session.lifecycleFailureContext(),
            ),
        )
    }

    /** 플랫폼 시작 중 해석된 종류·대상. 시작 하나 동안만 산다. */
    private class StartResolution(var kind: GattOperationKind, var identity: GattAttributeIdentity?) {
        /** 해석된 write 타입 상수로 종류를 정한다(콜백 짝은 이 종류와 무관하다). */
        fun resolveWrite(identity: GattAttributeIdentity, writeType: Int) {
            this.identity = identity
            kind = if (writeType == GattWriteTypes.NO_RESPONSE) {
                GattOperationKind.WriteWithoutResponse
            } else {
                GattOperationKind.WriteWithResponse
            }
        }
    }

    private fun prepareTestOperation(session: Session, operation: GattOperation<*>, resolution: StartResolution) {
        when (operation) {
            is QueuedRead -> resolution.identity = GattCharacteristicIdentity.from(operation.charRef)
            is QueuedWriteChunk -> {
                val resolved = testWriteType(session, operation.charRef, operation.requestedWriteType)
                resolution.resolveWrite(GattCharacteristicIdentity.from(operation.charRef), resolved)
            }
            is QueuedCccdWrite -> resolution.identity = GattDescriptorIdentity(
                GattCharacteristicIdentity.from(operation.charRef),
                CLIENT_CHARACTERISTIC_CONFIG_UUID,
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
        resolution: StartResolution,
    ): BleFailure? {
        val operation = scheduled.operation
        fun startFailure() =
            GattFailureFactory.operationStartFailure(session, scheduled.resolvedAs(resolution.kind, resolution.identity).operation)
        if (operation is QueuedRead) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, OperationKind.Read)
            if (target.propertyMask and GattProperties.READ == 0) {
                return startFailure()
            }
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            resolution.identity = target.identity
            return if (driver.readCharacteristic(
                    deviceId = session.deviceId,
                    gatt = gatt,
                    characteristic = target.characteristic,
                )
            ) null else startFailure()
        }
        if (operation is QueuedWriteChunk) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(resolution.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            val resolvedType = writeTypeInt(target.propertyMask, operation.requestedWriteType)
            resolution.resolveWrite(target.identity, resolvedType)
            return if (driver.writeCharacteristic(
                    deviceId = session.deviceId,
                    gatt = gatt,
                    characteristic = target.characteristic,
                value = operation.chunkCopy(),
                writeType = resolvedType,
            ).also { started ->
                if (started) {
                    emitPayloadObservation(
                        session, PayloadDirection.Tx, operation.charRef, operation.chunkCopy(),
                        scheduled.resolvedAs(resolution.kind, resolution.identity),
                    )
                }
            }
            ) null else startFailure()
        }
        if (operation is QueuedCccdWrite) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(resolution.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            resolution.identity = GattDescriptorIdentity(target.identity, CLIENT_CHARACTERISTIC_CONFIG_UUID)
            return startCccdWrite(
                driver, session, gatt, target.characteristic, target.cccdDescriptor,
                subscriptionCccdValue(operation.enabled, target.propertyMask, operation.value), subscriptionKind(operation.enabled),
            )
        }
        if (operation is QueuedDescriptorOperation) {
            val ref = operation.descriptor
            val target = resolveOperationTarget(session, ref.characteristic)
                ?: return GattFailureFactory.characteristicNotFound(session, GattFailureFactory.operationKind(resolution.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return GattFailureFactory.notConnected(session)
            resolution.identity = GattDescriptorIdentity(target.identity, ref.uuid.value, ref.instanceIndex)
            return startDescriptorOperation(driver, session, gatt, target, operation) { sent ->
                val resolved = scheduled.resolvedAs(resolution.kind, resolution.identity)
                emitPayloadObservation(session, PayloadDirection.Tx, ref.characteristic, sent, resolved, ref)
            }
        }
        val identity = operation.attributeIdentity as? GattCharacteristicIdentity
            ?: (operation.attributeIdentity as? GattDescriptorIdentity)?.characteristic
            ?: return startFailure()
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
            }) null else startFailure()
            is GattOperation.WriteWithoutResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = GattWriteTypes.NO_RESPONSE,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else startFailure()
            is GattOperation.Subscribe -> startCccdWrite(
                driver, session, gatt, characteristic, resolved.cccdDescriptor,
                subscriptionCccdValue(true, resolved.propertyMask, null), OperationKind.Subscribe,
            )
            is GattOperation.Unsubscribe -> startCccdWrite(
                driver, session, gatt, characteristic, resolved.cccdDescriptor,
                subscriptionCccdValue(false, resolved.propertyMask, null), OperationKind.Unsubscribe,
            )
            else -> startFailure()
        }
    }

    private fun subscriptionKind(enabled: Boolean): OperationKind =
        if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe

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
            descriptors = resolved.descriptors,
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
        descriptor: DescriptorRef? = null,
    ) {
        // descriptor payload 는 자기 characteristic 의 safe·sensitive 판정을 따른다([characteristic] 이 그 characteristic 이다).
        val observation = GattFailureFactory.payloadObserved(session, scheduled, descriptor)
        diagnosticIngress.observe(Severity.Info, observation) { options ->
            DiagnosticPolicy().payloadValues(options, direction, characteristic, payload)
        }
    }


    companion object {
        /** CCCD(00002902-...) 표준 UUID. BLE 스펙 고정값 — 기기마다 다르지 않다. */
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 성공 완료 때 값을 호출자에게 넘기는 종류. */
        private val READ_KINDS = setOf(GattOperationKind.Read, GattOperationKind.ReadDescriptor)
    }
}

/** 콜백 identity 를 공개 [CharacteristicRef] 로 되돌린다. */
internal fun GattCharacteristicIdentity.toCharacteristicRef(): CharacteristicRef = CharacteristicRef(
    service = ServiceRef(BleUuid(serviceUuid), serviceInstanceIndex),
    uuid = BleUuid(characteristicUuid),
    instanceIndex = characteristicInstanceIndex,
)
