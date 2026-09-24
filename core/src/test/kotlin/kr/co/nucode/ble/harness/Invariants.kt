package kr.co.nucode.ble.harness

import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import org.junit.Assert.assertEquals

/** 불변식 하나의 위반(plan platform-harness v1 §접근 "불변식"). */
internal data class InvariantViolation(val id: String, val detail: String)

/**
 * 한 시나리오가 끝난 뒤 불변식 검사에 넘기는 기록.
 * [states]/[actives] 는 하네스가 받은 게시 순서 그대로다. 두 흐름의 교차 순서는 [timeline] 에,
 * StateFlow 가 합치지 않은 발행 순서는 [connEvents](`BleEvent.ConnStateChanged`)에 있다.
 */
internal data class ConnectionHistory(
    val states: List<ConnectionState>,
    val actives: List<Set<CharacteristicRef>>,
    /** connectionState·activeSubscriptions 게시를 도착 순서대로 합친 기록(I4). */
    val timeline: List<TimelinePoint>,
    /** `BleEvent.ConnStateChanged` 로 발행된 상태 순서(I3). */
    val connEvents: List<ConnectionState>,
    /** 사용자가 마지막으로 연 연결을 `disconnect()` 로 끝냈는가(I2). */
    val userDisconnected: Boolean,
    /** `advancePastAllDeadlines()` 뒤에 기록을 잘랐는가(I6). */
    val pastAllDeadlines: Boolean,
    /** I7 시나리오 메타: 사용자 해제 뒤 두 번째 connect 에서 CCCD write 가 몇 번 나갔는가. null 이면 I7 대상 아님. */
    val descriptorWritesAfterReconnect: Int? = null,
    /** I8 시나리오 메타: 시나리오 안에서 `close()` 를 부른 시점의 [states] 길이. null 이면 I8 대상 아님. */
    val closedAt: Int? = null,
    /** I9 시나리오 메타: 늦은 콜백을 넣은 실행의 세대 통계. null 이면 I9 대상 아님. */
    val lateRun: GenerationStats? = null,
    /** I9 시나리오 메타: 같은 시나리오를 늦은 콜백 없이 돌린 대조 실행의 세대 통계. */
    val controlRun: GenerationStats? = null,
)

/** I9 비교 단위 — 재연결 시작 횟수, Connecting 진입 수, Recovering attempt 순서, 최종 상태. */
internal data class GenerationStats(
    val retainedConnects: Int,
    val connectingEntries: Int,
    val recoveringAttempts: List<Int>,
    val last: ConnectionState?,
)

/** 연결 불변식 I1·I2·I3·I4·I6·I7·I8·I9 를 모두 검사한다(I5 는 스캔 — task 3). */
internal fun checkConnectionInvariants(h: ConnectionHistory): List<InvariantViolation> =
    listOfNotNull(checkI1(h), checkI2(h), checkI3(h), checkI4(h), checkI6(h), checkI7(h), checkI8(h), checkI9(h))

/** I1 첫 연결 시도 이후 `Disconnected(Initial)` 게시 없음. */
internal fun checkI1(h: ConnectionHistory): InvariantViolation? {
    val firstAttempt = h.states.indexOfFirst { it is ConnectionState.Connecting }
    if (firstAttempt < 0) return null
    val at = h.states.withIndex().firstOrNull { (i, s) ->
        i > firstAttempt && s == ConnectionState.Disconnected(BleDisconnectReason.Initial)
    } ?: return null
    return InvariantViolation("I1", "Disconnected(Initial) at #${at.index}")
}

/** I2 사용자 `disconnect()` 로 끝난 연결의 최종 상태는 `Disconnected(Requested)`. */
internal fun checkI2(h: ConnectionHistory): InvariantViolation? {
    if (!h.userDisconnected) return null
    val last = h.states.lastOrNull()
    if (last == ConnectionState.Disconnected(BleDisconnectReason.Requested)) return null
    return InvariantViolation("I2", "last=$last")
}

/**
 * I3 한 연결 종결에 종료 상태 발행은 한 번 — 연속된 Disconnected 발행은 위반.
 * StateFlow 는 같은 값을 합치므로 `ConnStateChanged` 발행 순서로 본다.
 */
internal fun checkI3(h: ConnectionHistory): InvariantViolation? {
    val at = h.connEvents.zipWithNext().indexOfFirst { (a, b) ->
        a is ConnectionState.Disconnected && b is ConnectionState.Disconnected
    }
    if (at < 0) return null
    return InvariantViolation("I3", "${h.connEvents[at]} -> ${h.connEvents[at + 1]}")
}

