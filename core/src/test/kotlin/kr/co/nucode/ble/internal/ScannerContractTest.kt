package kr.co.nucode.ble.internal

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
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementFacts
import kr.co.nucode.ble.internal.platform.android.AndroidAdvertisementMapper
import kr.co.nucode.ble.internal.scan.Scanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerContractTest {
    private val scanner = Scanner({}, CoroutineScope(EmptyCoroutineContext)) { null }

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
        var platformFilter: BleScanFilter? = null
        val runningScanner = Scanner(
            emit = {},
            scope = CoroutineScope(EmptyCoroutineContext),
            scanner = { null },
        )
        runningScanner.startOverride = { filter, _, _, _ -> platformFilter = filter; null }

        assertNull(runningScanner.startScan(request))
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
        val events = mutableListOf<kr.co.nucode.ble.internal.event.BleEvent>()
        val scanner = Scanner(events::add, CoroutineScope(EmptyCoroutineContext)) { null }
        scanner.startOverride = { _, _, _, _ ->
            kr.co.nucode.ble.BleFailure(
                category = FailureCategory("scan"),
                code = FailureCode("SCAN_START_FAILED"),
                phase = FailurePhase.Scan,
                retryability = Retryability.Unknown,
            )
        }

        runCatching { scanner.startScan(BleScanFilter(), kr.co.nucode.ble.BleScanOptions()) } // 거부는 throw 로도 전달된다

        val failure = (events.single() as kr.co.nucode.ble.internal.event.BleEvent.Diagnostic).failure
        assertNull(failure.context.sessionId)
        assertNull(failure.context.connectionGeneration)
    }
}
