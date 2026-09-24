package kr.co.nucode.ble.internal.connection

import kr.co.nucode.ble.PeripheralId

/**
 * 링크 캐시 온기의 유일한 소유자(plan runtime-split 불변식 ④) — 쓰는 곳(스캔 관측·LinkSetup markWarm·
 * 어댑터 이탈 sweep)과 읽는 곳(lifecycle 의 cold-cache 재발견 판정)이 모두 이 객체를 거친다.
 * 코어 스레드에서만 불린다.
 */
internal class LinkCacheWarmth {

    /**
     * 이 클라이언트가 마지막 adapter Ready 이후 광고나 링크로 본 주소. 없으면 connect 앞에 재발견 스캔.
     */
    private val warmAddresses = mutableSetOf<PeripheralId>()

    fun mark(deviceId: PeripheralId) {
        warmAddresses += deviceId
    }

    fun isWarm(deviceId: PeripheralId): Boolean = deviceId in warmAddresses

    /** 어댑터가 Ready 를 벗어나면 스택 LE 캐시도 비워진다고 가정한다 — 다음 connect 는 재발견부터. */
    fun clear() {
        warmAddresses.clear()
    }
}
