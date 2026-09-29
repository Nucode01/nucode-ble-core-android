package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.ConnectOptions

/**
 * 이번 연결의 link deadline. 명시 [ConnectOptions.timeoutMillis] 가 있으면 그 값, 없으면 autoConnect 는
 * deadline 없음(null — OS 무기한 대기가 autoConnect 의 의미, 취소는 disconnect()), 아니면 [defaultLinkMillis].
 * 중복 connect 판정([ConnectionStart.admit])도 이 값으로 비교한다.
 */
internal fun ConnectOptions.linkDeadlineMillis(defaultLinkMillis: Long): Long? =
    timeoutMillis ?: if (autoConnect) null else defaultLinkMillis

/** 이미 세션이 있을 때의 중복 connect 판정 결과([ConnectionStart.admit]). */
internal sealed interface ConnectAdmission {
    /** 새 연결을 시작한다. */
    data object Proceed : ConnectAdmission

    /** 같은 effective options 로 이미 진행·대기 중 — 기존 연결·대기에 합류한다(no-op). */
    data object Joined : ConnectAdmission

    /** 충돌 — 호출자가 [code]·[retryability] 로 실패를 만들어 게시하고 던진다. */
    data class Conflict(val code: String, val retryability: Retryability) : ConnectAdmission
}

/** cold-cache 재발견 스캔의 결과 사실. */
internal enum class RediscoveryFact { Found, Unavailable, NotFound }

/** driver.connect 시작 실패의 종류 — 종결 원인 분류에 쓴다. */
internal enum class LinkStartFailureKind { Rejected, PermissionDenied }

/**
 * 연결 시작 정책(§4) — 중복 connect 판정, 시도 시작, 재발견 결과, 시작 실패·취소, 어댑터 복귀 재연결을
 * `(상태, 사건) → (새 상태, 할 일 목록)` 으로 판단한다. suspend 흐름(재발견 대기·driver.connect)과 예외 변환은
 * Runtime 이 하고, 각 await 뒤 사실을 사건으로 넣어 돌려받은 할 일([ConnectionEffect.ConnectLink]·
 * [ConnectionEffect.FailConnectCall] 등)대로 진행한다.
 */
internal object ConnectionStart {
    private fun diagnostic(severity: Severity, failure: BleFailure) = ConnectionEffect.Diagnostic(severity, failure)

    /**
     * 이미 세션이 있을 때의 중복 connect 판정(순수 결정, 상태는 바꾸지 않는다). 실패 구성·게시·throw 는 호출자가 한다.
     * - Disconnecting: 정리 중에는 새 connect 를 시작할 수 없다.
     * - Connecting/DiscoveringServices/Configuring/Ready: 같은 effective options 면 합류, 아니면 충돌.
     * - Disconnected 이고 플랫폼 재연결 대기(autoConnect handle 열림) 중이면 같은 handle 위에 두 번째 연결을
     *   시작하지 않고 기존 대기를 유지한다(docs/v1 connection-lifecycle "duplicate connect").
     */
    fun admit(state: ConnectionCoreState, options: ConnectOptions, defaultLinkMillis: Long): ConnectionStep {
        val admission = when (state.stage) {
            ConnectionStage.Disconnecting -> ConnectAdmission.Conflict(
                code = "CONNECT_DURING_DISCONNECT",
                // 정리는 곧 끝난다(Disconnected) — 그 뒤 다시 걸면 된다.
                retryability = Retryability.TransientMayHelp,
            )

            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            -> if (sameEffectiveOptions(state, options, defaultLinkMillis)) {
                ConnectAdmission.Joined
            } else {
                ConnectAdmission.Conflict(code = "CONNECTION_OPTIONS_CONFLICT", retryability = Retryability.Never)
            }

            ConnectionStage.Disconnected -> when {
                !ConnectionCore.awaitingPlatformRelink(state) -> ConnectAdmission.Proceed
                sameEffectiveOptions(state, options, defaultLinkMillis) -> ConnectAdmission.Joined
                else -> ConnectAdmission.Conflict(code = "CONNECTION_OPTIONS_CONFLICT", retryability = Retryability.Never)
            }
        }
        return ConnectionCore.accepted(state, listOf(ConnectionEffect.ConnectAdmitted(admission)))
    }

    /**
     * timeout 은 link stage 에서만 실제 lifecycle 동작을 바꾼다. discovery/configuration/Ready
     * 단계에서는 이미 소진된 timeout 을 options mismatch 로 취급하지 않는다.
     */
    private fun sameEffectiveOptions(state: ConnectionCoreState, requested: ConnectOptions, defaultLinkMillis: Long): Boolean {
        if (state.autoConnect != requested.autoConnect || state.requestedMtu != requested.requestedMtu) return false
        if (state.stage != ConnectionStage.Connecting) return true
        // autoConnect 첫 연결은 deadline 이 없다(null) — 같은 규칙(linkDeadlineMillis)으로 비교한다.
        return state.deadline?.timeoutMillis == requested.linkDeadlineMillis(defaultLinkMillis)
    }

