package kr.co.nucode.ble.internal.port

/**
 * 도메인이 시각을 읽고 기다리는 유일한 입구. 도메인은 시스템 시계·지연·기한 API 를 직접 쓰지 않고 이 포트로만 한다.
 * 구현(Driver)은 호출 코루틴 문맥을 바꾸지 않는다.
 */
internal interface ClockPort {
    /** 단조 시각(ms). 기한·시작 한도 창 계산용. */
    fun elapsedMillis(): Long

    /** 벽시계 시각(epoch ms). 로그 전용 억제 창만 쓴다. */
    fun wallMillis(): Long

    /** 호출 코루틴을 [millis] 동안 멈춘다. 취소 가능. */
    suspend fun sleep(millis: Long)

    /** [block] 이 [millis] 안에 끝나지 않으면 `withTimeout` 과 같은 예외(TimeoutCancellationException)로 끝낸다. */
    suspend fun <T> awaitWithin(millis: Long, block: suspend () -> T): T

    /** [block] 이 [millis] 안에 끝나지 않으면 null. */
    suspend fun <T> awaitWithinOrNull(millis: Long, block: suspend () -> T): T?
}
