package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.model.WriteResult
import kr.co.nucode.ble.internal.model.ConnectOptions
import android.content.Context
import android.os.SystemClock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.adapter.AdapterMonitor
import kr.co.nucode.ble.internal.platform.android.AndroidAdapterSource
import kr.co.nucode.ble.internal.connection.LinkCacheWarmth
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.connection.LinkSetupWiring
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.DiagnosticObservation
import kr.co.nucode.ble.internal.event.ClientEventBus
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.gatt.OperationQueue
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.PlatformScanPort
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.internal.scan.ScanStartLedger
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * 코어 진입점. 유일한 공개 진입점이다(설계 §3 규칙 4 — facade/Manager/Impl 겹 금지).
 *
 * private 주 생성자 + provider 주입(plan §접근 ①) 구조다 — 플랫폼 [Context]/어댑터
 * 를 직접 들고 있는 대신 [central] 만 플랫폼 구현에 넘기고, 플랫폼 구현이 매 호출 시 다시 얻는다. 공개 생성자와
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
    /**
     * 플랫폼 스캔 시작 기록 — null 이면 [scanner] 전용 기록(테스트 격리). 운영 생성자만 프로세스 공유
     * [processScanStartLedger] 를 넘긴다(Android 시작 한도는 앱 단위, plan scan-start-budget v2 목표 4).
     */
    private val scanStartLedger: ScanStartLedger? = null,
) {

    /** 테스트 전용 관측 지점 — 소유는 [RuntimeCloser.closeFailure]. */
    internal val closeFailure: Throwable? get() = closer.closeFailure

    constructor(context: Context, config: BleConfig = BleConfig()) : this(
        central = AndroidCentral(context.applicationContext),
        config = config,
        registerAdapterReceiver = true,
        gattDriverFactory = null,
        gattOperationTargetResolver = null,
        rediscoveryOverride = null,
        scanPortOverride = null,
        scanStartLedger = processScanStartLedger,
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
     * Android GATT 객체 없이도 공개 read/write/subscribe의 driver start 경계를
     * 검증할 수 있다. 둘 다 생략한 기본 production 조립은 실제 Android target resolution을
     * 유지한다.
     */
    constructor(
        config: BleConfig,
        gattDriver: GattDriver? = null,
        gattOperationTargetResolver: GattOperationTargetResolver? = null,
        rediscovery: PeripheralRediscovery? = null,
        adapterState: (() -> AdapterState)? = null,
        scanPort: PlatformScanPort? = null,
        scannerRediscovery: Boolean = false,
    ) : this(
        central = null,
        config = config,
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

    /**
     * 닫기 상태의 소유자. [eventBus]·[gattBridge] 가 생성 시 `isClosed` 람다로 잡으므로 그보다 먼저
     * 초기화된다. 정리 때만 쓰는 부품은 람다로 넘겨 close 시점에 평가한다.
     */
    private val closer: RuntimeCloser = RuntimeCloser(
        onRuntimeThread = onRuntimeThread,
        coreDispatcherOnly = coreDispatcherOnly,
        coreScope = coreScope,
        scannerOf = { scanner },
        registryOf = { registry },
        gattBridgeOf = { gattBridge },
        adapterMonitorOf = { adapterMonitor },
        eventBusOf = { eventBus },
        clientClosed = { commands.clientClosed() },
        cleanupFailure = { code, phase, retryability -> commands.transportFailure(code = code, phase = phase, retryability = retryability) },
    )

    // ── 배선 ② emit — 나가는 문은 [eventBus] 소유, 런타임은 같은 이름으로 위임만 한다 ──────────

    /**
     * 나가는 문의 소유자. 워커들이 캡처하는 `::emit` 등이 이 객체에 위임하므로 워커보다 먼저
     * 초기화돼야 한다(Kotlin 초기화 순서). 세션 조회는 람다로 미뤄 [registry] 선언 순서와 무관하다.
     */
    private val eventBus = ClientEventBus(
        config = config,
        isClosed = { closer.isClosed() },
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
        if (closer.isClosed()) return
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
        port = scanPortOverride ?: AndroidScanner(central),
        startLedger = scanStartLedger ?: ScanStartLedger { System.nanoTime() / 1_000_000 },
    )
    private val gattDriver = gattDriverFactory?.invoke(coreScope)
        ?: AndroidGattDriver(central, coreScope)
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
            isClosed = { closer.isClosed() },
            markWarm = linkCacheWarmth::mark,
            onRestoreFailed = eventBus::emitRestoreFailure,
            failureOf = { e, deviceId, ref -> commands.failureFromInternalException(e, FailurePhase.Configure, deviceId, ref) },
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

    private val adapterReaction: AdapterReaction = AdapterReaction(
        linkCacheWarmth = linkCacheWarmth,
        scanner = scanner,
        registry = registry,
        gattBridge = gattBridge,
        adapterMonitorOf = { adapterMonitor },
    )

    /** 공개 명령 칸 — closed 검사는 [closer], 준비 게이트는 [adapterReaction] 에 묻는다. */
    private val commands: RuntimeCommands = RuntimeCommands(
        isClosed = closer::isClosed,
        coreDispatcherOnly = coreDispatcherOnly,
        registry = registry,
        scanner = scanner,
        gattBridge = gattBridge,
        operationQueue = operationQueue,
        adapterReaction = adapterReaction,
        emit = ::emit,
    )

    private val adapterMonitor: AdapterMonitor = AdapterMonitor(
        scope = coreScope,
        adapterSource = AndroidAdapterSource(central, registerAdapterReceiver),
        central = central,
        adapterStateProbe = adapterStateProbe,
        onAdapterLost = adapterReaction::onAdapterLost,
        onAdapterReturned = adapterReaction::onAdapterReturned,
    )

    /** 어댑터/권한 상태. `Unauthorized(canRequest)`로 요청 가능/미허용을 구분한다(설계 §5, D7). */
    public val adapterState: StateFlow<AdapterState> get() = adapterMonitor.adapterState

    /** 테스트 seam — [AdapterReaction.refreshAdapterState] 위임. */
    internal fun refreshAdapterState() = adapterReaction.refreshAdapterState()

    /** 테스트 seam — [AdapterReaction.sweepSessions] 위임. */
    internal fun sweepSessions(cause: AdapterState) = adapterReaction.sweepSessions(cause)

    init {
        adapterMonitor.start()
    }

    // ── 공개 명령 API(설계 §5) — 본문은 [RuntimeCommands], 여기는 같은 이름의 위임만 ──
    internal fun checkOpen() = commands.checkOpen()

    internal fun isClosed(): Boolean = closer.isClosed()

    internal fun sessionForView(deviceId: PeripheralId): Session = commands.sessionForView(deviceId)

    public suspend fun startScan(filter: BleScanFilter = BleScanFilter(), options: BleScanOptions = BleScanOptions()): Unit =
        commands.startScan(filter, options)

    public suspend fun stopScan() = commands.stopScan()

    public suspend fun connect(deviceId: PeripheralId, options: ConnectOptions = ConnectOptions()): Unit =
        commands.connect(deviceId, options)

    internal suspend fun connectOfficial(deviceId: PeripheralId, options: BleConnectOptions) =
        commands.connectOfficial(deviceId, options)

    public suspend fun disconnect(deviceId: PeripheralId): Unit = commands.disconnect(deviceId)

    public suspend fun read(deviceId: PeripheralId, charRef: CharacteristicRef): ByteArray = commands.read(deviceId, charRef)

    public suspend fun write(
        deviceId: PeripheralId,
        charRef: CharacteristicRef,
        bytes: ByteArray,
        writeType: WriteType = WriteType.Auto,
        chunking: BleChunking = BleChunking.Automatic,
    ): WriteResult = commands.write(deviceId, charRef, bytes, writeType, chunking)

    public suspend fun subscribe(deviceId: PeripheralId, charRef: CharacteristicRef): Unit = commands.subscribe(deviceId, charRef)

    public suspend fun unsubscribe(deviceId: PeripheralId, charRef: CharacteristicRef): Unit = commands.unsubscribe(deviceId, charRef)


    /** [close] 가 예약한 정리가 끝났는지 기다린다(plan `runtime-no-runblocking` task 2(B)). */
    internal suspend fun awaitClosed() = closer.awaitClosed()

    /** Internal projection seam: no facade keeps a parallel snapshot or session map. */
    internal suspend fun officialSnapshot(): ClientSnapshot = closer.officialSnapshot()

    /** 닫기 게이트를 세우고 정리를 예약한다 — 의미는 [RuntimeCloser.close]. */
    fun close() = closer.close()
}

/**
 * 프로세스 공유 스캔 시작 기록 — 운영 생성자 `BleRuntime(context, config)` 만 쓴다. 시계는
 * `SystemClock.elapsedRealtime()`: deep sleep 에도 진행한다(nanoTime 은 멈춰 창이 늘어나 false throttle).
 */
private val processScanStartLedger = ScanStartLedger { SystemClock.elapsedRealtime() }
