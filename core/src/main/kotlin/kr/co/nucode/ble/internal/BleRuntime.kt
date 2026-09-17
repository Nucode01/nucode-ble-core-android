package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.platform.android.AndroidPermissionState

/**
 * [context] 로부터 [BluetoothAdapter] 를 매 호출 시 다시 조회하는 provider 를 만든다.
 * 고정 값으로 캐시하면 BT off → on 전환 사이에 갱신되지 않는 낡은 참조를 쓰게 된다
 * (GattBridge/Scanner 의 같은 계약, plan §접근 ①) — 조립부에 흩어져 있던 어댑터 획득
 * 람다 2개(task 2·4 가 임시로 넣은 것) + `computeAdapterState` 전용 캐시 val 을 이
 * 함수 하나로 합친다(plan task 5 §작업①).
 *
 * Runtime construction resolves the current adapter on every access so Bluetooth off/on never
 * retains a stale instance.
 */
private fun defaultAdapterProvider(context: Context): () -> BluetoothAdapter? = {
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

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
 * [appContext] 는 반드시 applicationContext 로 좁혀 저장한다 — Activity 를 그대로 잡으면
 * 코어가 소유하는 [coreScope](규칙 6, 수집자 0 이어도 계속 돈다)가 Activity 보다 오래 살아
 * 라이브러리가 누수를 만든다. 내부 테스트 생성자 경로에서는 null 이다.
 */
/**
 * The one live BLE runtime.  It owns the dispatcher, registry, scanner and all
 * platform workers; compatibility APIs must delegate to this object rather
 * than becoming a second owner.
 */
internal class BleRuntime private constructor(
    private val appContext: Context?,
    config: BleConfig,
    reconnectPolicy: ReconnectPolicy,
    private val adapter: () -> BluetoothAdapter?,
    private val registerAdapterReceiver: Boolean,
    private val gattDriverFactory: ((CoroutineScope) -> GattDriver)?,
    private val gattOperationTargetResolver: GattOperationTargetResolver?,
) {

    private val closeGate = AtomicBoolean(false)
    private val closed: Boolean get() = closeGate.get()
    private val closeCompleted = CompletableDeferred<Unit>()
    @Volatile
    private var closedOfficialSnapshot: ClientSnapshot? = null

    constructor(context: Context, config: BleConfig = BleConfig()) : this(
        appContext = context.applicationContext,
        config = config,
        reconnectPolicy = ReconnectPolicy(),
        adapter = defaultAdapterProvider(context.applicationContext),
        registerAdapterReceiver = true,
        gattDriverFactory = null,
        gattOperationTargetResolver = null,
    )

    /**
     * 테스트 전용 seam(plan task 5 §작업①, plan v2 변경 사항). 플랫폼 `Context` 없이
     * 배선 로직(`advanceConnectSequence` 분기 · [emit] 순서 · [droppedEventCount])만
     * 검증한다 — task 6 이 이 생성자로 `BleCoreWiringTest` 를 만든다.
     *
     * `appContext` 가 null 이라 [declaredPermissions] 는 항상 빈 집합이고 [permissionState] 는
     * 항상 `Unauthorized(canRequest = false)`, 브로드캐스트 리시버는 등록하지 않는다.
     * (다만 [computeAdapterState] 가 어댑터 null 을 먼저 걸러 이 경로 자체를 안 탄다 — 아래
     * 참고.) production `AndroidGattDriver.connect()` 는 null context 를 만나면 typed
     * adapter failure로 즉시 막는다(GattBridge KDoc, plan task 5 §작업①
     * 선택 (a)) — [gattDriver]와 [gattOperationTargetResolver]를 함께 주입하면 Android
     * `BluetoothGatt` 객체 없이도 공개 read/write/subscribe의 driver start 경계를 검증할 수
     * 있다. 둘 다 생략한 기본 production 조립은 실제 Android target resolution을 유지한다.
     */
    constructor(
        config: BleConfig,
        adapter: () -> BluetoothAdapter?,
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        gattDriver: GattDriver? = null,
        gattOperationTargetResolver: GattOperationTargetResolver? = null,
    ) : this(
        appContext = null,
        config = config,
        reconnectPolicy = reconnectPolicy,
        adapter = adapter,
        registerAdapterReceiver = false,
        gattDriverFactory = gattDriver?.let { driver -> { _: CoroutineScope -> driver } },
        gattOperationTargetResolver = gattOperationTargetResolver,
    )

    // ── 배선 ① coreScope — 규칙 6: 구독이 아니라 이 스코프가 동작을 구동한다 ──────────

    /**
     * [config]에 디스패처가 주입되지 않으면 코어가 단일 스레드 확정 디스패처를 만든다.
     * 단일 스레드에 상태를 고정해 [kr.co.nucode.ble.internal.Session]·[SessionRegistry]에
     * `Mutex`를 뿌리지 않아도 되게 한다(plan §접근 배선①).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun defaultDispatcher() = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** 진행 중 link attempt와 duplicate connect를 비교할 때 쓰는 정규화 기본값. */
    private val defaultLinkTimeoutMillis: Long = config.linkTimeoutMillis

    /**
     * [coreScope] 구성에 쓴 디스패처를 Job 없이 그대로 뽑아둔 필드(plan task 5 §작업④) —
     * [close] 가 `runBlocking(coreDispatcherOnly)` 로 정리 코드를 이 디스패처 위에서 돌리기
     * 위한 것이다. `coreScope.coroutineContext` 를 그대로 쓰지 않는 이유: 그 컨텍스트는
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

    /** 스캔결과·연결상태·수신·에러가 한 스트림으로 나온다(설계 §5). */
    public val events: Flow<BleEvent> = _events.asSharedFlow()

    private val _officialEvents = MutableSharedFlow<BleClientEvent>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val officialEvents: Flow<BleClientEvent> = _officialEvents.asSharedFlow()

    /** Structured scan ingress.  The official projection consumes this before
     * the compatibility event projection has discarded advertisement fields. */
    private val _scanObservations = MutableSharedFlow<ScanObservation>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val scanObservations: Flow<ScanObservation> = _scanObservations.asSharedFlow()

    private val dropped = AtomicLong(0)

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
     * 유실된 이벤트 수. `SUSPEND` + `tryEmit` 조합에서 버퍼가 찼을 때만 늘어난다.
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
     * 발행이 먼저다. 시퀀스 진행이 재귀 emit 을 유발하므로 순서를 바꾸면 이벤트 도착
     * 순서가 뒤집힌다(설계 §6 런타임 flow) — `advanceConnectSequence` 를 먼저 부르면
     * `MtuChanged` 처리 중 재귀로 발행되는 `Connected` 가 바깥의 `MtuChanged` 보다
     * 먼저 소비자에 닿는다.
     *
     * `internal` 로 올린 이유(plan task 5 §작업①) — 테스트 seam. 프로덕션 호출자는
     * 워커 4개가 생성자로 받은 `::emit` 참조뿐이고(규칙 7 불변), task 6 배선 테스트가
     * `advanceConnectSequence` 순서 · `dropped` 카운트를 직접 검증하려고 두드리는
     * 자리다.
     */
    internal fun emit(e: BleEvent) {
        if (closed) return
        if (e is BleEvent.Diagnostic) {
            diagnosticReporter.report(e.severity, e.failure)
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
            is BleEvent.ConnStateChanged -> registry.get(e.deviceId)?.let { session ->
                OfficialBleClientEvent.Connection(PeripheralId(e.deviceId.value), session.connectionState.value)
            }
            is BleEvent.ServicesDiscovered -> registry.get(e.deviceId)?.let { session ->
                OfficialBleClientEvent.Services(PeripheralId(e.deviceId.value), session.services.value)
            }
            is BleEvent.Diagnostic -> OfficialBleClientEvent.Diagnostic(e.severity, e.failure)
            else -> null
        }
        if (official != null && !_officialEvents.tryEmit(official)) dropped.incrementAndGet()
        if (!_events.tryEmit(e)) dropped.incrementAndGet()
        advanceConnectSequence(e)
    }

    private fun emitScanObservation(observation: ScanObservation) {
        if (closed) return
        _scanObservations.tryEmit(observation)
        if (!_officialEvents.tryEmit(
                OfficialBleClientEvent.Scan(ScanResult(observation.peripheralId, observation.rssi, observation.advertisement)),
            )) dropped.incrementAndGet()
    }

    // ── 워커 조립 — 상태는 registry 하나(규칙 2), 출구는 emit 하나(규칙 7) ───────────

    /**
     * 기기별 상태를 담는 유일한 registry(규칙 2). `internal` 로 올린 이유(plan task 5
     * §작업①) — 테스트 seam. task 6 배선 테스트가 세션을 직접 만들고 상태를 세팅해
     * `advanceConnectSequence` 분기를 구동한다. 프로덕션 호출자는 워커 4개와
     * `BleRuntime` 자신뿐이다.
     */
    internal val registry = SessionRegistry()

    private val scanner = Scanner(
        emit = ::emit,
        scope = coreScope,
        scanner = { adapter()?.bluetoothLeScanner },
        emitObservation = ::emitScanObservation,
    )
    private val gattDriver = gattDriverFactory?.invoke(coreScope)
        ?: AndroidGattDriver(appContext, adapter, coreScope)
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
        ),
    )
    private val operationQueue = OperationQueue(
        registry = registry,
        emit = ::emit,
        writeChunk = gattBridge::writeChunk,
        ackTimeoutMillis = config.operationTimeoutMillis,
        writeCommand = gattBridge::writeCommand,
    )
    private val reconnector = Reconnector(registry, ::emit, coreScope)

    init {
        // plan blecore-app-tunables 결정② — Reconnector.policy 는 4워커 공통 생성자 계약
        // (registry, emit, scope)을 지키려고 var 로 남아 있다(Reconnector KDoc) — 조립 직후
        // 여기서 config 값을 주입한다.
        reconnector.policy = reconnectPolicy
        reconnector.beginReconnectLifecycle = gattBridge::beginRetainedReconnect
    }

    // ── 배선 ③ 연결 시퀀스 오케스트레이션 ────────────────────────────────────────

    /**
     * 연결 시퀀스를 다음 단계로 민다.
     * ★ 상태 기계가 아니다(규칙 1) — Action/Reducer/Store 가 아니라 GATT 표준 연결 절차의
     *   호출 순서 + 재연결 메커니즘 트리거일 뿐이다. `Connected`/`Disconnected` 분기가
     *   늘어난 건 "정책"이 아니라 "이 절차 다음에 뭘 부르는가"라는 절차 그대로다 — 재시도
     *   횟수·백오프 같은 정책은 여전히 [Reconnector] 에 주입된 값이 정한다(plan §접근 ⑤,
     *   규칙 1 무위반).
     *
     * 체인: `ConnStateChanged(_, DiscoveringServices)` → discoverServices
     *      → `ServicesDiscovered` → `Configuring` → `gattBridge.requestMtu(_, session.requestedMtu)`
     *        (plan blecore-app-tunables 결정① — connect() 시점 옵션값, 구 상수 REQUESTED_MTU 대체)
     *      → `MtuChanged` → `Ready` 전이 + emit
     *      → `Ready` 도달 시 retryCount 리셋. legacy `Connected` 소비와 재구독은 Task 4까지
     *        병존하며, `Disconnected` 도착 시 기존 Reconnector 경계를 유지한다.
     *
     * readiness 상태 전이 자체를 다시 emit 하면 이 함수가 한 단계 재귀한다. 재귀 event는
     * 다음 platform command를 시작하지 않는 Configuring/Ready이므로 무한 재귀가 아니다.
     */
    private fun advanceConnectSequence(e: BleEvent) {
        when (e) {
            is BleEvent.ConnStateChanged -> when (val st = e.state) {
                is ConnState.DiscoveringServices -> gattBridge.discoverServices(e.deviceId)

                is ConnState.Ready -> {
                    val session = registry.get(e.deviceId) ?: return
                    session.retryCount = 0
                }

                is ConnState.Disconnected -> {
                    val session = registry.get(e.deviceId) ?: return
                    if (!session.suppressReconnect) {
                        reconnector.scheduleReconnect(
                            e.deviceId,
                            (st.reason as? DisconnectReason.Failed)?.failure,
                        )
                    }
                }

                else -> Unit
            }

            is BleEvent.ServicesDiscovered -> {
                // plan blecore-app-tunables 결정① — connect() 시점에 저장해둔 Session.requestedMtu
                // 를 그대로 쓴다(구 상수 REQUESTED_MTU 대체, GattBridge.connect KDoc). 세션이 이미
                // 사라졌으면(예: 그 사이 close()) 요청할 대상 자체가 없어 조용히 넘어간다.
                registry.get(e.deviceId)?.let { session ->
                    if (session.connectionCoordinator.stage == ConnectionStage.Configuring) {
                        emit(BleEvent.ConnStateChanged(e.deviceId, session.state))
                    }
                    gattBridge.requestMtu(e.deviceId, session.requestedMtu)
                }
            }

            is BleEvent.MtuChanged -> {
                val session = registry.get(e.deviceId) ?: return
                if (session.connectionCoordinator.stage == ConnectionStage.Configuring) {
                    restoreSubscriptionsBeforeReady(session)
                }
            }

            else -> Unit
        }
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
            emit(BleEvent.ConnStateChanged(session.deviceId, session.state))
        }
    }

    private fun emitRestoreFailure(deviceId: DeviceId, ref: CharRef, failure: BleFailure) {
        val characteristic = CharacteristicRef(
            service = ServiceRef(BleUuid(ref.serviceUuid), ref.serviceInstanceIndex),
            uuid = BleUuid(ref.charUuid),
            instanceIndex = ref.instanceIndex,
        )
        if (!_officialEvents.tryEmit(
                OfficialBleClientEvent.SubscriptionRestoreFailed(
                    peripheralId = PeripheralId(deviceId.value),
                    characteristic = characteristic,
                    failure = failure,
                ),
            )) dropped.incrementAndGet()
    }

    // ── adapterState ────────────────────────────────────────────────────────────

    /**
     * 앱 매니페스트(머지 결과)에 선언된 권한 집합(D7/M-8+#16 §잠재에러 "getPackageInfo IPC
     * 비용"). 불변이라 init 1회만 조회한다 — 명령마다 `PackageManager` IPC 를 타면 매
     * `startScan`/`connect` 호출마다 바인더 왕복이 생긴다.
     *
     * [appContext] 가 null(내부 테스트 생성자 경로)이면 조회할 대상이 없어 빈 집합이다 —
     * 이 경우 [evaluatePermissionState] 의 "필요 권한 중 선언 안 된 게 있다" 분기가 항상
     * 먼저 걸려 `Unauthorized(canRequest = false)` 로 판정된다(테스트 경로는 [computeAdapterState]
     * 의 어댑터 null 체크가 먼저라 실제로 이 분기까지 안 간다 — 아래 [computeAdapterState] 참고).
     *
     * API 33+ 전용 `getPackageInfo(String, PackageInfoFlags)` 오버로드 대신 구 오버로드
     * (`getPackageInfo(String, Int)`)를 그대로 쓴다 — minSdk 24 를 포함한 전 버전에서
     * 동작하고, 이 호출 자체가 deprecation 경고를 내지 않는다(경고가 나면 SDK_INT 분기로
     * 바꾼다는 게 원래 방침이었으나 이 시그니처는 여전히 유효해 분기가 필요 없다).
     */
    private val declaredPermissions: Set<String> = appContext?.let { ctx ->
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toSet()
        } catch (e: Exception) {
            null
        }
    } ?: emptySet()

    private val _adapterState = MutableStateFlow(computeAdapterState())

    /** 어댑터/권한 상태. `Unauthorized(canRequest)`로 요청 가능/미허용을 구분한다(설계 §5, D7). */
    public val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    /**
     * 어댑터 검사가 권한 검사보다 먼저다(plan task 7 §테스트) — 내부 테스트 생성자
     * (`adapter = { null }`)는 [appContext] 도 null 이라 [permissionState] 를 타면
     * `declaredPermissions` 가 비어 있어 조회 대상 없이도 항상 `Unauthorized(false)` 가
     * 나오는데, 이 순서 덕분에 그 경로 자체를 안 타고 `Unsupported` 로 먼저 끝난다 —
     * `BleCoreWiringTest` 56개가 이 순서에 기대고 있다.
     */
    private fun computeAdapterState(): AdapterState {
        val adapterInstance = adapter() ?: return AdapterState.Unsupported
        permissionState()?.let { return it }
        return if (adapterInstance.isEnabled) AdapterState.Ready else AdapterState.PoweredOff
    }

    /**
     * BLE 스캔 결과 배달 조건(review #2 — 이전 근거가 틀렸다) + D7(M-8+#16) 미선언/미허용
     * 구분. API 레벨별로 요구되는 권한이 다르다:
     *
     * ```
     * S(31)+          BLUETOOTH_SCAN + BLUETOOTH_CONNECT
     * Q(29)~R(30)     BLUETOOTH · BLUETOOTH_ADMIN(선언만) + ACCESS_FINE_LOCATION
     * N(24)~P(28)     BLUETOOTH · BLUETOOTH_ADMIN(선언만) + ACCESS_FINE_LOCATION 또는 ACCESS_COARSE_LOCATION
     * ```
     *
     * "BLUETOOTH/BLUETOOTH_ADMIN 은 설치 시 부여되는 일반 권한이라 확인 대상이 아니다"는
     * 예전 주석은 BLUETOOTH_SCAN/BLUETOOTH_CONNECT 자체의 권한 유형(런타임 권한)에는
     * 맞지만, 31 미만에서는 그 두 권한이 아예 존재하지 않고 대신 안드로이드 플랫폼이
     * BLE 스캔 "결과 배달"을 위치 권한에 묶어 놓았다는 사실을 놓쳤다(review #2). 다만
     * pre-31 앱이 `BLUETOOTH`/`BLUETOOTH_ADMIN` 자체를 매니페스트에서 뺐으면(M-8) 위치
     * 권한 검사만 통과해도 `startScan`/`connectGatt` 가 `SecurityException` 을 던진다 —
     * 그래서 이 두 권한은 grant 가 아니라 **선언 여부**를 [evaluatePermissionState] 에서
     * 함께 본다.
     *
     * 실제 판정은 [evaluatePermissionState](파일 최상위, 순수 함수)에 위임한다 —
     * `Build.VERSION.SDK_INT`/`declaredPermissions`/`context.checkSelfPermission` 을
     * 여기서 인자로 채워 넣기만 한다.
     */
    private fun permissionState(): AdapterState.Unauthorized? = evaluatePermissionState(
        sdkInt = Build.VERSION.SDK_INT,
        targetSdkInt = appContext?.applicationInfo?.targetSdkVersion ?: Build.VERSION.SDK_INT,
        declared = declaredPermissions,
        isGranted = { permission ->
            appContext?.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        },
    )

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
            coreScope.launch { updateAdapterState(computeAdapterState()) }
        }
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
        // 스캔도 어댑터와 함께 죽는다(review L) — isScanning 잔류를 두면 BT 복귀 후
        // startScan() 이 조용한 no-op 이 된다. 소비자에게는 Diagnostic 으로 알린다
        // (onScanFailed 는 이 경로에서 보장되지 않는다).
        if (scanner.isScanning) {
            scanner.stopScan()
            emit(
                diagnostic(
                    Severity.Warning,
                    FailureMapper.fromAdapterState(cause)
                        .copy(code = FailureCode("SCAN_STOPPED_BY_ADAPTER"), phase = FailurePhase.Scan),
                ),
            )
        }
        for (session in registry.all()) {
            if (session.state is ConnState.Disconnected) continue // 이미 끝난 세션은 조용히
            session.suppressReconnect = true
            session.reconnectJob?.cancel(); session.reconnectJob = null
            // resubscribeJob 정리(plan B) — reconnectJob 과 같은 취소 지점 3곳 중 하나.
            session.resubscribeJob?.cancel(); session.resubscribeJob = null
            session.timeoutJob?.cancel(); session.timeoutJob = null
            val failure = FailureMapper.fromAdapterState(cause).copy(
                context = session.lifecycleFailureContext(),
            )
            session.prepareDisconnect(DisconnectReason.Failed(failure))
            session.connectionCoordinator.completeCleanup(
                session.connectionCoordinator.generation,
                kr.co.nucode.ble.internal.TerminalCleanupReason.AdapterUnavailable,
            )
            gattBridge.close(session)
            // mtu/chunkSize 무효화(plan G) — GattBridge.handleConnectionStateChange 의
            // STATE_DISCONNECTED 정리와 대칭이다. 스윕도 세션을 Disconnected 로 보내므로
            // 협상 값이 이전 링크의 것으로 남으면 안 된다(재연결 후 협상 전 write 가
            // 낡은 chunkSize 로 나가는 경로 차단).
            session.mtu = null
            session.chunkSize = null
            emit(diagnostic(Severity.Warning, failure))
            emit(BleEvent.ConnStateChanged(session.deviceId, session.state))
        }
    }

    init {
        if (registerAdapterReceiver) {
            appContext?.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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
        deviceId: DeviceId? = null,
        charRef: CharRef? = null,
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
        deviceId: DeviceId? = null,
        charRef: CharRef? = null,
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
        deviceId: DeviceId? = null,
        charRef: CharRef? = null,
    ): BleFailure = when (error) {
        is BleException -> error.failure
        else -> transportFailure("INTERNAL_TRANSPORT_ERROR", phase, deviceId, charRef)
    }

    private fun unknownDevice(deviceId: DeviceId): BleException = BleException(
        transportFailure(
            code = "UNKNOWN_DEVICE",
            phase = FailurePhase.Validation,
            deviceId = deviceId,
            retryability = Retryability.Never,
        ),
    )

    private fun notReady(deviceId: DeviceId): BleException = BleException(
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

    /** Synchronous views join the same serialized registry owner as commands. */
    internal fun sessionForView(deviceId: DeviceId): Session {
        checkOpen()
        return onRuntimeBlocking {
            checkOpen()
            registry.getOrCreate(deviceId)
        }
    }

    private fun <T> onRuntimeBlocking(block: () -> T): T =
        if (onRuntimeThread.get() == true) block()
        else runBlocking(coreDispatcherOnly) { block() }

    public suspend fun startScan(filter: ScanFilter = ScanFilter(), options: ScanOptions = ScanOptions()): Unit =
        transportCommand(FailurePhase.Scan) {
        onCore {
            ensureAdapterReady(AndroidApiOperation.StartScan)
            scanner.startScan(filter, options)
        }
    }

    public suspend fun stopScan() {
        if (closed) return
        withContext(coreDispatcherOnly) {
            if (!closed) scanner.stopScan()
        }
    }

    public suspend fun connect(deviceId: DeviceId, options: ConnectOptions = ConnectOptions()): Unit =
        transportCommand(FailurePhase.Connect, deviceId) {
        onCore {
            val existing = registry.get(deviceId)
            if (existing != null) {
                when (existing.state) {
                    is ConnState.Disconnecting -> {
                        emitConnectConflict(
                            deviceId = deviceId,
                            code = "CONNECT_DURING_DISCONNECT",
                            message = "disconnect cleanup 중에는 새 connect를 시작할 수 없다: $deviceId",
                        )
                    }

                    is ConnState.Connecting,
                    is ConnState.DiscoveringServices,
                    is ConnState.Configuring,
                    is ConnState.Ready,
                    -> {
                        if (existing.hasSameEffectiveConnectOptions(options)) return@onCore
                        emitConnectConflict(
                            deviceId = deviceId,
                            code = "CONNECTION_OPTIONS_CONFLICT",
                            message = "진행 중인 연결의 effective options와 요청이 다르다: $deviceId",
                        )
                    }

                    is ConnState.Disconnected -> Unit
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
                // advanceConnectSequence 의 Disconnected 분기가 scheduleReconnect 로 가므로,
                // emit 전에 suppressReconnect 를 세워 그 경로를 막는다. 시작도 못 했는데
                // 백오프 5회를 도는 건 D2/M-4 와 같은 문제다. 다음 connect() 성공 경로가
                // GattBridge.connect 에서 이미 false 로 리셋한다.
                session.suppressReconnect = true
                val failure = e.failure
                session.prepareDisconnect(DisconnectReason.Failed(failure))
                emit(BleEvent.ConnStateChanged(deviceId, session.state))
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
    internal suspend fun connectOfficial(deviceId: DeviceId, options: BleConnectOptions) {
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
    private fun emitConnectConflict(deviceId: DeviceId, code: String, message: String): Nothing {
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

    public suspend fun disconnect(deviceId: DeviceId): Unit = transportCommand(FailurePhase.Close, deviceId) {
        if (closed) return@transportCommand
        withContext(coreDispatcherOnly) {
            if (closed) return@withContext
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            if (session.state is ConnState.Disconnected) {
                session.suppressReconnect = true
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.resubscribeJob?.cancel()
                session.resubscribeJob = null
                return@withContext
            }
            gattBridge.disconnect(deviceId)
        }
    }

    /**
     * 현재 연결 generation에서 characteristic 값을 한 번 읽는다. 응답 bytes는 notification
     * event로 방출하지 않고 이 호출 결과로만 반환한다.
     */
    public suspend fun read(deviceId: DeviceId, charRef: CharRef): ByteArray =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
            onCore {
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
        if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
        gattBridge.read(deviceId, charRef)
    }
        }

    /**
     * write 만 [WriteResult] 를 반환한다 — 부분 전달을 타입으로 강제해야 하므로(설계 §5).
     * 즉시 실패(모르는 DeviceId · 미연결)는 [OperationQueue.write] 가 이미 throw 한다.
     */
    public suspend fun write(
        deviceId: DeviceId,
        charRef: CharRef,
        bytes: ByteArray,
        writeType: WriteType = WriteType.Auto,
        chunking: Chunking = Chunking.Auto,
    ): WriteResult = transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
        if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
        operationQueue.write(deviceId, charRef, bytes, writeType, chunking)
    }
    }

    public suspend fun subscribe(deviceId: DeviceId, charRef: CharRef): Unit =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
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
    public suspend fun unsubscribe(deviceId: DeviceId, charRef: CharRef): Unit =
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            gattBridge.unsubscribe(deviceId, charRef)
            session.subscriptionState.removeDesired(charRef)
        }
    }

    /**
     * 콜드 스타트 장치(설계 §5) — 새로 구독한 소비자가 다음 이벤트를 기다리지 않고
     * 현재 상태를 즉시 안다. [kr.co.nucode.ble.internal.Session] 은 절대 반환하지 않는다
     * (규칙 5, `internal` 이라 컴파일러가 강제한다) — `toSnapshot()` 값 타입으로만 나간다.
     * 단일 스레드 확정의 따름정리로 `suspend`다(plan §미해결·전제).
     */
    public suspend fun snapshot(): Snapshot = onCore {
        Snapshot(
            adapterState = _adapterState.value,
            devices = registry.all().map { it.toSnapshot() },
        )
    }

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
                    peripheralId = PeripheralId(session.deviceId.value),
                    connectionState = session.connectionState.value,
                    services = session.services.value,
                    subscriptions = session.subscriptionState.desiredSnapshot.mapTo(linkedSetOf()) { ref ->
                        CharacteristicRef(
                            service = ServiceRef(BleUuid(ref.serviceUuid), ref.serviceInstanceIndex),
                            uuid = BleUuid(ref.charUuid),
                            instanceIndex = ref.instanceIndex,
                        )
                    },
                )
            },
            )
        }
    }

    /**
     * Close admission first, then synchronously release local resources on their
     * owner. Runtime callbacks clean up inline so they never wait for their own
     * dispatcher. The dispatcher context carries no Job, so scope cancellation
     * cannot interrupt cleanup or replace a command caller's cancellation.
     */
    fun close() {
        // This gate is deliberately before dispatch: no command can be admitted
        // between a close caller and deferred cleanup.
        if (!closeGate.compareAndSet(false, true)) return
        onRuntimeBlocking {
            scanner.stopScan()
            val sessions = registry.all()
            sessions.forEach { session ->
                // Scheduler completion은 GattBridge.close의 poison/drain이 유한 종결한다.
                session.resubscribeJob?.cancel()
                session.reconnectJob?.cancel()
                session.timeoutJob?.cancel()
                session.prepareDisconnect(DisconnectReason.ClientClosed)
                session.connectionCoordinator.completeCleanup(
                    session.connectionCoordinator.generation,
                    kr.co.nucode.ble.internal.TerminalCleanupReason.ClientClosed,
                )
                gattBridge.close(session)
            }
            closedOfficialSnapshot = ClientSnapshot(
                adapterState = _adapterState.value,
                isScanning = false,
                sessions = sessions.map { session ->
                    SessionSnapshot(
                        peripheralId = PeripheralId(session.deviceId.value),
                        connectionState = session.connectionState.value,
                        services = session.services.value,
                        subscriptions = session.subscriptionState.desiredSnapshot.mapTo(linkedSetOf()) { ref ->
                            CharacteristicRef(
                                service = ServiceRef(BleUuid(ref.serviceUuid), ref.serviceInstanceIndex),
                                uuid = BleUuid(ref.charUuid),
                                instanceIndex = ref.instanceIndex,
                            )
                        },
                    )
                },
            )
            sessions.forEach { registry.remove(it.deviceId) }
            if (registerAdapterReceiver) appContext?.unregisterReceiver(adapterStateReceiver)
            diagnosticDispatcher.close()
            coreScope.cancel()
            closeCompleted.complete(Unit)
        }
    }
}
