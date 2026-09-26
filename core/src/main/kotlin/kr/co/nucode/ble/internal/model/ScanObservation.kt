package kr.co.nucode.ble.internal.model

import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.PeripheralId

/** Structured common fields from one Android callback. */
internal class ScanObservation(
    val peripheralId: PeripheralId,
    val rssi: Int,
    val advertisement: AdvertisementData,
)
