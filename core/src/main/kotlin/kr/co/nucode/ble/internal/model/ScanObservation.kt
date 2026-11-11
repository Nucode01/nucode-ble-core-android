package kr.co.nucode.ble.internal.model

import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.PeripheralId

/** Structured common fields from one Android callback. */
internal class ScanObservation(
    val peripheralId: PeripheralId,
    val rssi: Int,
    val advertisement: AdvertisementData,
    /** 플랫폼이 준 결과 시각(Android `ScanResult.getTimestampNanos`). 코어는 바꾸거나 정렬하지 않는다. */
    val timestampNanos: Long,
)
