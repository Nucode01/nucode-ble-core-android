package kr.co.nucode.ble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.scan.ScanObservation
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan K task 1 — `scanResults` 유실도 `droppedEventCount` 에 집계된다.
 * `client.events` 수집자는 두지 않는다: 구독자 0 인 SharedFlow 의 tryEmit 은 실패하지 않으므로
 * 증가분은 scanResults 경로에서만 나온다.
 *
 * seam: internal `core.scanner` 의 `startOverride` 로 스캔을 시작하고 `handle(observation)` 으로
 * 플랫폼 콜백을 흉내낸다 — 요청 매칭 → emitObservation → emitScanObservation 실제 경로.
 */
class ScanObservationDropTest {

    @Test(timeout = 5_000)
    fun `느린 scanResults 수집자 뒤로 용량을 넘는 관측은 droppedEventCount 에 집계된다`() {
        val core = BleRuntime(
            config = BleConfig(eventBufferCapacity = 1),
            adapter = { null },
            adapterState = { AdapterState.Ready },
        )
        val client = DefaultBleClient(core)
        val scanner = core.scanner
        scanner.startOverride = { _, _ -> null }
        scanner.startScan(BleScanFilter(), BleScanOptions())
        val observation = ScanObservation(
            PeripheralId("AA:BB:CC:DD:EE:01"),
            -50,
            AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null),
        )

        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            client.scanResults.collect {
                received.countDown()
                release.await(5, TimeUnit.SECONDS) // 느린 수집자: 본문에서 막힌다
            }
        }
        try {
            // 수집자 구독 전 관측은 구독자 0 이라 조용히 사라진다(유실 아님) — 받을 때까지 반복.
            while (!received.await(10, TimeUnit.MILLISECONDS)) scanner.handle(observation)
            val before = core.droppedEventCount

            repeat(3) { scanner.handle(observation) }

            assertTrue(
                "scanResults 유실이 집계돼야 한다: before=$before after=${core.droppedEventCount}",
                core.droppedEventCount > before,
            )
        } finally {
            release.countDown()
            scope.cancel()
            client.close()
        }
    }
}
