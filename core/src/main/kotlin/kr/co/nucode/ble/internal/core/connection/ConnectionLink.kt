package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.Severity

/**
 * 링크 콜백 정책(§2·§3·§5) — 링크 상태·서비스 탐색·MTU·복원 완료·플랫폼 재연결 사건을
 * `(상태, 사건) → (새 상태, 할 일 목록)` 으로 판단한다. 출처(handle) 확인과 플랫폼 status → 실패 값 변환은
 * Runtime 이 하고, 여기서는 단계·원인·재연결 억제만 정한다.
 */
internal object ConnectionLink {
    /** MTU 협상/요청 실패 시 폴백 값 — BLE 표준 기본 ATT_MTU. */
    const val DEFAULT_MTU = 23

    private const val PERMISSION_DENIED = "PERMISSION_DENIED"

    private fun diagnostic(severity: Severity, failure: BleFailure) = ConnectionEffect.Diagnostic(severity, failure)

    private fun withDiagnostic(diag: ConnectionEffect, next: ConnectionStep) =
        ConnectionStep(next.state, listOf(diag) + next.effects, next.accepted)

    fun linkStateChanged(state: ConnectionCoreState, event: ConnectionEvent.LinkStateChanged): ConnectionStep {
        val failure = event.failure
        if (failure != null) return linkStatusFailed(state, event, failure)
        return when (event.link) {
            LinkFact.Up -> linkUp(state, event)
            LinkFact.Down -> linkDown(state, event)
            // 진행 중 표시(Connecting·Disconnecting)와 모르는 값은 성공 status 에서 상태를 바꾸지 않는다.
            LinkFact.Connecting, LinkFact.Disconnecting, LinkFact.Unknown -> ConnectionCore.accepted(state)
        }
    }

    /**
     * 실패 status. STATE_DISCONNECTED 만 어느 활성 단계에서든 종결 사실이다. 그 밖의 실패는 링크 시도(Connecting)의
     * 결과일 때만 실패다 — readiness 가 진행된 뒤 늦게 온 것은 단계와 기한을 되돌리지 않는다.
     */
    private fun linkStatusFailed(
        state: ConnectionCoreState,
        event: ConnectionEvent.LinkStateChanged,
        failure: BleFailure,
    ): ConnectionStep {
        val diag = diagnostic(Severity.Error, failure)
        if (event.link != LinkFact.Down && !ConnectionCore.accepts(state, event.generation, ConnectionStage.Connecting)) {
            return ConnectionStep(state, listOf(diag), accepted = false)
        }
        // Ready 전 실패는 첫 연결이든 플랫폼 재연결 generation 이든 handle 을 닫는다. Ready·Disconnecting 은 제외다.
        val preReadyFailure = state.stage != ConnectionStage.Ready && state.stage != ConnectionStage.Disconnecting
        val reason = if (preReadyFailure) {
            TerminalCleanupReason.LinkStatusFailure
        } else {
            TerminalCleanupReason.PlatformDisconnected
        }
        val cause = TerminalCause(reason, failure)
        // 먼저 고정된 원인(예: 사용자 해제)이 있으면 이 실패는 진단으로만 남는다.
        val latched = ConnectionCore.step(state, ConnectionEvent.Direct.LatchIfAbsent(event.generation, cause)).state
        val marked = if (preReadyFailure) latched.copy(suppressReconnect = true) else latched
        val next = if (event.link == LinkFact.Down) {
            ConnectionCleanup.cleanupCompleted(marked, event.generation, cause, event.status)
        } else {
            ConnectionCleanup.beginCleanup(marked, cause, preReadyFailure, event.disconnectCleanupMillis)
        }
        return withDiagnostic(diag, next)
    }

    private fun linkUp(state: ConnectionCoreState, event: ConnectionEvent.LinkStateChanged): ConnectionStep {
        val moved = ConnectionCore.step(
            state,
            ConnectionEvent.Direct.Transition(
                event.generation,
                ConnectionStage.Connecting,
                ConnectionStage.DiscoveringServices,
                event.discoveryMillis,
            ),
        )
        if (!moved.accepted) return moved
        return ConnectionStep(
            moved.state,
            moved.effects + listOf(
                ConnectionEffect.BeginOperationGeneration,
                ConnectionEffect.Announce,
                ConnectionEffect.LinkUp(event.generation),
            ),
            accepted = true,
        )
    }

    /**
     * 성공 status 의 STATE_DISCONNECTED. Ready 전(Connecting..Configuring) 해제는 링크 실패다 — OS 대기로 돌아가지 않고
     * handle 을 닫는다. Disconnecting 은 사용자 해제 경로라 제외한다. 고정된 원인은 지우지 않는다.
     */
    private fun linkDown(state: ConnectionCoreState, event: ConnectionEvent.LinkStateChanged): ConnectionStep {
        val stage = state.stage
        val preReady = event.generation == state.generation &&
            stage != ConnectionStage.Ready &&
            stage != ConnectionStage.Disconnecting &&
            stage != ConnectionStage.Disconnected
        val marked = if (preReady) state.copy(suppressReconnect = true) else state
        return ConnectionCleanup.cleanupCompleted(
            marked,
            event.generation,
            TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
            event.status,
        )
    }

