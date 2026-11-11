package kr.co.nucode.ble.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.harness.FakeAndroidPlatform
import kr.co.nucode.ble.harness.extendedSetAdvertiser
import kr.co.nucode.ble.harness.harness
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementMapper
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CANDIDATE-36c: 스캔 결과 시각 timestampNanos 가 플랫폼 문에서 공개 ScanResult 까지 그대로 온다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanTimestampTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:FF")
    private val nanos = 123_456_789_012L

    /** 드라이버 경로 그대로: facts 오버로드 → AndroidScanner.observation. 권한 없음(API 31+)·광고 정보 없음(API 26 미만) 모양. */
    private fun observed() = AndroidScanner.observation(
        id,
        -50,
        AndroidAdvertisementMapper.facts(
            timestampNanos = nanos,
            record = null,
            isConnectable = null,
            readDeviceType = { throw SecurityException("BLUETOOTH_CONNECT") },
            advertising = null,
        ),
    )

    @Test
    fun `권한 예외와 API 26 미만 모양에서도 facts 와 observation 이 시각을 싣는다`() {
        val observation = observed()
        assertEquals(nanos, observation.timestampNanos)
        assertEquals(AndroidDeviceType.Unknown, ScanResult(id, -50, observation.advertisement, 0L).androidDeviceType)
    }

    @Test
    fun `플랫폼 문이 준 시각이 client scanResults 의 ScanResult 까지 같은 값으로 온다`() = runTest {
        val h = harness()
        try {
            val results = mutableListOf<ScanResult>()
            backgroundScope.launch { h.client.scanResults.toList(results) }
            h.client.startScan(ScanRequest())
            runCurrent()
            h.fake.scanStarts.last().result(observed())
            runCurrent()
            assertEquals(listOf(nanos), results.map { it.timestampNanos })
        } finally {
            h.close()
        }
    }

    @Test
    fun `equals 와 hashCode 는 시각을 반영한다`() {
        val advertisement = AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null)
        val a = ScanResult(id, -50, advertisement, 1L)
        assertEquals(a, ScanResult(id, -50, advertisement, 1L))
        assertEquals(a.hashCode(), ScanResult(id, -50, advertisement, 1L).hashCode())
        assertNotEquals(a, ScanResult(id, -50, advertisement, 2L))
        assertNotEquals(a.hashCode(), ScanResult(id, -50, advertisement, 2L).hashCode())
        assertEquals(2L, a.copy(timestampNanos = 2L).timestampNanos)
    }

    @Test
    fun `sim 결과 시각은 같은 틱의 legacy 와 extended 결과에서도 엄격히 늘어난다`() = runTest {
        val h = harness()
        try {
            h.fake.advertiser = FakeAndroidPlatform.Advertiser(id, "BLE_LAB_TEST", emptySet(), visible = { true })
            h.fake.extendedAdvertiser = extendedSetAdvertiser()
            h.fake.advertiseExtended(10_000)
            val results = mutableListOf<ScanResult>()
            backgroundScope.launch { h.client.scanResults.toList(results) }
            h.client.startScan(ScanRequest(options = BleScanOptions(extendedAdvertising = true)))
            advanceTimeBy(1_000)
            runCurrent()

            val times = results.map { it.timestampNanos }
            assertTrue("결과가 legacy·extended 둘 다 있어야 한다: ${results.map { it.peripheralId }}", results.map { it.peripheralId }.toSet().size == 2)
            assertTrue("엄격히 늘어야 한다: $times", times.zipWithNext().all { (a, b) -> b > a })
            // 같은 틱 결과는 1 나노초 차이, 첫 결과는 가상 시간(100ms) 이상이다.
            assertTrue("같은 틱 결과가 있어야 한다: $times", times.zipWithNext().any { (a, b) -> b - a == 1L })
            assertTrue(times.first() >= 100_000_000L)
        } finally {
            h.close()
        }
    }
}
