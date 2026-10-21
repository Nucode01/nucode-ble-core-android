package kr.co.nucode.ble.android

import kr.co.nucode.ble.AdvertisementData

/**
 * Android only: the raw scan record bytes of this advertisement (`ScanRecord.getBytes()`), as a fresh copy.
 *
 * - For a legacy advertisement found by an active scan, Android joins the advertising data and the scan response
 *   into this one array.
 * - The library does not trim or normalize it: trailing zero padding, if the platform adds it, stays.
 * - The library does not decode the AD structures; the app does.
 * - Null when the platform gave no scan record. iOS has no equivalent and the common API does not carry it.
 */
public val AdvertisementData.androidScanRecordBytes: ByteArray?
    get() = scanRecordBytes
