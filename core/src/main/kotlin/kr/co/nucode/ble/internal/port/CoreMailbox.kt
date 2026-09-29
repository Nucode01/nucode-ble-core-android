package kr.co.nucode.ble.internal.port

import kr.co.nucode.ble.PeripheralId

/**
 * 플랫폼 콜백을 기기 귀속으로 코어 스레드에 넣는 Runtime 입구. Driver 는 이 포트만 알고
 * 코어 스코프·세션 귀속 구현은 모른다. 예외 귀속(그 기기 세션만 실패)과 홉 순서는 구현(Runtime)이 정한다.
 */
internal fun interface CoreMailbox {
    fun post(deviceId: PeripheralId, block: () -> Unit)
}
