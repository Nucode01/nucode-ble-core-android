package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothAdapter
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.adapter.AdapterMonitor
import kr.co.nucode.ble.internal.connection.LinkCacheWarmth
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.connection.LinkSetupWiring
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.event.DiagnosticObservation
import kr.co.nucode.ble.internal.event.ClientEventBus
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.ConnectAdmission
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.gatt.OperationQueue
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.PlatformScanPort
import kr.co.nucode.ble.internal.scan.ScanObservation
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * 코어 진입점. 유일한 공개 진입점이다(설계 §3 규칙 4 — facade/Manager/Impl 겹 금지).
 *
 * private 주 생성자 + provider 주입(plan §접근 ①) 구조다 — 플랫폼 [Context]/[BluetoothAdapter]
 * 를 직접 들고 있는 대신 provider 람다([adapter])로 매 호출 시 다시 얻는다. 공개 생성자와
 * 내부 테스트 생성자가 이 주 생성자로 수렴한다(plan task 5 §작업①).
 *
 * [central] 은 프로덕션 생성자가 applicationContext 로 만든다 — Activity 를 잡으면 코어가
 * 소유하는 [coreScope](규칙 6, 수집자 0 이어도 계속 돈다)가 Activity 보다 오래 살아
 * 라이브러리가 누수를 만든다. 내부 테스트 생성자 경로에서는 null 이다.
 */
/**
 * The one live BLE runtime.  It owns the dispatcher, registry, scanner and all
 * platform workers; compatibility APIs must delegate to this object rather
 * than becoming a second owner.
 */
