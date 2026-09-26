package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.ConnectionStageDeadline
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.StageAuthority
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import kr.co.nucode.ble.internal.session.toDisconnectReason

/**
 * [GattConnectionLifecycle] 의 정리·종료 칸 — lifecycle deadline, cleanup 시작·완료, terminate, 플랫폼 재연결 대기 종료,
 * 어댑터 이탈, client close, operation 무효화 종결을 소유한다. 상태는 갖지 않는다(세션·coordinator 가 소유).
 */
internal class LinkTeardown(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val operations: GattOperationHooks,
    private val authority: StageAuthority,
    private val publishState: (Session) -> Unit,
    private val emitDiagnostic: (Severity, BleFailure) -> Unit,
) {

    fun onLifecycleDeadline(
        deviceId: PeripheralId,
        deadline: ConnectionStageDeadline,
    ) {
        val session = registry.get(deviceId) ?: return
        val coordinator = session.connectionCoordinator
        if (deadline.generation != coordinator.generation || deadline.stage != coordinator.stage) return

        if (deadline.stage == ConnectionStage.Disconnecting) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.disconnectCleanupTimeout(session),
            )
            // 콜백 없이 deadline 으로 끝난다 — beginCleanup 이 고정한 원인이 그대로 공개 이유가 된다.
            completeLifecycleCleanup(
                session = session,
                generation = deadline.generation,
                fallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
            )
            return
        }

        val lifecycleFailure = GattFailureFactory.lifecycleTimeout(session, deadline.timeoutReason)
        emitDiagnostic(Severity.Warning, lifecycleFailure)
        beginCleanup(
            session = session,
            reason = deadline.timeoutReason,
            cause = lifecycleFailure,
            suppressReconnect = true,
        )
    }

    /** Lifecycle failure/user intent가 scheduler와 platform handle을 한 bounded 경로로 정리한다. */
    fun beginCleanup(
        session: Session,
        reason: TerminalCleanupReason,
        cause: BleFailure?,
        suppressReconnect: Boolean,
    ): Boolean {
        val timeouts = readinessTimeouts
        val generation = session.connectionCoordinator.generation
        val started = session.connectionCoordinator.beginCleanup(
            authority,
            targetGeneration = generation,
            cause = TerminalCause(reason, cause),
            scope = scope,
            disconnectCleanupTimeoutMillis = timeouts.disconnectCleanupMillis,
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
        )
        if (!started) return false

        if (suppressReconnect) session.suppressReconnect = true
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        publishState(session)
        try {
            driver.disconnect(session.deviceId, session.gatt)
        } catch (_: Exception) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.disconnectStartFailed(session),
            )
            completeLifecycleCleanup(
                session = session,
                generation = generation,
                fallback = TerminalCause(reason, cause),
            )
        }
        return true
    }

    /**
     * 즉시 종결(시작 거부·재발견 실패 등) — [cause] 를 아직 고정된 원인이 없을 때만 고정하고
     * generation 을 Disconnected 로 확정한 뒤 한 번 게시한다. 이미 고정된 원인이 있으면 그 원인이 이긴다.
     */
    fun terminate(session: Session, generation: Long, cause: TerminalCause): Boolean {
        val adapterLost = cause.reason == TerminalCleanupReason.AdapterUnavailable ||
            cause.reason == TerminalCleanupReason.PermissionDenied
        // adapter off 스윕은 플랫폼 재연결 대기 중(이미 Disconnected)인 세션도 끝낸다 — close 와 같은 경로.
        val terminated = (adapterLost && endPlatformWait(session, generation, cause)) || run {
            // 어댑터가 죽은 링크의 handle 은 OS 대기용으로 남기지 않는다(복귀 재연결은 새 handle 로 건다).
            if (adapterLost) session.suppressReconnect = true
            completeLifecycleCleanup(
                session = session,
                generation = generation,
                fallback = cause,
            )
        }
        // autoConnect 세션이 어댑터 꺼짐으로 끝났으면 어댑터 복귀 때 한 번 다시 건다(BleRuntime 이 소비).
        // 요청 원인이 아니라 고정된 최종 원인으로 판정한다 — 먼저 고정된 사용자 해제(Requested)가 이긴
        // generation 은 복귀 재연결 대상이 아니다(review H2).
        if (
            terminated &&
            session.autoConnect &&
            session.connectionCoordinator.terminalCleanupReason == TerminalCleanupReason.AdapterUnavailable
        ) {
            session.relinkAfterAdapter = true
        }
        return terminated
    }

    /**
     * 플랫폼 재연결 대기 중인 세션을 [cause] 로 끝낸다 — 사용자 해제·client close·adapter off 스윕의 공유 경로
     * ([ConnectionCoordinator.supersedePlatformWait] KDoc 의 소유자). 이미 Disconnected 인 generation 이
     * [Session.awaitingPlatformRelink] 일 때만 동작한다. suppressReconnect 를 세우고 대기 종료와 coordinator
     * 재게시를 한 번에 한 뒤 OS 대기를 취소하고(driver.disconnect + close — 열어 둔 autoConnect handle 이 다시 붙지 않게)
     * ConnStateChanged 를 낸다. 대기가 없으면 아무 것도 하지 않고 false(원래 원인 유지).
     */
    fun endPlatformWait(session: Session, generation: Long, cause: TerminalCause): Boolean {
        val coordinator = session.connectionCoordinator
        if (generation != coordinator.generation || coordinator.stage != ConnectionStage.Disconnected) return false
        // 대기 중이던 세션만 — 대기 중이던 세션은 앱 관점에서 아직 종착이 아니었다.
        if (!session.awaitingPlatformRelink) return false
        session.suppressReconnect = true
        // 대기 종료와 원인 대체를 한 번에 게시한다 — 중간에 옛 원인의 Disconnected 가 보이지 않는다.
        var superseded = false
        session.endPlatformWait { superseded = coordinator.supersedePlatformWait(authority, generation, cause) }
        if (!superseded) return false
        try {
            driver.disconnect(session.deviceId, session.gatt)
        } catch (_: Exception) {
            // beginCleanup 과 같은 규칙 — 대기 취소 시작 실패는 진단으로만 남기고 handle 은 그대로 닫는다
            // (스윕 루프를 끊지 않는다).
            emitDiagnostic(Severity.Warning, GattFailureFactory.disconnectStartFailed(session))
        }
        closeHandle(session)
        publishState(session)
        return true
    }

    /**
     * 게시 없이 generation 만 종결한다(connect() 의 즉시 실패 — 게시는 throw 를 받은 호출자 몫).
     * 공개 이유는 고정된 원인에서만 파생한다.
     */
    fun completeWithoutPublish(session: Session, generation: Long, fallback: TerminalCause): Boolean {
        finalizeTerminalCause(session, generation, fallback, status = 0)
        return session.connectionCoordinator.completeCleanup(authority, generation, fallback)
    }

    /**
     * 종결 게시 **전에** 원인을 확정한다 — 원인 없는 플랫폼 해제면 status 를 담은 LINK_FAILED 를 고정하고
     * Diagnostic 으로 낸다(게시 시점 파생에 status 가 필요 없게).
     */
    fun finalizeTerminalCause(session: Session, generation: Long, fallback: TerminalCause, status: Int) {
        val coordinator = session.connectionCoordinator
        if (generation != coordinator.generation || coordinator.stage == ConnectionStage.Disconnected) return
        val candidate = coordinator.terminalCause ?: fallback
        val derived = candidate.toDisconnectReason(
            generationStarted = true,
            unattributedFailure = { GattFailureFactory.unattributedLinkDisconnect(session, status) },
        )
        val derivedFailure = (derived as? DisconnectReason.Failed)?.failure
        if (candidate.failure == null && derivedFailure != null) {
            // 원인 없는 해제도 status 를 담은 실패 자체를 고정한다 — 게시 시점 파생에 status 가 필요 없다.
            coordinator.latchIfAbsent(authority, generation, TerminalCause(candidate.reason, derivedFailure))
            // LINK_FAILED 는 State 와 함께 Event 로도 전달한다(AsyncResource 규칙).
            emitDiagnostic(Severity.Warning, derivedFailure)
        }
    }

    /** Callback 또는 cleanup deadline이 현재 generation을 정확히 한 번 terminal로 만든다. */
    fun completeLifecycleCleanup(
        session: Session,
        generation: Long,
        fallback: TerminalCause,
        status: Int = 0,
    ): Boolean {
        finalizeTerminalCause(session, generation, fallback, status)
        val coordinator = session.connectionCoordinator
        // 링크가 끊긴 autoConnect generation 의 열린 handle 은 OS 재연결 대기용으로 남긴다 — 대기 시작과
        // Disconnected 전이를 한 번에 게시한다(Recovering 은 이 명시 상태에서만 파생, review H1).
        val keepForPlatformWait = session.autoConnect &&
            !session.suppressReconnect &&
            session.platformHandleOpen &&
            generation == coordinator.generation &&
            coordinator.stage != ConnectionStage.Disconnected
        val completed = if (keepForPlatformWait) {
            session.beginPlatformWait { coordinator.completeCleanup(authority, generation, fallback) }
        } else {
            coordinator.completeCleanup(authority, generation, fallback)
        }
        if (!completed) return false

        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        session.mtu = null
        session.chunkSize = null
        if (session.suppressReconnect) closeHandle(session)

        publishState(session)
        return true
    }

    /**
     * 어댑터 이탈 스윕의 세션 몫(복귀의 [resumeAfterAdapter] 와 짝). 이미 끝난 세션은 조용히(원인 유지).
     * 단 플랫폼 재연결 대기 중(Recovering)이면 앱 관점에서 아직 종착이 아니다 — 어댑터 원인으로 대기를
     * 끝낸다(close 와 같은 경로로 재게시, ConnectionCoordinator.supersedePlatformWait).
     *
     * 이 스윕은 STATE_DISCONNECTED 정리 블록과 **중복되는 게 의도다** — 콜백이 오는 기기에서는 둘 다
     * 돌지만, 먼저 도는 쪽이 세션을 Disconnected 로 바꿔두면 다른 쪽은 아래 skip 으로 걸러진다(둘 다
     * coreScope 홉이라 직렬). 콜백 자체가 안 오는 기기에서는 이 스윕만 돈다 — 그게 D4 의 존재 이유다.
     *
     * 종결은 [terminate] 로 한다 — 원인 고정·handle close(suppressReconnect)·mtu/chunkSize 무효화(plan G)·
     * 복원 job 정리(plan B)·게시를 완료 경로가 한 번에 한다. 이미 사용자 해제가 진행 중(Disconnecting)이면
     * 먼저 고정된 Requested 가 남는다(first wins — 사용자가 끊기를 요청했다).
     */
    fun onAdapterLost(session: Session, cause: AdapterState) {
        if (session.stage == ConnectionStage.Disconnected && !session.awaitingPlatformRelink) return
        val failure = FailureMapper.fromAdapterState(cause).copy(
            context = session.lifecycleFailureContext(),
        )
        emitDiagnostic(Severity.Warning, failure)
        val reason = if (cause is AdapterState.Unauthorized) {
            TerminalCleanupReason.PermissionDenied
        } else {
            TerminalCleanupReason.AdapterUnavailable
        }
        terminate(session, session.connectionCoordinator.generation, TerminalCause(reason, failure))
    }

    /**
     * Client close — 세션을 `Disconnected(ClientClosed)` 로 끝내고 platform handle 을 닫는다.
     *
     * 원인 대체 규칙은 [ConnectionCoordinator.supersedeForClose](종결되지 않은 generation)와
     * [endPlatformWait]([ConnectionCoordinator.supersedePlatformWait]): 플랫폼 재연결 대기 중(공개 상태 Recovering)인
     * generation 은 ClientClosed 로 끝난다. 대기가 없는 이미 종결된 generation 은 원래 원인(예: Failed)을 유지한다.
     * 연결 시도 전(generation 0) 세션은 닫힘이 첫 원인이다.
     *
     * close 는 종착이다 — suppressReconnect 를 세운다. 공개 상태는
     * coordinator 게시(onStageChanged → Session.connectionState)로 한 번 바뀌고, 시작된 generation 은
     * ConnStateChanged 도 낸다(BleRuntime 경유 시 close 게이트 뒤라 이벤트 버스에서는 버려진다 —
     * 닫힌 클라이언트의 최종 상태는 connectionState·closed snapshot 이 전달한다). 붙은 뒤 절차는
     * 진행하지 않는다(닫힌 런타임에서 다음 단계를 진행할 이유가 없다) — 진행 중인 복원 job 은 종결 stage 게시에서
     * LinkSetup 의 링크 이탈 listener 가 끊는다.
     */
    fun close(session: Session) {
        session.relinkAfterAdapter = false
        val coordinator = session.connectionCoordinator
        val generation = coordinator.generation
        if (endPlatformWait(session, generation, TerminalCause(TerminalCleanupReason.ClientClosed, null))) {
            // endPlatformWait 가 이미 handle 을 닫았다 — 두 번 닫지 않는다.
            return
        }
        session.suppressReconnect = true
        val superseded = coordinator.supersedeForClose(authority, generation)
        val completed = completeWithoutPublish(
            session,
            generation,
            TerminalCause(TerminalCleanupReason.ClientClosed, null),
        )
        // 연결 시도 전(고정된 원인 없음) 세션은 종결할 generation 이 없다 — 닫힘이 첫 원인이다.
        // 이미 종결된 generation 의 원인은 바꾸지 않는다(first wins).
        if (!completed && !superseded) coordinator.closeIdle(authority)
        if (generation > 0L && (superseded || completed)) {
            publishState(session)
        }
        closeHandle(session)
    }

    /** platform handle 만 닫는다(원인·공개 상태는 건드리지 않는다). 완료 경로의 suppress 정리에도 쓴다. */
    fun closeHandle(session: Session) {
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        driver.close(session.deviceId, session.gatt)
        session.gatt = null
        session.platformHandleOpen = false
        session.endPlatformWait()
    }

    /** Operation 절반의 poison 꼬리(plan §접근) — completeCleanup이 driver.disconnect/close보다
     * 먼저다(callback 동기 재진입 대비, 위 완료 순서 주석과 동일 근거). */
    fun terminateForOperationInvalidation(
        session: Session,
        cause: BleFailure,
        suppressReconnect: Boolean,
    ) {
        val connectionGeneration = session.connectionCoordinator.generation
        if (suppressReconnect) session.suppressReconnect = true
        // Platform disconnect/close보다 먼저 connection generation을 terminalize한다.
        // disconnect가 callback을 동기 재진입시키더라도 OperationInvalidated 원인이
        // PlatformDisconnected로 먼저 확정되거나 coordinator가 Ready에 남지 않는다.
        emitDiagnostic(Severity.Warning, cause)
        completeWithoutPublish(
            session,
            connectionGeneration,
            TerminalCause(TerminalCleanupReason.OperationInvalidated, cause),
        )
        val oldGatt = session.gatt
        driver.disconnect(session.deviceId, oldGatt)
        driver.close(session.deviceId, oldGatt)
        session.gatt = null
        session.platformHandleOpen = false
        session.endPlatformWait()
        session.mtu = null
        session.chunkSize = null

        publishState(session)
    }
}
