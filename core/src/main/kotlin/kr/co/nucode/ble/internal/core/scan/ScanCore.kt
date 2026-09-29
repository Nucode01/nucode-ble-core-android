package kr.co.nucode.ble.internal.core.scan

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.FailureCategories
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.PlatformScanSpec
import kr.co.nucode.ble.internal.model.ScanObservation

/**
 * 스캔 수명의 순수 상태 기계: `(상태, 사건) → (새 상태, 할 일 목록)`.
 *
 * 소비자는 둘이다 — 사용자 요청(최대 1개)과 재발견(주소별). 플랫폼 스캔은 한 번에 하나이고 두 소비자를
 * [PlatformScanSpec] 합집합으로 담는다. 합류 규칙·소비자 규칙·generation 귀속·시작 한도 판정을 정한다
 * (`contract/states.md` §7). 시간·입출력·스레드·코루틴을 쓰지 않는다. 플랫폼 호출과 시작 기록 잠금은
 * 할 일로 Runtime 에 맡기고, 시작 결과는 [ScanEvent.PlatformStartFinished] 로 돌려받는다.
 *
 * 시작 한도 근거: AOSP AppScanStats — 최근 멈춘 스캔 5개 중 가장 오래된 것의 시작이 30 s 안이면 registerScanner 가
 * status 6 으로 거절하고 앱에는 알려지지 않는다. 여유 1 s 는 그 밖의 시계 차이를 위한 것이다. 한도 값은 AOSP 구현값이라
 * 버전·OEM 마다 다를 수 있다.
 */
internal object ScanCore {
    /** AOSP AppScanStats 의 창 안 최대 시작 횟수. */
    const val START_BUDGET = 5

    /** AOSP AppScanStats 의 시작 횟수 창. */
    const val START_WINDOW_MILLIS = 30_000L

    /** 확인되지 않은 플랫폼 시각 차이(OEM·버전)를 위한 여유. */
    const val START_WINDOW_MARGIN_MILLIS = 1_000L

    /** Android BluetoothAdapter.checkBluetoothAddress 와 같은 형식(대문자 정규형). */
    private val MAC_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    fun step(state: ScanCoreState, event: ScanEvent): ScanStep = when (event) {
        is ScanEvent.StartRequested -> startRequested(state, event.request)
        ScanEvent.StopRequested -> stopRequested(state)
        is ScanEvent.RediscoveryRequested -> rediscoveryRequested(state, event.deviceId)
        is ScanEvent.RediscoveryReleased -> rediscoveryReleased(state, event.id)
        is ScanEvent.RediscoveryEndRequested -> ScanStep(
            state,
            state.rediscoveries
                .filter { it.address.value.equals(event.deviceId.value, ignoreCase = true) }
                .map { ScanEffect.EndRediscovery(it.id, event.outcome) },
        )
        ScanEvent.ReconcileDue -> reconcile(state.copy(reconcileScheduled = false), StartOrigin.Reconcile)
        is ScanEvent.PlatformStartFinished -> startFinished(state, event.result)
        is ScanEvent.PlatformResult -> platformResult(state, event.generation, event.observation)
        is ScanEvent.PlatformFailed -> platformFailed(state, event.generation, event.failure)
        is ScanEvent.AdapterLost -> adapterLost(state, event.cause)
        is ScanEvent.CloseRequested -> closeRequested(state, event.failure)
    }

    fun isUserScanning(state: ScanCoreState): Boolean = state.published is ScanState.Scanning