/** I4 activeSubscriptions 가 비어 있지 않으면 상태는 Configuring 또는 Ready — 결합 기록의 모든 점에서. */
internal fun checkI4(h: ConnectionHistory): InvariantViolation? {
    var state: ConnectionState? = null
    var active: Set<CharacteristicRef> = emptySet()
    for ((index, point) in h.timeline.withIndex()) {
        when (point) {
            is TimelinePoint.State -> state = point.state
            is TimelinePoint.Active -> active = point.refs
        }
        if (active.isEmpty() || state == null) continue
        if (state == ConnectionState.Configuring || state == ConnectionState.Ready) continue
        return InvariantViolation("I4", "active=${active.size} state=$state at #$index")
    }
    return null
}

/** I6 모든 deadline 이 지난 뒤 최종 상태는 Ready 또는 Disconnected(Recovering 포함 중간 상태에 멈춤 금지). */
internal fun checkI6(h: ConnectionHistory): InvariantViolation? {
    if (!h.pastAllDeadlines) return null
    val last = h.states.lastOrNull()
    if (last == ConnectionState.Ready || last is ConnectionState.Disconnected) return null
    return InvariantViolation("I6", "stuck=$last")
}

/** I7 사용자 해제 뒤 새 connect 는 이전 구독을 복원하지 않는다(CCCD write 없음, activeSubscriptions 비어 있음). */
internal fun checkI7(h: ConnectionHistory): InvariantViolation? {
    val writes = h.descriptorWritesAfterReconnect ?: return null
    val active = h.actives.lastOrNull().orEmpty()
    if (writes == 0 && active.isEmpty()) return null
    return InvariantViolation("I7", "cccdWrites=$writes active=${active.size}")
}

/**
 * I8 `close()` 뒤 최종 상태는 Disconnected(Recovering·Connecting·Disconnecting 등 중간 상태에 멈춤 금지)이고,
 * close 이후 종료 상태 게시는 많아야 한 번이다. 닫힌 런타임은 이벤트 버스를 닫으므로 connectionState 로 본다.
 */
internal fun checkI8(h: ConnectionHistory): InvariantViolation? {
    val closedAt = h.closedAt ?: return null
    val last = h.states.lastOrNull()
    if (last !is ConnectionState.Disconnected) return InvariantViolation("I8", "after close last=$last")
    val terminalsAfterClose = h.states.drop(closedAt).count { it is ConnectionState.Disconnected }
    if (terminalsAfterClose > 1) return InvariantViolation("I8", "terminal publishes after close=$terminalsAfterClose")
    return null
}

/**
 * I9 끝난 generation 의 늦은 종료 콜백은 generation 을 열거나 재시도 예산을 쓰지 않는다.
 * 같은 시나리오를 늦은 콜백 없이 돌린 대조 실행과 세대 통계가 같아야 한다.
 */
internal fun checkI9(h: ConnectionHistory): InvariantViolation? {
    val late = h.lateRun ?: return null
    val control = h.controlRun ?: return null
    // 최종 상태는 종류와 failure.code 로만 비교한다 — sessionId 등 실행마다 다른 문맥은 비교 대상이 아니다.
    if (late.copy(last = null) == control.copy(last = null) && late.last.kind() == control.last.kind()) return null
    return InvariantViolation("I9", "late=$late control=$control")
}

/** I9 최종 상태 비교 키 — 상태 종류 + (Failed·Recovering 이면) failure.code. */
internal fun ConnectionState?.kind(): String = when (this) {
    null -> "null"
    is ConnectionState.Recovering -> "Recovering(${lastFailure.code.value})"
    is ConnectionState.Disconnected -> when (val r = reason) {
        is BleDisconnectReason.Failed -> "Disconnected(Failed(${r.failure.code.value}))"
        else -> "Disconnected(${r::class.simpleName})"
    }
    else -> this::class.simpleName ?: toString()
}

/**
 * 관찰한 위반 집합이 고정 기준선과 정확히 같은지 본다 — 연결·스캔 시나리오 공통 형식.
 * [detail] 은 실패 메시지 끝에 붙는다(예: 최종 상태 표).
 */
internal fun assertViolationsPinned(known: Set<String>, observed: Set<String>, detail: String = "") {
    val sorted = observed.toSortedSet()
    val added = sorted - known
    val fixed = known.toSortedSet() - sorted
    assertEquals(
        "새 위반(회귀): $added\n고쳐진 위반(목록에서 빼라): $fixed\n관찰 전체:\n" +
            sorted.joinToString("\n") { "    \"$it\"," } + (if (detail.isEmpty()) "" else "\n$detail"),
        known.toSortedSet().toList(),
        sorted.toList(),
    )
}
