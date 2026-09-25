package kr.co.nucode.ble.internal.connection

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session

/**
 * LinkSetup → lifecycle 역방향 호출의 전부(좁은 hook). stage 전이와 상태 게시는 lifecycle 이 한다 —
 * LinkSetup 은 "다음에 무엇을 하라" 만 말한다(plan link-setup 불변식 ①②).
 */
internal interface LinkSteps {
    fun discoverServices(session: Session)

    fun requestMtu(session: Session, mtu: Int)

    /** 현재 공개 상태를 게시한다(lifecycle `publishState`). */
    fun publish(session: Session)

    /** [generation] 이 아직 Configuring 이면 Ready 로 전이하고 게시한다. 전이하지 못했으면 false. */
    fun completeReady(session: Session, generation: Long): Boolean
}

/**
 * 런타임이 주는 것 — 닫힘 게이트·warm 주소·공개 이벤트 채널·실패 변환은 BleRuntime 소유라 람다로 받는다.
 * 이것이 없으면(런타임 없이 bridge 만 만드는 테스트) 절차 입력은 아무 것도 하지 않는다.
 */
internal class LinkSetupWiring(
    val isClosed: () -> Boolean,
    val markWarm: (PeripheralId) -> Unit,
    val onRestoreFailed: (PeripheralId, CharacteristicRef, BleFailure) -> Unit,
    val failureOf: (Exception, PeripheralId, CharacteristicRef) -> BleFailure,
)

/**
 * 링크가 붙은 뒤 절차의 순서(plan link-setup) — 한 곳에 명시적 단계로 적는다:
 *
 * [onLinkUp] → 탐색 → [onServicesDiscovered] → (Configuring 게시) → MTU 요청
 *   → [onMtuSettled] → 구독 복원(직렬) → [LinkSteps.completeReady]
 *
 * ★ 상태 기계가 아니다 — stage 전이는 lifecycle 이 하고, 이 클래스는 순서와 복원 job 만 안다.
 * 복원 job 손잡이는 이 클래스 private 상태다(Session 필드가 아니다) — 정리 경로는 [cancel] 로만 끊는다.
 * 런타임이 닫혔으면 절차 입력은 아무 것도 하지 않는다.
 */
internal class LinkSetup(
    private val scope: CoroutineScope,
    private val steps: LinkSteps,
    private val restoreSubscription: suspend (PeripheralId, CharacteristicRef) -> Unit,
    private val diagnostic: (BleFailure) -> Unit,
    private val wiring: LinkSetupWiring?,
) {
    /** 세션별 복원 job(규칙 8 — delay·await 를 품은 코루틴 손잡이). */
    private val restoreJobs = mutableMapOf<Session, Job>()

    /** 링크가 붙어 DiscoveringServices 가 게시된 직후. */
    fun onLinkUp(session: Session, generation: Long) {
        val wiring = wiring ?: return
        if (wiring.isClosed()) return
        if (!session.connectionCoordinator.accepts(generation, ConnectionStage.DiscoveringServices)) return
        wiring.markWarm(session.deviceId)
        steps.discoverServices(session)
    }

    /** `ServicesDiscovered` 발행 직후. */
    fun onServicesDiscovered(session: Session, generation: Long) {
        val wiring = wiring ?: return
        if (wiring.isClosed()) return
        if (session.connectionCoordinator.accepts(generation, ConnectionStage.Configuring)) {
            steps.publish(session)
        }
        steps.requestMtu(session, session.requestedMtu)
    }

    /** MTU 확정(세션 반영) 직후(협상 성공·폴백 모두). */
    fun onMtuSettled(session: Session, generation: Long) {
        val wiring = wiring ?: return
        if (wiring.isClosed()) return
        if (session.connectionCoordinator.accepts(generation, ConnectionStage.Configuring)) {
            restoreSubscriptions(session, generation, wiring)
        }
    }

    /** 진행 중인 복원 job 을 끊는다 — lifecycle 정리 경로가 부른다. */
    fun cancel(session: Session) {
        restoreJobs.remove(session)?.cancel()
    }

    /** 테스트 seam — 현재 복원 job 손잡이. */
    internal fun restoreJob(session: Session): Job? = restoreJobs[session]

    /** Restores the desired CCCD intent serially while this generation remains Configuring. */
    private fun restoreSubscriptions(session: Session, generation: Long, wiring: LinkSetupWiring) {
        restoreJobs.remove(session)?.cancel()
        restoreJobs[session] = scope.launch {
            for (ref in session.subscriptionState.desiredSnapshot) {
                if (!session.isConfiguringGeneration(generation)) return@launch
                // 스냅샷을 뜬 뒤 사용자가 해제했으면 건너뛴다 — 켜면 해제 CCCD 뒤에 켜기가 서서 기기가 계속 통지한다.
                if (!session.subscriptionState.containsDesired(ref)) continue
                try {
                    restoreSubscription(session.deviceId, ref)
                } catch (e: CancellationException) {
                    coroutineContext.ensureActive()
                    return@launch
                } catch (e: Exception) {
                    if (!session.isConfiguringGeneration(generation)) return@launch
                    val failure = wiring.failureOf(e, session.deviceId, ref)
                    if (failure.retryability == Retryability.Never) {
                        session.subscriptionState.removeDesired(ref)
                        wiring.onRestoreFailed(session.deviceId, ref, failure)
                    } else {
                        diagnostic(failure)
                    }
                    continue
                }
                if (!session.isConfiguringGeneration(generation)) return@launch
                session.subscriptionState.markActive(ref, generation)
            }
            if (!session.isConfiguringGeneration(generation)) return@launch
            // Ready 게시 전에 이 복원의 손잡이만 비운다. 대체 generation 이 이미 자리를 가졌으면
            // 늦게 끝난 옛 복원이 그 손잡이를 지우면 안 된다.
            if (restoreJobs[session] === coroutineContext[Job]) restoreJobs.remove(session)
            steps.completeReady(session, generation)
        }
    }
}
