package kr.co.nucode.ble.internal

import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.adapter.AdapterMonitor
import kr.co.nucode.ble.internal.connection.LinkCacheWarmth
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [BleRuntime] 의 어댑터 반응 칸 — 어댑터 이탈 스윕·복귀 재개·준비 게이트 위임을 배선한다. 상태는 없다.
 * [AdapterMonitor] 는 이 객체의 콜백을 받아 생성되므로 함수 값으로 받아 호출 시점에 평가한다 — 생성 순환이 없다.
 */
internal class AdapterReaction(
    private val linkCacheWarmth: LinkCacheWarmth,
    private val scanner: Scanner,
    private val registry: SessionRegistry,
    private val gattBridge: GattBridge,
    private val adapterMonitorOf: () -> AdapterMonitor,
) {
    private val adapterMonitor: AdapterMonitor get() = adapterMonitorOf()

    /** 테스트 seam — [AdapterMonitor.refreshAdapterState] 위임. */
    internal fun refreshAdapterState() {
        adapterMonitor.refreshAdapterState()
    }

    /** 테스트 seam — 어댑터 이탈 스윕을 직접 구동한다(규약은 [onAdapterLost]). */
    internal fun sweepSessions(cause: AdapterState) {
        onAdapterLost(cause)
    }

    /**
     * BT off/resetting 스윕(D4/M-6). 어댑터가 죽으면 모든 gatt 핸들은 회복 불가다 —
     * stale 핸들에 백오프를 소진하거나(off 중 재연결은 구조적으로 실패) 세션이 Connected 로
     * 동결되는 것(콜백이 안 오는 기기)을 막는다. 이탈 판정과 스윕 1회 규칙은 [AdapterMonitor] 소유이고,
     * 여기는 자원별 소유자를 순서대로(온기 → 스캔 → 세션) 부르는 배선만 한다. 세션별 skip 규칙·원인
     * 매핑·종결은 GattConnectionLifecycle.onAdapterLost 에 있다.
     *
     * `internal` 테스트 seam [sweepSessions] 가 이 함수를 직접 부른다 — 리시버 경로는 등록이 안 되는
     * 테스트 생성자에서 검증할 수 없어, 상태 전이 자체를 그 seam 으로 구동해 검증한다.
     *
     * registry 에서 세션을 제거하지 않는다 — 기기 자체는 여전히 유효하고, 소비자가
     * connect() 를 다시 불러 되살릴 수 있어야 한다(D4 원문 "BT 복귀 자동 재연결은 안 함").
     */
    internal fun onAdapterLost(cause: AdapterState) {
        // 어댑터가 Ready 를 벗어나면 스택 LE 캐시도 비워진다고 가정한다 — 다음 connect 는 재발견부터.
        linkCacheWarmth.clear()
        scanner.stopForAdapter(cause)
        for (session in registry.all()) gattBridge.onAdapterLost(session, cause)
    }

    /** 어댑터 복귀 — 세션마다 lifecycle 에 넘긴다(재연결 판정·시작은 [GattBridge.resumeAfterAdapter] 소유). */
    internal fun onAdapterReturned() {
        for (session in registry.all()) {
            gattBridge.resumeAfterAdapter(session.deviceId) { adapterMonitor.adapterState.value is AdapterState.Ready }
        }
    }

    internal suspend fun ensureAdapterReady(operation: AndroidApiOperation) {
        adapterMonitor.ensureAdapterReady(operation)
    }
}
