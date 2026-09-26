package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CompletableDeferred
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.diagnostics.PayloadDirection
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.GattOperationKind
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [GattOperationEngine] 의 콜백 칸 — read·write·descriptor·notify 플랫폼 콜백의 귀속 판정과 작업 완료를 소유한다.
 * 상태는 갖지 않는다(작업 큐는 세션의 scheduler 가 소유).
 */
internal class OperationCallbacks(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val executor: OperationExecutor,
) {

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
        executor.emitPayloadObservation(session, PayloadDirection.Rx, characteristic.toCharacteristicRef(), event.value)
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
        if (event.status == GattStatus.SUCCESS) {
            executor.emitPayloadObservation(session, PayloadDirection.Rx, characteristic.toCharacteristicRef(), event.value, scheduled)
            completion.complete(event.value.copyOf())
        } else {
            completion.completeExceptionally(BleException(GattFailureFactory.operationStatusFailure(session, scheduled, event.status)))
        }
        executor.startNextOperation(session)
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
        if (event.status != GattStatus.SUCCESS) {
            executor.abortRemainingWriteChunks(session, scheduled, GattFailureFactory.operationStatusFailure(session, scheduled, event.status))
        }
        executor.startNextOperation(session)
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
        executor.startNextOperation(session)
    }

    private fun completeUnitOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        status: Int,
    ) {
        val completion = scheduled.completion as CompletableDeferred<Unit>
        if (status == GattStatus.SUCCESS) {
            completion.complete(Unit)
        } else {
            completion.completeExceptionally(BleException(GattFailureFactory.operationStatusFailure(session, scheduled, status)))
        }
    }

    private fun emitCallbackMismatch(session: Session, status: Int, callbackKind: String) {
        executor.emitDiagnostic(Severity.Warning, GattFailureFactory.callbackMismatch(session, status, callbackKind))
    }

    private fun emitDuplicateCallback(session: Session, status: Int, callbackKind: String) {
        executor.emitDiagnostic(Severity.Warning, GattFailureFactory.duplicateCallback(session, status, callbackKind))
    }
}
