package kr.co.nucode.ble.android

import android.bluetooth.BluetoothDevice
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CANDIDATE-36a: Android 전용 기기 종류(docs/requests/ble-lab-core-extensions.md §E). */
class AndroidDeviceTypeTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:FF")
    private val raw = byteArrayOf(2, 1, 6)

    /** 드라이버 경로 그대로: facts 오버로드(가드 포함) → map → ScanResult. ScanRecord 는 JVM 에서 만들 수 없어 원본은 copy 로 싣는다. */
    private fun scanned(readDeviceType: () -> Int): ScanResult {
        val facts = AndroidAdvertisementMapper.facts(record = null, isConnectable = true, readDeviceType = readDeviceType)
            .copy(scanRecordBytes = raw)
        return ScanResult(id, -50, AndroidAdvertisementMapper.map(facts))
    }

    @Test
    fun `플랫폼 기기 종류 네 값이 enum 으로 매핑되고 원본 바이트도 남는다`() {
        val cases = mapOf(
            BluetoothDevice.DEVICE_TYPE_CLASSIC to AndroidDeviceType.Classic,
            BluetoothDevice.DEVICE_TYPE_LE to AndroidDeviceType.Le,
            BluetoothDevice.DEVICE_TYPE_DUAL to AndroidDeviceType.Dual,
            BluetoothDevice.DEVICE_TYPE_UNKNOWN to AndroidDeviceType.Unknown,
        )
        cases.forEach { (code, expected) ->
            val result = scanned { code }
            assertEquals("code $code", expected, result.androidDeviceType)
            assertArrayEquals("code $code", raw, result.advertisement.androidScanRecordBytes)
        }
    }

    @Test
    fun `권한이 없어 getType 이 SecurityException 을 던지면 예외 없이 Unknown 이다`() {
        val result = scanned { throw SecurityException("BLUETOOTH_CONNECT") }
        assertEquals(AndroidDeviceType.Unknown, result.androidDeviceType)
        assertArrayEquals(raw, result.advertisement.androidScanRecordBytes)
        assertEquals(true, result.advertisement.isConnectable)
    }

    @Test
    fun `facts 는 결과마다 기기 종류를 한 번 읽는다`() {
        var reads = 0
        scanned { reads++; BluetoothDevice.DEVICE_TYPE_LE }
        assertEquals(1, reads)
    }

    @Test
    fun `공개 생성자로 만든 결과는 Unknown 이다`() {
        val advertisement = AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null)
        assertEquals(AndroidDeviceType.Unknown, ScanResult(id, -50, advertisement).androidDeviceType)
    }

    @Test
    fun `equals 와 hashCode 는 기기 종류를 반영한다`() {
        val le = scanned { BluetoothDevice.DEVICE_TYPE_LE }
        val sameLe = scanned { BluetoothDevice.DEVICE_TYPE_LE }
        val dual = scanned { BluetoothDevice.DEVICE_TYPE_DUAL }
        val unread = scanned { throw SecurityException() }

        assertEquals(le, sameLe)
        assertEquals(le.hashCode(), sameLe.hashCode())
        assertNotEquals(le, dual)
        assertNotEquals(le, unread)
        assertTrue(le.copy(rssi = -60).androidDeviceType == AndroidDeviceType.Le)
    }
}
