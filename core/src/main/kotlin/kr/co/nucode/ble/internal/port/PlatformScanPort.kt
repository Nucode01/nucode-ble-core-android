package kr.co.nucode.ble.internal.port

import kr.co.nucode.ble.internal.model.PlatformScanSpec
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.BleFailure

/**
 * 플랫폼 스캔의 유일한 문(plan scan-owner D2) — 시작·정지는 `Scanner` 만 부른다. 운영 구현은
 * `platform/android/AndroidScanner`. 어댑터가 하나뿐이어도 IO 경계라 seam 으로 둔다.
 *
 * [start] 는 이미 도는 스캔이 없을 때만 불린다(Scanner 가 재시작 전에 [stop] 한다). 반환값 null = 시작 성공,
 * 아니면 동기 거절 이유. 콜백은 플랫폼 스레드에서 올 수 있다 — 코어 홉은 콜백을 만든 Scanner 가 한다.
 * [stop] 은 도는 스캔이 없으면 no-op 이다.
 */
internal interface PlatformScanPort {
    fun start(
        spec: PlatformScanSpec,
        onResult: (ScanObservation) -> Unit,
        onFailure: (BleFailure) -> Unit,
    ): BleFailure?

    fun stop()
}
