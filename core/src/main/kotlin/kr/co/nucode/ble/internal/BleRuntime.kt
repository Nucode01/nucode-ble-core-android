package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.connection.ConnectSequence
import kr.co.nucode.ble.internal.connection.Reconnector
import kr.co.nucode.ble.internal.diagnostics.DiagnosticDispatcher
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.diagnostics.DiagnosticReporter
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.event.DiagnosticObservation
import kr.co.nucode.ble.internal.event.NotificationHub
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.gatt.OperationQueue
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.platform.android.AndroidPeripheralRediscovery
import kr.co.nucode.ble.internal.platform.android.AndroidPermissionState
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.ScanObservation
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

/**
 * D7(M-8+#16) 권한 판정 순수 함수 — `sdkInt`/매니페스트 선언 셋/런타임 grant 조회를 인자로
 * 받는다. `Build.VERSION.SDK_INT` 를 직접 참조하지 않는 이유는 테스트 JVM 에서 그 값이
 * 항상 0 이라 버전 매트릭스(31+/29~30/24~28)를 단위 테스트로 돌릴 수 없기 때문이다 —
 * Runtime 멤버가 아니라 파일 최상위 `internal` 함수로 둬 인스턴스 없이 테스트한다.
 *
 * 판정 순서(plan task 7 §구현②):
 * 1) 버전별 필요 권한 중 [declared] 에 없는 것이 있으면 → `Unauthorized(canRequest = false)`
 *    — 매니페스트 미선언. 요청 API 를 불러도 소용없다(앱 빌드 결함).
 * 2) 전부 선언은 됐는데 런타임 권한이 미허용이면 → `Unauthorized(canRequest = true)`.
 * 3) 전부 충족되면 → `null`(허용됨 — `Unauthorized` 아님. 어댑터 on/off 판정은 호출부 몫).
 *
 * pre-31 의 `BLUETOOTH`/`BLUETOOTH_ADMIN` 은 선언 여부만 본다 — 설치 시 부여되는 일반
 * 권한이라 grant 검사가 무의미하다(review #2 근거와 동일선상). 위치 권한은 선언+허용을
 * 모두 본다 — N~P 는 FINE/COARSE 어느 한쪽만 선언·허용돼도 충족된다(OR).
 *
 * [targetSdkInt] < 31 인 앱은 Android 12+ 기기에서도 legacy 권한 셋(BLUETOOTH/ADMIN+위치)으로
 * 합법 동작한다 — 그 조합을 SCAN/CONNECT 미선언 = "앱 빌드 결함(canRequest=false)" 으로
 * 오판하지 않도록 legacy 매트릭스로 내려보낸다(review L).
 *
 * ★ 알려진 한계(실기기 후 재검토): 31+ 에서 `BLUETOOTH_SCAN` 을 `neverForLocation` 없이
 * 선언한 앱은 스캔 결과 수신에 위치 권한도 필요하다 — 이 매트릭스는 그 플래그를 읽지
 * 않으므로(PackageInfo.requestedPermissionsFlags) 해당 조합을 통과로 오판할 수 있다.
 */