    /** 플랫폼 재연결 대기 handle 에 OS 가 다시 붙였다 — 대기를 끝내고 링크 기한 없는 새 generation 을 연다. */
    fun platformRelinkUp(state: ConnectionCoreState): ConnectionStep {
        if (!ConnectionCore.awaitingPlatformRelink(state)) return ConnectionCore.rejected(state)
        // 새 generation 이 Ready 전에 실패하면 첫 연결 실패와 같은 규칙으로 handle 을 닫는다(대기로 돌아가지 않는다).
        val begun = ConnectionCore.step(state.copy(platformWait = false), ConnectionEvent.Direct.BeginPlatformRelink)
        return ConnectionStep(begun.state, begun.effects + ConnectionEffect.Republish, begun.accepted)
    }

    fun servicesDiscovered(state: ConnectionCoreState, event: ConnectionEvent.ServicesDiscovered): ConnectionStep {
        val failure = event.failure
        if (failure != null) {
            if (!ConnectionCore.inDiscovery(state, event.generation)) return ConnectionCore.rejected(state)
            val begun = ConnectionCleanup.beginCleanup(
                state,
                TerminalCause(TerminalCleanupReason.ServiceDiscoveryStatusFailure, failure),
                suppressReconnect = true,
                disconnectCleanupMillis = event.disconnectCleanupMillis,
            )
            return withDiagnostic(diagnostic(Severity.Error, failure), begun)
        }
        return ConnectionCore.step(
            state,
            ConnectionEvent.Direct.Transition(
                event.generation,
                ConnectionStage.DiscoveringServices,
                ConnectionStage.Configuring,
                event.configurationMillis,
            ),
        )
    }

    fun discoveryStartFailed(state: ConnectionCoreState, event: ConnectionEvent.DiscoveryStartFailed): ConnectionStep {
        val reason = if (event.failure.code.value == PERMISSION_DENIED) {
            TerminalCleanupReason.PermissionDenied
        } else {
            TerminalCleanupReason.ServiceDiscoveryStartRejected
        }
        val begun = ConnectionCleanup.beginCleanup(
            state,
            TerminalCause(reason, event.failure),
            suppressReconnect = true,
            disconnectCleanupMillis = event.disconnectCleanupMillis,
        )
        return withDiagnostic(diagnostic(Severity.Error, event.failure), begun)
    }

    /** MTU 는 Configuring 인 현재 generation 의 사실일 때만 받는다. 협상 실패여도 링크는 살아 있어 기본값으로 진행한다. */
    fun mtuSettled(state: ConnectionCoreState, event: ConnectionEvent.MtuSettled): ConnectionStep {
        if (!ConnectionCore.inConfiguring(state, event.generation)) return ConnectionCore.rejected(state)
        val failure = event.failure
        val mtu = if (failure == null) event.mtu else DEFAULT_MTU
        return ConnectionCore.accepted(
            state,
            listOfNotNull(failure?.let { diagnostic(Severity.Warning, it) }) + listOf(
                ConnectionEffect.ApplyMtu(mtu),
                ConnectionEffect.RestoreSubscriptions(event.generation),
            ),
        )
    }

    /** requestMtu 호출 자체 실패 — 권한 상실이면 정리하고, 아니면 협상 실패와 같은 기본값 경로로 진행한다. */
    fun mtuRequestFailed(state: ConnectionCoreState, event: ConnectionEvent.MtuRequestFailed): ConnectionStep {
        val diag = diagnostic(Severity.Warning, event.failure)
        if (event.failure.code.value == PERMISSION_DENIED) {
            val begun = ConnectionCleanup.beginCleanup(
                state,
                TerminalCause(TerminalCleanupReason.PermissionDenied, event.failure),
                suppressReconnect = true,
                disconnectCleanupMillis = event.disconnectCleanupMillis,
            )
            return withDiagnostic(diag, begun)
        }
        return ConnectionCore.accepted(
            state,
            listOf(
                diag,
                ConnectionEffect.ApplyMtu(DEFAULT_MTU),
                ConnectionEffect.RestoreSubscriptions(state.generation),
            ),
        )
    }

    /** 복원이 끝난 generation 을 Ready 로 — generation·단계를 다시 확인하고 전이한 뒤 게시한다. */
    fun restoreFinished(state: ConnectionCoreState, event: ConnectionEvent.RestoreFinished): ConnectionStep {
        val moved = ConnectionCore.step(
            state,
            ConnectionEvent.Direct.Transition(
                event.generation,
                ConnectionStage.Configuring,
                ConnectionStage.Ready,
                timeoutMillis = null,
            ),
        )
        if (!moved.accepted) return moved
        return ConnectionStep(moved.state, moved.effects + ConnectionEffect.Announce, accepted = true)
    }
}
