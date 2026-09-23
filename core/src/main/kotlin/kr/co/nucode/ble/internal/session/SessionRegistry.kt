package kr.co.nucode.ble.internal.session

import kr.co.nucode.ble.PeripheralId

/**
 * 기기별 상태를 담는 유일한 PeripheralId 키 맵(설계 §3 규칙 2 — `sessions: Map[PeripheralId, Session]`).
 * 코어 전체를 통틀어 PeripheralId 를 키로 하는 맵은 이것 하나뿐이다.
 *
 * registry 멤버십(생성/조회/목록/제거)은 스레드 안전하다(plan `runtime-no-runblocking` task 1(A) —
 * `sessionForView` 가 코어 디스패처 밖에서 곧장 `getOrCreate` 를 부를 수 있어야 한다). 이 락이
 * 지키는 건 맵 하나뿐이다 — **Session 내부 상태(state/mtu/…)는 여전히 coreScope 단일
 * 스레드 위에서만 변경된다**(설계 §3 규칙 2 불변). 이 클래스는 그 상태를 들여다보지 않는다.
 */
internal class SessionRegistry {
    private val sessions = mutableMapOf<PeripheralId, Session>()

    /** 없으면 새로 만들어 등록한다. */
    @Synchronized
    fun getOrCreate(deviceId: PeripheralId): Session =
        sessions.getOrPut(deviceId) { Session(deviceId) }

    /** 이미 있는 것만 조회한다. connect 되지 않은 기기는 null. */
    @Synchronized
    fun get(deviceId: PeripheralId): Session? = sessions[deviceId]

    /** 제거된 Session 을 돌려준다 — 호출부가 gatt.close() 등 정리를 할 수 있게. */
    @Synchronized
    fun remove(deviceId: PeripheralId): Session? = sessions.remove(deviceId)

    /** officialSnapshot()·sweep 이 쓰는 전체 조회. 항상 스냅샷 복사본을 돌려준다. */
    @Synchronized
    fun all(): List<Session> = sessions.values.toList()
}
