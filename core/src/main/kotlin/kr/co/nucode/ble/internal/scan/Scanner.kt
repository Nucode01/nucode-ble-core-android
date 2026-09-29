package kr.co.nucode.ble.internal.scan

import kr.co.nucode.ble.internal.port.PlatformScanPort
import kr.co.nucode.ble.internal.port.PlatformScanSpec
import kr.co.nucode.ble.internal.model.ScanObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.FailureCategories
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.scan.ScanStartLedger.Companion.START_BUDGET
import kr.co.nucode.ble.internal.scan.ScanStartLedger.Companion.START_WINDOW_MARGIN_MILLIS
import kr.co.nucode.ble.internal.scan.ScanStartLedger.Companion.START_WINDOW_MILLIS

/**
 * 스캔 정책·상태의 소유자이자 플랫폼 스캔의 유일한 사용자(plan scan-owner D2) — 플랫폼 시작·정지는
 * [port] 로만 나간다. 소비자는 둘: 사용자 요청(최대 1개, [startScan]) 과 재발견([rediscover], 주소별 대기자).
 * 플랫폼 스캔은 한 번에 하나이고, 두 소비자를 [PlatformScanSpec] 합집합으로 담는다(합류 규칙은 [reconcile]).
 * 사용자 결과는 언제나 [matches] 로 한 번 더 거른다 — 주소 필터로 들어온 비대상 광고는 사용자에게 가지 않는다.
 * 재발견 소비자가 있는 동안 플랫폼 스캔 모드는 최소 LowLatency 다(재발견 시간 제한 안에 찾기 위해).
 *
 * 기기 의존(확인 필요): 사용자 필터 스캔 중 cold-cache 연결의 재발견은 합집합 재시작 1회를 쓴다 — Android 스캔
 * 시작 한도(짧은 구간 안 시작 횟수 제한)에 사용자 스캔과 같이 계산된다.
 *
 * 시작 한도(plan scan-start-budget): 성공한 플랫폼 시작 시각을 세어, 최근 [START_BUDGET] 회의 첫 시작이
 * [START_WINDOW_MILLIS] + [START_WINDOW_MARGIN_MILLIS] 안이면 플랫폼을 부르지 않고 SCAN_START_THROTTLED 로 거부한다.
 * 근거: AOSP AppScanStats — 최근 멈춘 스캔 5개 중 가장 오래된 것의 시작이 30 s 안이면(DeviceConfig 기본값)
 * registerScanner 가 status 6(SCAN_FAILED_SCANNING_TOO_FREQUENTLY)으로 거절하고, BluetoothLeScanner 는 이를 앱에
 * 알리지 않는다(onScanFailed 없음) — 실기기 P5(Pixel 5a/Android 14)에서 6번째 시작이 Scanning 인데 결과 0 으로 관측. 기록 시각은 플랫폼 시작 호출이
 * 돌아온 뒤라 플랫폼 기록(등록 왕복 뒤)보다 늦다. 여유 1 s 는 그 밖의 시계 차이를 위한 것이다. 한계: 한도 값은
 * AOSP 구현값이라 버전·OEM 마다 다를 수 있고, 같은 앱이 라이브러리 밖에서 한 스캔은 셀 수 없다 — 한도 안이라는 것이
 * 플랫폼 수락을 보장하지 않는다.
 * 한도 초과여도 도는 플랫폼 스캔은 멈추지 않는다 — 새로 들어온 소비자만 실패한다([startScan]·[scheduleReconcile]).
 */
