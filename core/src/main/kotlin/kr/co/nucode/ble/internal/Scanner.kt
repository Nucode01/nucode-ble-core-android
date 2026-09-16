package kr.co.nucode.ble.internal

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter as AndroidScanFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleServiceDataFilter
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanFilter
import kr.co.nucode.ble.ScanMode
import kr.co.nucode.ble.ScanOptions
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.platform.android.AndroidScanner

/** One active request plus a final platform-independent predicate. */
internal class Scanner(
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val emitObservation: (ScanObservation) -> Unit = {},
    scanner: () -> BluetoothLeScanner?,
) {
    private val androidScanner = AndroidScanner(scanner)

    /** Unit-test seam for observing the immutable request handed to the platform. */
    internal var startOverride: ((BleScanFilter, BleScanOptions) -> BleFailure?)? = null

    internal var isScanning: Boolean = false
        private set
    private var activeRequest: ScanRequest? = null

    /** Identical starts are no-ops; a different active request is never implicitly replaced. */
    fun startScan(request: ScanRequest): BleFailure? {
        val current = activeRequest
        if (current != null) return if (current == request) null else alreadyRunning()
        // BleScanFilter owns immutable copies, so this single request instance is safe to share
        // between Android's platform filter, active-request identity, and final predicate.
        val frozenRequest = ScanRequest(request.filter, request.options)
        val override = startOverride
        val failure = if (override != null) {
            override(frozenRequest.filter, frozenRequest.options)
        } else {
            androidScanner.start(
                filter = frozenRequest.filter,
                options = frozenRequest.options,
                onObservation = { observation -> scope.launch { handle(observation) } },
                onFailure = { failure -> scope.launch { handleFailure(failure) } },
            )
        }
        if (failure != null) return failure
        activeRequest = frozenRequest
        isScanning = true
        return null
    }

    /** Deprecated BleCore projection until its runtime migration. */
    fun startScan(filter: ScanFilter, options: ScanOptions) {
        val request = ScanRequest(filter.toCommonFilter(), options.toCommonOptions())
        startScan(request)?.let { emit(BleEvent.Diagnostic(Severity.Error, it)) }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            androidScanner.stop()
        } finally {
            // Local request ownership ends even when Android is concurrently revoked.
            activeRequest = null
            isScanning = false
        }
    }

    internal fun matches(filter: BleScanFilter, advertisement: AdvertisementData): Boolean {
        val candidates = buildList {
            filter.serviceUuids.forEach { add(advertisement.serviceUuids.contains(it)) }
            filter.manufacturerData.forEach { candidate ->
                add(advertisement.manufacturerData[candidate.companyId].matches(candidate.data, candidate.mask))
            }
            filter.serviceData.forEach { candidate ->
                add(advertisement.serviceData[candidate.serviceUuid].matches(candidate.data, candidate.mask))
            }
        }
        return (candidates.isEmpty() || candidates.any { it }) &&
            (filter.namePrefix == null || advertisement.localName?.startsWith(filter.namePrefix) == true)
    }

    /** Compatibility seam for the pre-Slice-4 mapping test. */
    internal fun toPlatformFilters(filter: ScanFilter): List<AndroidScanFilter> =
        androidScanner.toPlatformFilters(filter.toCommonFilter())

    private fun handle(observation: ScanObservation) {
        val request = activeRequest ?: return
        if (!matches(request.filter, observation.advertisement)) return
        // One callback has two deliberately distinct projections: the structured
        // official ingress and exactly one deprecated raw compatibility event.
        emitObservation(observation)
        emit(BleEvent.ScanResult(
            deviceId = observation.legacyDeviceId,
            name = observation.advertisement.localName,
            rssi = observation.rssi,
            advData = observation.legacyRaw,
        ))
    }

    private fun handleFailure(failure: BleFailure) {
        try {
            emit(BleEvent.Diagnostic(Severity.Error, failure))
        } finally {
            activeRequest = null
            isScanning = false
            androidScanner.stop()
        }
    }

    private fun alreadyRunning() = BleFailure(
        category = FailureCategory("scan"),
        code = FailureCode("SCAN_ALREADY_RUNNING"),
        phase = FailurePhase.Scan,
        retryability = Retryability.Never,
    )
}

/** Structured common fields plus a copied deprecated raw packet from one Android callback. */
internal class ScanObservation(
    val peripheralId: PeripheralId,
    val legacyDeviceId: DeviceId,
    val rssi: Int,
    val advertisement: AdvertisementData,
    legacyRaw: ByteArray,
) {
    private val rawCopy = legacyRaw.copyOf()
    val legacyRaw: ByteArray get() = rawCopy.copyOf()
}

private fun ByteArray?.matches(expected: ByteArray?, mask: ByteArray?): Boolean {
    if (this == null) return false
    if (expected == null) return true
    if (size < expected.size) return false
    return expected.indices.all { index ->
        val bitMask = mask?.get(index)?.toInt() ?: 0xFF
        (this[index].toInt() and bitMask) == (expected[index].toInt() and bitMask)
    }
}

private fun ScanFilter.toCommonFilter(): BleScanFilter = BleScanFilter(
    serviceUuids = serviceUuids.map { kr.co.nucode.ble.BleUuid(it) },
    namePrefix = namePrefix,
    manufacturerData = manufacturerData.map { BleManufacturerDataFilter(it.companyId, it.data, it.mask) },
    serviceData = serviceData.map { BleServiceDataFilter(kr.co.nucode.ble.BleUuid(it.serviceUuid), it.data, it.mask) },
)

private fun ScanOptions.toCommonOptions(): BleScanOptions = BleScanOptions(
    mode = when (scanMode) {
        ScanMode.LowPower -> BleScanMode.LowPower
        ScanMode.Balanced -> BleScanMode.Balanced
        ScanMode.LowLatency -> BleScanMode.LowLatency
    },
)