    /**
     * 새 연결 시도를 연다 — Connecting 게시·link deadline, 이전 generation 작업 정리, 이전 handle 정리, 이번 시도의
     * 모드(재연결 억제·autoConnect·요청 MTU) 확정. 모드는 driver.connect 호출 전에 서야 한다(동기 재진입 콜백이
     * 낡은 값을 보는 창이 없게). 마지막 할 일이 다음 진행을 정한다 — [rediscover] 면 재발견, 아니면 링크 시작.
     */
    fun beginConnect(
        state: ConnectionCoreState,
        options: ConnectOptions,
        defaultLinkMillis: Long,
        rediscover: Boolean,
    ): ConnectionStep {
        val begun = ConnectionCore.step(
            state,
            ConnectionEvent.Direct.BeginConnection(options.linkDeadlineMillis(defaultLinkMillis)),
        )
        // 이전 시도가 사용자 disconnect()·타임아웃 포기·시작 실패 중 무엇으로든 재연결 억제를 남겼더라도 새 시도는
        // 물려받지 않는다 — autoConnect 가 아니면 이 handle 은 처음부터 OS 대기용이 아니다. 새 시도는 어댑터 복귀
        // 재연결 대상도 아니다(이 시도 자체가 그 재연결이거나 사용자가 새로 건 것이다).
        val next = begun.state.copy(
            platformHandleOpen = false,
            platformWait = false,
            suppressReconnect = !options.autoConnect,
            autoConnect = options.autoConnect,
            relinkAfterAdapter = false,
            requestedMtu = options.requestedMtu,
        )
        val tail = if (rediscover) ConnectionEffect.Rediscover else ConnectionEffect.ConnectLink(options.autoConnect)
        return ConnectionCore.accepted(
            next,
            begun.effects + listOf(
                ConnectionEffect.Announce,
                // 이전 connection generation 의 waiter 를 먼저 끝낸 뒤에만 새 generation 을 연다.
                ConnectionEffect.InvalidateOperations,
                ConnectionEffect.BeginOperationGeneration,
                // stale gatt 를 close 없이 덮어쓰면 프로세스당 GATT 클라이언트 상한을 소모한다.
                ConnectionEffect.ReleaseStaleHandle,
            ) + (if (state.platformWait) listOf(ConnectionEffect.Republish) else emptyList()) + tail,
        )
    }

