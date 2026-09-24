package kr.co.nucode.ble.internal.connection

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.ReconnectPolicy
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

/**
 * 백오프 곡선. `base * 2^attempt` 를 `maxDelay` 로 clamp 한다 — 순수 함수라 `Reconnector`
 * 인스턴스 없이 테스트 가능하다(plan §테스트가능성).
 *
 * jitter 를 넣지 않는다: jitter 는 "같은 자원에 몰리는 다수의 독립 클라이언트"가 동시에
 * 재시도해 부하가 튀는 걸 막으려는 장치인데, 여기서는 기기 하나당 이 휴대폰의 재연결
 * 시도 하나뿐이라 몰릴 대상(공유 서버)이 없다 — thundering herd 전제가 성립하지 않는다.
 * 대신 값을 결정론적으로 유지하면 [computeBackoffDelayMillis] 자체를 정확한 값으로
 * 테스트할 수 있고, Diagnostic 로그에 찍히는 delay 값도 재현 가능해서 현장 디버깅에 유리하다.
 */
internal fun computeBackoffDelayMillis(attempt: Int, policy: ReconnectPolicy): Long {
    var delayMillis = policy.baseDelayMillis.coerceAtLeast(0)
    // 곱셈 대신 배 늘리기를 반복하며 maxDelay 초과 즉시 멈춘다 — attempt 가 아무리 커도
    // (Int.MAX_VALUE 급이라도) 실제 반복은 몇 번 안에 끝난다: base 가 지수적으로 자라
    // 곧 maxDelay 를 넘기 때문. `base shl attempt` 로 먼저 계산하면 attempt 가 크면
    // Long 이 오버플로/wraparound 되어 오히려 maxDelay 보다 작은 값이 나올 수 있다.
    repeat(attempt.coerceAtLeast(0)) {
        if (delayMillis >= policy.maxDelayMillis) return policy.maxDelayMillis
        delayMillis *= 2
    }
    return delayMillis.coerceIn(0, policy.maxDelayMillis)
}

/**
 * 재시도 여부 판정. `retryCount`(이미 시도한 횟수)가 정책의 최대 시도 횟수 미만이면 계속한다.
 * 순수 함수 — Session/Reconnector 상태 없이 테스트 가능하다.
 */
internal fun canRetry(retryCount: Int, policy: ReconnectPolicy): Boolean =
    retryCount < policy.maxAttempts

/**
 * 재연결 메커니즘만 담당한다. 정책(횟수 · 백오프 곡선 · 조건)은 [policy] 로 주입되고,
 * 실제 연결 재시도는 GattBridge(plan task 7)가 배선한다(설계 §6 "Reconnector — 메커니즘만").
 *
 * 연결 단계(`ConnectionStage`)에 `Reconnecting` 을 두지 않는다(설계 §5) — 재연결 중에도 상태는
 * `Disconnected` → `Connecting` 을 오갈 뿐이다.
 *
 * `delay()` 는 생성자로 받은 [scope] 의 디스패처 컨텍스트 안에서만 부른다 — `Thread.sleep` /
 * `System.currentTimeMillis` 를 쓰면 `runTest` 의 가상 시간이 먹지 않는다(plan §테스트가능성).
 */