internal class BleRuntime private constructor(
    private val central: AndroidCentral?,
    config: BleConfig,
    adapterProvider: (() -> BluetoothAdapter?)?,
    private val registerAdapterReceiver: Boolean,
    private val gattDriverFactory: ((CoroutineScope) -> GattDriver)?,
    private val gattOperationTargetResolver: GattOperationTargetResolver?,
    private val rediscoveryOverride: PeripheralRediscovery?,
    /** 플랫폼 스캔 문 seam — null 이면 운영 [kr.co.nucode.ble.internal.platform.android.AndroidScanner]. */
    private val scanPortOverride: PlatformScanPort?,
    /** 테스트 opt-in — true 면 central 없이도 재발견을 실제 [scanner] 소비자로 배선한다(e2e 하네스용). */
    private val scannerRediscovery: Boolean = false,
    /**
     * plan G task 1(G1) 어댑터 상태 probe seam — 있으면 AdapterMonitor 의 상태 계산이 실제
     * 어댑터/권한 판정을 완전히 건너뛰고 이 람다 값을 그대로 쓴다. 프로덕션 생성자는
     * 이 파라미터를 절대 전달하지 않는다(항상 null) — 전달하면 권한 판정(D7)을 우회해
     * 실제 기기에서 미허용 상태를 Ready 로 오판할 수 있다. 테스트 생성자만
     * `adapterState` 이름으로 받아 여기로 넘긴다.
     */
    private val adapterStateProbe: (() -> AdapterState)? = null,
) {

    private val closeGate = AtomicBoolean(false)
    private val closed: Boolean get() = closeGate.get()
    private val closeCompleted = CompletableDeferred<Unit>()
    @Volatile
    private var closedOfficialSnapshot: ClientSnapshot? = null

    /**
     * 지연 정리(`runCloseCleanup`) 도중 잡힌 예외(plan `runtime-no-runblocking/v2` review MEDIUM).
     * `null` = 정리가 예외 없이 끝났다. 정리는 코어 스레드에서만 도는데, 그 스레드의 uncaught
     * handler 로 예외를 올리면 Android 프로세스가 죽는다 — 그래서 여기 기록하고 삼킨다.
     * 테스트 전용 관측 지점이라 `internal`이다.
     */
    @Volatile
    internal var closeFailure: Throwable? = null
        private set

    /**
     * 어댑터 획득 provider — 프로덕션 경로는 [central]에 매 호출 시 다시 위임해 BT off → on
     * 전환 사이에 갱신되지 않는 낡은 참조를 쓰지 않는다(GattBridge/Scanner 의 같은 계약,
     * plan §접근 ①). [scanner]·[gattDriver]·[adapterMonitor] 가 초기화 블록에서 이
     * 프로퍼티를 쓰므로 그것들보다 앞에 선언해야 한다(Kotlin 초기화 순서).
     */
    private val adapter: () -> BluetoothAdapter? = adapterProvider ?: { central?.adapter() }

    constructor(context: Context, config: BleConfig = BleConfig()) : this(
        central = AndroidCentral(context.applicationContext),
        config = config,
        adapterProvider = null,
        registerAdapterReceiver = true,
        gattDriverFactory = null,
        gattOperationTargetResolver = null,
        rediscoveryOverride = null,
        scanPortOverride = null,
    )

    /**
     * 테스트 전용 seam(plan task 5 §작업①, plan v2 변경 사항). 플랫폼 `Context` 없이
     * 배선 로직([linkSetup] 분기 · [emit] 발행 · [droppedEventCount])만
     * 검증한다 — task 6 이 이 생성자로 `BleCoreWiringTest` 를 만든다.
     *
     * [central] 이 null 이라 AdapterMonitor 의 상태 계산은 항상 `evaluatePermissionState` 의
     * 빈 선언 집합 경로를 타 `Unauthorized(canRequest = false)` 가 나오고, 브로드캐스트
     * 리시버는 등록하지 않는다. (다만 그 계산이 어댑터 null 을 먼저 걸러 이
     * 경로 자체를 안 탄다 — 아래 참고.) production `AndroidGattDriver.connect()` 는 null
     * context 를 만나면 typed adapter failure로 즉시 막는다(GattBridge KDoc, plan task 5
     * §작업① 선택 (a)) — [gattDriver]와 [gattOperationTargetResolver]를 함께 주입하면
     * Android `BluetoothGatt` 객체 없이도 공개 read/write/subscribe의 driver start 경계를
     * 검증할 수 있다. 둘 다 생략한 기본 production 조립은 실제 Android target resolution을
     * 유지한다.
     */
    constructor(
        config: BleConfig,
        adapter: () -> BluetoothAdapter?,
        gattDriver: GattDriver? = null,
        gattOperationTargetResolver: GattOperationTargetResolver? = null,
        rediscovery: PeripheralRediscovery? = null,
        adapterState: (() -> AdapterState)? = null,
        scanPort: PlatformScanPort? = null,
        scannerRediscovery: Boolean = false,
    ) : this(
        central = null,
        config = config,
        adapterProvider = adapter,
        registerAdapterReceiver = false,
        gattDriverFactory = gattDriver?.let { driver -> { _: CoroutineScope -> driver } },
        gattOperationTargetResolver = gattOperationTargetResolver,
        rediscoveryOverride = rediscovery,
        scanPortOverride = scanPort,
        scannerRediscovery = scannerRediscovery,
        adapterStateProbe = adapterState,
    )

    // ── 배선 ① coreScope — 규칙 6: 구독이 아니라 이 스코프가 동작을 구동한다 ──────────

    /**
     * [config]에 디스패처가 주입되지 않으면 코어가 단일 스레드 확정 디스패처를 만든다.
     * 단일 스레드에 상태를 고정해 [kr.co.nucode.ble.internal.session.Session]·[SessionRegistry]에
     * `Mutex`를 뿌리지 않아도 되게 한다(plan §접근 배선①).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun defaultDispatcher() = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /**
     * [coreScope] 구성에 쓴 디스패처를 Job 없이 그대로 뽑아둔 필드(plan task 5 §작업④) —
     * [close] 가 `CoroutineScope(coreDispatcherOnly).launch { … }` 로 정리 코드를 이
     * 디스패처 위에 예약하기 위한 것이다(plan `runtime-no-runblocking` task 2(B) — 블로킹 대기
     * 없이 예약만 한다). `coreScope.coroutineContext` 를 그대로 쓰지 않는 이유: 그 컨텍스트는
     * `SupervisorJob` 을 포함하는데, 정리 도중 `coreScope.cancel()` 이 그 Job 을 취소해버리면
     * 정리 코드 자체가 취소된다 — 그래서 Job 이 아예 없는 디스패처만 따로 갖고 있어야
     * `coreScope` 취소와 독립적으로 정리를 끝까지 돌릴 수 있다.
     */
    private val onRuntimeThread = ThreadLocal<Boolean>()
    private val coreDispatcherOnly: CoroutineContext =
        (config.dispatcher ?: defaultDispatcher()) + onRuntimeThread.asContextElement(true)

    private val coreScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + coreDispatcherOnly)

    // ── 배선 ② emit — 나가는 문은 [eventBus] 소유, 런타임은 같은 이름으로 위임만 한다 ──────────

    /**
     * 나가는 문의 소유자. 워커들이 캡처하는 `::emit` 등이 이 객체에 위임하므로 워커보다 먼저
     * 초기화돼야 한다(Kotlin 초기화 순서). 세션 조회는 람다로 미뤄 [registry] 선언 순서와 무관하다.
     */
    private val eventBus = ClientEventBus(
        config = config,
        isClosed = { closed },
        sessionOf = { deviceId -> registry.get(deviceId) },
    )


    internal val officialEvents: Flow<BleClientEvent> get() = eventBus.officialEvents

    internal val scanObservations: Flow<ScanObservation> get() = eventBus.scanObservations

    /** [DefaultBleSession.notifications] 의 진입점. collect 시 동기 등록된다. */
    internal fun notifications(deviceId: PeripheralId, ref: CharacteristicRef): Flow<ByteArray> =
        eventBus.notifications(deviceId, ref)

    /** 테스트 관찰용 — 등록된 notification 수집자 수. */
    internal val notificationCollectorCount: Int
        get() = eventBus.notificationCollectorCount

    /** Internal producer callback injected into GattBridge; it never enters the public event type. */
    internal val diagnosticIngress: DiagnosticIngress get() = eventBus.diagnosticIngress

    /** 유실된 이벤트 수 — [ClientEventBus.droppedEventCount] 위임. */
    public val droppedEventCount: Long
        get() = eventBus.droppedEventCount

    /**
     * 이벤트 방출 진입점([ClientEventBus.emit] 위임). `internal` 로 올린 이유(plan task 5 §작업①) —
     * 테스트 seam. 프로덕션 호출자는 워커 4개가 생성자로 받은 `::emit` 참조뿐이고(규칙 7 불변),
     * task 6 배선 테스트가 공식 투영 · `dropped` 카운트를 직접 검증하려고 두드리는 자리다.
     */
    internal fun emit(e: BleEvent) = eventBus.emit(e)

    /** Same-module migration seam; external producers receive [diagnosticIngress] directly. */
    internal fun emit(observation: DiagnosticObservation) = eventBus.emit(observation)

    /** 순서: 닫힘 게이트 → 온기 mark → 발행. */
    private fun emitScanObservation(observation: ScanObservation) {
        if (closed) return
        linkCacheWarmth.mark(observation.peripheralId)
        eventBus.emitScanObservation(observation)
    }

    // ── 워커 조립 — 상태는 registry 하나(규칙 2), 출구는 emit 하나(규칙 7) ───────────

    /**
     * 기기별 상태를 담는 유일한 registry(규칙 2). `internal` 로 올린 이유(plan task 5
     * §작업①) — 테스트 seam. task 6 배선 테스트가 세션을 직접 만들고 상태를 세팅해
     * [linkSetup] 분기를 구동한다. 프로덕션 호출자는 워커 4개와
     * `BleRuntime` 자신뿐이다.
     */
    internal val registry = SessionRegistry()

    /** 링크 캐시 온기 소유자 — 스캔 관측·LinkSetup·sweep 이 쓰고 lifecycle 이 읽는다. */
    private val linkCacheWarmth = LinkCacheWarmth()

    /**
     * `internal` 인 이유 — 테스트 seam. 스캔 테스트가 `scanPort` fake 로 스캔을 시작하고
     * [Scanner.handle] 로 플랫폼 콜백을 주입한다. 프로덕션 호출자는 `BleRuntime` 자신뿐이다.
     */
    internal val scanState: StateFlow<ScanState> get() = scanner.scanState

    internal val scanner = Scanner(
        emit = ::emit,
        scope = coreScope,
        emitObservation = ::emitScanObservation,
        port = scanPortOverride ?: AndroidScanner { adapter()?.bluetoothLeScanner },
    )
    private val gattDriver = gattDriverFactory?.invoke(coreScope)
        ?: AndroidGattDriver(central?.context, adapter, coreScope)
    /**
     * 재발견은 [scanner] 의 소비자다(plan scan-owner D2) — 플랫폼 스캔 문은 하나. 테스트 생성자는 주입값만
     * (없으면 재발견 없음), `scannerRediscovery = true` 면 실제 [scanner] 를 쓴다.
     */
    private val rediscovery: PeripheralRediscovery? = rediscoveryOverride
        ?: scanner.takeIf { central != null || scannerRediscovery }
    private val gattBridge = GattBridge(
        registry = registry,
        emit = ::emit,
        diagnosticIngress = diagnosticIngress,
        scope = coreScope,
        driver = gattDriver,
        ackTimeoutMillis = config.operationTimeoutMillis,
        operationTargetResolver = gattOperationTargetResolver,
        readinessTimeouts = ConnectionReadinessTimeouts(
            linkMillis = config.linkTimeoutMillis,
            discoveryMillis = config.discoveryTimeoutMillis,
            configurationMillis = config.configurationTimeoutMillis,
            disconnectCleanupMillis = config.disconnectCleanupTimeoutMillis,
            rediscoveryMillis = config.rediscoveryTimeoutMillis,
        ),
        rediscovery = rediscovery,
        linkCacheWarm = linkCacheWarmth::isWarm,
        // 붙은 뒤 절차(LinkSetup)는 bridge 가 lifecycle 옆에 조립한다 — 런타임은 제 소유만 람다로 준다.
        linkSetupWiring = LinkSetupWiring(
            isClosed = { closed },
            markWarm = linkCacheWarmth::mark,
            onRestoreFailed = eventBus::emitRestoreFailure,
            failureOf = { e, deviceId, ref -> failureFromInternalException(e, FailurePhase.Configure, deviceId, ref) },
        ),
    )

    /**
     * 붙은 뒤 절차. `internal` 인 이유 — 테스트 seam: 배선 테스트가 이벤트 발행 없이
     * 절차 분기를 직접 구동하고 복원 job 을 관측한다.
     */
    internal val linkSetup: LinkSetup get() = gattBridge.linkSetup

    private val operationQueue = OperationQueue(
        registry = registry,
        emit = ::emit,
        ackTimeoutMillis = config.operationTimeoutMillis,
        writeCommand = gattBridge::writeCommand,
    )

    // ── adapterState ────────────────────────────────────────────────────────────

    private val adapterMonitor = AdapterMonitor(
        scope = coreScope,
        adapter = adapter,
        central = central,
        adapterStateProbe = adapterStateProbe,
        context = central?.context?.takeIf { registerAdapterReceiver },
        onAdapterLost = ::onAdapterLost,
        onAdapterReturned = ::onAdapterReturned,
    )

    /** 어댑터/권한 상태. `Unauthorized(canRequest)`로 요청 가능/미허용을 구분한다(설계 §5, D7). */
    public val adapterState: StateFlow<AdapterState> get() = adapterMonitor.adapterState

    /** 테스트 seam — [AdapterMonitor.refreshAdapterState] 위임. */
    internal fun refreshAdapterState() {
        adapterMonitor.refreshAdapterState()
    }

    /** 테스트 seam — 어댑터 이탈 스윕을 직접 구동한다(규약은 [onAdapterLost]). */
    internal fun sweepSessions(cause: AdapterState) {
        onAdapterLost(cause)
    }

    /**
     * BT off/resetting 스윕(D4/M-6). 어댑터가 죽으면 모든 gatt 핸들은 회복 불가다 —
     * stale 핸들에 백오프를 소진하거나(off 중 재연결은 구조적으로 실패) 세션이 Connected 로
     * 동결되는 것(콜백이 안 오는 기기)을 막는다. 이탈 판정과 스윕 1회 규칙은 [AdapterMonitor] 소유이고,
     * 여기는 자원별 소유자를 순서대로(온기 → 스캔 → 세션) 부르는 배선만 한다. 세션별 skip 규칙·원인
     * 매핑·종결은 GattConnectionLifecycle.onAdapterLost 에 있다.
     *
     * `internal` 테스트 seam [sweepSessions] 가 이 함수를 직접 부른다 — 리시버 경로는 등록이 안 되는
     * 테스트 생성자에서 검증할 수 없어, 상태 전이 자체를 그 seam 으로 구동해 검증한다.
     *
     * registry 에서 세션을 제거하지 않는다 — 기기 자체는 여전히 유효하고, 소비자가
     * connect() 를 다시 불러 되살릴 수 있어야 한다(D4 원문 "BT 복귀 자동 재연결은 안 함").
     */
    private fun onAdapterLost(cause: AdapterState) {
        // 어댑터가 Ready 를 벗어나면 스택 LE 캐시도 비워진다고 가정한다 — 다음 connect 는 재발견부터.
        linkCacheWarmth.clear()
        scanner.stopForAdapter(cause)
        for (session in registry.all()) gattBridge.onAdapterLost(session, cause)
    }

    /** 어댑터 복귀 — 세션마다 lifecycle 에 넘긴다(재연결 판정·시작은 [GattBridge.resumeAfterAdapter] 소유). */
    private fun onAdapterReturned() {
        for (session in registry.all()) {
            gattBridge.resumeAfterAdapter(session.deviceId) { adapterMonitor.adapterState.value is AdapterState.Ready }
        }
    }

    init {
        adapterMonitor.start()
    }

    private fun ensureAdapterReady(operation: AndroidApiOperation) {
        adapterMonitor.ensureAdapterReady(operation)
    }

    /** Converts remaining internal migration errors at the public command boundary only. */
    private suspend fun <T> transportCommand(
        phase: FailurePhase,
        deviceId: PeripheralId? = null,
        charRef: CharacteristicRef? = null,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        // Caller cancellation is control flow, never a transport failure.
        throw e
    } catch (e: BleException) {
        throw e
    }

    private fun diagnostic(severity: Severity, failure: BleFailure): BleEvent.Diagnostic =
        BleEvent.Diagnostic(severity, failure)

    private fun transportFailure(
        code: String,
        phase: FailurePhase,
        deviceId: PeripheralId? = null,
        charRef: CharacteristicRef? = null,
        retryability: Retryability = Retryability.Unknown,
    ): BleFailure = BleFailure(
        category = FailureCategory(
            when (phase) {
                FailurePhase.Adapter -> "environment"
                FailurePhase.Scan -> "scan"
                FailurePhase.Operation -> "operation"
                else -> "connection"
            },
        ),
        code = FailureCode(code),
        phase = phase,
        retryability = retryability,
        // A command for an existing peripheral belongs to that session.  Unknown
        // devices and client-level operations deliberately retain an empty context;
        // manufacturing a session for diagnostics would create false correlation.
        context = deviceId?.let(registry::get)?.lifecycleFailureContext() ?: FailureContext(),
    )

    private fun failureFromInternalException(
        error: Exception,
        phase: FailurePhase,
        deviceId: PeripheralId? = null,
        charRef: CharacteristicRef? = null,
    ): BleFailure = when (error) {
        is BleException -> error.failure
        else -> transportFailure("INTERNAL_TRANSPORT_ERROR", phase, deviceId, charRef)
    }

    private fun unknownDevice(deviceId: PeripheralId): BleException = BleException(
        transportFailure(
            code = "UNKNOWN_DEVICE",
            phase = FailurePhase.Validation,
            deviceId = deviceId,
            retryability = Retryability.Never,
        ),
    )

    private fun notReady(deviceId: PeripheralId): BleException = BleException(
        transportFailure(
            code = "NOT_READY",
            phase = FailurePhase.Operation,
            deviceId = deviceId,
            retryability = Retryability.NewConnectionMayHelp,
        ),
    )

    // ── 공개 명령 API(설계 §5) ────────────────────────────────────────────────────
    // 코어 상태가 coreScope 단일 스레드에 고정돼 있어, 상태를 만지는 모든 진입은
    // onCore 로 그 디스패처 위에 넘어간다(plan §접근 배선①, D5/review #7).

    /**
     * 명령 공통 진입로 — closed 검사 + 코어 스레드 전환(plan D5, review #7).
     * ★ Job 없이 디스패처만 넘긴다: `withContext(coreScope.coroutineContext)` 로 coreScope 의
     *   `SupervisorJob` 을 섞으면 호출자의 Job 이 그 Job 으로 덮여, 호출자의 `withTimeout`·
     *   취소가 이 블록에 전파되지 않고(타임아웃이 발화해도 블록이 coreScope 자식이라 안
     *   죽는다) [close] 후 호출은 `JobCancellationException` 으로 호출자 코루틴을 조용히
     *   죽인다(에러도 로그도 없이). 디스패처만 넘기면 블록은 호출자 Job 의 자식으로 남아
     *   취소가 정상 전파되고, close 후 호출은 아래 [closed] 검사로 정의된 실패가 된다.
     */
    private suspend fun <T> onCore(block: suspend () -> T): T {
        if (closed) throw clientClosed()
        return withContext(coreDispatcherOnly) {
            // 재검사(review M): 진입 검사 통과 후 디스패처 큐 적재 전에 close() 가 끼어들면,
            // 이 블록이 정리 **뒤에** 실행돼 플랫폼 자원(스캔·connectGatt)을 되살릴 수 있다 —
            // 그 자원은 회수 경로가 없다(이후 명령이 전부 ISE 로 막히므로).
            if (closed) throw clientClosed()
            block()
        }
    }

    private fun clientClosed(): BleException = BleException(
        transportFailure(
            code = "CLIENT_CLOSED",
            phase = FailurePhase.Close,
            retryability = Retryability.Never,
        ),
    )

    internal fun checkOpen() {
        if (closed) throw clientClosed()
    }

    internal fun isClosed(): Boolean = closed

    /**
     * View 생성은 코어 디스패처를 기다리지 않는다(plan `runtime-no-runblocking` task 1(A)).
     * `Session` 생성자는 StateFlow·coordinator 초기화뿐 — launch 도 부수효과도 없어 코어
     * 스레드 밖에서 만들어도 안전하고, registry 멤버십은 이제 동기화돼 있다(위 KDoc).
     *
     * v2 의 조건부 `remove`("이 호출이 만든 것만 지운다")는 아직 부족했다(plan v3 review
     * HIGH round-2 재발). `existing == null`은 "이 호출이 그 Session 을 만들었다"는 뜻이
     * 아니라 "이 호출이 [registry.get] 시점에 그 Session 을 못 봤다"는 뜻일 뿐이다 —
     * `checkOpen()` 과 `getOrCreate` 사이에서 다른 스레드가 같은 [deviceId] 로 먼저
     * `getOrCreate`(다른 `sessionForView` 호출이든, `connect()` 의 ~862 `existing ?:
     * registry.getOrCreate`든)를 끝내 살아 있는 Session S' 을 등록해뒀을 수 있다. 그 경우
     * 이 호출의 `getOrCreate`는 새로 만들지 않고 S' 을 그대로 받아온다(registry 는
     * `@Synchronized` — 같은 키에 두 인스턴스가 생기지 않는다) — 그런데 [existing] 은 이미
     * null 로 캡처된 뒤라, 아래서 gate 가 서 있으면 "내가 만들었으니 지운다" 분기가 S' 을
     * 지운다. S' 을 만든/채택한 쪽이 이 호출이 아니라도 상관없이 지워지므로, `close()` 의
     * 지연 정리(`runCloseCleanup`, `registry.all()` 스냅샷)가 그 스냅샷을 뜨기 전에 S' 이
     * 사라지면 `gattBridge.close`(driver.close)가 영원히 안 불린다(GATT 핸들 누수) —
     * v2 리뷰가 고친 경합과 형태만 다른 같은 결함이다.
     *
     * 그래서 `remove` 자체를 없앤다. gate 뒤에 살아남는 Session 은 두 갈래뿐이고 둘 다
     * 안전하다:
     * - cleanup 이 이미 `registry.all()` 스냅샷을 뜬 *뒤에* 이 호출이 `getOrCreate`로 새
     *   Session 을 등록한 경우 — 그 Session 은 스냅샷에 없어 cleanup 이 못 보지만, 애초에
     *   GATT 핸들을 가진 적이 없다(방금 생성된 순수 로컬 객체, driver.connect 를 부른 적
     *   없음) — close 할 게 없어 안전하게 GC 된다. 그리고 이 함수가 곧바로 `checkOpen()`
     *   재검사(아래 `if (closed)`)로 던지므로, 호출자는 이 Session 을 절대 손에 넣지
     *   못한다 — 좀비 Session 이 공개 API 로 새어나가지 않는다.
     * - cleanup 이 아직 스냅샷을 뜨지 *않았고* 이 호출(또는 동시의 다른 경로)이 만든
     *   Session 이 registry 에 남아 있는 경우 — cleanup 이 뒤이어 그 스냅샷에서 이 Session
     *   을 보고 정상적으로 정리한다(멱등, 한 번만).
     * 어느 갈래든 [checkOpen] 이 먼저 닫힌 클라이언트를 걸러 좀비 노출이 없고, cleanup 은
     * 자신이 실제로 스냅샷한 Session 집합만 책임진다 — 이 함수가 그 집합을 사후에 축소할
     * 권한을 갖지 않는다.
     */
    internal fun sessionForView(deviceId: PeripheralId): Session {
        checkOpen()
        val session = registry.getOrCreate(deviceId)
        if (closed) throw clientClosed()
        return session
    }

    public suspend fun startScan(filter: BleScanFilter = BleScanFilter(), options: BleScanOptions = BleScanOptions()): Unit =
        transportCommand(FailurePhase.Scan) {
        onCore {
            ensureAdapterReady(AndroidApiOperation.StartScan)
            scanner.startScan(filter, options)
        }
    }

    public suspend fun stopScan() {
        if (closed) return
        // 정지는 정리 동작이다 — 취소된 호출자라도 스캔이 남으면 안 되므로 NonCancellable 로
        // 블록을 끝까지 돌리고, 취소는 그 뒤 ensureActive() 로 호출자에게 그대로 전파한다.
        withContext(NonCancellable + coreDispatcherOnly) {
            if (!closed) scanner.stopScan()
        }
        currentCoroutineContext().ensureActive()
    }

    public suspend fun connect(deviceId: PeripheralId, options: ConnectOptions = ConnectOptions()): Unit =
        transportCommand(FailurePhase.Connect, deviceId) {
        onCore {
            val existing = registry.get(deviceId)
            // 중복 connect 판정은 연결 stage 소유자(lifecycle)가 한다 — 실패 구성·게시·throw 는 여기서 그대로.
            if (existing != null) {
                when (val admission = gattBridge.admitConnect(existing, options)) {
                    ConnectAdmission.Proceed -> Unit
                    ConnectAdmission.Joined -> return@onCore
                    is ConnectAdmission.Conflict ->
                        emitConnectConflict(deviceId = deviceId, code = admission.code, retryability = admission.retryability)
                }
            }

            ensureAdapterReady(AndroidApiOperation.ConnectGatt)
            if (existing == null) registry.getOrCreate(deviceId)
            // 시작 실패의 종결·게시·suppressReconnect 는 lifecycle connect 가 끝낸 뒤 같은 BleException 을 재던진다.
            gattBridge.connect(deviceId, options)
            // review LOW-1 — 재발견 대기 중 close 가 연결을 끝냈으면 정상 반환하지 않는다.
            if (closed) throw clientClosed()
        }
    }

    /**
     * 공개 connect 옵션을 런타임 연결로 옮긴다. `autoReconnect = true` 는 Android `autoConnect = true` 로
     * 연결하고 재연결 주체를 플랫폼에 넘긴다(handle 유지, link deadline 없음). 그 밖에는 `autoConnect = false`
     * 이고 링크가 끊기면 재연결 없이 Disconnected(Failed) 로 끝난다. handle 을 OS 대기용으로 남길지
     * (suppressReconnect)는 lifecycle 이 연결 시작 시점에 모드로 정한다.
     */
    internal suspend fun connectOfficial(deviceId: PeripheralId, options: BleConnectOptions) {
        onCore { connect(deviceId, ConnectOptions(autoConnect = options.autoReconnect)) }
    }

    /**
     * `connect`는 Unit을 반환하므로 conflict를 조용한 no-op으로 만들면 호출자가 새 옵션이
     * 적용됐다고 오해한다. Slice 3의 typed failure model 전까지는 기존 즉시 실패 관례에 맞춰
     * deterministic IllegalStateException과 Diagnostic을 함께 남긴다.
     */
    private fun emitConnectConflict(deviceId: PeripheralId, code: String, retryability: Retryability): Nothing {
        val failure = transportFailure(code, FailurePhase.Connect, deviceId, retryability = retryability)
        emit(
            diagnostic(
                Severity.Warning,
                failure,
            ),
        )
        throw BleException(failure)
    }

    public suspend fun disconnect(deviceId: PeripheralId): Unit = transportCommand(FailurePhase.Close, deviceId) {
        if (closed) return@transportCommand
        // 해제는 정리 동작이다 — 취소된 호출자라도 링크 정리가 시작돼야 하므로 NonCancellable 로
        // 블록을 끝까지 돌리고, 취소는 그 뒤 ensureActive() 로 호출자에게 그대로 전파한다(stopScan 과 같은 규칙).
        withContext(NonCancellable + coreDispatcherOnly) {
            if (closed) return@withContext
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            // 사용자 명시 해제는 구독 의도를 복원하지 않는다(v1 계약) — 링크 유실·재연결 경로는 의도를 유지한다.
            session.subscriptionState.clearDesired()
            // 단계별 판정(플랫폼 재연결 대기 종결·어댑터 복귀 재연결 취소·이미 끝난 세션·정리 시작)은 lifecycle 이 한다.
            gattBridge.disconnect(deviceId)
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * 현재 연결 generation에서 characteristic 값을 한 번 읽는다. 응답 bytes는 notification
     * event로 방출하지 않고 이 호출 결과로만 반환한다.
     */
    public suspend fun read(deviceId: PeripheralId, charRef: CharacteristicRef): ByteArray =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
            onCore {
        ensureAdapterReady(AndroidApiOperation.ReadCharacteristic)
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
        if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
        gattBridge.read(deviceId, charRef)
    }
        }

    /**
     * write 만 [WriteResult] 를 반환한다 — 부분 전달을 타입으로 강제해야 하므로(설계 §5).
     * 즉시 실패는 이 함수가 직접 throw 한다 — 순서: 어댑터 사용 불가 → 모르는 PeripheralId → 세션 미Ready.
     * 청크 단위 실패는 [OperationQueue.write] 가 처리한다.
     */
    public suspend fun write(
        deviceId: PeripheralId,
        charRef: CharacteristicRef,
        bytes: ByteArray,
        writeType: WriteType = WriteType.Auto,
        chunking: BleChunking = BleChunking.Automatic,
    ): WriteResult = transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
        ensureAdapterReady(AndroidApiOperation.WriteCharacteristic)
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
        if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
        operationQueue.write(deviceId, charRef, bytes, writeType, chunking)
    }
    }

    public suspend fun subscribe(deviceId: PeripheralId, charRef: CharacteristicRef): Unit =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
            ensureAdapterReady(AndroidApiOperation.WriteDescriptor)
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
            // CCCD ack 성공 후에만 기록한다(plan §접근 ③, review #18/H-1) — 먼저 기록하면
            // 실패 시 롤백 없이 남아 snapshot·재구독이 거짓 사실 위에 선다.
            val startGeneration = session.connectionCoordinator.generation
            gattBridge.subscribe(deviceId, charRef)
            // ack 뒤 재개 전에 사용자 disconnect(clearDesired)·정리가 시작됐으면 의도를 되살리지 않는다 —
            // 시작한 generation 이 더 이상 Ready 가 아니면 disconnect 로 무효화된 다른 연산과 같이 던진다.
            if (!session.connectionCoordinator.accepts(startGeneration, ConnectionStage.Ready)) {
                throw BleException(GattFailureFactory.operationInvalidated(session))
            }
            session.subscriptionState.addDesired(charRef)
            session.subscriptionState.markActive(charRef, session.connectionCoordinator.generation)
        }
    }

    /**
     * 구독 해제 — D8 best-effort(plan design decisions v1, review L-1). 유일한 throw 는
     * `UNKNOWN_DEVICE` typed failure다. [GattBridge.unsubscribe] 는 더 이상 throw 하지 않는다 —
     * CCCD write 실패는 Diagnostic 으로만 나간다. desired intent 제거는 CCCD 해제를 요청하기 전에
     * 먼저 한다(성패와 무관) — 소비자 의도("이벤트 그만")는 로컬 라우팅을 껐다는 사실만으로
     * 이미 달성된다. [subscribe] 가 여전히 throw 하는 것과 비대칭이다 — 켜는 방향은 실패가
     * 안 보이면 notify 를 하염없이 기다리게 되지만, 끄는 방향은 로컬에서 이미 지웠으므로
     * 잃을 게 없다(규칙 1 — throw 로 소비자에게 재시도 상태 기계를 강제하지 않는다).
     */
    public suspend fun unsubscribe(deviceId: PeripheralId, charRef: CharacteristicRef): Unit =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            // 의도는 요청 순간 지운다(CCCD 해제 전) — 진행 중인 복원 루프가 이 ref 를 다시 켜지 않게
            // 매 항목 의도를 재확인한다(LinkSetup, plan cross-review-fixes LOW-7).
            session.subscriptionState.removeDesired(charRef)
            gattBridge.unsubscribe(deviceId, charRef)
        }
    }

    /** [close] 가 예약한 정리가 끝났는지 기다린다(plan `runtime-no-runblocking` task 2(B)). */
    internal suspend fun awaitClosed() = closeCompleted.await()

    /** Internal projection seam: no facade keeps a parallel snapshot or session map. */
    internal suspend fun officialSnapshot(): ClientSnapshot {
        closedOfficialSnapshot?.let { return it }
        return withContext(coreDispatcherOnly) {
            if (closed) {
                // The close caller may have set admission before it could queue
                // cleanup. Suspending yields the owner to that cleanup instead
                // of returning a pre-close snapshot during this interval.
                closeCompleted.await()
                return@withContext requireNotNull(closedOfficialSnapshot)
            }
            ClientSnapshot(
            adapterState = adapterMonitor.adapterState.value,
            isScanning = scanner.isScanning,
            sessions = registry.all().map { session ->
                SessionSnapshot(
                    peripheralId = session.deviceId,
                    connectionState = session.connectionState.value,
                    services = session.services.value,
                    subscriptions = session.subscriptionState.desiredSnapshot,
                )
            },
            )
        }
    }

    /**
     * 정리 본문(plan `runtime-no-runblocking` task 2(B)) — [close] 에서 추출했다. 코어
     * 스레드 위라면 [close] 가 인라인으로, 아니면 [coreDispatcherOnly] 에 예약된 코루틴이
     * 이 함수를 돈다. 정리가 실패해도 `closeCompleted` 는 반드시 완료돼야 한다 —
     * 그러지 않으면 [officialSnapshot] 이 영원히 멈춘다.
     *
     * `runCloseCleanupBody` 가 던지면(예: `gattBridge.close` 안 driver 호출 실패) 여기서
     * 삼킨다(plan v2 review MEDIUM). [close] 는 코어 스레드 위에서 이 함수를 inline 으로도
     * 부르므로, 삼키지 않으면 그 예외가 스레드의 uncaught handler 까지 올라가 Android
     * 프로세스를 죽인다 — 두 호출 경로(inline/예약된 코루틴)가 같은 의미(호출자에게 절대
     * throw 하지 않는다, 대신 기록한다)를 갖도록 여기 한 곳에서만 catch 한다.
     */
    private fun runCloseCleanup() {
        try {
            // 세션별 실패는 반환값으로 받아 보고한다 — 디스패처 close(finally) 보다 먼저 보고해야
            // CLEANUP_FAILURE 가 logger 큐로도 나갈 기회를 얻는다(호환 스트림은 어느 경우든 받는다).
            runCloseCleanupBody()?.let { failure ->
                closeFailure = failure
                reportCleanupFailure(failure)
            }
        } catch (t: Throwable) {
            closeFailure = t
            reportCleanupFailure(t)
            // 실패 지점에 따라 closedOfficialSnapshot 이 아직 안 섰을 수 있다 — officialSnapshot()
            // 이 typed 결과로 끝나려면 폴백이라도 세워야 한다.
            if (closedOfficialSnapshot == null) {
                closedOfficialSnapshot = ClientSnapshot(
                    adapterState = adapterMonitor.adapterState.value,
                    isScanning = false,
                    sessions = emptyList(),
                )
            }
        } finally {
            // receiver·디스패처·스코프 정리는 정상·실패 공통으로 딱 한 번 — 보고 뒤라 디스패처 close 가 보고를 삼키지 않는다.
            runCatching { adapterMonitor.close() }
            runCatching { eventBus.close() }
            runCatching { coreScope.cancel() }
            closeCompleted.complete(Unit)
        }
    }

    /**
     * plan v3 review MEDIUM Fix 2 — 세션별로 `runCatching`(fault-isolate)한다. 한 세션의
     * `gattBridge.close`(driver.close)가 던지면 v2 코드는 그 예외가 이 함수 전체를 끝내
     * 뒤 세션들이 정리되지 않고(GATT 핸들 누수) `adapterMonitor.close()`/`eventBus.close()`/
     * `coreScope.cancel()` 꼬리까지 스킵됐다 — 한 핸들의 실패가 나머지 정리를 막으면 안 된다.
     * 첫 예외만 [firstFailure] 에 모아 반환한다 — 호출자인 [runCloseCleanup] 이 그 값을
     * [closeFailure] 에 기록하고 로거로 보고한다(호출자에게는 절대 전파되지 않는다). 스냅샷
     * 구성은 세션 실패 여부와 무관하게 항상 돌고, receiver 해제(`adapterMonitor.close()`)·`eventBus.close()`·
     * `coreScope.cancel()` 는 [runCloseCleanup] 의 finally 가 담당한다(예기치 못한 throw 에도 실행).
     */
    private fun runCloseCleanupBody(): Throwable? {
        scanner.stopScan()
        // 재발견 소비자도 끝낸다 — 남아 있으면 close 뒤에도 플랫폼 스캔이 돈다.
        scanner.endRediscoveriesForClose(clientClosed().failure)
        val sessions = registry.all()
        var firstFailure: Throwable? = null
        sessions.forEach { session ->
            runCatching {
                // Scheduler completion은 GattBridge.close의 poison/drain이 유한 종결한다.
                // 복원 job 도 lifecycle close 가 끊는다.
                // lifecycle close 가 ClientClosed 를 고정·종결하고 suppressReconnect 를 세운다. 플랫폼 재연결
                // 대기 중 세션도 ClientClosed 로 끝난다.
                gattBridge.close(session)
            }.onFailure { t -> if (firstFailure == null) firstFailure = t }
        }
        closedOfficialSnapshot = ClientSnapshot(
            adapterState = adapterMonitor.adapterState.value,
            isScanning = false,
            sessions = sessions.map { session ->
                SessionSnapshot(
                    peripheralId = session.deviceId,
                    connectionState = session.connectionState.value,
                    services = session.services.value,
                    subscriptions = session.subscriptionState.desiredSnapshot,
                )
            },
        )
        sessions.forEach { runCatching { registry.remove(it.deviceId) } }
        // receiver 해제·디스패처/스코프 정리는 runCloseCleanup 의 finally 로 미룬다 — 예기치 못한 throw 에도
        // 반드시 돌게(receiver 누수 방지) + 보고가 디스패처 close 보다 먼저이게.
        return firstFailure
    }

    /**
     * 정리 실패를 [config]의 logger 로 보고한다(plan v2 review MEDIUM (a)) — 구성된 logger 가
     * 있으면 진단 reporter([ClientEventBus.diagnosticIngress])를 직접 부른다. `emit(diagnostic(...))` 을 쓰지 않는 이유:
     * 그 공개 경로는 `closed` 게이트를 검사해 이미 닫힌 시점에는 조용히 버린다(위 [emit] 참고) —
     * 정리는 게이트가 선 뒤에만 돌므로 그 경로로는 절대 보고되지 않는다. `diagnosticIngress.report`
     * 는 게이트를 보지 않고 이 실패를 호환 이벤트 스트림과(아직 열려 있으면) logger 워커 큐로
     * non-blocking 하게 흘린다 — [closeFailure] 는 logger 유무와 무관하게 항상 기록되는
     * 테스트/진단 보조 관측점이다.
     */
    private fun reportCleanupFailure(error: Throwable) {
        // plan v3 review LOW Fix 4 — client 가 이미 닫힌 뒤의 실패는 CLIENT_CLOSED 와 같은
        // 이유로 재시도 대상이 아니다: 재시도할 대상 client 자체가 더는 없다.
        val failure = transportFailure(code = "CLEANUP_FAILURE", phase = FailurePhase.Close, retryability = Retryability.Never)
        try {
            eventBus.diagnosticIngress.report(Severity.Error, failure) {
                mapOf(
                    "cleanup_exception" to DiagnosticValue.Text(
                        (error.message ?: error::class.java.simpleName).take(DiagnosticValue.MAX_TEXT_LENGTH),
                    ),
                )
            }
        } catch (_: Throwable) {
            // Hostile/failing logger path cannot escape cleanup; closeFailure above already holds the record.
        }
    }

    /**
     * Close admission first, then schedule cleanup on its owner and return
     * immediately — [close] never blocks the caller's dispatcher. On the core
     * thread, cleanup runs inline (no dispatch round-trip). Off the core
     * thread, cleanup is scheduled onto [coreDispatcherOnly], which carries no
     * `Job`, so [coreScope]`.cancel()` inside cleanup cannot cancel cleanup
     * itself. All `Session` mutation is confined to this single dispatcher —
     * there is no separate state store (design §3 rule 2, `PublicModels.kt`
     * `BleConfig` KDoc) — so a command that already began mutating `Session`
     * state before this gate flips runs to completion on that same thread
     * before cleanup gets its turn there too (not a FIFO-ordering claim about
     * commands not yet admitted). [officialSnapshot] / [awaitClosed] observe cleanup completion —
     * at the moment [close] returns, `BluetoothGatt.close()` may not have run
     * yet; if the process exits immediately after [close] returns, the
     * platform reclaims any still-open handles on process death.
     *
     * Cleanup never rethrows to a caller — there is none to rethrow to, since
     * both the inline and the scheduled path run [runCloseCleanup], which
     * catches every `Throwable`, records it in [closeFailure], and reports it
     * through the configured logger when one exists (plan
     * `runtime-no-runblocking/v2` review MEDIUM). [awaitClosed] always
     * completes even when cleanup failed partway through.
     */
    fun close() {
        // This gate is deliberately before dispatch: no command can be admitted
        // between a close caller and deferred cleanup.
        if (!closeGate.compareAndSet(false, true)) return
        if (onRuntimeThread.get() == true) {
            runCloseCleanup()
        } else {
            CoroutineScope(coreDispatcherOnly).launch { runCloseCleanup() }
        }
    }
}