    /**
     * 재발견 스캔이 끝났다. 그 사이 generation 이 끝났으면 그쪽이 정리한 것이라 연결을 시작하지 않고 호출을 끝낸다.
     * 찾지 못한 경우 autoConnect 는 진단만 남기고 OS 대기로 들어간다("무기한 대기" 계약).
     */
    fun rediscoveryFinished(state: ConnectionCoreState, event: ConnectionEvent.RediscoveryFinished): ConnectionStep {
        if (!ConnectionCore.accepts(state, event.generation, ConnectionStage.Connecting)) {
            return rediscoveryEnded(state, event)
        }
        val link = ConnectionEffect.ConnectLink(state.autoConnect)
        return when (event.outcome) {
            RediscoveryFact.Found -> ConnectionCore.accepted(state, listOf(link))
            // 폴백: 직접 연결.
            RediscoveryFact.Unavailable ->
                ConnectionCore.accepted(state, listOfNotNull(event.failure?.let { diagnostic(Severity.Warning, it) }) + link)
            RediscoveryFact.NotFound -> {
                val failure = requireNotNull(event.failure) { "NotFound 에는 실패 값이 필요하다" }
                if (state.autoConnect) {
                    ConnectionCore.accepted(state, listOf(diagnostic(Severity.Warning, failure), link))
                } else {
                    val completed = ConnectionCleanup.completeWithoutPublish(
                        state.copy(suppressReconnect = true),
                        event.generation,
                        TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
                    )
                    ConnectionCore.accepted(
                        completed.state,
                        completed.effects + listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.FailConnectCall(failure)),
                    )
                }
            }
        }
    }

    /**
     * 재발견 중 disconnect()/sweep/close() 가 generation 을 끝냈다. 재발견 중 disconnect() 는 gatt 가 아직 없어
     * driver.disconnect() 가 no-op 이라 플랫폼 콜백이 오지 않는다 — Disconnecting 에 남아 정리 기한까지 기다리지
     * 않고 재발견 resume 이 온 이 시점에 직접 끝낸다. 연결이 시작되지 않았으니 정상 반환하지 않는다:
     * 고정된 종결 실패로 던지고, 사용자 해제(실패 없음)는 이 connect 가 무효화된 것이다. close 는 반환 직후
     * Runtime 의 closed 검사가 던진다.
     */
    private fun rediscoveryEnded(state: ConnectionCoreState, event: ConnectionEvent.RediscoveryFinished): ConnectionStep {
        val completed = if (state.generation == event.generation && state.stage == ConnectionStage.Disconnecting) {
            ConnectionCleanup.cleanupCompleted(
                state,
                event.generation,
                TerminalCause(TerminalCleanupReason.UserRequested, null),
                status = 0,
            )
        } else {
            ConnectionCore.accepted(state)
        }
        val cause = completed.state.terminalCause.takeIf { completed.state.generation == event.generation }
        val call = when {
            cause?.failure != null -> ConnectionEffect.FailConnectCall(cause.failure)
            cause?.reason == TerminalCleanupReason.ClientClosed -> ConnectionEffect.EndConnectCall
            else -> ConnectionEffect.FailConnectCall(event.invalidatedFailure)
        }
        return ConnectionCore.accepted(completed.state, completed.effects + call)
    }

    /** 호출자 취소 — 열린 generation 을 닫지 않으면 gatt 없는 Connecting 이 link deadline 까지 남는다. 취소는 삼키지 않는다. */
    fun callCancelled(state: ConnectionCoreState, generation: Long): ConnectionStep {
        if (!ConnectionCore.accepts(state, generation, ConnectionStage.Connecting)) return ConnectionCore.rejected(state)
        val completed = ConnectionCleanup.completeWithoutPublish(
            state.copy(suppressReconnect = true),
            generation,
            TerminalCause(TerminalCleanupReason.UserRequested, null),
        )
        return ConnectionCore.accepted(
            completed.state,
            completed.effects + listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.Announce),
        )
    }

    /** driver.connect 시작 실패 — 시작 실패는 OS 대기 대상이 아니라 handle 을 남기지 않고, 게시는 호출자 몫이다. */
    fun linkStartFailed(state: ConnectionCoreState, event: ConnectionEvent.LinkStartFailed): ConnectionStep {
        val reason = when (event.kind) {
            LinkStartFailureKind.Rejected -> TerminalCleanupReason.LinkStartRejected
            LinkStartFailureKind.PermissionDenied -> TerminalCleanupReason.PermissionDenied
        }
        val completed = ConnectionCleanup.completeWithoutPublish(
            state.copy(suppressReconnect = true),
            event.generation,
            TerminalCause(reason, event.failure),
        )
        return ConnectionCore.accepted(
            completed.state,
            completed.effects + listOf(ConnectionEffect.InvalidateOperations, ConnectionEffect.FailConnectCall(event.failure)),
        )
    }

    /** driver.connect 가 성공해 platform handle 이 열렸다. */
    fun linkStarted(state: ConnectionCoreState): ConnectionStep =
        ConnectionCore.accepted(state.copy(platformHandleOpen = true))

    /**
     * connect 호출이 시작 실패로 던지려 한다 — 유령 Connecting 방지: 상태·스트림 둘 다 종결시킨다. 보통은 시작 경로가
     * throw 전에 이 generation 을 이미 종결(원인 고정)했으므로 게시만 남는다. 아직 열려 있으면 종결이 고정·게시까지 한다.
     */
    fun callFailed(state: ConnectionCoreState, failure: BleFailure): ConnectionStep {
        val base = state.copy(suppressReconnect = true)
        val ended = ConnectionCleanup.terminate(
            base,
            base.generation,
            TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
        )
        if (ended.accepted) return ended
        return ConnectionCore.accepted(ended.state, ended.effects + ConnectionEffect.Announce)
    }

    /**
     * 어댑터 복귀 — 어댑터 꺼짐 스윕이 끝낸 autoConnect 세션(사용자 해제·close 가 아직 내리지 않음)에 autoConnect 연결을
     * 한 번 건다(새 handle). 예약·재시도는 없다. 세션 조건과 함께 어댑터가 아직 Ready 인지도 본다.
     */
    fun adapterReturned(state: ConnectionCoreState, event: ConnectionEvent.AdapterReturned): ConnectionStep {
        if (!state.relinkAfterAdapter || state.stage != ConnectionStage.Disconnected || !event.adapterReady) {
            return ConnectionCore.rejected(state)
        }
        return beginConnect(
            state,
            ConnectOptions(autoConnect = true, requestedMtu = state.requestedMtu),
            event.defaultLinkMillis,
            event.rediscover,
        )
    }
}
