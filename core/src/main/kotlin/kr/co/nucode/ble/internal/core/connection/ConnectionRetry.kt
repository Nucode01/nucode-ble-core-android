package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.Severity

/**
 * Android 첫 직접 연결 재시도(contract/states.md §2). 링크 수립 실패(HCI 0x3E)는 연결 요청 직후의 status 133 으로 온다.
 * autoReconnect=false 연결이 `Connecting`(링크 전)에서 그 133 을 요청 뒤 [FAST_FAILURE_WINDOW_MILLIS] 안에 받으면 handle 을
 * 닫고 같은 generation·같은 link deadline 안에서 다시 건다. 단계·기한은 그대로라 공개 상태는 `Connecting` 하나이고,
 * 재시도마다 그 133 을 진단으로만 낸다. 시도가 모두 빠른 133 이면 마지막 133 이, 기한이 다하면 기한 정책이 끝낸다.
 */
internal object ConnectionRetry {
    /** 연결 요청 뒤 이 시간 안에 온 133 만 다시 건다. 남은 link deadline 도 이만큼 있어야 다시 건다. */
    const val FAST_FAILURE_WINDOW_MILLIS = 5_000L

    /** 다시 거는 최대 횟수(시도는 이보다 하나 많다). */
    const val MAX_RETRIES = 2

    /** Android GATT_ERROR — 링크 수립 실패가 이 status 로 온다. */
    const val GATT_ERROR = 133

    /** [event] 의 실패가 빠른 133 이면 다시 거는 단계를 돌려준다. 아니면 null 이고 호출자가 지금 규칙대로 끝낸다. */
    fun retry(state: ConnectionCoreState, event: ConnectionEvent.LinkStateChanged, failure: BleFailure): ConnectionStep? {
        val attempt = state.linkAttempt ?: return null
        val requestedAt = attempt.requestedAtMillis ?: return null
        val deadline = state.deadline ?: return null
        val remaining = deadline.timeoutMillis - (event.atMillis - attempt.connectingSinceMillis)
        val eligible = event.status == GATT_ERROR &&
            !state.autoConnect &&
            state.terminalCause == null &&
            ConnectionCore.accepts(state, event.generation, ConnectionStage.Connecting) &&
            attempt.retries < MAX_RETRIES &&
            event.atMillis - requestedAt <= FAST_FAILURE_WINDOW_MILLIS &&
            remaining >= FAST_FAILURE_WINDOW_MILLIS
        if (!eligible) return null
        // 새 handle 의 LinkStarted 가 열림 표시와 요청 시각을 다시 세운다.
        val next = state.copy(
            platformHandleOpen = false,
            linkAttempt = attempt.copy(requestedAtMillis = null, retries = attempt.retries + 1),
        )
        return ConnectionCore.accepted(
            next,
            listOf(
                ConnectionEffect.Diagnostic(Severity.Warning, failure, event.atMillis - requestedAt),
                ConnectionEffect.ReleaseStaleHandle,
                ConnectionEffect.RetryLink(event.generation),
            ),
        )
    }

    /**
     * [event] 가 현재 generation 의 `Connecting` 링크 시도 결과면 이번 시도의 연결 요청 뒤 경과 ms 다. 요청 전이거나 그 밖이면 null.
     * 진단에만 싣는다 — 다음 lab 이 '연결 요청 → 133' 지연을 로그로 잰다.
     */
    fun requestElapsedMillis(state: ConnectionCoreState, event: ConnectionEvent.LinkStateChanged): Long? {
        val requestedAt = state.linkAttempt?.requestedAtMillis ?: return null
        if (!ConnectionCore.accepts(state, event.generation, ConnectionStage.Connecting)) return null
        return event.atMillis - requestedAt
    }

    /** 다시 건 driver.connect 가 던졌다 — connect() 호출자는 이미 반환했으니 그 시작 실패로 고정하고 게시한다. */
    fun startFailed(state: ConnectionCoreState, event: ConnectionEvent.RetryStartFailed): ConnectionStep {
        if (!ConnectionCore.accepts(state, event.generation, ConnectionStage.Connecting)) return ConnectionCore.rejected(state)
        val ended = ConnectionCleanup.terminate(
            state.copy(suppressReconnect = true),
            event.generation,
            TerminalCause(event.kind.cleanupReason, event.failure),
        )
        if (!ended.accepted) return ended
        return ended.copy(effects = listOf(ConnectionEffect.Diagnostic(Severity.Error, event.failure)) + ended.effects)
    }
}