internal fun evaluatePermissionState(
    sdkInt: Int,
    declared: Set<String>,
    isGranted: (String) -> Boolean,
    targetSdkInt: Int = sdkInt,
): AdapterState.Unauthorized? = AndroidPermissionState.evaluate(
    sdkInt = sdkInt,
    targetSdkInt = targetSdkInt,
    declared = declared,
    isGranted = isGranted,
)

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
    reconnectPolicy: ReconnectPolicy,
    adapterProvider: (() -> BluetoothAdapter?)?,
    private val registerAdapterReceiver: Boolean,
    private val gattDriverFactory: ((CoroutineScope) -> GattDriver)?,
    private val gattOperationTargetResolver: GattOperationTargetResolver?,
    private val rediscoveryOverride: PeripheralRediscovery?,
    /**
     * plan G task 1(G1) 어댑터 상태 probe seam — 있으면 [computeAdapterState] 가 실제
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
     * plan §접근 ①). [scanner]·[gattDriver]·[computeAdapterState] 가 초기화 블록에서 이
     * 프로퍼티를 쓰므로 그것들보다 앞에 선언해야 한다(Kotlin 초기화 순서).
     */
    private val adapter: () -> BluetoothAdapter? = adapterProvider ?: { central?.adapter() }

    constructor(context: Context, config: BleConfig = BleConfig()) : this(
        central = AndroidCentral(context.applicationContext),
        config = config,
        reconnectPolicy = ReconnectPolicy(),
        adapterProvider = null,
        registerAdapterReceiver = true,
        gattDriverFactory = null,
        gattOperationTargetResolver = null,
        rediscoveryOverride = null,
    )

    /**
     * 테스트 전용 seam(plan task 5 §작업①, plan v2 변경 사항). 플랫폼 `Context` 없이
     * 배선 로직([connectSequence] 분기 · [emit] 발행 · [droppedEventCount])만
     * 검증한다 — task 6 이 이 생성자로 `BleCoreWiringTest` 를 만든다.
     *
     * [central] 이 null 이라 [computeAdapterState] 는 항상 `evaluatePermissionState` 의
     * 빈 선언 집합 경로를 타 `Unauthorized(canRequest = false)` 가 나오고, 브로드캐스트
     * 리시버는 등록하지 않는다. (다만 [computeAdapterState] 가 어댑터 null 을 먼저 걸러 이
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
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        gattDriver: GattDriver? = null,
        gattOperationTargetResolver: GattOperationTargetResolver? = null,
        rediscovery: PeripheralRediscovery? = null,
        adapterState: (() -> AdapterState)? = null,
    ) : this(
        central = null,
        config = config,
        reconnectPolicy = reconnectPolicy,
        adapterProvider = adapter,
        registerAdapterReceiver = false,
        gattDriverFactory = gattDriver?.let { driver -> { _: CoroutineScope -> driver } },
        gattOperationTargetResolver = gattOperationTargetResolver,
        rediscoveryOverride = rediscovery,
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

    /** 진행 중 link attempt와 duplicate connect를 비교할 때 쓰는 정규화 기본값. */
    private val defaultLinkTimeoutMillis: Long = config.linkTimeoutMillis

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

    // ── 배선 ② emit — 규칙 7: 방출 지점은 이 함수 하나뿐 ─────────────────────────────

    private val _events = MutableSharedFlow<BleEvent>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * 내부 신호(연결 상태·서비스·MTU·진단)의 관찰 탭. production 소비자는 없다 — 테스트가 신호
     * 순서를 관찰하는 자리다. notification 은 여기 오지 않는다([notifications], plan J).
     */
    public val events: Flow<BleEvent> = _events.asSharedFlow()

    private val _officialEvents = MutableSharedFlow<BleClientEvent>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val officialEvents: Flow<BleClientEvent> = _officialEvents.asSharedFlow()

    /** Structured scan ingress. It is the only scan projection; no raw advertisement event exists. */
    private val _scanObservations = MutableSharedFlow<ScanObservation>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val scanObservations: Flow<ScanObservation> = _scanObservations.asSharedFlow()

    private val dropped = AtomicLong(0)

    /** notification 은 [_events] 를 거치지 않고 수집자별 버퍼로 간다(plan J). */
    private val notificationHub = NotificationHub(
        capacity = config.eventBufferCapacity,
        onDrop = { dropped.incrementAndGet() },
    )

    /** [DefaultBleSession.notifications] 의 진입점. collect 시 동기 등록된다. */
    internal fun notifications(deviceId: PeripheralId, ref: CharacteristicRef): Flow<ByteArray> =
        notificationHub.subscribe(deviceId, ref)

    /** 테스트 관찰용 — 등록된 notification 수집자 수. */
    internal val notificationCollectorCount: Int
        get() = notificationHub.collectorCount

    // Null logger is a genuine fast path: no queue and no worker are allocated by the dispatcher.
    private val diagnosticDispatcher = DiagnosticDispatcher(
        logger = config.logger,
        capacity = config.diagnosticOptions.queueCapacity,
    )
    private val diagnosticReporter = DiagnosticReporter(
        emitCompatibility = ::emitCompatibilityDiagnostic,
        dispatcher = diagnosticDispatcher,
        options = config.diagnosticOptions,
    )
    /** Internal producer callback injected into GattBridge; it never enters the public event type. */
    internal val diagnosticIngress: DiagnosticIngress = diagnosticReporter

    /**
     * 유실된 이벤트 수. `SUSPEND` + `tryEmit` 조합에서 버퍼가 찼을 때, 그리고 notification
     * 수집자의 개별 버퍼([NotificationHub])가 찼을 때(수집자 하나당 한 번) 늘어난다.
     * 유실이 관측 불가능하면 이 배선의 의미가 없다(plan §배선②).
     */
    public val droppedEventCount: Long
        get() = dropped.get()

    /**
     * 이벤트 방출 진입점. 이 함수 하나로만 모은다(규칙 7) — 워커 4개는 생성자로
     * 이 함수 참조(`::emit`)를 받을 뿐, `_events.tryEmit`을 직접 부르지 않는다.
     *
     * `SUSPEND` + `tryEmit` 조합이 의도다: 절대 블로킹하지 않으면서(GATT 콜백 스레드를
     * 막지 않음) 버퍼가 차면 `tryEmit`이 `false`를 돌려줘 유실 사실을 알 수 있다.
     * `DROP_OLDEST`로 바꾸면 `tryEmit`이 항상 `true`라 유실이 조용해진다(plan §배선②).
     *
     * 발행만 한다 — 제어를 일으키지 않는다(plan connect-sequence 불변식 ①). 연결 절차는
     * 발행 소유자가 발행 **직후** [ConnectSequence] hook(`publishState` 도우미 ·
     * `onServicesDiscovered` · `onMtuSettled`)을 불러 진행한다. 순서가 "발행 먼저, 진행 나중"
     * 이어야 `MtuChanged` 뒤 절차가 발행하는 `Ready` 가 그 `MtuChanged` 보다 먼저 소비자에
     * 닿지 않는다(설계 §6 런타임 flow).
     *
     * `internal` 로 올린 이유(plan task 5 §작업①) — 테스트 seam. 프로덕션 호출자는
     * 워커 4개가 생성자로 받은 `::emit` 참조뿐이고(규칙 7 불변), task 6 배선 테스트가
     * 공식 투영 · `dropped` 카운트를 직접 검증하려고 두드리는 자리다.
     */
    internal fun emit(e: BleEvent) {
        if (closed) return
        if (e is BleEvent.Diagnostic) {
            diagnosticReporter.report(e.severity, e.failure)
            return
        }
        if (e is BleEvent.CharValue) {
            // notification 은 공용 버스에 넣지 않는다 — 무관한 이벤트가 버퍼를 채워 유실되는 것 방지.
            notificationHub.deliver(e.deviceId, e.charRef, e.bytes)
            return
        }
        emitCompatibility(e)
    }

    /** Same-module migration seam; external producers receive [diagnosticIngress] directly. */
    internal fun emit(observation: DiagnosticObservation) {
        if (closed) return
        diagnosticIngress.report(observation.severity, observation.failure, observation::values)
    }

    /** Emits an already-projected compatibility event without entering the reporter again. */
    private fun emitCompatibilityDiagnostic(e: BleEvent.Diagnostic) = emitCompatibility(e)

    private fun emitCompatibility(e: BleEvent) {
        // Capture each edge on its owner before later transitions or collector
        // scheduling can change the Session values it describes.
        val official = when (e) {
            is BleEvent.ConnStateChanged -> registry.get(e.deviceId)?.let {
                OfficialBleClientEvent.Connection(e.deviceId, e.state)
            }
            is BleEvent.ServicesDiscovered -> registry.get(e.deviceId)?.let { session ->
                OfficialBleClientEvent.Services(e.deviceId, session.services.value)
            }
            is BleEvent.Diagnostic -> OfficialBleClientEvent.Diagnostic(e.severity, e.failure)
            else -> null
        }
        if (official != null && !_officialEvents.tryEmit(official)) dropped.incrementAndGet()
        if (!_events.tryEmit(e)) dropped.incrementAndGet()
    }

    private fun emitScanObservation(observation: ScanObservation) {
        if (closed) return
        warmAddresses += observation.peripheralId
        if (!_scanObservations.tryEmit(observation)) dropped.incrementAndGet()
    }

    // ── 워커 조립 — 상태는 registry 하나(규칙 2), 출구는 emit 하나(규칙 7) ───────────

    /**
     * 기기별 상태를 담는 유일한 registry(규칙 2). `internal` 로 올린 이유(plan task 5
     * §작업①) — 테스트 seam. task 6 배선 테스트가 세션을 직접 만들고 상태를 세팅해
     * [connectSequence] 분기를 구동한다. 프로덕션 호출자는 워커 4개와
     * `BleRuntime` 자신뿐이다.
     */
    internal val registry = SessionRegistry()

    /**
     * 이 클라이언트가 마지막 adapter Ready 이후 광고나 링크로 본 주소. 없으면 connect 앞에 재발견 스캔.
     */
    private val warmAddresses = mutableSetOf<PeripheralId>()

    /**
     * `internal` 인 이유 — 테스트 seam. 스캔 테스트가 `startOverride` 로 스캔을 시작하고
     * [Scanner.handle] 로 플랫폼 콜백을 주입한다. 프로덕션 호출자는 `BleRuntime` 자신뿐이다.
     */
    internal val scanState: StateFlow<ScanState> get() = scanner.scanState

    internal val scanner = Scanner(
        emit = ::emit,
        scope = coreScope,
        scanner = { adapter()?.bluetoothLeScanner },
        emitObservation = ::emitScanObservation,
    )
    private val gattDriver = gattDriverFactory?.invoke(coreScope)
        ?: AndroidGattDriver(central?.context, adapter, coreScope)
    private val rediscovery: PeripheralRediscovery? = rediscoveryOverride
        ?: central?.let { c -> AndroidPeripheralRediscovery { c.adapter()?.bluetoothLeScanner } }
    /**
     * 연결 절차 진행([ConnectSequence]). emit 은 발행만 하고, 상태·서비스·MTU 발행 소유자가
     * 발행 직후 이 hook 을 부른다. 협력자(bridge·reconnector)는 호출 시점에 평가되는
     * 람다로만 참조하므로 그들보다 먼저 조립해 직접 넘긴다. `internal` 인 이유 — 테스트 seam: 배선 테스트가 이벤트 발행 없이
     * 절차 분기를 직접 구동한다.
     */
    internal val connectSequence: ConnectSequence = ConnectSequence(
        registry = registry,
        isClosed = { closed },
        markWarm = { deviceId -> warmAddresses += deviceId },
        discoverServices = { deviceId -> gattBridge.discoverServices(deviceId) },
        requestMtu = { deviceId, mtu -> gattBridge.requestMtu(deviceId, mtu) },
        scheduleReconnect = { deviceId, failure -> reconnector.scheduleReconnect(deviceId, failure) },
        publishState = ::publishState,
        restoreSubscriptionsBeforeReady = ::restoreSubscriptionsBeforeReady,
    )

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
        linkCacheWarm = { deviceId -> deviceId in warmAddresses },
        connectSequence = connectSequence,
        // 소진 선판정 질의 — reconnector 는 아래에서 조립되므로 호출 시점에 평가되는 람다로 넘긴다.
        reconnectExhaustion = { session, cause -> reconnector.exhaustedFailure(session, cause) },
    )
    private val operationQueue = OperationQueue(
        registry = registry,
        emit = ::emit,
        writeChunk = gattBridge::writeChunk,
        ackTimeoutMillis = config.operationTimeoutMillis,
        writeCommand = gattBridge::writeCommand,
    )
    /** 테스트 seam: JVM 에서 재연결 시작(`startRetainedConnect`)을 대체한다 — `scanner` 와 같은 internal 관례. */
    internal val reconnector = Reconnector(registry, ::emit, coreScope, connectSequence)

    init {
        // plan blecore-app-tunables 결정② — Reconnector.policy 는 4워커 공통 생성자 계약
        // (registry, emit, scope)을 지키려고 var 로 남아 있다(Reconnector KDoc) — 조립 직후
        // 여기서 config 값을 주입한다.
        reconnector.policy = reconnectPolicy
        reconnector.beginReconnectLifecycle = gattBridge::beginRetainedReconnect
        reconnector.terminate = gattBridge::terminate
    }

    // ── 배선 ③ 연결 시퀀스 오케스트레이션 ────────────────────────────────────────


    /**
     * 런타임이 직접 내는 상태 발행의 유일한 경로 — 발행 뒤 [connectSequence] 를 진행한다
     * (plan connect-sequence 불변식 ②). `internal` 인 이유 — 테스트 seam.
     */
    internal fun publishState(session: Session) {
        emit(BleEvent.ConnStateChanged(session.deviceId, session.connectionState.value))
        connectSequence.onStatePublished(session.deviceId)
    }

    /** Restores the desired CCCD intent serially while this generation remains Configuring. */
    private fun restoreSubscriptionsBeforeReady(session: Session) {
        session.resubscribeJob?.cancel()
        val generation = session.connectionCoordinator.generation
        session.resubscribeJob = coreScope.launch {
            for (ref in session.subscriptionState.desiredSnapshot) {
                if (!session.isConfiguringGeneration(generation)) return@launch
                try {
                    gattBridge.restoreSubscription(session.deviceId, ref)
                } catch (e: CancellationException) {
                    coroutineContext.ensureActive()
                    return@launch
                } catch (e: Exception) {
                    if (!session.isConfiguringGeneration(generation)) return@launch
                    val failure = failureFromInternalException(e, FailurePhase.Configure, session.deviceId, ref)
                    if (failure.retryability == Retryability.Never) {
                        session.subscriptionState.removeDesired(ref)
                        emitRestoreFailure(session.deviceId, ref, failure)
                    } else {
                        emit(diagnostic(Severity.Warning, failure))
                    }
                    continue
                }
                if (!session.isConfiguringGeneration(generation)) return@launch
                session.subscriptionState.markActive(ref, generation)
            }
            if (!session.isConfiguringGeneration(generation)) return@launch
            val accepted = session.connectionCoordinator.transition(
                callbackGeneration = generation,
                expectedStage = ConnectionStage.Configuring,
                nextStage = ConnectionStage.Ready,
                scope = coreScope,
            )
            if (!accepted) return@launch
            // Clear only this completed restore before publishing Ready. A replacement generation
            // may already own the slot; an old completion must never erase that handle.
            if (session.resubscribeJob === coroutineContext[Job]) {
                session.resubscribeJob = null
            }
            session.retryCount = 0
            publishState(session)
        }
    }

    private fun emitRestoreFailure(deviceId: PeripheralId, ref: CharacteristicRef, failure: BleFailure) {
        if (!_officialEvents.tryEmit(
                OfficialBleClientEvent.SubscriptionRestoreFailed(
                    peripheralId = deviceId,
                    characteristic = ref,
                    failure = failure,
                ),
            )) dropped.incrementAndGet()
    }

    // ── adapterState ────────────────────────────────────────────────────────────

    private val _adapterState = MutableStateFlow(computeAdapterState())

    /** 어댑터/권한 상태. `Unauthorized(canRequest)`로 요청 가능/미허용을 구분한다(설계 §5, D7). */
    public val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    /**
     * 어댑터 검사가 권한 검사보다 먼저다(plan task 7 §테스트) — 내부 테스트 생성자
     * (`adapter = { null }`)는 [central] 도 null 이라 권한 판정 경로를 타면 항상 빈 선언
     * 집합으로 `Unauthorized(false)` 가 나오는데, 이 순서 덕분에 그 경로 자체를 안 타고
     * `Unsupported` 로 먼저 끝난다 — `BleCoreWiringTest` 56개가 이 순서에 기대고 있다.
     *
     * 권한 판정 자체(D7/M-8+#16, BLE 스캔 결과 배달 조건 — review #2)는 [central] 이
     * 있으면 [AndroidCentral.permissionState] 로, 없으면(테스트 생성자 경로) 파일 최상위
     * [evaluatePermissionState] 에 빈 선언 집합/미허용을 직접 채워 위임한다 — 둘 다 최종
     * 판정 로직은 [AndroidPermissionState.evaluate] 로 수렴한다.
     */
    private fun computeAdapterState(): AdapterState {
        adapterStateProbe?.let { return it() }
        val adapterInstance = adapter() ?: return AdapterState.Unsupported
        val unauthorized = if (central != null) {
            central.permissionState()
        } else {
            // 테스트 생성자 경로(Context 없음): 선언 집합이 비어 있고 grant 조회가 불가한 현재 동작과 동치.
            evaluatePermissionState(sdkInt = Build.VERSION.SDK_INT, declared = emptySet(), isGranted = { false })
        }
        unauthorized?.let { return it }
        return if (adapterInstance.isEnabled) AdapterState.Ready else AdapterState.PoweredOff
    }

    /**
     * 어댑터 on/off 브로드캐스트(review #3 해동) — 잡는 즉시 [_adapterState] 를 다시
     * 계산해 반영한다. `coreScope.launch` 로 홉하는 이유는 다른 워커 콜백과 동일하다
     * (GattBridge/Scanner KDoc) — `onReceive` 는 브로드캐스트를 발송한 시스템 스레드에서
     * 불리고, `_adapterState` 를 coreScope 단일 스레드 밖에서 직접 건드리면 그 확정이
     * 깨진다.
     *
     * 시스템이 직접 보내는 브로드캐스트(앱이 아니라 플랫폼 발신)라 API 33+ 의
     * `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` 플래그 요구가 면제된다 — 그 플래그는
     * "다른 앱이 내 리시버로 브로드캐스트를 보낼 수 있는가"를 통제하는 것이라 발신자가
     * 시스템 자신인 표준 브로드캐스트에는 적용되지 않는다(plan §미해결·전제 — 실기기
     * 미검증). 그래서 `Context.registerReceiver(receiver, filter)` 2-인자로 충분하다.
     */
    private val adapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            coreScope.launch { refreshAdapterState() }
        }
    }

    /**
     * [adapterStateReceiver] 본문 추출(plan G task 1) — 리시버는 등록이 안 되는 테스트
     * 생성자 경로에서 검증할 수 없어(브로드캐스트 자체가 없다), 이 함수를 직접 호출해
     * [adapterStateProbe] 플립 → [updateAdapterState] 전이(스윕 1회 규칙 포함)를 검증한다.
     * 프로덕션에서는 항상 [coreScope] 위(코어 단일 스레드)에서만 불린다 — 이 함수 자체는
     * 스레드 확정을 강제하지 않는다. `sweepSessions`(위 KDoc, plan task 6 지시)와 같은
     * internal 테스트 seam 규약: 테스트는 이 함수를 코어 디스패처 밖(테스트 스레드)에서
     * 직접 부른다 — [BleCoreWiringTest] 가 `core.sweepSessions(...)`를 같은 방식으로
     * 직접 부르는 것과 동일 전례.
     */
    internal fun refreshAdapterState() {
        updateAdapterState(computeAdapterState())
    }

    /**
     * BT off/resetting 스윕(D4/M-6). 어댑터가 죽으면 모든 gatt 핸들은 회복 불가다 —
     * stale 핸들에 백오프를 소진하거나(off 중 재연결은 구조적으로 실패) 세션이 Connected 로
     * 동결되는 것(콜백이 안 오는 기기)을 막는다. BT 복귀 시 자동 재연결은 하지 않는다 —
     * 그건 정책이라 소비자가 connect() 재호출로(설계 §12 v0.2 미결정).
     *
     * [GattBridge] 의 `handleConnectionStateChange` STATE_DISCONNECTED 정리 블록과
     * **중복되는 게 의도다** — 콜백이 오는 기기에서는 그 블록과 이 스윕이 둘 다 돌지만,
     * 이 스윕이 먼저 `state` 를 `Disconnected` 로 바꿔두면 나중에 콜백이 와도(또는 콜백이
     * 먼저 왔으면 이 스윕이) 이미 `Disconnected` 인 세션은 아래 continue 로 걸러진다 —
     * 어느 순서로 오든 한쪽만 실제로 정리한다(plan §잠재에러 "D4 스윕 vs GATT 콜백" —
     * 둘 다 coreScope 홉이라 직렬). 콜백 자체가 안 오는 기기(어댑터가 죽어 STATE_DISCONNECTED
     * 가 발화하지 않는 케이스)에서는 이 스윕만 돈다 — 그게 D4 의 존재 이유다.
     *
     * `internal` 인 이유 — 테스트 seam(plan task 6 지시, `emit`/`registry` 와 같은 규약).
     * 리시버 경로(`adapterStateReceiver`)는 등록이 안 되는 테스트 생성자에서 검증할 수
     * 없어, 상태 전이 자체를 이 함수로 직접 구동해 검증한다.
     *
     * registry 에서 세션을 제거하지 않는다 — 기기 자체는 여전히 유효하고, 소비자가
     * connect() 를 다시 불러 되살릴 수 있어야 한다(D4 원문 "BT 복귀 자동 재연결은 안 함").
     */
    internal fun sweepSessions(cause: AdapterState) {
        // 어댑터가 Ready 를 벗어나면 스택 LE 캐시도 비워진다고 가정한다 — 다음 connect 는 재발견부터.
        warmAddresses.clear()
        // 스캔도 어댑터와 함께 죽는다(review L) — isScanning 잔류를 두면 BT 복귀 후
        // startScan() 이 조용한 no-op 이 된다. 소비자에게는 Diagnostic 으로 알린다
        // (onScanFailed 는 이 경로에서 보장되지 않는다).
        if (scanner.isScanning) {
            val scanFailure = FailureMapper.fromAdapterState(cause)
                .copy(code = FailureCode("SCAN_STOPPED_BY_ADAPTER"), phase = FailurePhase.Scan)
            scanner.stopForAdapter(scanFailure)
            emit(diagnostic(Severity.Warning, scanFailure))
        }
        for (session in registry.all()) {
            // 이미 끝난 세션은 조용히(원인 유지). 단 재연결 대기 중(Recovering·예약된 reconnect job)이면
            // 앱 관점에서 아직 종착이 아니다 — 어댑터 원인으로 회복을 끝낸다(lifecycle 이 close 와 같은
            // 경로로 재게시, ConnectionCoordinator.supersedeRetryPending).
            if (session.stage == ConnectionStage.Disconnected && !session.isRetryPending) continue
            session.suppressReconnect = true
            session.reconnectJob?.cancel(); session.reconnectJob = null
            // resubscribeJob 정리(plan B) — reconnectJob 과 같은 취소 지점 3곳 중 하나.
            session.resubscribeJob?.cancel(); session.resubscribeJob = null
            session.timeoutJob?.cancel(); session.timeoutJob = null
            val failure = FailureMapper.fromAdapterState(cause).copy(
                context = session.lifecycleFailureContext(),
            )
            emit(diagnostic(Severity.Warning, failure))
            // lifecycle 종결 API 로 끝낸다 — 원인 고정·handle close(suppressReconnect)·mtu/chunkSize
            // 무효화(plan G)·게시를 lifecycle 완료 경로가 한 번에 한다. 이미 사용자 해제가 진행 중
            // (Disconnecting)이면 먼저 고정된 Requested 가 남는다(first wins — 사용자가 끊기를 요청했다).
            val reason = if (cause is AdapterState.Unauthorized) {
                TerminalCleanupReason.PermissionDenied
            } else {
                TerminalCleanupReason.AdapterUnavailable
            }
            gattBridge.terminate(session, session.connectionCoordinator.generation, TerminalCause(reason, failure))
        }
    }

    init {
        if (registerAdapterReceiver) {
            central?.context?.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }

    /**
     * 즉시 알 수 있는 실패는 throw 한다(plan §명령 실패의 두 갈래) — 권한 없음 · 어댑터 off.
     * 끊김·재시도 같은 비동기 실패는 여기서 다루지 않고 events 로만 나간다.
     *
     * 매번 [computeAdapterState] 로 재계산한다(review #3 "냉동 해제") — [adapterStateReceiver]
     * 는 `ACTION_STATE_CHANGED` 브로드캐스트만 잡는다. 사용자가 설정 앱에서 위치/BLUETOOTH
     * 권한을 새로 허용하는 것처럼 그 리시버가 못 잡는 변화는, 늦어도 다음 명령 진입에서
     * 이 재계산으로 반영된다.
     */
    /**
     * adapterState 쓰기의 유일한 지점(review High). Ready → non-Ready 전이 감지를 쓰는 쪽
     * (리시버·명령 재계산)이 아니라 여기서 한다 — 어느 경로가 먼저 전이를 관측하든 스윕이
     * 정확히 1회 돈다. 감지가 리시버에만 있으면, BT off 직후 명령이 먼저 재계산해 값을
     * 써버린 경우 리시버가 "이미 non-Ready 였다"고 읽어 스윕을 건너뛴다(콜백 안 오는
     * 기기에서 세션 Connected 동결 — D4 가 막으려던 바로 그 상태).
     *
     * D4/M-6 — Ready 로 들어오거나 non-Ready 사이를 오가는 전이는 스윕 대상이 아니다
     * (이미 스윕된 세션만 남아 Disconnected continue 가드로 no-op).
     */
    private fun updateAdapterState(newState: AdapterState) {
        val wasReady = _adapterState.value is AdapterState.Ready
        _adapterState.value = newState
        if (wasReady && newState !is AdapterState.Ready) sweepSessions(newState)
    }

    private fun ensureAdapterReady(
        operation: AndroidApiOperation,
    ) {
        updateAdapterState(computeAdapterState())
        when (val state = _adapterState.value) {
            is AdapterState.Unauthorized -> throw BleException(
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Adapter,
                    operation = operation,
                    canRequest = state.canRequest,
                ),
            )
            AdapterState.Unsupported, AdapterState.PoweredOff, AdapterState.Resetting ->
                throw BleException(FailureMapper.fromAdapterState(state))
            AdapterState.Ready -> Unit
        }
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
            if (existing != null) {
                when (existing.stage) {
                    ConnectionStage.Disconnecting -> {
                        emitConnectConflict(
                            deviceId = deviceId,
                            code = "CONNECT_DURING_DISCONNECT",
                            message = "disconnect cleanup 중에는 새 connect를 시작할 수 없다: ${deviceId.value}",
                        )
                    }

                    ConnectionStage.Connecting,
                    ConnectionStage.DiscoveringServices,
                    ConnectionStage.Configuring,
                    ConnectionStage.Ready,
                    -> {
                        if (existing.hasSameEffectiveConnectOptions(options)) return@onCore
                        emitConnectConflict(
                            deviceId = deviceId,
                            code = "CONNECTION_OPTIONS_CONFLICT",
                            message = "진행 중인 연결의 effective options와 요청이 다르다: ${deviceId.value}",
                        )
                    }

                    ConnectionStage.Disconnected -> Unit
                }
            }

            ensureAdapterReady(AndroidApiOperation.ConnectGatt)
            val session = existing ?: registry.getOrCreate(deviceId)
            try {
                gattBridge.connect(deviceId, options)
            } catch (e: BleException) {
                // 유령 Connecting 방지(D6/M-7): 시작 자체가 실패했으면 상태·스트림 둘 다 종결시킨다.
                // throw 는 호출자에게 가지만(두 갈래 규약), events 만 보는 다른 소비자도
                // Connecting 이 끝났음을 알아야 한다.
                //
                // connect 시작 실패는 재연결 대상이 아니다(D6) — Disconnected 를 emit 하면
                // ConnectSequence.onStatePublished 의 Disconnected 분기가 scheduleReconnect 로 가므로,
                // emit 전에 suppressReconnect 를 세워 그 경로를 막는다. 시작도 못 했는데
                // 백오프 5회를 도는 건 D2/M-4 와 같은 문제다. 다음 connect() 성공 경로가
                // GattBridge.connect 에서 이미 false 로 리셋한다.
                session.suppressReconnect = true
                val failure = e.failure
                // 보통은 lifecycle 이 throw 전에 이 generation 을 이미 종결(원인 고정)했으므로 게시만
                // 남는다. 아직 열려 있으면 lifecycle 종결 API 가 고정·게시까지 한다.
                val terminated = gattBridge.terminate(
                    session,
                    session.connectionCoordinator.generation,
                    TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
                )
                if (!terminated) publishState(session)
                throw BleException(failure)
            }
        }
    }

    /**
     * Slice 4 deliberately supports only the explicit no-reconnect contract.
     * It never maps an official option onto Android's `autoConnect`; desired
     * active reconnect ownership is a later runtime feature, not a value to
     * silently discard here.
     */
    internal suspend fun connectOfficial(deviceId: PeripheralId, options: BleConnectOptions) {
        when (options.reconnectPolicy) {
            BleReconnectPolicy.Disabled -> {
                onCore {
                    registry.getOrCreate(deviceId).effectiveReconnectPolicy = BleReconnectPolicy.Disabled
                    connect(deviceId, ConnectOptions(autoConnect = false))
                    registry.get(deviceId)?.suppressReconnect = true
                }
            }
            is BleReconnectPolicy.ExponentialBackoff -> onCore {
                // Policy is session-owned and must be present before connectGatt: a synchronous
                // platform failure is already eligible for the bounded recovery path.
                registry.getOrCreate(deviceId).effectiveReconnectPolicy = options.reconnectPolicy
                connect(deviceId, ConnectOptions(autoConnect = false))
            }
        }
    }

    /**
     * `connect`는 Unit을 반환하므로 conflict를 조용한 no-op으로 만들면 호출자가 새 옵션이
     * 적용됐다고 오해한다. Slice 3의 typed failure model 전까지는 기존 즉시 실패 관례에 맞춰
     * deterministic IllegalStateException과 Diagnostic을 함께 남긴다.
     */
    private fun emitConnectConflict(deviceId: PeripheralId, code: String, message: String): Nothing {
        val failure = transportFailure(code, FailurePhase.Connect, deviceId, retryability = Retryability.Never)
        emit(
            diagnostic(
                Severity.Warning,
                failure,
            ),
        )
        throw BleException(failure)
    }

    /**
     * timeout은 link stage에서만 실제 lifecycle 동작을 바꾼다. discovery/configuration/Ready
     * 단계에서는 이미 소진된 timeout을 options mismatch로 취급하지 않는다. generation 0의
     * legacy seam에는 deadline snapshot이 없으므로 기존 autoConnect/MTU 의미만 보존한다.
     */
    private fun Session.hasSameEffectiveConnectOptions(requested: ConnectOptions): Boolean {
        if (autoConnect != requested.autoConnect || requestedMtu != requested.requestedMtu) return false
        if (
            connectionCoordinator.stage != ConnectionStage.Connecting
        ) return true

        return connectionCoordinator.activeDeadline?.timeoutMillis ==
            requested.effectiveLinkTimeoutMillis(defaultLinkTimeoutMillis)
    }

    public suspend fun disconnect(deviceId: PeripheralId): Unit = transportCommand(FailurePhase.Close, deviceId) {
        if (closed) return@transportCommand
        // 해제는 정리 동작이다 — 취소된 호출자라도 링크 정리가 시작돼야 하므로 NonCancellable 로
        // 블록을 끝까지 돌리고, 취소는 그 뒤 ensureActive() 로 호출자에게 그대로 전파한다(stopScan 과 같은 규칙).
        withContext(NonCancellable + coreDispatcherOnly) {
            if (closed) return@withContext
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            if (session.stage == ConnectionStage.Disconnected) {
                session.suppressReconnect = true
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.resubscribeJob?.cancel()
                session.resubscribeJob = null
                return@withContext
            }
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
            gattBridge.subscribe(deviceId, charRef)
            session.subscriptionState.addDesired(charRef)
            session.subscriptionState.markActive(charRef, session.connectionCoordinator.generation)
        }
    }

    /**
     * 구독 해제 — D8 best-effort(plan design decisions v1, review L-1). 유일한 throw 는
     * `UNKNOWN_DEVICE` typed failure다. [GattBridge.unsubscribe] 는 더 이상 throw 하지 않는다 —
     * CCCD write 실패는 Diagnostic 으로만 나간다. desired intent 제거는 그 성패와
     * 무관하게 항상 실행한다 — 소비자 의도("이벤트 그만")는 로컬 라우팅을 껐다는 사실만으로
     * 이미 달성된다. [subscribe] 가 여전히 throw 하는 것과 비대칭이다 — 켜는 방향은 실패가
     * 안 보이면 notify 를 하염없이 기다리게 되지만, 끄는 방향은 로컬에서 이미 지웠으므로
     * 잃을 게 없다(규칙 1 — throw 로 소비자에게 재시도 상태 기계를 강제하지 않는다).
     */
    public suspend fun unsubscribe(deviceId: PeripheralId, charRef: CharacteristicRef): Unit =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            gattBridge.unsubscribe(deviceId, charRef)
            session.subscriptionState.removeDesired(charRef)
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
            adapterState = _adapterState.value,
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
                    adapterState = _adapterState.value,
                    isScanning = false,
                    sessions = emptyList(),
                )
            }
        } finally {
            // receiver·디스패처·스코프 정리는 정상·실패 공통으로 딱 한 번 — 보고 뒤라 디스패처 close 가 보고를 삼키지 않는다.
            runCatching { if (registerAdapterReceiver) central?.context?.unregisterReceiver(adapterStateReceiver) }
            runCatching { diagnosticDispatcher.close() }
            runCatching { coreScope.cancel() }
            closeCompleted.complete(Unit)
        }
    }

    /**
     * plan v3 review MEDIUM Fix 2 — 세션별로 `runCatching`(fault-isolate)한다. 한 세션의
     * `gattBridge.close`(driver.close)가 던지면 v2 코드는 그 예외가 이 함수 전체를 끝내
     * 뒤 세션들이 정리되지 않고(GATT 핸들 누수) `unregisterReceiver`/`diagnosticDispatcher.close()`/
     * `coreScope.cancel()` 꼬리까지 스킵됐다 — 한 핸들의 실패가 나머지 정리를 막으면 안 된다.
     * 첫 예외만 [firstFailure] 에 모아 반환한다 — 호출자인 [runCloseCleanup] 이 그 값을
     * [closeFailure] 에 기록하고 로거로 보고한다(호출자에게는 절대 전파되지 않는다). 스냅샷
     * 구성은 세션 실패 여부와 무관하게 항상 돌고, receiver 해제·`diagnosticDispatcher.close()`·
     * `coreScope.cancel()` 는 [runCloseCleanup] 의 finally 가 담당한다(예기치 못한 throw 에도 실행).
     */
    private fun runCloseCleanupBody(): Throwable? {
        scanner.stopScan()
        val sessions = registry.all()
        var firstFailure: Throwable? = null
        sessions.forEach { session ->
            runCatching {
                // Scheduler completion은 GattBridge.close의 poison/drain이 유한 종결한다.
                session.resubscribeJob?.cancel()
                session.reconnectJob?.cancel()
                session.timeoutJob?.cancel()
                // lifecycle close 가 ClientClosed 를 고정·종결하고 suppressReconnect 를 세운다. 소진 선판정은
                // ClientClosed 를 거부하고(latchExhaustion), 재연결 대기 중 세션도 ClientClosed 로 끝난다.
                gattBridge.close(session)
            }.onFailure { t -> if (firstFailure == null) firstFailure = t }
        }
        closedOfficialSnapshot = ClientSnapshot(
            adapterState = _adapterState.value,
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
     * 있으면 [diagnosticReporter] 를 직접 부른다. `emit(diagnostic(...))` 을 쓰지 않는 이유:
     * 그 공개 경로는 `closed` 게이트를 검사해 이미 닫힌 시점에는 조용히 버린다(위 [emit] 참고) —
     * 정리는 게이트가 선 뒤에만 돌므로 그 경로로는 절대 보고되지 않는다. `diagnosticReporter.report`
     * 는 게이트를 보지 않고 이 실패를 호환 이벤트 스트림과(아직 열려 있으면) logger 워커 큐로
     * non-blocking 하게 흘린다 — [closeFailure] 는 logger 유무와 무관하게 항상 기록되는
     * 테스트/진단 보조 관측점이다.
     */
    private fun reportCleanupFailure(error: Throwable) {
        // plan v3 review LOW Fix 4 — client 가 이미 닫힌 뒤의 실패는 CLIENT_CLOSED 와 같은
        // 이유로 재시도 대상이 아니다: 재시도할 대상 client 자체가 더는 없다.
        val failure = transportFailure(code = "CLEANUP_FAILURE", phase = FailurePhase.Close, retryability = Retryability.Never)
        try {
            diagnosticReporter.report(Severity.Error, failure) {
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
