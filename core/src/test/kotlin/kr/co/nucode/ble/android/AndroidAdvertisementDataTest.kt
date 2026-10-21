package kr.co.nucode.ble.android

import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementFacts
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CANDIDATE-31: Android 전용 원본 광고 바이트(docs/requests/ble-lab-core-extensions.md §A.6). */
class AndroidAdvertisementDataTest {

    private fun facts(raw: ByteArray?) = AndroidAdvertisementFacts(
        localName = "probe",
        serviceUuids = emptySet(),
        manufacturerData = emptyMap(),
        serviceData = emptyMap(),
        txPower = null,
        isConnectable = true,
        scanRecordBytes = raw,
    )

    @Test
    fun `mapper 는 원본을 복사해 싣고 원본 배열을 바꿔도 값은 그대로다`() {
        val raw = byteArrayOf(2, 1, 6, 0, 0)
        val mapped = AndroidAdvertisementMapper.map(facts(raw))
        raw[0] = 9
        assertArrayEquals(byteArrayOf(2, 1, 6, 0, 0), mapped.androidScanRecordBytes)
    }

    @Test
    fun `확장 속성은 복사본을 주어 돌려받은 배열을 바꿔도 값은 그대로다`() {
        val mapped = AndroidAdvertisementMapper.map(facts(byteArrayOf(2, 1, 6)))
        mapped.androidScanRecordBytes!![0] = 7
        assertArrayEquals(byteArrayOf(2, 1, 6), mapped.androidScanRecordBytes)
    }

    @Test
    fun `플랫폼이 scanRecord 를 주지 않으면 null 이다`() {
        assertNull(AndroidAdvertisementMapper.map(facts(null)).androidScanRecordBytes)
    }

    @Test
    fun `공개 생성자로 만든 값에는 원본이 없다`() {
        assertNull(AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null).androidScanRecordBytes)
    }

    @Test
    fun `equals 와 hashCode 는 원본 바이트를 반영한다`() {
        val a = AndroidAdvertisementMapper.map(facts(byteArrayOf(2, 1, 6)))
        val same = AndroidAdvertisementMapper.map(facts(byteArrayOf(2, 1, 6)))
        val other = AndroidAdvertisementMapper.map(facts(byteArrayOf(2, 1, 4)))
        val none = AndroidAdvertisementMapper.map(facts(null))

        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, other)
        assertNotEquals(a, none)
        assertNotEquals(none, a)
    }
}
