package kr.co.nucode.ble.android

import android.bluetooth.BluetoothDevice
import kr.co.nucode.ble.PlatformAdvertising
import kr.co.nucode.ble.ScanResult

/** Android only: an LE PHY as Android reports it on a scan result. */
public enum class AndroidPhy { Le1M, Le2M, LeCoded }

/** Android only: whether Android received the whole advertisement (`ScanResult.getDataStatus()`). */
public enum class AndroidAdvertisingDataStatus { Complete, Truncated }

/**
 * Android only: how one scan result was advertised.
 *
 * @property isLegacy false for an extended advertisement. Extended results arrive only from a scan with
 *   `BleScanOptions.extendedAdvertising = true` on an adapter that supports it.
 * @property secondaryPhy null when the advertisement used no secondary channel (every legacy advertisement).
 * @property advertisingSid the advertising set ID (0..15), or null when the advertisement carried none.
 */
public data class AndroidAdvertisingInfo(
    val isLegacy: Boolean,
    val primaryPhy: AndroidPhy,
    val secondaryPhy: AndroidPhy?,
    val advertisingSid: Int?,
    val dataStatus: AndroidAdvertisingDataStatus,
)

/**
 * Android only: the advertising details of this scan result, read once when the result arrived.
 *
 * - Before Android 8.0, Android reports none, and the value is legacy · [AndroidPhy.Le1M] · no secondary PHY ·
 *   no SID · [AndroidAdvertisingDataStatus.Complete]. A result built with the public constructor, and iOS, get the
 *   same value.
 * - The raw bytes (`androidScanRecordBytes`) are not cut, also for a truncated advertisement.
 * - Equality of [ScanResult] includes it.
 */
public val ScanResult.androidAdvertisingInfo: AndroidAdvertisingInfo
    get() = advertisement.platformAdvertising?.let(::decode) ?: LEGACY

private val LEGACY = AndroidAdvertisingInfo(
    isLegacy = true,
    primaryPhy = AndroidPhy.Le1M,
    secondaryPhy = null,
    advertisingSid = null,
    dataStatus = AndroidAdvertisingDataStatus.Complete,
)

// 상수는 컴파일 때 값으로 들어간다(API 26 미만에서도 안전). 0 = ScanResult.PHY_UNUSED, 0xFF = SID_NOT_PRESENT,
// 0 = DATA_COMPLETE. 플랫폼은 primary 로 1M·Coded 만 주므로 모르는 값은 1M 으로 본다.
private fun decode(info: PlatformAdvertising) = AndroidAdvertisingInfo(
    isLegacy = info.isLegacy,
    primaryPhy = phy(info.primaryPhy) ?: AndroidPhy.Le1M,
    secondaryPhy = phy(info.secondaryPhy),
    advertisingSid = info.advertisingSid.takeUnless { it == SID_NOT_PRESENT },
    dataStatus = if (info.dataStatus == DATA_COMPLETE) AndroidAdvertisingDataStatus.Complete else AndroidAdvertisingDataStatus.Truncated,
)

private fun phy(code: Int): AndroidPhy? = when (code) {
    BluetoothDevice.PHY_LE_1M -> AndroidPhy.Le1M
    BluetoothDevice.PHY_LE_2M -> AndroidPhy.Le2M
    BluetoothDevice.PHY_LE_CODED -> AndroidPhy.LeCoded
    else -> null
}

private const val SID_NOT_PRESENT = 0xFF
private const val DATA_COMPLETE = 0
