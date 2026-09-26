package kr.co.nucode.ble.internal.model

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
     * **재연결 소유권은 플랫폼이다**: 라이브러리는 링크 재시작을 스스로 예약하지 않는다(plan
     * autoconnect-reconnect v2) — 링크가 끊겨도 handle 을 열어 두고 OS 재연결을 기다린다.
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

