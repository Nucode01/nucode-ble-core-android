package kr.co.nucode.ble.internal.gatt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.internal.ConnectOptions
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.WriteType
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.connection.LinkSetupWiring
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCause

/**
 * JVM unit tests cannot construct a usable [BluetoothGatt] service graph. This package-internal
 * target keeps the Android command object and its stable callback identity together so an injected
 * fake [GattDriver] can exercise the same platform-start branch as production.
 */
internal data class GattOperationTarget(
    val characteristic: BluetoothGattCharacteristic,
    val identity: GattCharacteristicIdentity,
    val propertyMask: Int,
    val cccdDescriptor: BluetoothGattDescriptor? = null,
)

/** Test-only resolution seam. Production leaves this null and retains exact BluetoothGatt lookup. */
internal fun interface GattOperationTargetResolver {
    fun resolve(session: Session, charRef: CharacteristicRef): GattOperationTarget?
}

/**
 * `GattCharacteristicSnapshot` → 공개 `CharacteristicRef` 생성자. `handleServicesDiscovered`
 * (lifecycle)와 `handleCharacteristicChanged`/`handleCharacteristicRead`(operation) 양쪽에서
 * 쓰는 공유 유틸이다.
 */
internal fun GattCharacteristicSnapshot.toCharacteristicRef(): CharacteristicRef = CharacteristicRef(
    service = ServiceRef(BleUuid(serviceUuid), serviceInstanceIndex),
    uuid = BleUuid(charUuid),
    instanceIndex = instanceIndex,
)

/**
 * [GattDriver] callback fact → `BleEvent` 변환과 [Session] 갱신(설계 §6 구성요소).
 * 정책·판정을 넣지 않는다 — 연결 단계·종결 판정은 [GattConnectionLifecycle] 몫이고,
 * 이 클래스는 콜백이 들어온 그 순간의 사실만 Session 에 반영하고 emit 한다.
 *
 * Android binder callback의 [scope] 홉은 production [AndroidGattDriver]가 담당한다.
 * 이 클래스는 driver가 전달한 event를 이미 coreScope 위에 있다고 보고 처리한다. fake는
 * Android `BluetoothGatt`를 만들지 않고 같은 event를 주입할 수 있다.
 *
 * 권한 게이트는 `BleRuntime.ensureAdapterReady` 가 명령 진입에서 수행한다 — 이 클래스는
 * 게이트 통과 후에만 불린다.
 *
 * [ackTimeoutMillis] 는 CCCD write(구독/해제) ack 대기 상한이다(plan blecore-app-tunables
 * 결정③ — 구 상수 `CCCD_ACK_TIMEOUT_MS` 대체). `BleRuntime` 조립부가 `BleConfig.operationTimeoutMillis`
 * 를 그대로 넘긴다 — `OperationQueue` 의 write ack 타임아웃과 값은 같아도 워커 간 참조 금지
 * 원칙상 상수를 공유하지 않고 각자 주입받는다(OperationQueue KDoc과 동일 근거).
 *
 * 이 클래스 자체는 연결-생애주기·operation 절반을 소유하지 않는다(plan D task 1·2) — 순수
 * 배선 façade다. 생성자 시그니처는 `BleRuntime` + 테스트 5파일이 직접 의존하므로 불변으로
 * 유지한다. 두 절반은 [GattLifecycleHooks]/[GattOperationHooks]로만 서로를 부른다.
 */
