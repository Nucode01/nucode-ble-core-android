package kr.co.nucode.ble.internal.gatt

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.DescriptorRef
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.model.GattOperationKind
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/** descriptor 를 대상으로 하는 큐 항목. 실행기는 이 표시로 descriptor 시작([startDescriptorOperation])에 넘긴다. */
internal interface QueuedDescriptorOperation {
    val descriptor: DescriptorRef
}

/** descriptor read 명령 데이터. 플랫폼 descriptor 해석은 FIFO head 에 오를 때 실행기가 한다. */
internal class QueuedDescriptorRead(
    override val generation: Long,
    override val descriptor: DescriptorRef,
) : GattOperation<ByteArray>, StartTrackedGattOperation, QueuedDescriptorOperation {
    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val timeoutMillis: Long? = null
    override val kind: GattOperationKind = GattOperationKind.ReadDescriptor
    override val attributeIdentity: GattDescriptorIdentity = descriptor.identity()
}

/** descriptor write 명령 데이터. 항상 응답 있는 쓰기 한 번이고 나누지 않는다. */
internal class QueuedDescriptorWrite(
    override val generation: Long,
    override val descriptor: DescriptorRef,
    value: ByteArray,
) : GattOperation<Unit>, StartTrackedGattOperation, QueuedDescriptorOperation {
    private val immutableValue = value.copyOf()
    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val timeoutMillis: Long? = null
    override val kind: GattOperationKind = GattOperationKind.WriteDescriptor
    override val attributeIdentity: GattDescriptorIdentity = descriptor.identity()

    /** CCCD(0x2902) 쓰기는 로컬 알림 수신과 구독 상태를 함께 맞춘다(B.4). */
    val isCccd: Boolean get() = descriptor.uuid.value == OperationExecutor.CLIENT_CHARACTERISTIC_CONFIG_UUID

    fun valueCopy(): ByteArray = immutableValue.copyOf()
}

internal fun DescriptorRef.identity(): GattDescriptorIdentity =
    GattDescriptorIdentity(GattCharacteristicIdentity.from(characteristic), uuid.value, instanceIndex)

/** 콜백 identity 를 공개 [DescriptorRef] 로 되돌린다. */
internal fun GattDescriptorIdentity.toDescriptorRef(): DescriptorRef =
    DescriptorRef(characteristic.toCharacteristicRef(), BleUuid(descriptorUuid), descriptorInstanceIndex)

/** CCCD 값이 0 이 아닌가(구독을 켜는 값인가). */
internal fun ByteArray.enablesNotifications(): Boolean = any { it != 0.toByte() }

/**
 * [GattOperationEngine] 의 descriptor 칸 — readDescriptor·writeDescriptor 의 접수·대기와, 앱 CCCD write 가 성공했을 때의
 * 구독 상태 반영을 소유한다. 상태는 갖지 않는다(구독 진실은 세션의 SubscriptionState).
 */
internal class DescriptorCommands(
    private val registry: SessionRegistry,
    private val ackTimeoutMillis: Long,
    private val executor: OperationExecutor,
    private val diagnosticIngress: DiagnosticIngress,
) {

    suspend fun read(deviceId: PeripheralId, descriptor: DescriptorRef): ByteArray {
        val session = admittedSession(deviceId)
        val scheduled = executor.submit(
            session,
            QueuedDescriptorRead(session.gattOperationScheduler.currentGeneration, descriptor),
        )
        return executor.awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    /**
     * CCCD 쓰기면 구독 상태를 큐 완료 쪽에서 고친다 — 호출자가 취소돼도 성공한 쓰기는 상태에 남는다(subscribe 와 같다).
     * 같은 큐에서 나중에 끝난 subscribe·unsubscribe·CCCD write 가 상태를 정한다.
     */
    suspend fun write(deviceId: PeripheralId, descriptor: DescriptorRef, value: ByteArray) {
        val session = admittedSession(deviceId)
        val operation = QueuedDescriptorWrite(session.gattOperationScheduler.currentGeneration, descriptor, value)
        val scheduled = executor.submit(session, operation) { queued ->
            if (operation.isCccd) applyCccdOnSuccess(session, operation, queued)
        }
        executor.awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    private fun admittedSession(deviceId: PeripheralId): Session {
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        if (!session.canStartPublicGattOperation()) throw BleException(GattFailureFactory.notConnected(session))
        return session
    }

    /**
     * 기기가 성공으로 응답한 뒤에만 고친다. 0 이 아니면 의도(마지막 값)·활성에 넣는다 — 접수 뒤 요청 순간 해제(unsubscribe)가
     * 있었거나 시작 generation 이 더 이상 Ready 가 아니면 넣지 않는다. 0 이면 뺀다.
     */
    private fun applyCccdOnSuccess(session: Session, operation: QueuedDescriptorWrite, queued: ScheduledGattOperation<Unit>) {
        val charRef = operation.descriptor.characteristic
        val value = operation.valueCopy()
        val startGeneration = session.connectionCoordinator.generation
        val removalMark = session.subscriptionState.removalMark(charRef)
        queued.completion.invokeOnCompletion { cause ->
            if (cause != null) return@invokeOnCompletion
            val enables = value.enablesNotifications()
            val changed = if (enables) {
                session.connectionCoordinator.readyFor(startGeneration) &&
                    session.subscriptionState.confirmCccdWritten(
                        charRef, value, session.connectionCoordinator.generation, removalMark,
                    )
            } else {
                session.subscriptionState.clearByCccdWrite(charRef)
            }
            if (changed) observeSubscriptionChange(session, queued, operation.descriptor, enables)
        }
    }

    /**
     * 앱 CCCD write 가 활성 구독을 바꿨다는 logger 전용 기록 — 테스터 로그에서 subscribe 와 구분한다. 문맥의 작업 종류는 바뀐 방향
     * (Subscribe·Unsubscribe)이다. 같은 write 의 payload 기록(WriteDescriptor)과 실패 값이 같으면 진단 속도 제한이 값 키를 보지 않아
     * 이 기록을 버린다.
     */
    private fun observeSubscriptionChange(
        session: Session,
        queued: ScheduledGattOperation<Unit>,
        descriptor: DescriptorRef,
        enabled: Boolean,
    ) {
        val payload = GattFailureFactory.payloadObserved(session, queued, descriptor)
        val kind = if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe
        val record = payload.copy(context = payload.context.copy(operationKind = kind))
        diagnosticIngress.observe(Severity.Info, record) {
            mapOf(SUBSCRIPTION_CHANGE_KEY to DiagnosticValue.Text(if (enabled) "enabled" else "disabled"))
        }
    }

    internal companion object {
        /** 구독 변경 진단 값의 키. */
        const val SUBSCRIPTION_CHANGE_KEY = "subscription_change"
    }
}

/** (uuid, 순번)으로 탐색 순서의 descriptor 목록에서 하나를 고른다. 없으면 null. */
internal fun GattOperationTarget.descriptorAt(uuid: UUID, instanceIndex: Int) =
    descriptors.filter { it.uuid == uuid }.getOrNull(instanceIndex)?.descriptor
