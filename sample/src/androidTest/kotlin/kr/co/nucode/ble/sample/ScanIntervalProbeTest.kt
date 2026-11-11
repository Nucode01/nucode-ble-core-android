package kr.co.nucode.ble.sample

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.android.AndroidBleClientFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * 측정 프로브(CANDIDATE-36c): LowLatency 스캔 창 동안 BLE_LAB_TEST 결과 콜백 수와 간격을 잰다.
 * 인자 `hardwareBle=true` 와 `scanProbe=true` 일 때만 돈다. 창 길이는 인자 `scanProbeSeconds`(기본 10).
 * 기록 줄: logcat 태그 `BleProbe`, `PROBE|android|scanInterval|durationMs=…|count=…|peripherals=…|stamp…|arrival…`.
 * 간격은 첫 기기 결과의 timestampNanos 차이(stamp)와 받은 시각 차이(arrival, elapsedRealtimeNanos), ms. iOS ScanIntervalProbe 와 키가 같다.
 */
class ScanIntervalProbeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        },
    )

    private val args get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Entry(val stamp: Long, val arrival: Long, val id: String)

    @Test
    fun scanInterval(): Unit = runBlocking {
        assumeTrue(args.getString("hardwareBle") == "true" && args.getString("scanProbe") == "true")
        val seconds = args.getString("scanProbeSeconds")?.toInt() ?: 10
        val client = AndroidBleClientFactory.create(context)
        val entries = mutableListOf<Entry>()
        try {
            withTimeout(10_000) { client.adapterState.first { it is AdapterState.Ready } }
            // scanResults 는 지난 값을 주지 않으므로 startScan 전에 구독을 걸어 둔다
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                client.scanResults.collect { r ->
                    val arrival = SystemClock.elapsedRealtimeNanos()
                    synchronized(entries) { entries += Entry(r.timestampNanos, arrival, r.peripheralId.value) }
                }
            }
            client.startScan(
                ScanRequest(BleScanFilter(namePrefix = "BLE_LAB_TEST"), BleScanOptions(mode = BleScanMode.LowLatency)),
            )
            val start = SystemClock.elapsedRealtimeNanos()
            // runBlocking 스레드를 막지 않아야 수집기가 결과를 받은 때 바로 센다
            delay(seconds * 1000L)
            val stateAtEnd = client.scanState.value
            client.stopScan()
            val end = SystemClock.elapsedRealtimeNanos()
            collector.cancel()

            val inside = synchronized(entries) { entries.filter { it.arrival in start..end } }
            val firstId = inside.firstOrNull()?.id
            val target = inside.filter { it.id == firstId }
            val line = listOf(
                "durationMs=${seconds * 1000}",
                "count=${target.size}",
                "peripherals=${inside.map { it.id }.toSet().size}",
                summary("stamp", gaps(target.map { it.stamp / 1_000_000.0 })),
                summary("arrival", gaps(target.map { (it.arrival - start) / 1_000_000.0 })),
                "scanState=${stateAtEnd.label()}",
            ).joinToString("|")
            Log.i(TAG, "PROBE|android|scanInterval|$line")
            assertTrue("BLE_LAB_TEST 결과가 둘 이상이어야 간격을 잰다: $line", target.size > 1)
        } finally {
            client.close()
        }
    }

    private fun ScanState.label(): String = when (this) {
        is ScanState.Scanning -> "Scanning"
        is ScanState.Idle -> lastFailure?.let { "Idle:${it.code.value}" } ?: "Idle"
    }

    private fun gaps(values: List<Double>): List<Double> = values.zipWithNext { a, b -> b - a }

    /** 정렬한 간격의 min·median·p90·max(ms, 소수 3자리)와 0·음수 간격 수. */
    private fun summary(name: String, gaps: List<Double>): String {
        if (gaps.isEmpty()) return "${name}Gaps=0"
        val sorted = gaps.sorted()
        val n = sorted.size
        fun ms(v: Double) = "%.3f".format(java.util.Locale.ROOT, v)
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        return listOf(
            "${name}MinMs=${ms(sorted[0])}",
            "${name}MedianMs=${ms(median)}",
            "${name}P90Ms=${ms(sorted[((n - 1) * 0.9).toInt()])}",
            "${name}MaxMs=${ms(sorted[n - 1])}",
            "${name}ZeroGaps=${gaps.count { it == 0.0 }}",
            "${name}NegGaps=${gaps.count { it < 0.0 }}",
        ).joinToString("|")
    }

    private companion object {
        const val TAG = "BleProbe"
    }
}
