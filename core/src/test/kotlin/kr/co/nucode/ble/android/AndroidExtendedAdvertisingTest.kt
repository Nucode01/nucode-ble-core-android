package kr.co.nucode.ble.android

import android.bluetooth.BluetoothDevice
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.internal.model.PlatformScanSpec
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.PlatformAdvertising
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementMapper
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CANDIDATE-36b: Android extended 광고 스캔 옵션의 드라이버 결정과 결과의 광고 정보. */
class AndroidExtendedAdvertisingTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:FF")
    private val raw = byteArrayOf(2, 1, 6)

    private val extended = PlatformAdvertising(isLegacy = false, primaryPhy = 1, secondaryPhy = 2, advertisingSid = 3, dataStatus = 0)

    /** 드라이버 경로 그대로: facts 오버로드 → map → ScanResult. ScanRecord 는 JVM 에서 만들 수 없어 원본은 copy 로 싣는다. */
    private fun scanned(advertising: PlatformAdvertising?): ScanResult {
        val facts = AndroidAdvertisementMapper.facts(
            timestampNanos = 1L,
            record = null,
            isConnectable = true,
            readDeviceType = { BluetoothDevice.DEVICE_TYPE_LE },
            advertising = advertising,
        ).copy(scanRecordBytes = raw)
        return ScanResult(id, -50, AndroidAdvertisementMapper.map(facts), facts.timestampNanos)
    }

    private val legacyInfo = AndroidAdvertisingInfo(true, AndroidPhy.Le1M, null, null, AndroidAdvertisingDataStatus.Complete)

    @Test
    fun `extended 결과의 광고 정보가 매퍼를 거쳐 enum 으로 풀린다`() {
        val result = scanned(extended)
        assertEquals(
            AndroidAdvertisingInfo(false, AndroidPhy.Le1M, AndroidPhy.Le2M, 3, AndroidAdvertisingDataStatus.Complete),
            result.androidAdvertisingInfo,
        )
        assertArrayEquals(raw, result.advertisement.androidScanRecordBytes)
        assertEquals(AndroidDeviceType.Le, result.androidDeviceType)
    }

    @Test
    fun `secondary 0 과 SID 0xFF 는 없음, Coded 와 잘린 데이터도 푼다`() {
        val legacy = scanned(PlatformAdvertising(true, 1, 0, 0xFF, 0)).androidAdvertisingInfo
        assertEquals(legacyInfo, legacy)

        val coded = scanned(PlatformAdvertising(false, 3, 3, 15, 2)).androidAdvertisingInfo
        assertEquals(
            AndroidAdvertisingInfo(false, AndroidPhy.LeCoded, AndroidPhy.LeCoded, 15, AndroidAdvertisingDataStatus.Truncated),
            coded,
        )
        assertEquals(AndroidPhy.Le1M, scanned(PlatformAdvertising(false, 9, 9, 0, 0)).androidAdvertisingInfo.primaryPhy)
        assertEquals(null, scanned(PlatformAdvertising(false, 9, 9, 0, 0)).androidAdvertisingInfo.secondaryPhy)
    }

    @Test
    fun `읽지 못한 결과와 공개 생성자로 만든 결과는 legacy 기본값이다`() {
        assertEquals(legacyInfo, scanned(null).androidAdvertisingInfo)
        val advertisement = AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null)
        assertEquals(legacyInfo, ScanResult(id, -50, advertisement, 1L).androidAdvertisingInfo)
    }

    @Test
    fun `equals 와 hashCode 는 광고 정보를 반영한다`() {
        val ext = scanned(extended)
        val sameExt = scanned(extended.copy())
        val otherSid = scanned(extended.copy(advertisingSid = 4))
        val unread = scanned(null)

        assertEquals(ext, sameExt)
        assertEquals(ext.hashCode(), sameExt.hashCode())
        assertNotEquals(ext, otherSid)
        assertNotEquals(ext.hashCode(), otherSid.hashCode())
        assertNotEquals(ext, unread)
    }

    @Test
    fun `세 with 는 어느 순서로 불러도 다른 숨은 칸을 지킨다`() {
        val base = AdvertisementData("n", emptySet(), emptyMap(), emptyMap(), null, true)
        val orders: List<(AdvertisementData) -> AdvertisementData> = listOf(
            { it.withScanRecordBytes(raw).withPlatformDeviceType(BluetoothDevice.DEVICE_TYPE_DUAL).withPlatformAdvertising(extended) },
            { it.withPlatformAdvertising(extended).withPlatformDeviceType(BluetoothDevice.DEVICE_TYPE_DUAL).withScanRecordBytes(raw) },
            { it.withPlatformDeviceType(BluetoothDevice.DEVICE_TYPE_DUAL).withPlatformAdvertising(extended).withScanRecordBytes(raw) },
            { it.withPlatformAdvertising(extended).withScanRecordBytes(raw).withPlatformDeviceType(BluetoothDevice.DEVICE_TYPE_DUAL) },
        )
        orders.forEachIndexed { index, build ->
            val result = ScanResult(id, -50, build(base), 1L)
            assertEquals("order $index", false, result.androidAdvertisingInfo.isLegacy)
            assertEquals("order $index", AndroidDeviceType.Dual, result.androidDeviceType)
            assertArrayEquals("order $index", raw, result.advertisement.androidScanRecordBytes)
        }
    }

    @Test
    fun `extended 스캔은 요청과 API 26 이상과 어댑터 지원이 모두 참일 때만이다`() {
        val requested = PlatformScanSpec(BleScanFilter(), emptySet(), BleScanMode.Balanced, extendedAdvertising = true)
        val legacy = requested.copy(extendedAdvertising = false)
        var reads = 0
        val supported = { reads++; true }
        val unsupported = { reads++; false }

        assertTrue(AndroidScanner.scansExtended(requested, 26, supported))
        assertFalse(AndroidScanner.scansExtended(requested, 34, unsupported))
        assertEquals(2, reads)

        // 지원 읽기는 API 26 전용 호출이라 요청이 없거나 API 24·25 면 부르지 않는다.
        val mustNotRead = { error("isLeExtendedAdvertisingSupported 를 불렀다") }
        assertFalse(AndroidScanner.scansExtended(legacy, 34, mustNotRead))
        assertFalse(AndroidScanner.scansExtended(requested, 25, mustNotRead))
        assertFalse(AndroidScanner.scansExtended(requested, 24, mustNotRead))
    }
}