internal class GattBridge(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val ackTimeoutMillis: Long,
    private val operationStarter: ((Session, ScheduledGattOperation<*>) -> BleFailure?)? = null,
    private val operationWriteTypeResolver: ((Session, CharacteristicRef, WriteType) -> Int)? = null,
    private val operationTargetResolver: GattOperationTargetResolver? = null,
    private val readinessTimeouts: ConnectionReadinessTimeouts = ConnectionReadinessTimeouts(
        linkMillis = 15_000,
        discoveryMillis = 10_000,
        configurationMillis = 10_000,
    ),
    private val diagnosticIngress: DiagnosticIngress = DiagnosticIngress.None,
    private val rediscovery: PeripheralRediscovery? = null,
    private val linkCacheWarm: ((PeripheralId) -> Boolean)? = null,
    private val linkSetupWiring: LinkSetupWiring? = null,
) {
    internal constructor(
        registry: SessionRegistry,
        emit: (BleEvent) -> Unit,
        scope: CoroutineScope,
        context: Context?,
        adapter: () -> BluetoothAdapter?,
        ackTimeoutMillis: Long,
    ) : this(
        registry = registry,
        emit = emit,
        scope = scope,
        driver = AndroidGattDriver(context, adapter, scope),
        ackTimeoutMillis = ackTimeoutMillis,
    )

    /**
     * 두 절반은 서로를 hook 인터페이스로만 안다. 순환은 여기서 끊는다: [lifecycle]에 넘기는
     * forwarding 객체는 [operations]를 호출 시점에만 읽고, 두 ctor 모두 hook을 호출하지
     * 않으며, `setEventSink`는 둘 다 만들어진 뒤 `init` 끝에서 건다. lifecycle↔[linkSetup] 도
     * 같은 방식이다 — lifecycle 은 `{ linkSetup }` 람다로 호출 시점에만 읽고, [linkSetup] 은
     * lifecycle 을 [kr.co.nucode.ble.internal.connection.LinkSteps] 로 받아 `init` 에서 만든다.
     */
    private lateinit var operations: GattOperationEngine

    /** 붙은 뒤 절차. `internal` 인 이유 — 테스트 seam(BleRuntime 경유 절차 직접 구동·복원 job 관측). */
    internal lateinit var linkSetup: LinkSetup
        private set

    private val lifecycle = GattConnectionLifecycle(
        registry,
        emit,
        scope,
        driver,
        readinessTimeouts,
        rediscovery,
        linkCacheWarm,
        linkSetup = { linkSetup },
        operations = object : GattOperationHooks {
            override fun invalidateAll(session: Session, failure: BleFailure) =
                operations.invalidateAll(session, failure)

            override fun beginGenerationIfPoisoned(session: Session) =
                operations.beginGenerationIfPoisoned(session)
        },
    )

    init {
        operations = GattOperationEngine(
            registry = registry,
            emit = emit,
            driver = driver,
            ackTimeoutMillis = ackTimeoutMillis,
            operationStarter = operationStarter,
            operationWriteTypeResolver = operationWriteTypeResolver,
            operationTargetResolver = operationTargetResolver,
            diagnosticIngress = diagnosticIngress,
            lifecycle = lifecycle,
        )
        linkSetup = LinkSetup(
            scope = scope,
            steps = lifecycle,
            restoreSubscription = { deviceId, ref -> operations.restoreSubscription(deviceId, ref) },
            diagnostic = { failure -> emit(BleEvent.Diagnostic(Severity.Warning, failure)) },
            wiring = linkSetupWiring,
        )
        driver.setEventSink(::handleEvent)
    }

    private fun handleEvent(event: GattDriverEvent) {
        when (event) {
            is GattDriverEvent.ConnectionStateChanged -> lifecycle.handleConnectionStateChange(event)
            is GattDriverEvent.ServicesDiscovered -> lifecycle.handleServicesDiscovered(event)
            is GattDriverEvent.MtuChanged -> lifecycle.handleMtuChanged(event)
            is GattDriverEvent.CharacteristicChanged -> operations.handleCharacteristicChanged(event)
            is GattDriverEvent.CharacteristicRead -> operations.handleCharacteristicRead(event)
            is GattDriverEvent.CharacteristicWrite -> operations.handleCharacteristicWrite(event)
            is GattDriverEvent.DescriptorWrite -> operations.handleDescriptorWrite(event)
        }
    }

    // ── lifecycle 위임 ────────────────────────────────────────────────────────

    /** lifecycle 종결 API — BleRuntime 은 상태를 직접 쓰지 않고 이것으로 종료한다. */
    fun terminate(session: Session, generation: Long, cause: TerminalCause): Boolean =
        lifecycle.terminate(session, generation, cause)

    suspend fun connect(deviceId: PeripheralId, options: ConnectOptions) = lifecycle.connect(deviceId, options)

    /** 중복 connect 판정 — lifecycle 이 한다. */
    fun admitConnect(session: Session, options: ConnectOptions): ConnectAdmission = lifecycle.admitConnect(session, options)

    fun disconnect(deviceId: PeripheralId) = lifecycle.disconnect(deviceId)

    /** 어댑터 복귀 재연결 — lifecycle 이 판정·시작한다. */
    fun resumeAfterAdapter(deviceId: PeripheralId, adapterReady: () -> Boolean) =
        lifecycle.resumeAfterAdapter(deviceId, adapterReady)

    /** 어댑터 이탈 — 세션별 skip 판정·Diagnostic·종결은 lifecycle 이 한다. */
    fun onAdapterLost(session: Session, cause: AdapterState) = lifecycle.onAdapterLost(session, cause)

    fun discoverServices(deviceId: PeripheralId) = lifecycle.discoverServices(deviceId)

    fun requestMtu(deviceId: PeripheralId, mtu: Int) = lifecycle.requestMtu(deviceId, mtu)

    /** Close the current platform handle while keeping ownership in the existing [Session]. */
    fun close(session: Session) = lifecycle.close(session)

    // ── operation 위임 ────────────────────────────────────────────────────────

    suspend fun read(deviceId: PeripheralId, charRef: CharacteristicRef): ByteArray = operations.read(deviceId, charRef)

    suspend fun subscribe(deviceId: PeripheralId, charRef: CharacteristicRef) = operations.subscribe(deviceId, charRef)

    /** Internal lifecycle path. It shares the FIFO but its timeout may recover via reconnect. */
    suspend fun restoreSubscription(deviceId: PeripheralId, charRef: CharacteristicRef) = operations.restoreSubscription(deviceId, charRef)

    suspend fun unsubscribe(deviceId: PeripheralId, charRef: CharacteristicRef) = operations.unsubscribe(deviceId, charRef)

    suspend fun writeCommand(
        session: Session,
        charRef: CharacteristicRef,
        chunks: List<ByteArray>,
        writeType: WriteType,
        timeoutMillis: Long,
    ): List<Int> = operations.writeCommand(session, charRef, chunks, writeType, timeoutMillis)
}
