package kr.co.nucode.ble

/**
 * 스캔 결과 하나의 광고 정보, 플랫폼 코드 그대로(Android `ScanResult`, API 26+). [AdvertisementData] 의 숨은 칸이라
 * 같은 패키지에 둔다. 공개 enum 으로 푸는 것은 Android 패키지 확장 `androidAdvertisingInfo` 다.
 */
internal data class PlatformAdvertising(
    val isLegacy: Boolean,
    val primaryPhy: Int,
    /** `PHY_UNUSED`(0)면 secondary 채널이 없다. */
    val secondaryPhy: Int,
    /** `SID_NOT_PRESENT`(0xFF)면 SID 가 없다. */
    val advertisingSid: Int,
    val dataStatus: Int,
)
