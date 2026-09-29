package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.session.Session

/**
 * 시험 전용 — 종결 전이([complete])와 함께 플랫폼 재연결 대기를 세우고 공개 상태를 다시 파생한다.
 * 운영 코드는 대기를 Core 정책으로만 바꾸므로 이 입구는 main 에 없다.
 */
internal fun Session.beginPlatformWait(complete: () -> Boolean): Boolean {
    check(!connectionCoordinator.isEnded) {
        "플랫폼 재연결 대기는 종결 전이와 함께 시작한다: ${connectionCoordinator.stage}"
    }
    connectionCoordinator.setPlatformWait(true)
    val completed = complete()
    if (!completed) connectionCoordinator.setPlatformWait(false)
    republish()
    return completed
}
