package kr.co.nucode.ble.internal.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.Severity

/**
 * 스캔 정책·상태의 소유자이자 플랫폼 스캔의 유일한 사용자(plan scan-owner D2) — 플랫폼 시작·정지는
 * [port] 로만 나간다. 소비자는 둘: 사용자 요청(최대 1개, [startScan]) 과 재발견([rediscover], 주소별 대기자).
 * 플랫폼 스캔은 한 번에 하나이고, 두 소비자를 [PlatformScanSpec] 합집합으로 담는다(합류 규칙은 [reconcile]).
 * 사용자 결과는 언제나 [matches] 로 한 번 더 거른다 — 주소 필터로 들어온 비대상 광고는 사용자에게 가지 않는다.
 * 재발견 소비자가 있는 동안 플랫폼 스캔 모드는 최소 LowLatency 다(재발견 시간 제한 안에 찾기 위해).
 *
 * 기기 의존(확인 필요): 사용자 필터 스캔 중 cold-cache 연결의 재발견은 합집합 재시작 1회를 쓴다 — Android 스캔
 * 시작 한도(짧은 구간 안 시작 횟수 제한)에 사용자 스캔과 같이 계산된다.
 */
internal class Scanner(
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val emitObservation: (ScanObservation) -> Unit = {},
    private val port: PlatformScanPort,
) : PeripheralRediscovery {

    internal var isScanning: Boolean = false
        private set
    private var activeRequest: ScanRequest? = null

    /** 진행 중 재발견 소비자 — [rediscover] 가 등록하고 그 finally 가 반드시 해제한다. */
    private val rediscoveries = mutableListOf<RediscoveryWaiter>()

    /** 지금 플랫폼에서 도는 스캔의 spec. null = 플랫폼 스캔 없음. 쓰기는 [reconcile]·[stopPlatform] 만. */
    private var platformSpec: PlatformScanSpec? = null

    /**
     * 플랫폼 시작 시도마다 1 증가(예약)하는 스캔 식별 — 실패한 시작도 자기 값을 소비하므로 이후 성공한
     * 시작과 값을 공유하지 않는다. 플랫폼 실패·결과 콜백은 시작 시 캡처한 값을 싣고, 현재 값이 아닌
     * 콜백은 버린다(연결 콜백의 generation 귀속과 같은 규칙). 합류로 인한 내부 재시작도 새 값을 쓴다.
     * 쓰기는 [reconcile] 만.
     */
    internal var scanGeneration: Long = 0L
        private set
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle(null))
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Identical starts are no-ops; a different active request is never implicitly replaced. */
    fun startScan(request: ScanRequest): BleFailure? {
        val current = activeRequest
        if (current != null) return if (current == request) null else alreadyRunning()
        // BleScanFilter owns immutable copies, so this single request instance is safe to share
        // between Android's platform filter, active-request identity, and final predicate.
        val frozenRequest = ScanRequest(request.filter, request.options)
        // 성공한 reconcile 뒤에만 확정한다 — 실패·예외면 되돌려 사용자 상태가 고착되지 않게 한다.
        activeRequest = frozenRequest
        val failure = try {
            reconcile()
        } catch (e: Exception) {
            activeRequest = null
            throw e
        }
        if (failure != null) {
            // 사용자 요청은 등록되지 않는다(scanState 불변). 재시작으로 끊긴 재발견은 같은 실패로 끝난다.
            activeRequest = null
            endRediscoveries(failure)
            return failure
        }
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

    /**
     * 정상 정지 — Idle(null). 사용자 소비자만 빠진다: 재발견이 남아 있으면 플랫폼 스캔은 재시작 없이
     * 그대로 돈다(사용자 필터 결과는 [handle] 에서 버려진다).
     */
    fun stopScan() {
        if (!isScanning) return
        try {
            activeRequest = null
            if (rediscoveries.isEmpty()) stopPlatform()
        } finally {
            // Local request ownership ends even when Android is concurrently revoked.
            activeRequest = null
            setScanState(ScanState.Idle(null))
        }
    }

    /** 어댑터 이탈 등 외부 원인 — 모든 소비자를 끝낸다: 사용자 Idle([failure]), 재발견 Unavailable([failure]). */
    fun stopForAdapter(failure: BleFailure) = endAll(failure)

    /**
     * 재발견 소비자(plan scan-owner 목표 3) — 주소 등록 → 필요하면 (재)시작 → Found / 시간 초과 NotFound /
     * Unavailable. 등록 해제는 finally 에서 반드시 한다(코루틴 취소 포함) — 마지막 소비자면 플랫폼 스캔을 멈춘다.
     * 코어 디스패처에서 불린다(Scanner 의 나머지 상태와 같은 스레드).
     * 주소는 Android 정규형(대문자)으로 맞춰 플랫폼 필터에 싣는다 — MAC 형식이 아니면 플랫폼 스캔과 사용자
     * 소비자를 건드리지 않고 바로 Unavailable(SCAN_START_FAILED). 잘못된 주소 하나가 합집합 재시작을 깨뜨려
     * 돌던 사용자 스캔까지 끝내지 않게 하려는 것이다. 결과 라우팅은 여전히 대소문자를 무시한다.
     * 예상 밖 예외(취소 제외)는 Unavailable(SCAN_START_FAILED) 로 바꾼다 — 연결은 직접 연결 폴백으로 이어진다.
     */
    override suspend fun rediscover(deviceId: PeripheralId, timeoutMillis: Long): RediscoveryOutcome {
        val canonical = deviceId.value.uppercase()
        if (!MAC_ADDRESS.matches(canonical)) return RediscoveryOutcome.Unavailable(FailureMapper.scanStartFailed())
        val waiter = RediscoveryWaiter(PeripheralId(canonical))
        rediscoveries += waiter
        try {
            reconcile()?.let(::failConsumers)
            return withTimeoutOrNull(timeoutMillis) { waiter.outcome.await() } ?: RediscoveryOutcome.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 재시작 도중 포트가 던졌으면 도는 플랫폼 스캔이 없다 — 사용자 소비자도 같은 실패로 끝내
            // Scanning 인데 스캔이 없는 상태를 남기지 않는다.
            val failure = FailureMapper.scanStartFailed()
            failConsumers(failure)
            return RediscoveryOutcome.Unavailable(failure)
        } finally {
            rediscoveries -= waiter
            if (rediscoveries.isEmpty() && activeRequest == null) stopPlatform()
        }
    }

    /**
     * 클라이언트 close — 재발견 소비자를 [failure] 로 끝내고, 사용자 소비자가 없으면 플랫폼 스캔도 멈춘다
     * (대기 코루틴의 finally 는 스코프 취소로 돌지 않을 수 있다).
     */
    fun endRediscoveriesForClose(failure: BleFailure) {
        try {
            if (activeRequest == null) stopPlatform()
        } finally {
            endRediscoveries(failure)
        }
    }

    /**
     * 합류 규칙(plan scan-owner 목표 3) — 지금 소비자들이 원하는 spec 을 도는 플랫폼 스캔이 덮으면 그대로 두고,
     * 못 덮을 때만 한 번 정지 → 합집합으로 시작한다. 재시작 동안 [scanState] 는 건드리지 않는다(Idle 깜빡임 없음).
     * 소비자가 없으면 정지. 반환값은 시작 실패(null = 성공 또는 재시작 불필요) — 소비자 정리는 호출자 몫.
     */
    private fun reconcile(): BleFailure? {
        val desired = desiredSpec() ?: run {
            stopPlatform()
            return null
        }
        val current = platformSpec
        if (current != null && current.covers(desired)) return null
        stopPlatform()
        // 시작 시도마다 generation 을 예약한다 — 플랫폼 호출 전에 올려 콜백 람다가 이 값을 캡처한다.
        val startGeneration = scanGeneration + 1
        scanGeneration = startGeneration
        val onResult: (ScanObservation) -> Unit = { observation -> scope.launch { handle(startGeneration, observation) } }
        val onFailure: (BleFailure) -> Unit = { failure -> scope.launch { handleFailure(startGeneration, failure) } }
        val failure = port.start(desired, onResult, onFailure)
        if (failure == null) platformSpec = desired
        return failure
    }

    /**
     * 사용자 요청 없음 → 주소 필터 + LowLatency. 사용자 전체 스캔 → 주소 필터 불필요(전체가 덮는다).
     * 사용자 필터 있음 → 사용자 필터 ∪ 주소 필터. 재발견이 있으면 모드는 max(사용자 모드, LowLatency),
     * 없으면 사용자 모드 그대로.
     */
    private fun desiredSpec(): PlatformScanSpec? {
        val user = activeRequest
        val addresses = rediscoveries.mapTo(linkedSetOf()) { it.deviceId }
        return when {
            user == null && addresses.isEmpty() -> null
            user == null -> PlatformScanSpec(userFilter = null, addresses = addresses, mode = BleScanMode.LowLatency)
            user.filter.scansAll() -> PlatformScanSpec(user.filter, emptySet(), jointMode(user, addresses))
            else -> PlatformScanSpec(user.filter, addresses, jointMode(user, addresses))
        }
    }

    /** LowLatency 가 가장 높은 모드라 재발견이 있으면 곧 LowLatency 다. */
    private fun jointMode(user: ScanRequest, addresses: Set<PeripheralId>): BleScanMode =
        if (addresses.isEmpty()) user.options.mode else BleScanMode.LowLatency

    /**
     * 도는 스캔이 [desired] 의 모든 소비자를 담는가 — 남는 주소·사용자 필터는 라우팅에서 버리므로 덮음으로 친다.
     * 모드는 도는 쪽이 같거나 높아야 덮는다(재발견이 끝나 원하는 모드가 내려가도 재시작하지 않는다).
     */
    private fun PlatformScanSpec.covers(desired: PlatformScanSpec): Boolean {
        if (mode.rank() < desired.mode.rank()) return false
        if (desired.userFilter != null && userFilter != desired.userFilter) return false
        return userFilter?.scansAll() == true || addresses.containsAll(desired.addresses)
    }

    private fun BleScanMode.rank(): Int = when (this) {
        BleScanMode.LowPower -> 0
        BleScanMode.Balanced -> 1
        BleScanMode.LowLatency -> 2
    }

    /** 플랫폼 필터로 옮길 조건이 없는 사용자 필터 = 전체 스캔(namePrefix 는 [matches] 만 본다). */
    private fun BleScanFilter.scansAll(): Boolean =
        serviceUuids.isEmpty() && manufacturerData.isEmpty() && serviceData.isEmpty()

    private fun stopPlatform() {
        if (platformSpec == null) return
        platformSpec = null
        port.stop()
    }

    /** 도는 스캔의 실패 — 사용자 스캔이면 기존 규칙대로 Error Diagnostic 후 모든 소비자를 같은 실패로 끝낸다. */
    private fun failConsumers(failure: BleFailure) {
        try {
            if (isScanning) emit(BleEvent.Diagnostic(Severity.Error, failure))
        } finally {
            endAll(failure)
        }
    }

    private fun endAll(failure: BleFailure) {
        try {
            stopPlatform()
        } finally {
            endRediscoveries(failure)
            if (isScanning) {
                activeRequest = null
                setScanState(ScanState.Idle(failure))
            }
        }
    }

    private fun endRediscoveries(failure: BleFailure) {
        rediscoveries.forEach { it.outcome.complete(RediscoveryOutcome.Unavailable(failure)) }
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

    /**
     * 플랫폼 결과 콜백 진입점(코어 스레드). [generation] 은 그 콜백을 만든 시작의 generation —
     * 다른(이전) 스캔이거나 멈춘 스캔의 결과는 버린다. 재발견 주소와 같으면 그 대기자를 Found 로 끝내고,
     * 사용자에게는 사용자 필터([matches])를 통과한 것만 간다.
     */
    private fun handle(generation: Long, observation: ScanObservation) {
        if (generation != scanGeneration || platformSpec == null) return
        rediscoveries
            .filter { it.deviceId.value.equals(observation.peripheralId.value, ignoreCase = true) }
            .forEach { it.outcome.complete(RediscoveryOutcome.Found) }
        if (!isScanning) return
        val request = activeRequest ?: return
        if (!matches(request.filter, observation.advertisement)) return
        // One callback has exactly one projection: the structured official ingress.
        emitObservation(observation)
    }

    /**
     * 플랫폼 실패 콜백 진입점(코어 스레드). [generation] 은 그 콜백을 만든 시작의 generation.
     * 다른(이전) 스캔이거나 이미 멈춘 스캔의 실패는 상태를 바꾸지 않고 Warning Diagnostic 만 남긴다 —
     * 먼저 확정된 종료 이유(정지·어댑터·런타임 실패)가 유지된다. 현재 스캔의 실패는 모든 소비자를 끝낸다.
     */
    private fun handleFailure(generation: Long, failure: BleFailure) {
        if (generation != scanGeneration || platformSpec == null) {
            emit(BleEvent.Diagnostic(Severity.Warning, failure))
            return
        }
        failConsumers(failure)
    }

    private fun alreadyRunning() = BleFailure(
        category = FailureCategory("scan"),
        code = FailureCode("SCAN_ALREADY_RUNNING"),
        phase = FailurePhase.Scan,
        retryability = Retryability.Never,
    )
}

/** Android BluetoothAdapter.checkBluetoothAddress 와 같은 형식(대문자 정규형) — scan 패키지는 Android API 를 쓰지 않는다. */
private val MAC_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

/** 재발견 소비자 하나 — [Scanner.rediscover] 호출 하나의 수명. */
private class RediscoveryWaiter(val deviceId: PeripheralId) {
    val outcome = CompletableDeferred<RediscoveryOutcome>()
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