internal class Scanner(
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val emitObservation: (ScanObservation) -> Unit = {},
    private val port: PlatformScanPort,
    /** 재발견 시간 제한과 기본 [startLedger] 의 시작 한도 창 계산용 시계 포트. Factory 가 운영 시계를 넣고 테스트는 시험 시계를 넣는다. */
    private val clock: ClockPort,
    /**
     * 성공한 플랫폼 시작 기록. 기본값은 이 Scanner 전용(테스트 격리, 시계 = [clock]) — Factory 조립(`androidRuntime`)만
     * 프로세스 공유 기록(시계 elapsedRealtime)을 넘긴다(한도는 앱 단위).
     */
    private val startLedger: ScanStartLedger = ScanStartLedger(clock::elapsedMillis),
) : PeripheralRediscovery {

    internal var isScanning: Boolean = false
        private set
    private var activeRequest: ScanRequest? = null

    /** 진행 중 재발견 소비자 — [rediscover] 가 등록하고 그 finally 가 반드시 해제한다. */
    private val rediscoveries = mutableListOf<RediscoveryWaiter>()

    /** [scheduleReconcile] 가 걸어 둔 턴 끝 reconcile 이 아직 돌지 않았는가. */
    private var reconcileScheduled = false

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
        val result = try {
            reconcile()
        } catch (e: Exception) {
            activeRequest = null
            throw e
        }
        when (result) {
            is ReconcileResult.Throttled -> {
                // 플랫폼은 건드리지 않았다 — 도는 재발견 스캔은 그대로 두고 사용자 요청만 거부한다(scanState 불변).
                activeRequest = null
                return result.failure
            }
            is ReconcileResult.StartFailed -> {
                // 사용자 요청은 등록되지 않는다(scanState 불변). 재시작으로 끊긴 재발견은 같은 실패로 끝난다.
                activeRequest = null
                endRediscoveries(result.failure)
                return result.failure
            }
            ReconcileResult.Ok -> Unit
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

    /**
     * 어댑터 이탈 — 스캔도 어댑터와 함께 죽는다(review L). 모든 소비자를 끝낸다: 사용자 Idle(실패),
     * 재발견 Unavailable(실패). isScanning 잔류를 두면 BT 복귀 후 startScan() 이 조용한 no-op 이 된다.
     * 소비자에게는 Diagnostic 으로 알린다(onScanFailed 는 이 경로에서 보장되지 않는다) — 단 사용자 스캔이
     * 끊겼을 때만. 재발견만 돌던 플랫폼 스캔도 함께 끝나지만(재발견 Unavailable) Diagnostic 은 없다.
     */
    fun stopForAdapter(cause: AdapterState) {
        val userWasScanning = isScanning
        val failure = FailureMapper.fromAdapterState(cause)
            .copy(code = FailureCode("SCAN_STOPPED_BY_ADAPTER"), phase = FailurePhase.Scan)
        stopForAdapter(failure)
        // 플랫폼 시작 기록이 비워지는 것은 BT off/on 에서만 실측했다(Pixel 5a / Android 14, [ScanStartLedger.clear]).
        // 권한 상실 등 전원이 살아 있는 이탈에서는 플랫폼 기록이 남을 수 있으므로 비우지 않는다.
        if (cause == AdapterState.PoweredOff || cause == AdapterState.Resetting) startLedger.clear()
        if (userWasScanning) emit(BleEvent.Diagnostic(Severity.Warning, failure))
    }

    /**
     * 외부 원인 [failure] 로 모든 소비자를 끝낸다(Diagnostic 없음): 사용자 Idle([failure]), 재발견 Unavailable([failure]).
     * 시작 기록 비움은 원인을 아는 [stopForAdapter] (AdapterState) 가 정한다.
     */
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
            scheduleReconcile()
            return clock.awaitWithinOrNull(timeoutMillis) { waiter.outcome.await() } ?: RediscoveryOutcome.NotFound
        } finally {
            rediscoveries -= waiter
            if (rediscoveries.isEmpty() && activeRequest == null) stopPlatform()
        }
    }

    /**
     * 재발견 등록의 (재)시작을 이번 디스패처 턴 끝의 [reconcile] 한 번으로 모은다(review MED-3) — 같은 턴에
     * 등록된 N 개(예: 어댑터 복귀로 재연결되는 autoReconnect 세션 N 개)가 플랫폼 스캔을 N 번 재시작하지 않는다.
     * 시작 실패는 즉시 reconcile 때와 같이 모든 소비자에게 퍼진다([failConsumers]). 시작 한도 초과는 도는 스캔이
     * 덮지 못하는(모드 포함 [covers] 규칙) 재발견만 Unavailable 로 끝낸다(사용자 스캔·Diagnostic 불변). 사용자 startScan/stopScan 은
     * 동기 API 라 지금처럼 즉시 reconcile 한다.
     */
    private fun scheduleReconcile() {
        if (reconcileScheduled) return
        reconcileScheduled = true
        scope.launch {
            reconcileScheduled = false
            val result = try {
                reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 재시작 도중 포트가 던졌으면 도는 플랫폼 스캔이 없다 — 사용자 소비자도 같은 실패로 끝내
                // Scanning 인데 스캔이 없는 상태를 남기지 않는다.
                ReconcileResult.StartFailed(FailureMapper.scanStartFailed())
            }
            when (result) {
                is ReconcileResult.Throttled -> endUncoveredRediscoveries(result.failure)
                is ReconcileResult.StartFailed -> failConsumers(result.failure)
                ReconcileResult.Ok -> Unit
            }
        }
    }

    /**
     * 한 주소의 재발견 소비자를 지금 [outcome] 으로 끝낸다(review LOW-4 — 재발견 중 disconnect()).
     * 등록 해제와 마지막 소비자일 때의 플랫폼 정지는 [rediscover] 의 finally 가 resume 에서 한다.
     * 대기 중인 소비자가 없으면 아무 것도 하지 않는다.
     */
    override fun endRediscovery(deviceId: PeripheralId, outcome: RediscoveryOutcome) {
        rediscoveries
            .filter { it.deviceId.value.equals(deviceId.value, ignoreCase = true) }
            .forEach { it.outcome.complete(outcome) }
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
     * 소비자가 없으면 정지. 시작 한도 판정은 [stopPlatform] 전에 한다 — 초과면 도는 스캔을 그대로 두고 Throttled.
     * 반환값의 소비자 정리는 호출자 몫.
     */
    private fun reconcile(): ReconcileResult {
        val desired = desiredSpec() ?: run {
            stopPlatform()
            return ReconcileResult.Ok
        }
        val current = platformSpec
        if (current != null && current.covers(desired)) return ReconcileResult.Ok
        // 판정 → 정지·시작 → 성공 기록은 ledger 잠금 안에서 원자적으로 — 공유 기록을 쓰는 다른 클라이언트와 경합하지 않게.
        var failure: BleFailure? = null
        val waitMillis = startLedger.tryStart {
            failure = startPlatform(desired)
            failure == null
        }
        if (waitMillis != null) return ReconcileResult.Throttled(startThrottled(waitMillis))
        return failure?.let { ReconcileResult.StartFailed(it) } ?: ReconcileResult.Ok
    }

    /** 도는 스캔을 멈추고 [desired] 로 시작한다 — 반환은 플랫폼 시작 실패(null = 성공). [reconcile] 의 ledger 잠금 안에서만. */
    private fun startPlatform(desired: PlatformScanSpec): BleFailure? {
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

    /**
     * 시작 한도 초과 — 도는 플랫폼 스캔이 [covers] 규칙으로 덮지 못하는 재발견만 [failure] 로 끝낸다. 재발견에 필요한
     * spec 은 그 주소를 포함하고 모드가 LowLatency 이상이다(클래스 KDoc 불변식) — 예: Balanced 전체 스캔은 덮지 못한다.
     */
    private fun endUncoveredRediscoveries(failure: BleFailure) {
        val current = platformSpec
        rediscoveries
            .filterNot { current != null && current.covers(PlatformScanSpec(null, setOf(it.deviceId), BleScanMode.LowLatency)) }
            .forEach { it.outcome.complete(RediscoveryOutcome.Unavailable(failure)) }
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
        category = FailureCategories.of("SCAN_ALREADY_RUNNING"),
        code = FailureCode("SCAN_ALREADY_RUNNING"),
        phase = FailurePhase.Scan,
        retryability = Retryability.Never,
    )

    private fun startThrottled(waitMillis: Long) = BleFailure(
        category = FailureCategories.of("SCAN_START_THROTTLED"),
        code = FailureCode("SCAN_START_THROTTLED"),
        phase = FailurePhase.Scan,
        retryability = Retryability.TransientMayHelp,
        platformDetail = null,
        message = "Platform scan start budget exhausted ($START_BUDGET starts within " +
            "${START_WINDOW_MILLIS + START_WINDOW_MARGIN_MILLIS} ms); " +
            "next start possible in $waitMillis ms",
    )

    /** [reconcile] 결과 — 호출자가 소비자 규칙을 고른다. */
    private sealed interface ReconcileResult {
        /** 성공한 시작, 재시작 불필요, 또는 정지. */
        data object Ok : ReconcileResult
        /** 플랫폼이 시작을 거절했다(도는 스캔 없음). */
        data class StartFailed(val failure: BleFailure) : ReconcileResult
        /** 시작 한도 초과 — 플랫폼을 부르지 않았고 도는 스캔은 그대로다. */
        data class Throttled(val failure: BleFailure) : ReconcileResult
    }
}

/** Android BluetoothAdapter.checkBluetoothAddress 와 같은 형식(대문자 정규형) — scan 패키지는 Android API 를 쓰지 않는다. */
private val MAC_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

/** 재발견 소비자 하나 — [Scanner.rediscover] 호출 하나의 수명. */
private class RediscoveryWaiter(val deviceId: PeripheralId) {
    val outcome = CompletableDeferred<RediscoveryOutcome>()
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

