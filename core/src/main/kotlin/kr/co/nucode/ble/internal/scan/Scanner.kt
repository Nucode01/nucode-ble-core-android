package kr.co.nucode.ble.internal.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.internal.core.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.core.scan.ScanCore
import kr.co.nucode.ble.internal.core.scan.ScanCoreState
import kr.co.nucode.ble.internal.core.scan.ScanEffect
import kr.co.nucode.ble.internal.core.scan.ScanEvent
import kr.co.nucode.ble.internal.core.scan.StartResult
import kr.co.nucode.ble.internal.core.scan.StartOrigin
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.internal.port.PlatformScanPort

/**
 * 스캔 수명의 Runtime 셸 — 플랫폼 시작·정지는 [port] 로만 나간다. 판단(합류 규칙·소비자 규칙·generation 귀속·
 * 시작 한도 판정·실패 조립)은 전부 [ScanCore] 의 순수 `step` 이 하고, 이 클래스는 입구(API 호출·플랫폼 콜백·턴 끝 예약)를
 * [ScanEvent] 하나로 바꿔 넣은 뒤 돌려받은 [ScanEffect] 를 순서대로 실행한다. 든 것은 [ScanCoreState] 하나, 재발견 대기자의
 * handle 표(열쇠 = Core 가 매긴 소비자 번호), 게시용 거울([scanState])뿐이다. 거울은 [ScanEffect.PublishState] 실행기만 쓴다.
 *
 * 시작 한도(plan scan-start-budget): 한도 판정·상수·근거는 [ScanCore], 프로세스 공유 기록과 잠금은 [ScanStartLedger]. 판정 →
 * 정지·시작 → 기록은 ledger 잠금 안에서 원자적이다(시작 실행기). 한도 초과여도 도는 플랫폼 스캔은 멈추지 않는다.
 *
 * 할 일 실행 중 예외는 남은 할 일을 모두 실행한 뒤 첫 예외를 다시 던진다.
 *
 * R5 규칙: Core 밖 Domain 인 이 파일은 스캔 공개 상태 값·판정 실패 조립·한도 상수를 이름으로 만들지 않고,
 * 상태 칸을 가변으로 들지 않는다(`ArchitectureRulesTest.R5_scanAdapterCoreIsPure`).
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

    /** 스캔 수명의 진실. 쓰기는 [dispatch] 와 시작 실행기의 결과 사건뿐이다. */
    private var state = ScanCoreState()

    /** 진행 중 재발견 대기자 — 열쇠는 Core 가 매긴 소비자 번호. [rediscover] 가 만들고 그 finally 가 해제한다. */
    private val handles = HashMap<Long, CompletableDeferred<RediscoveryOutcome>>()

    private val mirror = MutableStateFlow(state.published)
    val scanState: StateFlow<ScanState> = mirror.asStateFlow()

    internal val isScanning: Boolean get() = ScanCore.isUserScanning(state)

    /** 플랫폼 시작 시도마다 1 증가하는 스캔 식별(Core 가 매긴다). */
    internal val scanGeneration: Long get() = state.generation

    /** 한 [dispatch] 호출 동안 할 일 실행기가 모으는 결과. */
    private class Run {
        var replied = false
        var reply: BleFailure? = null
        var registered: Long? = null
        var refused: BleFailure? = null
        var error: Throwable? = null
    }

    /** Identical starts are no-ops; a different active request is never implicitly replaced. */
    fun startScan(request: ScanRequest): BleFailure? = dispatch(ScanEvent.StartRequested(request)).reply

    /**
     * Runtime ingress: 거부된 시작은 Core 가 Diagnostic 을 낸 뒤 [BleException] 으로 호출자에게 던진다.
     * 같은 요청 재호출은 no-op 이고, 실패 시 [scanState] 는 바뀌지 않는다(Scanning 이 되지 않음).
     */
    fun startScan(filter: BleScanFilter, options: BleScanOptions) {
        startScan(ScanRequest(filter, options))?.let { throw BleException(it) }
    }

    /** 정상 정지 — 사용자 소비자만 빠진다. 재발견이 남아 있으면 플랫폼 스캔은 그대로 돈다. */
    fun stopScan() {
        dispatch(ScanEvent.StopRequested)
    }

    /** 어댑터 이탈 — 스캔도 어댑터와 함께 죽는다. 모든 소비자를 끝내는 규칙은 [ScanCore] 가 정한다. */
    fun stopForAdapter(cause: AdapterState) {
        dispatch(ScanEvent.AdapterLost(cause))
    }

    /**
     * 재발견 소비자 — 등록 → (턴 끝 reconcile 이) 필요하면 (재)시작 → Found / 시간 초과 NotFound / Unavailable.
     * 등록 해제는 finally 에서 반드시 한다(코루틴 취소 포함). 코어 디스패처에서 불린다.
     * 주소 형식 검사·합류 규칙은 [ScanCore] 가 한다. 시간 제한은 [clock] 포트로 잰다.
     */
    override suspend fun rediscover(deviceId: PeripheralId, timeoutMillis: Long): RediscoveryOutcome {
        val run = Run()
        try {
            dispatch(ScanEvent.RediscoveryRequested(deviceId), run)
            run.refused?.let { return RediscoveryOutcome.Unavailable(it) }
            val outcome = handles.getValue(requireNotNull(run.registered))
            return clock.awaitWithinOrNull(timeoutMillis) { outcome.await() } ?: RediscoveryOutcome.NotFound
        } finally {
            run.registered?.let { id ->
                handles.remove(id)
                dispatch(ScanEvent.RediscoveryReleased(id))
            }
        }
    }

    /** 한 주소의 재발견 소비자를 지금 [outcome] 으로 끝낸다. 등록 해제는 [rediscover] 의 finally 가 한다. */
    override fun endRediscovery(deviceId: PeripheralId, outcome: RediscoveryOutcome) {
        dispatch(ScanEvent.RediscoveryEndRequested(deviceId, outcome))
    }

    /** 클라이언트 close — 재발견 소비자를 [failure] 로 끝내고, 사용자 소비자가 없으면 플랫폼 스캔도 멈춘다. */
    fun endRediscoveriesForClose(failure: BleFailure) {
        dispatch(ScanEvent.CloseRequested(failure))
    }

    /** 사건 하나를 Core 에 넣고 상태를 바꾼 뒤 할 일을 실행한다. 실행 중 첫 예외는 남은 할 일 뒤에 다시 던진다. */
    private fun dispatch(event: ScanEvent, run: Run = Run()): Run {
        step(event, run)
        run.error?.let { throw it }
        return run
    }

    private fun step(event: ScanEvent, run: Run) {
        val next = ScanCore.step(state, event)
        state = next.state
        next.effects.forEach { effect ->
            try {
                execute(effect, run)
            } catch (e: Throwable) {
                if (run.error == null) run.error = e
            }
        }
    }

    private fun execute(effect: ScanEffect, run: Run) {
        when (effect) {
            is ScanEffect.StartPlatform -> startPlatform(effect, run)
            ScanEffect.StopPlatform -> port.stop()
            is ScanEffect.PublishState -> mirror.value = effect.state
            is ScanEffect.Deliver -> emitObservation(effect.observation)
            is ScanEffect.Registered -> {
                handles[effect.id] = CompletableDeferred()
                run.registered = effect.id
            }
            is ScanEffect.EndRediscovery -> handles[effect.id]?.complete(effect.outcome)
            is ScanEffect.RediscoveryRefused -> run.refused = effect.failure
            is ScanEffect.Reply -> {
                run.replied = true
                run.reply = effect.failure
            }
            is ScanEffect.Diagnostic -> emit(BleEvent.Diagnostic(effect.severity, effect.failure))
            ScanEffect.ScheduleReconcile -> scope.launch { dispatch(ScanEvent.ReconcileDue) }
            ScanEffect.ClearStartLedger -> startLedger.clear()
        }
    }

    /**
     * 판정 → 정지·시작 → 성공 기록을 ledger 잠금 안에서 원자적으로 한다 — 공유 기록을 쓰는 다른 클라이언트와 경합하지 않게.
     * 결과(Ok·Failed·Throttled·Threw)는 같은 호출 안에서 Core 에 돌려준다. 콜백은 시작 시 받은 generation 을 캡처해 우편함
     * (scope)으로 넣는다. 포트가 던진 예외는 턴 끝 reconcile 이 기다릴 때만 삼키고(Core 가 실패로 처리), 아니면 다시 던진다.
     */
    private fun startPlatform(effect: ScanEffect.StartPlatform, run: Run) {
        val generation = effect.generation
        val onResult: (ScanObservation) -> Unit = { observation ->
            scope.launch { dispatch(ScanEvent.PlatformResult(generation, observation)) }
        }
        val onFailure: (BleFailure) -> Unit = { failure ->
            scope.launch { dispatch(ScanEvent.PlatformFailed(generation, failure)) }
        }
        val origin = state.pendingStart
        var failure: BleFailure? = null
        var thrown: Throwable? = null
        var stopped = !effect.stopFirst
        val waitMillis = startLedger.tryStart {
            try {
                if (effect.stopFirst) port.stop()
                stopped = true
                failure = port.start(effect.spec, onResult, onFailure)
            } catch (e: Throwable) {
                thrown = e
            }
            thrown == null && failure == null
        }
        val result = when {
            waitMillis != null -> StartResult.Throttled(waitMillis)
            thrown is CancellationException || (thrown != null && thrown !is Exception) -> StartResult.Aborted(afterStop = stopped)
            thrown != null -> StartResult.Threw(afterStop = stopped)
            else -> failure?.let { StartResult.Failed(it) } ?: StartResult.Ok
        }
        step(ScanEvent.PlatformStartFinished(result), run)
        thrown?.let { if (it !is Exception || it is CancellationException || origin == StartOrigin.UserStart) throw it }
    }
}
