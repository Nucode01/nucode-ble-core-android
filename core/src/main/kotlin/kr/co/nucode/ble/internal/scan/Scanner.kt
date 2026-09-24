package kr.co.nucode.ble.internal.scan

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter as AndroidScanFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
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
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle(null))
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

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
        setScanState(ScanState.Scanning(frozenRequest))
        return null
    }

    /**
     * Runtime ingress: 거부된 시작은 Diagnostic 을 남긴 뒤 [BleException] 으로 호출자에게 던진다.
     * 같은 요청 재호출은 no-op 이고, 실패 시 [scanState] 는 바뀌지 않는다(Scanning 이 되지 않음).
     */
    fun startScan(filter: BleScanFilter, options: BleScanOptions) {
        val request = ScanRequest(filter, options)
        startScan(request)?.let {
            emit(BleEvent.Diagnostic(Severity.Error, it))
            throw BleException(it)
        }
    }

    /** 정상 정지 — Idle(null). */
    fun stopScan() = stop(lastFailure = null)

    /** 어댑터 이탈 등 외부 원인으로 멈출 때 — Idle([failure]) 로 원인을 남긴다. */
    fun stopForAdapter(failure: BleFailure) = stop(lastFailure = failure)

    private fun stop(lastFailure: BleFailure?) {
        if (!isScanning) return
        try {
            androidScanner.stop()
        } finally {
            // Local request ownership ends even when Android is concurrently revoked.
            activeRequest = null
            setScanState(ScanState.Idle(lastFailure))
        }
    }

    /** [isScanning] 과 [scanState] 를 함께 바꾸는 유일한 경로 — 둘이 어긋나지 않게 한다. */
    private fun setScanState(state: ScanState) {
        isScanning = state is ScanState.Scanning
        _scanState.value = state
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
    internal fun toPlatformFilters(filter: BleScanFilter): List<AndroidScanFilter> =
        androidScanner.toPlatformFilters(filter)

    /** 플랫폼 콜백 진입점(코어 스레드). `internal` 인 이유 — 테스트가 관측을 주입하는 seam. */
    internal fun handle(observation: ScanObservation) {
        val request = activeRequest ?: return
        if (!matches(request.filter, observation.advertisement)) return
        // One callback has exactly one projection: the structured official ingress.
        emitObservation(observation)
    }

    /** 플랫폼 실패 콜백 진입점(코어 스레드). `internal` 인 이유 — 테스트가 런타임 실패를 주입하는 seam. */
    internal fun handleFailure(failure: BleFailure) {
        try {
            emit(BleEvent.Diagnostic(Severity.Error, failure))
        } finally {
            activeRequest = null
            setScanState(ScanState.Idle(failure))
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

/** Structured common fields from one Android callback. */
internal class ScanObservation(
    val peripheralId: PeripheralId,
    val rssi: Int,
    val advertisement: AdvertisementData,
)

private fun ByteArray?.matches(expected: ByteArray?, mask: ByteArray?): Boolean {
    if (this == null) return false
    if (expected == null) return true
    if (size < expected.size) return false
    return expected.indices.all { index ->
        val bitMask = mask?.get(index)?.toInt() ?: 0xFF
        (this[index].toInt() and bitMask) == (expected[index].toInt() and bitMask)
    }
}

