package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.GattOperationScheduler
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [GattOperationEngine] 의 구독 칸 — subscribe·restore·unsubscribe 의 CCCD 작업 예약과 실패 보고를 소유한다.
 * 상태는 갖지 않는다(구독 목록은 세션의 SubscriptionState 가 소유).
 */
internal class SubscriptionCommands(
    private val registry: SessionRegistry,
    private val ackTimeoutMillis: Long,
    private val operationStarter: ((Session, ScheduledGattOperation<*>) -> BleFailure?)?,
    private val operationTargetResolver: GattOperationTargetResolver?,
    private val executor: OperationExecutor,
) {

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
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        val characteristicIdentity = when {
            operationStarter != null -> GattCharacteristicIdentity.from(charRef)
            operationTargetResolver != null -> executor.resolveOperationTarget(session, charRef)?.identity
                ?: throw BleException(GattFailureFactory.characteristicNotFound(session, OperationKind.Subscribe))
            else -> {
                val characteristic = session.resolve(charRef)
                    ?: throw BleException(GattFailureFactory.characteristicNotFound(session, OperationKind.Subscribe))
                executor.exactIdentity(session, characteristic, charRef)
            }
        }
        val identity = GattDescriptorIdentity(
            characteristic = characteristicIdentity,
            descriptorUuid = OperationExecutor.CLIENT_CHARACTERISTIC_CONFIG_UUID,
        )
        val scheduled = session.gattOperationScheduler.enqueue(
            QueuedCccdWrite(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
                enabled = true,
                descriptorIdentity = identity,
            ),
        )
        executor.startNextOperation(session)
        executor.awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    /**
     * LinkSetup 복원 경로. [subscribe] 와 같은 FIFO·같은 실패 처리다 — timeout 으로 poison 되면
     * [GattConnectionLifecycle.terminateForOperationInvalidation] 이 handle 을 닫고 세션을 끝낸다(재연결로 회복하지 않는다).
     */
    suspend fun restoreSubscription(deviceId: PeripheralId, charRef: CharacteristicRef) = subscribe(deviceId, charRef)

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
        // 서비스 탐색이 끝나기 전(Connecting·DiscoveringServices)엔 resolve 할 목록이 없다 — 켜진 CCCD 도
        // 아직 없으므로 로컬 해제로 충분하고, CHARACTERISTIC_NOT_FOUND 진단을 내면 거짓이다.
        val stage = session.connectionCoordinator.stage
        if (stage == ConnectionStage.Disconnected ||
            stage == ConnectionStage.Connecting ||
            stage == ConnectionStage.DiscoveringServices ||
            session.gattOperationScheduler.isPoisoned
        ) return
        val characteristicIdentity = when {
            operationStarter != null -> GattCharacteristicIdentity.from(charRef)
            operationTargetResolver != null -> executor.resolveOperationTarget(session, charRef)?.identity
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
                executor.exactIdentity(session, characteristic, charRef)
            } catch (e: BleException) {
                emitUnsubscribeFailure(session, charRef, e.failure)
                return
            }
            }
        }
        val identity = GattDescriptorIdentity(
            characteristic = characteristicIdentity,
            descriptorUuid = OperationExecutor.CLIENT_CHARACTERISTIC_CONFIG_UUID,
        )
        val scheduled = session.gattOperationScheduler.enqueue(
            QueuedCccdWrite(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
                enabled = false,
                descriptorIdentity = identity,
            ),
        )
        executor.startNextOperation(session)

        try {
            executor.awaitAfterStart(session, scheduled, ackTimeoutMillis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlin.coroutines.coroutineContext.ensureActive()
            executor.emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.operationInvalidated(session, scheduled),
            )
        } catch (e: BleException) {
            emitUnsubscribeFailure(session, charRef, e.failure)
        }
    }

    private fun emitUnsubscribeFailure(session: Session, charRef: CharacteristicRef, failure: BleFailure) {
        executor.emitDiagnostic(
            Severity.Warning,
            failure.copy(
                context = failure.context.copy(operationKind = OperationKind.Unsubscribe),
            ),
        )
    }

}
