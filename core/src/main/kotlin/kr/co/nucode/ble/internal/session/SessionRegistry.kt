package kr.co.nucode.ble.internal.session

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.normalized

/**
 * 코어 스코프 코루틴이 어느 세션 소유인지 싣는 컨텍스트 원소. 잡히지 않은 예외 처리기(BleRuntime)가
 * 이것으로 예외를 그 세션에만 귀속한다 — 없으면 귀속 불명이라 Diagnostic 으로만 알린다.
 */
internal class SessionOwner(val deviceId: PeripheralId) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionOwner>
}

/**
 * 기기별 상태를 담는 유일한 PeripheralId 키 맵(설계 §3 규칙 2 — `sessions: Map[PeripheralId, Session]`).
 * 코어 전체를 통틀어 PeripheralId 를 키로 하는 맵은 이것 하나뿐이다.
 *
 * registry 멤버십(생성/조회/목록/제거)은 스레드 안전하다(plan `runtime-no-runblocking` task 1(A) —
 * `sessionForView` 가 코어 디스패처 밖에서 곧장 `getOrCreate` 를 부를 수 있어야 한다). 이 락이
 * 지키는 건 맵 하나뿐이다 — **Session 내부 상태(state/mtu/…)는 여전히 coreScope 단일
 * 스레드 위에서만 변경된다**(설계 §3 규칙 2 불변). 이 클래스는 그 상태를 들여다보지 않는다.
 */
internal class SessionRegistry(val clock: ClockPort) {
    private val sessions = mutableMapOf<PeripheralId, Session>()

    /** 없으면 새로 만들어 등록한다. */
    @Synchronized
    fun getOrCreate(deviceId: PeripheralId): Session {
        val id = deviceId.normalized()
        return sessions.getOrPut(id) { Session(id, clock) }
    }

    /** 이미 있는 것만 조회한다. connect 되지 않은 기기는 null. */
    @Synchronized
    fun get(deviceId: PeripheralId): Session? = sessions[deviceId.normalized()]

    /** 제거된 Session 을 돌려준다 — 호출부가 gatt.close() 등 정리를 할 수 있게. */
    @Synchronized
    fun remove(deviceId: PeripheralId): Session? = sessions.remove(deviceId.normalized())

    /** officialSnapshot()·sweep 이 쓰는 전체 조회. 항상 스냅샷 복사본을 돌려준다. */
    @Synchronized
    fun all(): List<Session> = sessions.values.toList()
}
