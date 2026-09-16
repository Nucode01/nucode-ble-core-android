package kr.co.nucode.ble.internal

import kr.co.nucode.ble.DeviceId

/**
 * 기기별 상태를 담는 유일한 DeviceId 키 맵(설계 §3 규칙 2 — `sessions: Map[DeviceId, Session]`).
 * 코어 전체를 통틀어 DeviceId 를 키로 하는 맵은 이것 하나뿐이다.
 *
 * coreScope 단일 스레드(`limitedParallelism(1)`)에 고정되어서만 호출된다는 전제다.
 * 스레드 안전하지 않다 — Mutex 를 두지 않는다(plan §접근 배선①).
 */
internal class SessionRegistry {
    private val sessions = mutableMapOf<DeviceId, Session>()

    /** 없으면 새로 만들어 등록한다. */
    fun getOrCreate(deviceId: DeviceId): Session =
        sessions.getOrPut(deviceId) { Session(deviceId) }

    /** 이미 있는 것만 조회한다. connect 되지 않은 기기는 null. */
    fun get(deviceId: DeviceId): Session? = sessions[deviceId]

    /** 제거된 Session 을 돌려준다 — 호출부가 gatt.close() 등 정리를 할 수 있게. */
    fun remove(deviceId: DeviceId): Session? = sessions.remove(deviceId)

    /** snapshot()(plan task 3)이 쓰는 전체 조회. */
    fun all(): List<Session> = sessions.values.toList()
}
