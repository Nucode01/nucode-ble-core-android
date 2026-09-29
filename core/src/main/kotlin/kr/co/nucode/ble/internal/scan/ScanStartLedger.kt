package kr.co.nucode.ble.internal.scan

/**
 * 성공한 플랫폼 스캔 시작 기록(plan scan-start-budget v2 목표 4) — Android 시작 한도는 앱(UID) 단위라 운영
 * 생성자는 프로세스 하나의 인스턴스를 모든 [Scanner] 에 넘긴다(같은 UID 의 다른 프로세스는 아래 한계). 여러 BleClient 가 각자 코어 스레드에서 부르므로
 * 판정 → 시작 → 기록을 한 잠금 안에서 한다(두 클라이언트가 함께 판정을 통과해 한도를 넘지 않게).
 * 테스트·기본값은 인스턴스별 기록이다(JVM 테스트 격리).
 *
 * 한도: 최근 [START_BUDGET] 회의 첫 시작이 [START_WINDOW_MILLIS] + [START_WINDOW_MARGIN_MILLIS] 안이면 거부.
 * 근거는 [Scanner] 클래스 KDoc. 판정은 호출 전 시각, 기록은 [tryStart] 의 시작 호출이 돌아온 뒤 시각이다 — AOSP 는
 * scanner 등록 왕복 뒤 GattService.startScan 에서 기록하고 BluetoothLeScanner.startScan 은 그 뒤에 돌아온다(최대
 * 2 s 대기, android14-release). 판정은 플랫폼(registerScanner)보다 앞이라 보수적이다.
 * 플랫폼과의 차이: 플랫폼은 멈춘 스캔만 센다(mLastScans 는 recordScanStop 에서 채움) — 라이브러리는 시작을 세므로
 * 더 보수적이다. 플랫폼은 UID 단위(getAppScanStatsByUid)지만 ledger 는 프로세스 단위라 같은 앱의 다른 프로세스
 * 시작은 세지 못한다.
 *
 * @param clock 단조 시계(ms). 운영은 `SystemClock.elapsedRealtime()`(deep sleep 에도 진행 — nanoTime 은 멈춰
 * 창이 늘어나 false throttle). scan 패키지는 Android API 를 쓰지 않으므로 운영 시계는 Factory 가 만든
 * [kr.co.nucode.ble.internal.port.ClockPort] 의 `elapsedMillis` 로 BleRuntime 이 넣는다.
 */
internal class ScanStartLedger(private val clock: () -> Long) {

    /** 최근 성공한 시작 시각, 오래된 순 최대 [START_BUDGET] 개. 접근은 `this` 잠금 안에서만. */
    private val startTimes = ArrayDeque<Long>()

    /**
     * 한도 안이면 [start] 를 부르고, true(성공)면 호출이 돌아온 뒤 시각을 기록한다(플랫폼 기록이
     * 등록 왕복 뒤라서) — 반환 null. 판정은 호출 전 시각. 한도 초과면 [start] 를 부르지
     * 않고 다음 시작 가능까지 ms 를 돌려준다. 거절된 시작(false)은 세지 않는다.
     */
    @Synchronized
    fun tryStart(start: () -> Boolean): Long? {
        val startAt = clock()
        if (startTimes.size >= START_BUDGET) {
            val waitMillis = startTimes.first() + START_WINDOW_MILLIS + START_WINDOW_MARGIN_MILLIS - startAt + 1
            if (waitMillis > 0) return waitMillis
        }
        if (!start()) return null
        startTimes.addLast(clock())
        if (startTimes.size > START_BUDGET) startTimes.removeFirst()
        return null
    }

    /**
     * 어댑터 이탈 — 기록을 비운다(v2 목표 6). 실측(Pixel 5a/Android 14, ble_scan_always_enabled 0·1 모두): 성공 시작
     * 5회 → `svc bluetooth disable/enable` → 6번째 시작 status 0 허용, 토글 없는 대조는 status 6 거절. 다른 버전·OEM
     * 은 확인 필요.
     */
    @Synchronized
    fun clear() {
        startTimes.clear()
    }

    internal companion object {
        /** AOSP AppScanStats 의 창 안 최대 시작 횟수([Scanner] KDoc 근거). */
        const val START_BUDGET = 5
        /** AOSP AppScanStats 의 시작 횟수 창. */
        const val START_WINDOW_MILLIS = 30_000L
        /**
         * 확인되지 않은 플랫폼 시각 차이(OEM·버전)를 위한 여유 — AOSP 14 에서는 기록 ≥ 플랫폼 기록, 판정 ≤ 플랫폼
         * 판정이라 이 차이가 없다.
         */
        const val START_WINDOW_MARGIN_MILLIS = 1_000L
    }
}
