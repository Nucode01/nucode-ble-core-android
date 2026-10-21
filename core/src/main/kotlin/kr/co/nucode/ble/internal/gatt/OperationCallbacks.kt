package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.port.toIdentity
import kr.co.nucode.ble.internal.port.GattDriverEvent
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.diagnostics.PayloadDirection
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent
import kr.co.nucode.ble.internal.core.gatt.GattResponseCategory
import kr.co.nucode.ble.internal.model.GattAttributeIdentity
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

    /** Read callback은 현재 generation의 동일 characteristic read만 완료한다(짝 판정은 큐 Core). */
    internal fun handleCharacteristicRead(event: GattDriverEvent.CharacteristicRead) {
        val session = attributedSession(event, ackKind = "CharacteristicRead", ackStatus = event.status) ?: return
        val characteristic = event.characteristic
        executor.dispatch(
            session,
            responded(session, GattResponseCategory.Read, characteristic?.toIdentity(), event.status),
            response = characteristic?.let { ReadResponse(it.toCharacteristicRef(), event.value) },
        )
    }

    /** 동일 generation/kind/characteristic write callback만 완료한다(callback 에는 write 방식 사실이 없다). */
    internal fun handleCharacteristicWrite(event: GattDriverEvent.CharacteristicWrite) {
        val session = attributedSession(event, ackKind = "CharacteristicWrite", ackStatus = event.status) ?: return
        executor.dispatch(
            session,
            responded(session, GattResponseCategory.CharacteristicWrite, event.attributeIdentity, event.status),
        )
    }

    /** 동일 generation/kind/descriptor callback만 CCCD operation을 완료한다(callback 에는 구독 방향 사실이 없다). */
    internal fun handleDescriptorWrite(event: GattDriverEvent.DescriptorWrite) {
        val session = attributedSession(event, ackKind = "DescriptorWrite", ackStatus = event.status) ?: return
        executor.dispatch(
            session,
            responded(session, GattResponseCategory.DescriptorWrite, event.attributeIdentity, event.status),
        )
    }

    /** 동일 generation/descriptor read callback만 descriptor read 를 완료한다. 값은 성공 완료 때 호출자에게 간다. */
    internal fun handleDescriptorRead(event: GattDriverEvent.DescriptorRead) {
        val session = attributedSession(event, ackKind = "DescriptorRead", ackStatus = event.status) ?: return
        val identity = event.attributeIdentity
        executor.dispatch(
            session,
            responded(session, GattResponseCategory.DescriptorRead, identity, event.status),
            response = identity?.toDescriptorRef()?.let { ReadResponse(it.characteristic, event.value, it) },
        )
    }

    private fun responded(
        session: Session,
        category: GattResponseCategory,
        identity: GattAttributeIdentity?,
        status: Int,
    ): GattQueueEvent = GattQueueEvent.Responded(
        category = category,
        generation = session.gattOperationScheduler.currentGeneration,
        identity = identity,
        status = status,
        context = session.lifecycleFailureContext(),
    )

    private fun emitDuplicateCallback(session: Session, status: Int, callbackKind: String) {
        executor.emitDiagnostic(Severity.Warning, GattFailureFactory.duplicateCallback(session, status, callbackKind))
    }
}
