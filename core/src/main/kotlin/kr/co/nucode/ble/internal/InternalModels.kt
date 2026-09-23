package kr.co.nucode.ble.internal

import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleFailure

/** nullable ByteArray 내용 비교. 둘 다 null 이면 같고, 하나만 null 이면 다르다. */
private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    if (this == null || other == null) return this == null && other == null
    return this.contentEquals(other)
}

/** write 방식. Auto 는 characteristic 속성을 보고 코어가 고른다. */
internal enum class WriteType {
    WithResponse,
    WithoutResponse,
    Auto,
}

/**
 * write 결과. partiallyDelivered 를 타입으로 강제한다 — 로그 문자열로 흘리면
 * 소비 제품이 무시하고, 기기는 불완전한 명령을 받은 상태가 된다(설계 §5).
 */
internal sealed interface WriteResult {
    /** Every chunk completed successfully; the count includes an empty payload's one chunk. */
    public data class Completed(val chunksCompleted: Int) : WriteResult {
        init {
            require(chunksCompleted > 0) { "chunksCompleted must be positive" }
        }
    }

    /**
     * A write that did not complete. [failure.writeProgress] carries the transport progress and
     * delivery uncertainty; consumers must not infer that the remaining bytes are safe to replay.
     */
    public data class Failed(
        val failure: BleFailure,
    ) : WriteResult

    public data class TooLarge(val size: Int, val max: Int) : WriteResult
}

/**
 * 연결 옵션. 최소한만.
 *
 * @param timeoutMillis 타임아웃 시 코어는 포기한다(D2) — 자동 재연결을 시작하지 않는다.
 * 재시도는 소비자가 connect() 를 다시 불러야 한다(정책은 밖). null 이면 플랫폼 기본 동작.
 * @param requestedMtu MTU 협상 요청값(plan 결정①). 기본 247 은 Android/iOS 에서 흔한 협상
 * 상한이다(청크 244 바이트). 기기 펌웨어가 큰 MTU 요청에 이상 동작하면 낮춰라. 협상이 실패하면
 * 코어는 기본 23/20 으로 폴백한다(설계 §12 결정).
 */
internal data class ConnectOptions(
    /**
     * true 면 플랫폼 백그라운드 연결 — 기기가 전파 범위에 나타날 때까지 시스템이 대기·재연결한다.
     * **재연결 소유권이 플랫폼으로 넘어간다**: 코어 Reconnector(BleConfig.reconnectPolicy)는
     * 이 연결에 대해 예약·실행 모두 하지 않는다(배타 — plan app-tunables v3).
     *
     * 주의: ① [timeoutMillis] 와의 조합 — autoConnect 의 존재 이유가 무기한 대기인데
     * 타임아웃은 그 대기를 포기시킨다. 의도가 아니면 함께 쓰지 마라.
     * ② 연결이 의도적으로 느리다(low duty cycle 스캔). ③ 시스템 캐시에 없는 비본딩
     * 주소로는 조용히 붙지 않을 수 있다 — 실기기 확인 항목.
     */
    val autoConnect: Boolean = false,
    val timeoutMillis: Long? = null,
    val requestedMtu: Int = 247,
) {
    init {
        require(requestedMtu in 23..517) {
            "requestedMtu 는 ATT 스펙 범위(23..517) 안이어야 한다: $requestedMtu"
        }
    }
}

/**
 * 연결 시도에 적용할 link deadline. `null`은 "deadline 없음"이 아니라 [BleConfig]의
 * 기본값을 사용한다는 뜻이므로, 진행 중인 connect의 중복 여부는 원본 nullable 값이 아닌
 * 이 정규화된 값으로 판단해야 한다.
 *
 * link가 이미 성공한 뒤에는 이 값이 현재 연결 동작에 영향을 주지 않는다. 그 시점의
 * duplicate connect 비교는 autoConnect와 requestedMtu만으로 충분하며, `BleRuntime`가 그
 * lifecycle 경계를 적용한다.
 */
internal fun ConnectOptions.effectiveLinkTimeoutMillis(defaultLinkTimeoutMillis: Long): Long =
    timeoutMillis ?: defaultLinkTimeoutMillis

/**
 * 재연결 정책. 최대 시도 횟수 · 지수 백오프의 밑변 · 상한만 둔다(과설계 금지).
 * 기본값은 스켈레톤 단계의 추정치이고, 설계 §12-5 "재시도 기본값"이 실기기로 확정한다.
 *
 * 설계 §4 "재연결 정책(횟수·백오프·조건)은 주입" 의 주입구다(plan 결정②) — 이전에는
 * `internal` 이라 [BleConfig] 에서 주입할 수 없었다.
 */
internal data class ReconnectPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    /**
     * 재연결 시도 하나의 상한(plan blecore-lifecycle §감사결과 C, 결정 — 공개, 기본값이라
     * 비파괴). 이 시간 안에 `CONNECTED` 콜백이 오지 않으면 이 시도는 **포기가 아니라 다음
     * 백오프로** 넘어간다(`Reconnector.reconnect` 가 `Diagnostic("RECONNECT_ATTEMPT_TIMEOUT")`
     * 를 내고 `gatt.disconnect()` 해 `scheduleReconnect` 가 `retryCount` 로 다음 시도/give-up
     * 을 정한다) — `ConnectOptions.timeoutMillis`(사용자 connect 의 포기)와 의미가 다르다:
     * 정책상 재시도 예산이 아직 남아 있으므로 여기서 완전히 포기하면 안 된다.
     */
    val attemptTimeoutMillis: Long = 30_000,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts 는 음수일 수 없다: $maxAttempts" }
        require(baseDelayMillis > 0) { "baseDelayMillis 는 0보다 커야 한다: $baseDelayMillis" }
        require(maxDelayMillis >= baseDelayMillis) {
            "maxDelayMillis($maxDelayMillis) 는 baseDelayMillis($baseDelayMillis) 이상이어야 한다"
        }
        require(attemptTimeoutMillis > 0) {
            "attemptTimeoutMillis 는 0보다 커야 한다: $attemptTimeoutMillis"
        }
    }
}
