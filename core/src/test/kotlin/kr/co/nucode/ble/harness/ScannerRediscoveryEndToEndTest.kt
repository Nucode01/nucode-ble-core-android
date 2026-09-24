package kr.co.nucode.ble.harness

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.internal.scan.PlatformScanSpec
import kr.co.nucode.ble.internal.scan.ScanObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan scan-owner task 3(T) — 실제 Scanner 재발견 배선(`scannerRediscovery = true`)으로 공개 API 끝에서 끝까지:
 * 필터 사용자 스캔 중 cold-cache connect → 합집합 LowLatency 재시작 1회 → 그 시작에서 주소 관측 → Ready.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerRediscoveryEndToEndTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:31")
    private val other = PeripheralId("AA:BB:CC:DD:EE:32")
    private val service = BleUuid(UUID.randomUUID())
    private val request = ScanRequest(BleScanFilter(serviceUuids = listOf(service)), BleScanOptions(BleScanMode.LowPower))

    private fun observation(peripheral: PeripheralId, withService: Boolean) = ScanObservation(
        peripheral,
        -50,
        AdvertisementData(null, if (withService) setOf(service) else emptySet(), emptyMap(), emptyMap(), null, null),
    )

    @Test
    fun `필터 사용자 스캔 중 cold-cache connect - 합집합 재시작 1회 후 Ready, 사용자 결과는 사용자 필터만`() = runTest {
        val h = harness(scannerRediscovery = true)
        val results = mutableListOf<ScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.client.scanResults.collect { results += it } }
        launch { h.client.startScan(request) }
        runCurrent()
        assertEquals(1, h.fake.scanStarts.size)

        h.connect(id)
        assertEquals(2, h.fake.scanStarts.size)
        assertEquals(1, h.fake.scanStops)
        assertEquals(PlatformScanSpec(request.filter, setOf(id), BleScanMode.LowLatency), h.fake.scanStarts.last().spec)

        // 주소로만 맞는 광고 — 재발견은 Found, 사용자에게는 가지 않는다.
        h.fake.scanStarts.last().result(observation(id, withService = false))
        h.waitUntil("재발견 뒤 Ready") { h.session(id).connectionState.value == ConnectionState.Ready }
        assertTrue("주소 결과가 사용자에게 갔다: $results", results.isEmpty())

        h.fake.scanStarts.last().result(observation(other, withService = true))
        runCurrent()
        assertEquals(listOf(other), results.map { it.peripheralId })

        // 재시작 1회뿐이고 사용자는 내내 Scanning 이다.
        assertEquals(2, h.fake.scanStarts.size)
        assertTrue("사용자가 Scanning 이 아닌 상태를 봤다: ${h.scanStates}", h.scanStates.drop(1).all { it == ScanState.Scanning(request) })
        assertEquals(ScanState.Scanning(request), h.client.scanState.value)
        h.close()
    }

    @Test
    fun `재발견 대기 중 close - 플랫폼 스캔을 멈춘다`() = runTest {
        val h = harness(scannerRediscovery = true)
        h.connect(id)
        assertEquals(1, h.fake.scanStarts.size)
        assertEquals(0, h.fake.scanStops)
        h.close()
        assertEquals(1, h.fake.scanStops)
    }
}
