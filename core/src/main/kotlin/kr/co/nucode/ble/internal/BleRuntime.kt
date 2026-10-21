package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.internal.port.CoreMailbox
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.port.PlatformScanPort
import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.model.WriteResult
import kr.co.nucode.ble.internal.model.ConnectOptions
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kr.co.nucode.ble.internal.port.AdapterSource
import kr.co.nucode.ble.internal.connection.LinkCacheWarmth
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.connection.LinkSetupWiring
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.DiagnosticObservation
import kr.co.nucode.ble.internal.event.ClientEventBus
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.gatt.OperationQueue
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.internal.scan.ScanStartLedger
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.SessionOwner
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * The one live BLE runtime.  It owns the dispatcher, registry, scanner and all
 * platform workers; compatibility APIs must delegate to this object rather
 * than becoming a second owner.
 *
 * 코어 진입점. 유일한 공개 진입점이다(설계 §3 규칙 4 — facade/Manager/Impl 겹 금지).
 * 플랫폼 구현(Driver)은 포트로만 받는다 — 만드는 곳은 Factory 조립
 * (`kr.co.nucode.ble.android.androidRuntime`) 하나이고, 시험은 시험 소스의 `BleRuntime(...)` 함수가 만든다.
 * 이 파일은 `android.*` 와 `platform.android` 를 참조하지 않는다.
 */
internal class BleRuntime internal constructor(
    config: BleConfig,
    /** Driver 콜백 입구([mailbox])를 받아 GATT 포트 구현을 만든다 — 만드는 곳은 Factory 조립뿐이다. */
    gattDriverFactory: (CoreMailbox) -> GattDriver,
    private val scanPort: PlatformScanPort,
    private val adapterSource: AdapterSource,
    /** 도메인이 시각·지연·기한을 얻는 유일한 입구 — 구현은 Factory 조립이 넘긴다. */
    private val clock: ClockPort,
    /** 플랫폼 스캔 시작 기록 — null 이면 [scanner] 전용 기록(테스트 격리). Factory 조립만 프로세스 공유 기록을 넘긴다(Android 시작 한도는 앱 단위). */
    private val scanStartLedger: ScanStartLedger?,
    /** true 면 재발견을 실제 [scanner] 소비자로 배선한다(운영 조립과 e2e 하네스). false 면 [rediscoveryOverride] 만. */
    private val scannerRediscovery: Boolean,
    private val gattOperationTargetResolver: GattOperationTargetResolver? = null,
    private val rediscoveryOverride: PeripheralRediscovery? = null,
    /**
     * plan G task 1(G1) 어댑터 상태 probe seam — 있으면 AdapterMonitor 의 상태 계산이 실제
     * 어댑터/권한 판정을 완전히 건너뛰고 이 람다 값을 그대로 쓴다. 운영 조립은
     * 이 파라미터를 절대 전달하지 않는다(항상 null) — 전달하면 권한 판정(D7)을 우회해
     * 실제 기기에서 미허용 상태를 Ready 로 오판할 수 있다.
     */
    private val adapterStateProbe: (() -> AdapterState)? = null,
) {

    /** 테스트 전용 관측 지점 — 소유는 [RuntimeCloser.closeFailure]. */
    internal val closeFailure: Throwable? get() = closer.closeFailure

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
        CoroutineScope(
            SupervisorJob() + coreDispatcherOnly +
                CoroutineExceptionHandler { context, error -> onUncaughtCoreException(context, error) },
        )

    /**
     * 코어 스코프의 마지막 방어선 — 잡히지 않은 예외가 프로세스나 다른 세션을 끌고 가지 않게 한다.
     * [SessionOwner] 를 실은 코루틴의 예외는 그 세션만 `Disconnected(Failed:INTERNAL_TRANSPORT_ERROR)` 로
     * 끝내고 handle 을 닫는다. 귀속 불명이면 `Diagnostic(Error, INTERNAL_TRANSPORT_ERROR)` 만 낸다.
     * 취소는 실패가 아니다. 상태는 코어 디스패처에서만 만지므로 Job 없는 디스패처로 한 홉 옮긴다
     * (coreScope 취소와 독립 — [closer] 와 같은 이유). 처리기 자신은 던지지 않는다.
     */
    private fun onUncaughtCoreException(context: CoroutineContext, error: Throwable) {
        if (error is CancellationException) return
        val deviceId = context[SessionOwner]?.deviceId
        CoroutineScope(coreDispatcherOnly).launch {
            runCatching {
                if (closer.isClosed()) return@launch
                val session = deviceId?.let(registry::get)
                val failure = commands.transportFailure(
                    code = "INTERNAL_TRANSPORT_ERROR",
                    phase = FailurePhase.Operation,
                    deviceId = deviceId,
                    retryability = Retryability.NewConnectionMayHelp,
                )
                if (session == null) {
                    emit(BleEvent.Diagnostic(Severity.Error, failure))
                } else {
                    gattBridge.failInternal(session, failure)
                }
            }
        }
    }

    /** 테스트 seam — 세션 귀속 컨텍스트([deviceId] 가 null 이면 귀속 없음)로 코어 스코프에 코루틴을 띄운다. */
    internal fun launchOwned(deviceId: PeripheralId?, block: suspend () -> Unit) {
        coreScope.launch(deviceId?.let { SessionOwner(it) } ?: kotlin.coroutines.EmptyCoroutineContext) { block() }
    }

    /** Driver 콜백 입구 — 세션 귀속 코루틴 하나로 코어 스코프에 넣는다(예외 귀속·홉 순서는 [launchOwned] 와 같은 길). */
    internal val mailbox: CoreMailbox = CoreMailbox { deviceId, block ->
        coreScope.launch(SessionOwner(deviceId)) { block() }
    }

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
        wallMillis = clock::wallMillis,
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
    internal val registry = SessionRegistry(clock)

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
        port = scanPort,
        clock = clock,
        startLedger = scanStartLedger ?: ScanStartLedger(clock::elapsedMillis),
    )
    private val gattDriver = gattDriverFactory(mailbox)
    /**
     * 재발견은 [scanner] 의 소비자다(plan scan-owner D2) — 플랫폼 스캔 문은 하나. `rediscoveryOverride` 가 있으면 그것,
     * 없으면 `scannerRediscovery = true` 일 때만 실제 [scanner] 를 쓴다.
     */
    private val rediscovery: PeripheralRediscovery? = rediscoveryOverride
        ?: scanner.takeIf { scannerRediscovery }
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
        adapterSource = adapterSource,
        clock = clock,
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

    internal suspend fun readDescriptor(deviceId: PeripheralId, descriptor: DescriptorRef): ByteArray =
        commands.readDescriptor(deviceId, descriptor)

    internal suspend fun writeDescriptor(deviceId: PeripheralId, descriptor: DescriptorRef, value: ByteArray): Unit =
        commands.writeDescriptor(deviceId, descriptor, value)


    /** [close] 가 예약한 정리가 끝났는지 기다린다(plan `runtime-no-runblocking` task 2(B)). */
    internal suspend fun awaitClosed() = closer.awaitClosed()

    /** Internal projection seam: no facade keeps a parallel snapshot or session map. */
    internal suspend fun officialSnapshot(): ClientSnapshot = closer.officialSnapshot()

    /** 닫기 게이트를 세우고 정리를 예약한다 — 의미는 [RuntimeCloser.close]. */
    fun close() = closer.close()
}