@SuppressLint("MissingPermission")
internal class Reconnector(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val sequence: ConnectSequenceHooks,
) {

    /** 상태 발행의 유일한 경로 — 발행 뒤 연결 절차 hook 을 부른다(plan connect-sequence 불변식 ②). */
    private fun publishState(session: Session) {
        emit(BleEvent.ConnStateChanged(session.deviceId, session.connectionState.value))
        sequence.onStatePublished(session.deviceId)
    }

    /**
     * 재연결 정책. 생성자 4번째 파라미터로 만들지 않는다 — 4·5·6·7 워커가 공유하는
     * 생성자 계약(registry, emit, scope 셋)을 깨면 task 3 조립이 어긋난다(plan §워커 생성자 계약).
     * 기본값 있는 `var` 로 노출해 필요하면 조립부가 바꿔 끼운다.
     */
    var policy: ReconnectPolicy = ReconnectPolicy()

    /**
     * GattBridge 가 generation 수용과 deadline 을 소유한다. 보존한 platform handle 을 시작하기
     * 직전에 부른다 — 콜백이 도착할 수 있는 시점 뒤가 아니다. 이 generation 의 link deadline 이
     * 재연결 시도의 유일한 상한이다(plan callback-ingress 목표 4). 조립부가 함수 참조로 건다.
     */
    lateinit var beginReconnectLifecycle: (PeripheralId) -> Long?

    /**
     * 보존한 platform handle 로 재연결을 시작한다 — 조립부가 driver 경유 GattBridge 메서드를
     * 함수 참조로 건다. Reconnector 는 `BluetoothGatt` 를 직접 부르지 않는다(plan callback-ingress
     * 불변식 ③). `false` 는 시작 자체가 거부된 것이다.
     */
    lateinit var startRetainedConnect: (Session) -> Boolean

    /**
     * lifecycle 종결 API(`GattConnectionLifecycle.terminate`) — 조립부가 함수 참조로 건다.
     * Reconnector 는 종료 원인·공개 상태를 직접 쓰지 않는다(plan terminal-cause v2 목표 4).
     * 미배선이면 첫 종결에서 바로 드러나도록 lateinit 이다.
     */
    lateinit var terminate: (Session, Long, TerminalCause) -> Boolean

    /** 예산과 무관하게 이번 종결 뒤 재연결을 하지 않는 조건(사용자 해제·autoConnect·Never·이미 예약). */
    private fun retryBlocked(session: Session, cause: BleFailure?): Boolean =
        session.suppressReconnect ||
            session.autoConnect ||
            cause?.retryability == Retryability.Never ||
            session.reconnectJob != null

    private fun budgetOf(session: Session): ReconnectPolicy =
        session.effectiveReconnectPolicy.toReconnectPolicyOrNull() ?: policy

    /**
     * 순수 질의 — 지금 [cause] 로 Disconnected 가 게시되면 [scheduleReconnect] 가 재시도를 예약하는가.
     * 소진 선판정(lifecycle)과 예약(scheduleReconnect)이 같은 판정을 쓰게 하는 단일 기준이다.
     */
    fun willRetry(session: Session, cause: BleFailure?): Boolean =
        !retryBlocked(session, cause) && canRetry(session.retryCount, budgetOf(session))

    /**
     * 순수 질의 — 재시도가 없는 이유가 **예산 소진**일 때만 소진 실패(공식 RECONNECT_EXHAUSTED,
     * legacy RECONNECT_GIVE_UP)를 돌려준다. 원 원인의 context·platformDetail 을 감싸고
     * retryability 는 Never 다(plan 결정 2). 재시도함·suppress·Never·autoConnect 면 null.
     */
    fun exhaustedFailure(session: Session, cause: BleFailure?): BleFailure? {
        if (retryBlocked(session, cause) || willRetry(session, cause)) return null
        val official = session.effectiveReconnectPolicy is BleReconnectPolicy.ExponentialBackoff
        return reconnectFailure(
            if (official) "RECONNECT_EXHAUSTED" else "RECONNECT_GIVE_UP",
            session,
            cause,
        ).copy(retryability = Retryability.Never)
    }

    /**
     * 끊김을 감지한 쪽이 재연결을 요청할 때 부른다. [cause]는 원본 failure를 그대로
     * 보존해 재연결 diagnostic의 allowlisted platform detail과 correlation에 이어 붙인다.
     *
     * 재시도 가능 여부와 무관하게 매 호출마다 Diagnostic 을 방출한다 — Diagnostic 은
     * "삼키지 않는다"는 계약이라, 코어가 내부적으로 재시도했다는 사실 자체가 반드시 남아야
     * 한다(설계 §5).
     *
     * 기기별 예약 손잡이는 [Session.reconnectJob] 하나다. LAZY job을 상태 발행 전에 이
     * 필드에 설치하므로, `publishState` 뒤 [ConnectSequenceHooks.onStatePublished] 가 (stage 가
     * 여전히 Disconnected 라서) 동기 재진입해도 같은 재연결을 다시 예약하지 않는다. 실제 platform start 직전에 손잡이를 비워야 시작 실패로 방출된 다음
     * Disconnected가 다음 bounded retry를 예약할 수 있다.
     */
    fun scheduleReconnect(deviceId: PeripheralId, cause: BleFailure? = null) {
        val session = registry.get(deviceId) ?: return
        // 배타(plan v3): autoConnect 연결의 재연결 소유권은 플랫폼이다 — 코어가 겹으로
        // 예약하면 give-up Diagnostic 이 거짓이 된다(플랫폼 대기는 계속 살아 있으므로).
        // 중복 방지: 예약은 기기당 하나. LAZY job은 start() 전 동기 event 방출 구간에서
        // isActive가 false이므로, 손잡이의 존재 자체를 예약 중인 상태로 취급한다(retryBlocked).
        //
        // 예산 소진은 여기서 판정·게시하지 않는다 — lifecycle 이 종결 게시 전에 [exhaustedFailure]
        // 로 원인을 소진으로 고정하고 suppressReconnect 를 세운다(이중 게시 제거, plan terminal-cause
        // v2 §접근). 여기서는 같은 질의 [willRetry] 가 false 면 예약하지 않을 뿐이다.
        if (!willRetry(session, cause)) return
        val attempt = session.retryCount
        val reconnectPolicy = budgetOf(session)
        val official = session.effectiveReconnectPolicy is BleReconnectPolicy.ExponentialBackoff

        val delayMillis = computeBackoffDelayMillis(attempt, reconnectPolicy)
        session.retryCount = attempt + 1

        val failure = reconnectFailure("RECONNECT_ATTEMPT", session, cause)
        // LAZY job 을 먼저 설치한다. 아래 publishState 가 ConnectSequence.onStatePublished 를
        // 동기로 부르고, 그 Disconnected 분기가 이 scheduleReconnect 로 재진입할 때 이 손잡이를
        // 보고 두 번째 continuation 을 만들지 않아야 한다.
        val reconnectJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMillis)
            reconnect(deviceId)
        }
        session.reconnectJob = reconnectJob
        if (official) {
            session.publishRecovering(
                attempt = attempt + 1,
                retryAfterMillis = delayMillis,
                lastFailure = failure,
            )
        }

        emit(
            BleEvent.Diagnostic(
                severity = Severity.Info,
                failure = failure,
            ),
        )
        // This is also the public-state edge for Recovering.  The event carries the Recovering
        // projection captured here; the coordinator stage stays Disconnected.
        if (official) publishState(session)
        reconnectJob.start()
    }

    /**
     * 백오프 대기가 끝난 뒤 실제로 연결을 재개하는 지점. 상태를 `Connecting` 으로 되돌리고
     * 기존 [Session.gatt] 로 재연결을 시도한다(plan §접근 ⑤) — 새 `BluetoothDevice.connectGatt()`
     * 를 다시 부르지 않는다. `GattBridge.handleConnectionStateChange` 가 재연결 후보
     * (`suppressReconnect == false`)의 gatt 를 close 하지 않는 이유가 정확히 이 재사용이다 —
     * 여기서 새로 connectGatt 하면 기존 핸들이 누수되고, 재사용 가능한 이 gatt 를 close
     * 안 해둔 앞단의 선택이 무의미해진다.
     *
     * retained connect 시작이 `false` 를 돌려주면(호출 자체가 시작도 못 한 것) callback은 다시
     * 오지 않는다. 이 시도의 `Disconnected` 발행(`publishState` → `ConnectSequence.onStatePublished`)이
     * 다음 bounded retry/give-up을 요청할 수 있도록, platform start 전에 현재 job guard를 해제한다. 여기서 직접 재귀
     * 호출하지 않는다. 재시도 정책과 backoff는 여전히 hook 경로의 [scheduleReconnect] 한
     * 곳만 소유하므로, `runTest`에서는 `advanceTimeBy`로 각 attempt를 관찰할 수 있다.
     */
    private suspend fun reconnect(deviceId: PeripheralId) {
        val session = registry.get(deviceId) ?: return
        // 실행 시점 재확인(review H-2): 예약(백오프 대기) 중에 사용자가 disconnect() 를
        // 불렀을 수 있다 — 예약 시점 가드(ConnectSequence.onStatePublished)만으로는
        // 이미 큐에 들어간 시도를 못 막는다. 사용자가 끊은 기기를 코어가 되붙이면 안 된다.
        if (session.suppressReconnect || session.autoConnect) return
        // 이 job이 곧 platform start를 끝내므로 더는 "backoff 대기 중"이 아니다. false
        // start가 Disconnected를 emit하면 ConnectSequence.onStatePublished가 즉시 다음 retry를
        // 예약한다. 이 참조를 남기면 `reconnectJob != null` guard가 그 유일한 continuation을 막는다.
        session.reconnectJob = null
        // Generation must be incremented before touching the retained GATT handle so callbacks
        // cannot mutate a preceding lifecycle.
        val generation = beginReconnectLifecycle(deviceId) ?: return
        session.subscriptionState.beginGeneration(generation)
        publishState(session)

        // 플랫폼 시작은 false 외에 예외로도 실패한다 — driver 가 권한 회수를 BleException(PERMISSION_DENIED)
        // 으로 던진다. 이 job 은 handler 없는 core scope 에서 돌므로 여기서 잡지 않으면 크래시다.
        // 어느 쪽이든 방금 연 generation 을 같은 terminate 경로로 닫는다.
        val startFailure: TerminalCause? = try {
            if (startRetainedConnect(session)) null else rejectedStart(session)
        } catch (e: BleException) {
            if (e.failure.code.value == "PERMISSION_DENIED") {
                // 권한 없음은 재시도해도 같다 — 재연결을 멈춘다(connect() 의 권한 실패와 같은 규칙).
                session.suppressReconnect = true
                TerminalCause(TerminalCleanupReason.PermissionDenied, e.failure)
            } else {
                TerminalCause(TerminalCleanupReason.LinkStartRejected, e.failure)
            }
        } catch (_: Exception) {
            rejectedStart(session)
        }
        if (startFailure != null) {
            emit(
                BleEvent.Diagnostic(
                    severity = Severity.Warning,
                    failure = requireNotNull(startFailure.failure),
                ),
            )
            // Connecting 잔류 금지(review — plan v2 ①): 시작조차 못 한 시도를 열린 채 두면
            // H-3 가드가 이후 사용자 connect() 를 조용한 no-op 으로 삼킨다. D6(시작 실패 =
            // 상태·스트림 종결)과 같은 패턴으로 닫는다 — 이 Disconnected emit 이
            // ConnectSequence.onStatePublished 를 타고 scheduleReconnect 로 가서, retryCount 가
            // 다음 백오프/give-up 을 판정한다(무한 재귀 아님 — maxAttempts 상한). 권한 실패는
            // suppressReconnect 로 더 예약하지 않는다.
            terminate(session, generation, startFailure)
            return
        }

        // 시도 상한은 따로 두지 않는다 — beginReconnectLifecycle 이 연 generation 의 link deadline
        // 이 초과 시 LINK_TIMEOUT 으로 이 시도를 종결한다(plan callback-ingress 목표 4).
    }

    /** 시작 거부(false 또는 예기치 않은 예외) — 재연결 시작 실패 코드로 이 시도를 닫는다. */
    private fun rejectedStart(session: Session): TerminalCause {
        val failure = FailureMapper.gattStartRejected(
            phase = FailurePhase.Connect,
            operation = AndroidApiOperation.Connect,
            context = session.lifecycleFailureContext(),
        ).copy(code = FailureCode("RECONNECT_CONNECT_START_FAILED"))
        return TerminalCause(TerminalCleanupReason.LinkStartRejected, failure)
    }

    private fun reconnectFailure(
        code: String,
        session: Session,
        cause: BleFailure?,
    ): BleFailure = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode(code),
        phase = FailurePhase.Reconnect,
        retryability = cause?.retryability ?: Retryability.Unknown,
        context = cause?.context?.takeIf { it.sessionId != null }
            ?: session.lifecycleFailureContext().copy(
                operationId = cause?.context?.operationId,
                operationKind = cause?.context?.operationKind,
                invalidationReason = cause?.context?.invalidationReason,
            ),
        platformDetail = cause?.platformDetail,
    )

    private fun BleReconnectPolicy.toReconnectPolicyOrNull(): ReconnectPolicy? = when (this) {
        BleReconnectPolicy.Disabled -> null
        is BleReconnectPolicy.ExponentialBackoff -> ReconnectPolicy(
            maxAttempts = maxAttempts,
            baseDelayMillis = initialDelayMillis,
            maxDelayMillis = maxDelayMillis,
        )
    }
}