    /** 사용자 결과의 최종 필터. 서비스 UUID·제조사·서비스 데이터 조건은 OR, namePrefix 는 AND. */
    fun matches(filter: BleScanFilter, advertisement: AdvertisementData): Boolean {
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
     * 시작 한도 판정. [startTimes] 는 성공한 시작 시각(오래된 순), [now] 는 호출 전 시각이다.
     * 한도 안이면 null, 초과면 다음 시작 가능까지 ms.
     */
    fun startBudgetWait(startTimes: List<Long>, now: Long): Long? {
        if (startTimes.size >= START_BUDGET) {
            val waitMillis = startTimes.first() + START_WINDOW_MILLIS + START_WINDOW_MARGIN_MILLIS - now + 1
            if (waitMillis > 0) return waitMillis
        }
        return null
    }

    /** 성공한 시작을 [at] 에 기록한다. 최근 [START_BUDGET] 개만 남는다. */
    fun recordStart(startTimes: List<Long>, at: Long): List<Long> = (startTimes + at).takeLast(START_BUDGET)

    private fun startRequested(state: ScanCoreState, request: ScanRequest): ScanStep {
        val current = state.userRequest
        if (current != null) {
            if (current == request) return ScanStep(state, listOf(ScanEffect.Reply(null)))
            val failure = alreadyRunning()
            return ScanStep(state, listOf(ScanEffect.Diagnostic(Severity.Error, failure), ScanEffect.Reply(failure)))
        }
        // BleScanFilter owns immutable copies, so this single request instance is safe to share
        // between the platform filter, active-request identity, and the final predicate.
        val frozen = ScanRequest(request.filter, request.options)
        // 성공한 reconcile 뒤에만 확정한다 — 실패·예외면 되돌려 사용자 상태가 고착되지 않게 한다.
        return reconcile(state.copy(userRequest = frozen), StartOrigin.UserStart)
    }

    private fun stopRequested(state: ScanCoreState): ScanStep {
        if (!isUserScanning(state)) return ScanStep(state, emptyList())
        val stopping = state.rediscoveries.isEmpty()
        val effects = buildList {
            if (stopping && state.platform != null) add(ScanEffect.StopPlatform)
            add(ScanEffect.PublishState(ScanState.Idle(null)))
        }
        return ScanStep(
            state.copy(
                userRequest = null,
                platform = if (stopping) null else state.platform,
                published = ScanState.Idle(null),
            ),
            effects,
        )
    }

    private fun rediscoveryRequested(state: ScanCoreState, deviceId: PeripheralId): ScanStep {
        val canonical = deviceId.value.uppercase()
        if (!MAC_ADDRESS.matches(canonical)) {
            return ScanStep(state, listOf(ScanEffect.RediscoveryRefused(FailureMapper.scanStartFailed())))
        }
        val id = state.nextConsumerId
        val registered = state.copy(
            rediscoveries = state.rediscoveries + RediscoveryConsumer(id, PeripheralId(canonical)),
            nextConsumerId = id + 1,
        )
        // 같은 턴에 등록된 N 개가 플랫폼 스캔을 N 번 재시작하지 않게 턴 끝 reconcile 한 번으로 모은다.
        return if (registered.reconcileScheduled) {
            ScanStep(registered, listOf(ScanEffect.Registered(id)))
        } else {
            ScanStep(registered.copy(reconcileScheduled = true), listOf(ScanEffect.Registered(id), ScanEffect.ScheduleReconcile))
        }
    }

    private fun rediscoveryReleased(state: ScanCoreState, id: Long): ScanStep {
        val next = state.copy(rediscoveries = state.rediscoveries.filterNot { it.id == id })
        if (next.rediscoveries.isNotEmpty() || next.userRequest != null) return ScanStep(next, emptyList())
        return stopPlatform(next)
    }

    private fun closeRequested(state: ScanCoreState, failure: BleFailure): ScanStep {
        val stopped = if (state.userRequest == null) stopPlatform(state) else ScanStep(state, emptyList())
        return ScanStep(stopped.state, stopped.effects + endRediscoveries(state, failure))
    }

    private fun platformResult(
        state: ScanCoreState,
        generation: Long,
        observation: ScanObservation,
    ): ScanStep {
        if (generation != state.generation || state.platform == null) return ScanStep(state, emptyList())
        val effects = buildList<ScanEffect> {
            state.rediscoveries
                .filter { it.address.value.equals(observation.peripheralId.value, ignoreCase = true) }
                .forEach { add(ScanEffect.EndRediscovery(it.id, RediscoveryOutcome.Found)) }
            val request = state.userRequest
            if (isUserScanning(state) && request != null && matches(request.filter, observation.advertisement)) {
                add(ScanEffect.Deliver(observation))
            }
        }
        return ScanStep(state, effects)
    }

    private fun platformFailed(state: ScanCoreState, generation: Long, failure: BleFailure): ScanStep {
        if (generation != state.generation || state.platform == null) {
            return ScanStep(state, listOf(ScanEffect.Diagnostic(Severity.Warning, failure)))
        }
        return failConsumers(state, failure)
    }

    private fun adapterLost(state: ScanCoreState, cause: AdapterState): ScanStep {
        val userWasScanning = isUserScanning(state)
        val failure = FailureMapper.fromAdapterState(cause)
            .copy(code = FailureCode("SCAN_STOPPED_BY_ADAPTER"), phase = FailurePhase.Scan)
        val ended = endAll(state, failure)
        // 플랫폼 시작 기록이 비워지는 것은 BT off/on 에서만 실측했다. 권한 상실 등 전원이 살아 있는 이탈에서는
        // 플랫폼 기록이 남을 수 있으므로 비우지 않는다.
        val clear = if (cause == AdapterState.PoweredOff || cause == AdapterState.Resetting) {
            listOf(ScanEffect.ClearStartLedger)
        } else {
            emptyList()
        }
        val diagnostic = if (userWasScanning) listOf(ScanEffect.Diagnostic(Severity.Warning, failure)) else emptyList()
        return ScanStep(ended.state, ended.effects + clear + diagnostic)
    }

    /**
     * 합류 규칙 — 지금 소비자들이 원하는 spec 을 도는 플랫폼 스캔이 덮으면 그대로 두고, 못 덮을 때만 한 번
     * 정지 → 합집합으로 시작한다. 재시작 동안 공개 상태는 건드리지 않는다. 소비자가 없으면 정지.
     * 시작이 필요하면 할 일만 내고 결과([ScanEvent.PlatformStartFinished])를 기다린다.
     */
    private fun reconcile(state: ScanCoreState, origin: StartOrigin): ScanStep {
        val desired = desiredSpec(state)
        if (desired == null) {
            val stopped = stopPlatform(state)
            return settle(stopped.state, origin, StartResult.Ok, stopped.effects)
        }
        val current = state.platform
        if (current != null && current.covers(desired)) return settle(state, origin, StartResult.Ok, emptyList())
        return ScanStep(
            state.copy(pendingStart = origin),
            listOf(ScanEffect.StartPlatform(desired, state.generation + 1, stopFirst = current != null)),
        )
    }

    private fun startFinished(state: ScanCoreState, result: StartResult): ScanStep {
        val origin = state.pendingStart ?: return ScanStep(state, emptyList())
        val cleared = state.copy(pendingStart = null)
        val applied = when (result) {
            // 플랫폼을 부르지 않았다 — 도는 스캔과 generation 은 그대로다.
            is StartResult.Throttled -> cleared
            // 시작 시도마다 generation 을 소비하고, 정지 단계에서 platform 은 이미 비었다.
            StartResult.Ok -> cleared.copy(platform = desiredSpec(cleared), generation = cleared.generation + 1)
            is StartResult.Failed -> cleared.copy(platform = null, generation = cleared.generation + 1)
            is StartResult.Threw -> cleared.copy(
                platform = null,
                generation = if (result.afterStop) cleared.generation + 1 else cleared.generation,
            )
            is StartResult.Aborted -> cleared.copy(
                platform = null,
                generation = if (result.afterStop) cleared.generation + 1 else cleared.generation,
            )
        }
        return settle(applied, origin, result, emptyList())
    }

    /** reconcile 결과에 따른 소비자 규칙. [lead] 는 이미 정해진 앞선 할 일이다. */
    private fun settle(state: ScanCoreState, origin: StartOrigin, result: StartResult, lead: List<ScanEffect>): ScanStep {
        val tail = when (origin) {
            StartOrigin.UserStart -> settleUser(state, result)
            StartOrigin.Reconcile -> settleReconcile(state, result)
        }
        return ScanStep(tail.state, lead + tail.effects)
    }

    private fun settleUser(state: ScanCoreState, result: StartResult): ScanStep {
        val request = state.userRequest
        return when (result) {
            StartResult.Ok -> {
                val scanning = ScanState.Scanning(requireNotNull(request))
                ScanStep(
                    state.copy(published = scanning),
                    listOf(ScanEffect.PublishState(scanning), ScanEffect.Reply(null)),
                )
            }
            is StartResult.Throttled -> {
                // 플랫폼은 건드리지 않았다 — 도는 재발견 스캔은 그대로 두고 사용자 요청만 거부한다.
                val failure = startThrottled(result.waitMillis)
                ScanStep(
                    state.copy(userRequest = null),
                    listOf(ScanEffect.Diagnostic(Severity.Error, failure), ScanEffect.Reply(failure)),
                )
            }
            is StartResult.Failed -> {
                // 사용자 요청은 등록되지 않는다. 재시작으로 끊긴 재발견은 같은 실패로 끝난다.
                val rolledBack = state.copy(userRequest = null)
                ScanStep(
                    rolledBack,
                    endRediscoveries(rolledBack, result.failure) +
                        listOf(ScanEffect.Diagnostic(Severity.Error, result.failure), ScanEffect.Reply(result.failure)),
                )
            }
            is StartResult.Threw, is StartResult.Aborted -> ScanStep(state.copy(userRequest = null), emptyList())
        }
    }

    private fun settleReconcile(state: ScanCoreState, result: StartResult): ScanStep = when (result) {
        StartResult.Ok -> ScanStep(state, emptyList())
        is StartResult.Throttled -> {
            val failure = startThrottled(result.waitMillis)
            ScanStep(state, endUncoveredRediscoveries(state, failure))
        }
        is StartResult.Failed -> failConsumers(state, result.failure)
        // 재시작 도중 포트가 던졌으면 도는 플랫폼 스캔이 없다 — 사용자 소비자도 같은 실패로 끝낸다.
        is StartResult.Threw -> failConsumers(state, FailureMapper.scanStartFailed())
        // 취소·Error 는 호출자에게 다시 던져질 뿐 — 소비자를 건드리지 않는다.
        is StartResult.Aborted -> ScanStep(state, emptyList())
    }

    /**
     * 사용자 요청 없음 → 주소 필터 + LowLatency. 사용자 전체 스캔 → 주소 필터 불필요(전체가 덮는다).
     * 사용자 필터 있음 → 사용자 필터 ∪ 주소 필터. 재발견이 있으면 모드는 max(사용자 모드, LowLatency).
     */
    private fun desiredSpec(state: ScanCoreState): PlatformScanSpec? {
        val user = state.userRequest
        val addresses = state.rediscoveries.map { it.address }.toSet()
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

    private fun stopPlatform(state: ScanCoreState): ScanStep =
        if (state.platform == null) ScanStep(state, emptyList()) else ScanStep(state.copy(platform = null), listOf(ScanEffect.StopPlatform))

    /** 도는 스캔의 실패 — 사용자 스캔이면 Error Diagnostic 후 모든 소비자를 같은 실패로 끝낸다. */
    private fun failConsumers(state: ScanCoreState, failure: BleFailure): ScanStep {
        val lead = if (isUserScanning(state)) listOf(ScanEffect.Diagnostic(Severity.Error, failure)) else emptyList()
        val ended = endAll(state, failure)
        return ScanStep(ended.state, lead + ended.effects)
    }

    private fun endAll(state: ScanCoreState, failure: BleFailure): ScanStep {
        val stopped = stopPlatform(state)
        val ends = endRediscoveries(stopped.state, failure)
        if (!isUserScanning(stopped.state)) return ScanStep(stopped.state, stopped.effects + ends)
        val idle = ScanState.Idle(failure)
        return ScanStep(
            stopped.state.copy(userRequest = null, published = idle),
            stopped.effects + ends + ScanEffect.PublishState(idle),
        )
    }

    /** 도는 플랫폼 스캔이 [covers] 규칙으로 덮지 못하는 재발견만 [failure] 로 끝낸다. */
    private fun endUncoveredRediscoveries(state: ScanCoreState, failure: BleFailure): List<ScanEffect> {
        val current = state.platform
        return state.rediscoveries
            .filterNot { current != null && current.covers(PlatformScanSpec(null, setOf(it.address), BleScanMode.LowLatency)) }
            .map { ScanEffect.EndRediscovery(it.id, RediscoveryOutcome.Unavailable(failure)) }
    }

    private fun endRediscoveries(state: ScanCoreState, failure: BleFailure): List<ScanEffect> =
        state.rediscoveries.map { ScanEffect.EndRediscovery(it.id, RediscoveryOutcome.Unavailable(failure)) }

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
