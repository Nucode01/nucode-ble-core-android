package kr.co.nucode.ble.internal

import java.io.File
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleServiceDataFilter
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.harness.RecordingScanPort
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementFacts
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementMapper
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.internal.scan.ScanStartLedger
import kr.co.nucode.ble.internal.scan.Scanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.internal.scan.RediscoveryOutcome

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerContractTest {
    private val scanner = Scanner({}, CoroutineScope(EmptyCoroutineContext), port = AndroidScanner(central = null), clock = TestClock)

    @Test
    fun `candidate filters are OR and advertised name is AND`() {
        val service = BleUuid(UUID.randomUUID())
        val advertisement = AdvertisementData(
            localName = "beacon-1",
            serviceUuids = setOf(service),
            manufacturerData = mapOf(7 to byteArrayOf(1, 2)),
            serviceData = emptyMap(),
            txPower = null,
            isConnectable = null,
        )
        assertTrue(scanner.matches(
            BleScanFilter(
                manufacturerData = listOf(BleManufacturerDataFilter(9)),
                serviceUuids = listOf(service),
                namePrefix = "beacon",
            ),
            advertisement,
        ))
        assertFalse(scanner.matches(BleScanFilter(serviceUuids = listOf(service), namePrefix = "other"), advertisement))
    }

    @Test
    fun `mapper preserves empty payload and copies it`() {
        val payload = byteArrayOf(4)
        val mapped = AndroidAdvertisementMapper.map(AndroidAdvertisementFacts(
            localName = null,
            serviceUuids = emptySet(),
            manufacturerData = mapOf(1 to payload),
            serviceData = emptyMap(),
            txPower = 0,
            isConnectable = false,
        ))
        payload[0] = 9 // source cannot mutate the mapped value
        assertTrue(mapped.manufacturerData.containsKey(1))
        assertArrayEquals(byteArrayOf(4), mapped.manufacturerData[1])

        val leakedGetterValue = requireNotNull(mapped.manufacturerData[1])
        leakedGetterValue[0] = 7
        assertArrayEquals(byteArrayOf(4), mapped.manufacturerData[1])
    }

    @Test
    fun `started scan retains one immutable request for platform identity and final predicate`() {
        val service = BleUuid(UUID.randomUUID())
        val serviceUuids = mutableListOf(service)
        val bytes = byteArrayOf(1)
        val manufacturers = mutableListOf(BleManufacturerDataFilter(7, bytes))
        val request = ScanRequest(BleScanFilter(serviceUuids = serviceUuids, manufacturerData = manufacturers))
        val port = RecordingScanPort()
        val runningScanner = Scanner(
            emit = {},
            scope = CoroutineScope(EmptyCoroutineContext),
            port = port,
            clock = TestClock,
        )

        assertNull(runningScanner.startScan(request))
        val platformFilter = port.starts.single().spec.userFilter
        serviceUuids.clear()
        manufacturers.clear()
        bytes[0] = 2

        val originalAdvertisement = AdvertisementData(
            localName = null,
            serviceUuids = emptySet(),
            manufacturerData = mapOf(7 to byteArrayOf(1)),
            serviceData = emptyMap(),
            txPower = null,
            isConnectable = null,
        )
        val changedAdvertisement = AdvertisementData(
            localName = null,
            serviceUuids = emptySet(),
            manufacturerData = mapOf(7 to byteArrayOf(2)),
            serviceData = emptyMap(),
            txPower = null,
            isConnectable = null,
        )
        assertTrue(runningScanner.matches(requireNotNull(platformFilter), originalAdvertisement))
        assertFalse(runningScanner.matches(requireNotNull(platformFilter), changedAdvertisement))
        assertNull(runningScanner.startScan(request))

        val failure = requireNotNull(runningScanner.startScan(ScanRequest(BleScanFilter())))
        assertEquals("SCAN_ALREADY_RUNNING", failure.code.value)
    }

    @Test
    fun `client level scan failure does not manufacture a session correlation`() {
        val events = mutableListOf<kr.co.nucode.ble.internal.model.BleEvent>()
        val port = RecordingScanPort {
            kr.co.nucode.ble.BleFailure(
                category = FailureCategory("scan"),
                code = FailureCode("SCAN_START_FAILED"),
                phase = FailurePhase.Scan,
                retryability = Retryability.Unknown,
            )
        }
        val scanner = Scanner(events::add, CoroutineScope(EmptyCoroutineContext), port = port, clock = TestClock)

        runCatching { scanner.startScan(BleScanFilter(), kr.co.nucode.ble.BleScanOptions()) } // 거부는 throw 로도 전달된다

        val failure = (events.single() as kr.co.nucode.ble.internal.model.BleEvent.Diagnostic).failure
        assertNull(failure.context.sessionId)
        assertNull(failure.context.connectionGeneration)
    }

    /**
     * plan scan-owner 불변식 ① — 플랫폼 스캔(`BluetoothLeScanner.startScan/stopScan`)과 그 콜백 객체는
     * platform/android/AndroidScanner 에만 있다. 한계: LE 스캐너 수신자 이름(`*leScanner`, `bluetoothLeScanner`,
     * `scanner()`)과 `: ScanCallback()` 구현으로 잡는다 — 다른 이름의 수신자는 ScanCallback 쪽이 잡는다.
     */
    @Test
    fun `플랫폼 스캔 시작·정지는 AndroidScanner 에만 있다`() {
        val platformCall = Regex("""\b(\w*[Ll]eScanner|scanner\(\))\??\.(startScan|stopScan)\(""")
        val callback = Regex(""":\s*ScanCallback\(\)""")
        val sites = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().let { platformCall.containsMatchIn(it) || callback.containsMatchIn(it) } }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertEquals(listOf("src/main/kotlin/kr/co/nucode/ble/internal/platform/android/AndroidScanner.kt"), sites)
    }

    // ── 플랫폼 스캔 시작 한도(plan scan-start-budget) — 30 s 창 + 여유 1 s 안 성공 시작 5회 ──

    private var clock = 0L
    private val throttlePort = RecordingScanPort()
    private val throttleEvents = mutableListOf<BleEvent>()
    private val service = BleUuid(UUID.randomUUID())
    private val filtered = ScanRequest(BleScanFilter(serviceUuids = listOf(service)), BleScanOptions(BleScanMode.LowPower))
    private val target = PeripheralId("AA:BB:CC:DD:EE:01")

    private fun CoroutineScope.throttledScanner() =
        Scanner(throttleEvents::add, this, port = throttlePort, clock = TestClock, startLedger = ScanStartLedger { clock })

    /** 사용자 시작·정지로 성공한 플랫폼 시작을 [count] 번 쌓는다. */
    private fun Scanner.spendStarts(count: Int) = repeat(count) {
        assertNull(startScan(filtered))
        stopScan()
    }

    private fun TestScope.rediscoverAsync(scanner: Scanner) =
        async { scanner.rediscover(target, 1_000) }.also { runCurrent() }

    @Test
    fun `30 s 안 6번째 사용자 시작은 플랫폼을 부르지 않고 SCAN_START_THROTTLED 로 거부된다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(5)
        clock = 30_000
        val before = scanner.scanState.value
        val thrown = runCatching { scanner.startScan(filtered.filter, filtered.options) }.exceptionOrNull()
        val failure = (thrown as BleException).failure
        assertEquals("SCAN_START_THROTTLED", failure.code.value)
        assertEquals(FailurePhase.Scan, failure.phase)
        assertEquals(Retryability.TransientMayHelp, failure.retryability)
        assertNull(failure.platformDetail)
        assertEquals(5, throttlePort.starts.size)
        assertEquals(before, scanner.scanState.value)
        assertTrue(throttleEvents.any { it is BleEvent.Diagnostic && it.failure.code.value == "SCAN_START_THROTTLED" })
    }

    @Test
    fun `창 31 s 가 지나면 다시 시작할 수 있다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(5)
        clock = 31_001
        assertNull(scanner.startScan(filtered))
        assertEquals(6, throttlePort.starts.size)
    }

    @Test
    fun `플랫폼이 거절한 시작은 한도에 세지 않는다`() = runTest {
        val scanner = throttledScanner()
        throttlePort.onStart = {
            BleFailure(FailureCategory("scan"), FailureCode("SCAN_START_FAILED"), FailurePhase.Scan, Retryability.Unknown)
        }
        repeat(5) { assertEquals("SCAN_START_FAILED", scanner.startScan(filtered)?.code?.value) }
        throttlePort.onStart = { null }
        scanner.spendStarts(5)
        assertEquals(10, throttlePort.starts.size)
    }

    @Test
    fun `사용자 스캔 중 한도 초과면 새 재발견만 Unavailable 이고 사용자 스캔은 유지된다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(4)
        assertNull(scanner.startScan(filtered))
        val outcome = rediscoverAsync(scanner)
        val result = outcome.await()
        assertEquals("SCAN_START_THROTTLED", ((result as RediscoveryOutcome.Unavailable).failure).code.value)
        assertEquals(5, throttlePort.starts.size)
        assertEquals(4, throttlePort.stops)
        assertTrue(throttlePort.running)
        assertEquals(ScanState.Scanning(filtered), scanner.scanState.value)
        assertTrue(throttleEvents.none { it is BleEvent.Diagnostic })
    }

    @Test
    fun `재발견만 도는 중 한도 초과면 사용자 시작만 거부되고 재발견 스캔은 유지된다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(4)
        val outcome = rediscoverAsync(scanner)
        assertEquals(5, throttlePort.starts.size)
        assertEquals("SCAN_START_THROTTLED", scanner.startScan(filtered)?.code?.value)
        runCurrent()
        assertEquals(5, throttlePort.starts.size)
        assertTrue(throttlePort.running)
        assertFalse(outcome.isCompleted)
        assertEquals(ScanState.Idle(null), scanner.scanState.value)
        outcome.cancel()
        runCurrent()
    }

    private val scanAll = ScanRequest(BleScanFilter(), BleScanOptions(BleScanMode.LowLatency))

    @Test
    fun `창 경계 31_000 ms 는 거부되고 31_001 ms 는 허용된다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(5)
        clock = 31_000
        assertEquals("SCAN_START_THROTTLED", scanner.startScan(filtered)?.code?.value)
        assertEquals(5, throttlePort.starts.size)
        clock = 31_001
        assertNull(scanner.startScan(filtered))
        assertEquals(6, throttlePort.starts.size)
    }

    @Test
    fun `도는 스캔이 덮어 재시작하지 않은 재발견은 한도에 세지 않는다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(4)
        clock = 10_000
        assertNull(scanner.startScan(scanAll))
        val outcomes = (1..4).map { n ->
            async { scanner.rediscover(PeripheralId("AA:BB:CC:DD:EE:1$n"), 1_000) }.also { runCurrent() }
        }
        assertEquals(5, throttlePort.starts.size)
        outcomes.forEach { it.cancel() }
        runCurrent()
        scanner.stopScan()
        clock = 31_001
        assertNull(scanner.startScan(filtered))
        assertEquals(6, throttlePort.starts.size)
    }

    @Test
    fun `Balanced 전체 사용자 스캔 중 한도 초과면 재발견은 모드를 못 덮어 Unavailable 이다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(4)
        val balancedAll = ScanRequest(BleScanFilter(), BleScanOptions(BleScanMode.Balanced))
        assertNull(scanner.startScan(balancedAll))
        val outcome = rediscoverAsync(scanner)
        assertTrue(outcome.isCompleted)
        val result = outcome.await()
        assertEquals("SCAN_START_THROTTLED", ((result as RediscoveryOutcome.Unavailable).failure).code.value)
        assertEquals(5, throttlePort.starts.size)
        assertTrue(throttlePort.running)
        assertEquals(ScanState.Scanning(balancedAll), scanner.scanState.value)
    }

    @Test
    fun `두 Scanner 가 한 ledger 를 공유하면 시작을 합산해 거부한다`() = runTest {
        val ledger = ScanStartLedger { clock }
        val otherPort = RecordingScanPort()
        val first = Scanner(throttleEvents::add, this, port = throttlePort, clock = TestClock, startLedger = ledger)
        val second = Scanner({}, this, port = otherPort, clock = TestClock, startLedger = ledger)
        first.spendStarts(3)
        second.spendStarts(2)
        clock = 30_000
        assertEquals("SCAN_START_THROTTLED", first.startScan(filtered)?.code?.value)
        assertEquals(3, throttlePort.starts.size)
        assertEquals(2, otherPort.starts.size)
    }

    @Test
    fun `어댑터 전원 꺼짐 뒤에는 시작 기록이 비워져 시작이 허용된다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(5)
        scanner.stopForAdapter(AdapterState.PoweredOff)
        clock = 1_000
        assertNull(scanner.startScan(filtered))
        assertEquals(6, throttlePort.starts.size)
    }

    @Test
    fun `권한 상실 이탈은 시작 기록을 비우지 않는다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(5)
        scanner.stopForAdapter(AdapterState.Unauthorized(canRequest = true))
        clock = 1_000
        assertEquals("SCAN_START_THROTTLED", scanner.startScan(filtered)?.code?.value)
        assertEquals(5, throttlePort.starts.size)
    }

    @Test
    fun `한도 초과 때 도는 재발견 스캔이 덮는 재발견은 살아남고 새 재발견만 Unavailable 이다`() = runTest {
        val scanner = throttledScanner()
        scanner.spendStarts(4)
        val first = rediscoverAsync(scanner)
        assertEquals(5, throttlePort.starts.size)
        val stopsBefore = throttlePort.stops
        // 다음 디스패처 턴에 다른 주소 재발견 — 합친 spec 은 재시작이 필요하지만 한도 초과
        val second = async { scanner.rediscover(PeripheralId("AA:BB:CC:DD:EE:02"), 1_000) }.also { runCurrent() }
        val result = second.await()
        assertEquals("SCAN_START_THROTTLED", ((result as RediscoveryOutcome.Unavailable).failure).code.value)
        assertFalse(first.isCompleted)
        assertEquals(5, throttlePort.starts.size)
        assertEquals(stopsBefore, throttlePort.stops)
        assertTrue(throttlePort.running)
        // 살아남은 재발견은 도는 스캔의 관측을 계속 받는다 — 거부가 도는 스캔의 generation 을 건드리지 않는다.
        throttlePort.starts.last().onResult(
            ScanObservation(target, -50, AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null)),
        )
        runCurrent()
        assertEquals(RediscoveryOutcome.Found, first.await())
    }
}
