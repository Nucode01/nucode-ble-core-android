package kr.co.nucode.ble.internal.port

import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.PeripheralId

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

/**
 * 플랫폼 스캔 한 번의 요청 — 소비자들의 합집합.
 * [userFilter] 가 null 이면 사용자 소비자 없음(주소 필터만), [addresses] 는 재발견 대상 주소들.
 * 플랫폼 필터 목록은 OR 이라 사용자 필터 ∪ 주소 필터로 두 소비자를 한 스캔에 담는다. 사용자 결과의
 * 최종 판정은 여전히 `Scanner.matches` 다(주소로 들어온 비대상 광고는 사용자에게 가지 않는다).
 */
internal data class PlatformScanSpec(
    val userFilter: BleScanFilter?,
    val addresses: Set<PeripheralId>,
    val mode: BleScanMode,
)
