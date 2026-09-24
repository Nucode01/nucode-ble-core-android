package kr.co.nucode.ble.harness

/**
 * 플랫폼 콜백 한 번의 도착 모양(plan platform-harness v1 §접근 "콜백 변형").
 * [FakeAndroidPlatform] 의 각 명령 스크립트가 이 값으로 콜백을 낸다.
 */
sealed interface CallbackVariant {
    /** 콜백이 코어 디스패처 홉 한 번 뒤에 온다. */
    data class Arrive(val status: Int = 0) : CallbackVariant

    /** 콜백이 오지 않는다 — deadline 경로만 남는다. */
    object Missing : CallbackVariant

    /** 콜백이 [delayMillis] 가상 시간 뒤에 온다. */
    data class Late(val delayMillis: Long, val status: Int = 0) : CallbackVariant

    /**
     * 재연결 시작(retained `gatt.connect()`)이 반환되기 전에 콜백이 코어 큐에 들어간다.
     * production 에선 마이크로초 경합으로만 나는 순서라 기본([Arrive])이 아니라 이 변형으로만 재현한다.
     * 재연결 외 명령에선 [Arrive] 와 같다.
     */
    data class ArriveBeforeStartReturns(val status: Int = 0) : CallbackVariant

    /** 같은 콜백이 두 번 온다. */
    data class Duplicate(val status: Int = 0) : CallbackVariant
}
