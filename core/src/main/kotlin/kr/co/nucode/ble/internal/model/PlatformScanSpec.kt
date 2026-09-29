package kr.co.nucode.ble.internal.model

import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.PeripheralId

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
