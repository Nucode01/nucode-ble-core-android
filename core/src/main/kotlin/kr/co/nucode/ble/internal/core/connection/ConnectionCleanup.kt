package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.Severity

/**
 * 정리·종료 정책(§2·§3·§9·§10) — 기한 만료·사용자 해제·close·어댑터 이탈·작업 무효화·내부 실패를
 * `(상태, 사건) → (새 상태, 할 일 목록)` 으로 판단한다. 할 일 목록 순서가 실행 순서다.
 * 상태는 여기서 먼저 확정되고 driver·게시는 Runtime 이 목록 순서대로 실행한다.
 */
internal object ConnectionCleanup {
    private fun context(state: ConnectionCoreState): FailureContext =
        FailureContext(sessionId = state.failureSessionId, connectionGeneration = state.generation)

    private fun diagnostic(severity: Severity, failure: BleFailure) = ConnectionEffect.Diagnostic(severity, failure)

    private fun closeHandle() = listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.ReleaseHandle)

    /** 만료된 기한의 정책 — 이전 generation/단계의 기한이면 무시한다. */
    fun lifecycleDeadline(state: ConnectionCoreState, event: ConnectionEvent.LifecycleDeadline): ConnectionStep {
        val deadline = event.deadline
        if (deadline.generation != state.generation || deadline.stage != state.stage) {
            return ConnectionCore.rejected(state)
        }
        if (deadline.stage == ConnectionStage.Disconnecting) {
            // 콜백 없이 기한으로 끝난다 — 시작 때 고정된 원인이 그대로 공개 이유가 된다.
            val completed = cleanupCompleted(
                state,
                deadline.generation,
                TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
                status = 0,
            )
            return ConnectionStep(
                completed.state,
                listOf(diagnostic(Severity.Warning, ConnectionFailures.disconnectCleanupTimeout(context(state)))) +
                    completed.effects,
                completed.accepted,
            )
        }
        val failure = ConnectionFailures.lifecycleTimeout(deadline.timeoutReason, context(state))
        val begun = beginCleanup(
            state,
            TerminalCause(deadline.timeoutReason, failure),
            suppressReconnect = true,
            disconnectCleanupMillis = event.disconnectCleanupMillis,
        )
        return ConnectionStep(begun.state, listOf(diagnostic(Severity.Warning, failure)) + begun.effects, begun.accepted)
    }

    /** 정리를 시작한다 — scheduler 와 platform handle 을 한 bounded 경로로 정리한다. */
    fun beginCleanup(
        state: ConnectionCoreState,
        cause: TerminalCause,
        suppressReconnect: Boolean,
        disconnectCleanupMillis: Long,
    ): ConnectionStep {
        val begun = ConnectionCore.step(
            state,
            ConnectionEvent.Direct.BeginCleanup(state.generation, cause, disconnectCleanupMillis),
        )
        if (!begun.accepted) return begun
        val next = if (suppressReconnect) begun.state.copy(suppressReconnect = true) else begun.state
        return ConnectionStep(
            next,
            begun.effects + listOf(
                ConnectionEffect.InvalidateOperations,
                ConnectionEffect.Announce,
                ConnectionEffect.DisconnectLink(ConnectionEvent.DisconnectStartFailed(state.generation, cause)),
            ),
            accepted = true,
        )
    }

    /** driver.disconnect 시작 실패 — 진단을 남기고, 정리를 마칠 원인이 있으면 그 원인으로 마친다. */
    fun disconnectStartFailed(state: ConnectionCoreState, event: ConnectionEvent.DisconnectStartFailed): ConnectionStep {
        val diag = diagnostic(Severity.Warning, ConnectionFailures.disconnectStartFailed(context(state)))
        val fallback = event.fallback ?: return ConnectionCore.accepted(state, listOf(diag))
        val completed = cleanupCompleted(state, event.generation, fallback, status = 0)
        return ConnectionStep(completed.state, listOf(diag) + completed.effects, completed.accepted)
    }

    /**
     * 종결 게시 **전에** 원인을 확정한다 — 원인 없는 플랫폼 해제면 status 를 담은 LINK_FAILED 를 고정하고
     * 진단으로 낸다(게시 시점 파생에 status 가 필요 없게).
     */
    private fun finalizeCause(
        state: ConnectionCoreState,
        generation: Long,
        fallback: TerminalCause,
        status: Int,
    ): ConnectionStep {
        if (generation != state.generation || state.stage == ConnectionStage.Disconnected) {
            return ConnectionCore.accepted(state)
        }
        val candidate = state.terminalCause ?: fallback
        val derived = candidate.toDisconnectReason(
            generationStarted = true,
            unattributedFailure = ConnectionCore.unattributedLinkDisconnect(state, status),
        )
        val derivedFailure = (derived as? DisconnectReason.Failed)?.failure
        if (candidate.failure != null || derivedFailure == null) return ConnectionCore.accepted(state)
        // 원인 없는 해제도 status 를 담은 실패 자체를 고정한다. LINK_FAILED 는 State 와 함께 Event 로도 전달한다.
        val latched = if (state.terminalCause == null) {
            state.copy(terminalCause = TerminalCause(candidate.reason, derivedFailure))
        } else {
            state
        }
        return ConnectionCore.accepted(latched, listOf(diagnostic(Severity.Warning, derivedFailure)))
    }

    /** 콜백 또는 정리 기한이 현재 generation 을 정확히 한 번 종결로 만든다. */
    fun cleanupCompleted(
        state: ConnectionCoreState,
        generation: Long,
        fallback: TerminalCause,
        status: Int,
    ): ConnectionStep {
        val finalized = finalizeCause(state, generation, fallback, status)
        val current = finalized.state
        if (generation != current.generation || current.stage == ConnectionStage.Disconnected) {
            return ConnectionStep(current, finalized.effects, accepted = false)
        }
        // 링크가 끊긴 autoConnect generation 의 열린 handle 은 OS 재연결 대기용으로 남긴다 — 대기 시작과
        // Disconnected 전이를 한 번에 게시한다(Recovering 은 이 명시 상태에서만 파생).
        val keepForPlatformWait = current.autoConnect && !current.suppressReconnect && current.platformHandleOpen
        val completed = ConnectionCore.step(
            if (keepForPlatformWait) current.copy(platformWait = true) else current,
            ConnectionEvent.Direct.CompleteCleanup(generation, fallback),
        )
        if (!completed.accepted) return ConnectionStep(current, finalized.effects, accepted = false)
        val release = if (completed.state.suppressReconnect) closeHandle() else emptyList()
        return ConnectionStep(
            completed.state,
            finalized.effects + completed.effects +
                listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.ClearLinkParameters) +
                release + ConnectionEffect.Announce,
            accepted = true,
        )
    }

    /** 게시 없이 generation 만 종결한다 — 공개 이유는 고정된 원인에서만 파생한다. */
    fun completeWithoutPublish(state: ConnectionCoreState, generation: Long, fallback: TerminalCause): ConnectionStep {
        val finalized = finalizeCause(state, generation, fallback, status = 0)
        val completed = ConnectionCore.step(finalized.state, ConnectionEvent.Direct.CompleteCleanup(generation, fallback))
        return ConnectionStep(completed.state, finalized.effects + completed.effects, completed.accepted)
    }

    /** 즉시 종결 — 먼저 고정된 원인이 있으면 그 원인이 이긴다. */
    fun terminate(state: ConnectionCoreState, generation: Long, cause: TerminalCause): ConnectionStep {
        val adapterLost = cause.reason == TerminalCleanupReason.AdapterUnavailable ||
            cause.reason == TerminalCleanupReason.PermissionDenied
        // 어댑터 이탈 스윕은 플랫폼 재연결 대기 중(이미 Disconnected)인 세션도 끝낸다 — close 와 같은 경로.
        val waited = if (adapterLost) endPlatformWait(state, generation, cause) else null
        val ended = if (waited != null && waited.accepted) {
            waited
        } else {
            // 어댑터가 죽은 링크의 handle 은 OS 대기용으로 남기지 않는다(복귀 재연결은 새 handle 로 건다).
            cleanupCompleted(if (adapterLost) state.copy(suppressReconnect = true) else state, generation, cause, status = 0)
        }
        if (!ended.accepted) return ended
        // autoConnect 세션이 어댑터 꺼짐으로 끝났으면 어댑터 복귀 때 한 번 다시 건다. 요청 원인이 아니라 고정된
        // 최종 원인으로 판정한다 — 먼저 고정된 사용자 해제가 이긴 generation 은 복귀 재연결 대상이 아니다.
        val relink = ended.state.autoConnect &&
            ended.state.terminalCause?.reason == TerminalCleanupReason.AdapterUnavailable
        return if (relink) ended.copy(state = ended.state.copy(relinkAfterAdapter = true)) else ended
    }

    /**
     * 플랫폼 재연결 대기 중인 세션을 [cause] 로 끝낸다 — 사용자 해제·client close·어댑터 이탈 스윕의 공유 경로.
     * 이미 Disconnected 인 generation 이 대기 중일 때만 동작하고, 대기가 없으면 원래 원인을 유지한 채 거절한다.
     */
    fun endPlatformWait(state: ConnectionCoreState, generation: Long, cause: TerminalCause): ConnectionStep {
        if (generation != state.generation || state.stage != ConnectionStage.Disconnected) {
            return ConnectionCore.rejected(state)
        }
        if (!ConnectionCore.awaitingPlatformRelink(state)) return ConnectionCore.rejected(state)
        // 대기 종료와 원인 대체를 한 번에 게시한다 — 중간에 옛 원인의 Disconnected 가 보이지 않는다.
        val superseded = ConnectionCore.step(
            state.copy(suppressReconnect = true, platformWait = false),
            ConnectionEvent.Direct.SupersedePlatformWait(generation, cause),
        )
        if (!superseded.accepted) return superseded
        return ConnectionStep(
            superseded.state,
            superseded.effects + listOf(
                // 대기 취소 시작 실패는 진단으로만 남기고 handle 은 그대로 닫는다(스윕 루프를 끊지 않는다).
                ConnectionEffect.DisconnectLink(ConnectionEvent.DisconnectStartFailed(generation, null)),
            ) + closeHandle() + ConnectionEffect.Announce,
            accepted = true,
        )
    }

    /** 사용자 해제. */
    fun disconnectRequested(state: ConnectionCoreState, event: ConnectionEvent.DisconnectRequested): ConnectionStep {
        val base = state.copy(relinkAfterAdapter = false)
        val cause = TerminalCause(TerminalCleanupReason.UserRequested, null)
        // 플랫폼 재연결 대기 중(이미 Disconnected)이면 사용자 해제가 대기를 끝낸다 — Disconnected(Requested).
        val waited = endPlatformWait(base, base.generation, cause)
        if (waited.accepted) return waited
        // 이미 끝났고 대기도 없다 — 대기가 아닌 종결 generation 의 handle 은 이미 닫혔다.
        if (base.stage == ConnectionStage.Disconnected) {
            return ConnectionCore.accepted(base.copy(suppressReconnect = true))
        }
        val begun = beginCleanup(base, cause, suppressReconnect = true, event.disconnectCleanupMillis)
        // connect 가 cold-cache 재발견을 기다리는 중이면 그 재발견을 지금 끝낸다. gatt 가 없어 플랫폼 콜백이 오지
        // 않으니, 끝내지 않으면 재발견 상한까지 Disconnecting·스캔이 남는다.
        val tail = if (event.hasHandle) emptyList() else listOf(ConnectionEffect.EndRediscovery)
        return ConnectionStep(begun.state, begun.effects + tail, begun.accepted)
    }

    /**
     * client close — 세션을 `Disconnected(ClientClosed)` 로 끝내고 platform handle 을 닫는다. 플랫폼 재연결 대기 중인
     * generation 은 ClientClosed 로 끝나고, 대기가 없는 이미 종결된 generation 은 원래 원인을 유지한다.
     * 연결 시도 전(generation 0) 세션은 닫힘이 첫 원인이다. close 는 종착이라 재연결을 억제한다.
     */
    fun close(state: ConnectionCoreState): ConnectionStep {
        val base = state.copy(relinkAfterAdapter = false)
        val generation = base.generation
        val closed = TerminalCause(TerminalCleanupReason.ClientClosed, null)
        val waited = endPlatformWait(base, generation, closed)
        // endPlatformWait 가 이미 handle 을 닫았다 — 두 번 닫지 않는다.
        if (waited.accepted) return waited
        val superseded = ConnectionCore.step(
            base.copy(suppressReconnect = true),
            ConnectionEvent.Direct.SupersedeForClose(generation),
        )
        val completed = completeWithoutPublish(superseded.state, generation, closed)
        // 연결 시도 전(고정된 원인 없음) 세션은 종결할 generation 이 없다 — 닫힘이 첫 원인이다.
        // 이미 종결된 generation 의 원인은 바꾸지 않는다(first wins).
        val idle = if (!completed.accepted && !superseded.accepted) {
            ConnectionCore.step(completed.state, ConnectionEvent.Direct.CloseIdle)
        } else {
            ConnectionCore.accepted(completed.state)
        }
        val announce = if (generation > 0L && (superseded.accepted || completed.accepted)) {
            listOf(ConnectionEffect.Announce)
        } else {
            emptyList()
        }
        return ConnectionStep(
            idle.state,
            superseded.effects + completed.effects + idle.effects + announce + closeHandle(),
            accepted = true,
        )
    }

    /** 어댑터 이탈 스윕의 세션 몫. 이미 끝난 세션은 조용히(원인 유지), 대기 중이면 어댑터 원인으로 대기를 끝낸다. */
    fun adapterLost(state: ConnectionCoreState, adapter: AdapterState): ConnectionStep {
        if (state.stage == ConnectionStage.Disconnected && !ConnectionCore.awaitingPlatformRelink(state)) {
            return ConnectionCore.rejected(state)
        }
        val failure = FailureMapper.fromAdapterState(adapter).copy(context = context(state))
        val reason = if (adapter is AdapterState.Unauthorized) {
            TerminalCleanupReason.PermissionDenied
        } else {
            TerminalCleanupReason.AdapterUnavailable
        }
        val ended = terminate(state, state.generation, TerminalCause(reason, failure))
        return ended.copy(effects = listOf(diagnostic(Severity.Warning, failure)) + ended.effects)
    }

    /**
     * GATT 작업 쪽이 세션을 끝내야 하는 실패를 확정했다. 게시 전에 generation 을 종결해 두어 disconnect 가 콜백을
     * 동기로 재진입시켜도 원인이 PlatformDisconnected 로 먼저 확정되거나 Ready 에 남지 않게 한다.
     */
    fun operationInvalidated(state: ConnectionCoreState, event: ConnectionEvent.OperationInvalidated): ConnectionStep {
        val base = if (event.suppressReconnect) state.copy(suppressReconnect = true) else state
        val completed = completeWithoutPublish(
            base,
            base.generation,
            TerminalCause(TerminalCleanupReason.OperationInvalidated, event.failure),
        )
        return ConnectionStep(
            completed.state,
            listOf(diagnostic(event.severity, event.failure)) + completed.effects + listOf(
                ConnectionEffect.DisconnectLink(null),
                ConnectionEffect.ReleaseHandle,
                ConnectionEffect.ClearLinkParameters,
                ConnectionEffect.Announce,
            ),
            accepted = true,
        )
    }

    /** 작업 시작에서 권한 상실이 확인됐다 — 세션 자원 실패라 정리한다. */
    fun operationPermissionLost(
        state: ConnectionCoreState,
        failure: BleFailure,
        disconnectCleanupMillis: Long,
    ): ConnectionStep = beginCleanup(
        state,
        TerminalCause(TerminalCleanupReason.PermissionDenied, failure),
        suppressReconnect = true,
        disconnectCleanupMillis = disconnectCleanupMillis,
    )

    /**
     * 세션 소유 코루틴에서 잡히지 않은 예외 — 그 세션만 `Disconnected(Failed)` 로 끝내고 handle 을 한 번 닫는다.
     * 플랫폼 재연결 대기 중이면 대기를 끝내고, 이미 끝났는데 handle 만 남았으면 handle 만 닫는다.
     */
    fun internalFailure(state: ConnectionCoreState, failure: BleFailure): ConnectionStep {
        if (state.stage != ConnectionStage.Disconnected) {
            return operationInvalidated(
                state,
                ConnectionEvent.OperationInvalidated(failure, suppressReconnect = true, severity = Severity.Error),
            )
        }
        val diag = diagnostic(Severity.Error, failure)
        val waited = endPlatformWait(state, state.generation, TerminalCause(TerminalCleanupReason.OperationInvalidated, failure))
        if (waited.accepted) return waited.copy(effects = listOf(diag) + waited.effects)
        if (!state.platformHandleOpen) return ConnectionCore.accepted(state, listOf(diag))
        return ConnectionCore.accepted(
            state.copy(suppressReconnect = true),
            listOf(diag) + closeHandle() + ConnectionEffect.Announce,
        )
    }

    /** platform handle 이 닫혔다 — 열림 표시와 플랫폼 재연결 대기를 내리고, 대기였으면 공개 상태를 다시 파생한다. */
    fun handleReleased(state: ConnectionCoreState): ConnectionStep {
        val republish = if (state.platformWait) listOf(ConnectionEffect.Republish) else emptyList()
        return ConnectionCore.accepted(state.copy(platformHandleOpen = false, platformWait = false), republish)
    }
}
