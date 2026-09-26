package kr.co.nucode.ble.internal.event

import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.DiagnosticObservation
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.diagnostics.DiagnosticDispatcher
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.diagnostics.DiagnosticReporter
import kr.co.nucode.ble.internal.model.ScanObservation
import kr.co.nucode.ble.internal.session.Session

/**
 * 나가는 문의 유일한 소유자(plan runtime-split 불변식 ②) — 이벤트 흐름·`dropped`·[NotificationHub]·
 * 진단 dispatcher/reporter·emit 라우팅. 나가는 흐름의 `tryEmit` 은 이 파일에만 있다(grep 가드).
 *
 * 런타임에게서는 좁은 함수만 받는다 — [isClosed](닫힘 게이트), [sessionOf](legacy→official 변환 시
 * 세션 조회).
 */
internal class ClientEventBus(
    config: BleConfig,
    private val isClosed: () -> Boolean,
    private val sessionOf: (PeripheralId) -> Session?,
) {

    // ── 배선 ② emit — 규칙 7: 방출 지점은 이 함수 하나뿐 ─────────────────────────────

    private val _officialEvents = MutableSharedFlow<BleClientEvent>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val officialEvents: Flow<BleClientEvent> = _officialEvents.asSharedFlow()

    /** Structured scan ingress. It is the only scan projection; no raw advertisement event exists. */
    private val _scanObservations = MutableSharedFlow<ScanObservation>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val scanObservations: Flow<ScanObservation> = _scanObservations.asSharedFlow()

    private val dropped = AtomicLong(0)

    /** notification 은 [officialEvents] 를 거치지 않고 수집자별 버퍼로 간다(plan J). */
    private val notificationHub = NotificationHub(
        capacity = config.eventBufferCapacity,
        onDrop = { dropped.incrementAndGet() },
    )

    /** [kr.co.nucode.ble.DefaultBleSession.notifications] 의 진입점. collect 시 동기 등록된다. */
    fun notifications(deviceId: PeripheralId, ref: CharacteristicRef): Flow<ByteArray> =
        notificationHub.subscribe(deviceId, ref)

    /** 테스트 관찰용 — 등록된 notification 수집자 수. */
    val notificationCollectorCount: Int
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
    val diagnosticIngress: DiagnosticIngress = diagnosticReporter

    /**
     * 유실된 이벤트 수. `SUSPEND` + `tryEmit` 조합에서 버퍼가 찼을 때, 그리고 notification
     * 수집자의 개별 버퍼([NotificationHub])가 찼을 때(수집자 하나당 한 번) 늘어난다.
     * 유실이 관측 불가능하면 이 배선의 의미가 없다(plan §배선②).
     */
    val droppedEventCount: Long
        get() = dropped.get()

    /**
     * 이벤트 방출 진입점. 이 함수 하나로만 모은다(규칙 7) — 워커 4개는 생성자로
     * 이 함수 참조(`::emit`)를 받을 뿐, 나가는 흐름의 `tryEmit`을 직접 부르지 않는다.
     *
     * `SUSPEND` + `tryEmit` 조합이 의도다: 절대 블로킹하지 않으면서(GATT 콜백 스레드를
     * 막지 않음) 버퍼가 차면 `tryEmit`이 `false`를 돌려줘 유실 사실을 알 수 있다.
     * `DROP_OLDEST`로 바꾸면 `tryEmit`이 항상 `true`라 유실이 조용해진다(plan §배선②).
     *
     * 발행만 한다 — 제어를 일으키지 않는다(plan connect-sequence 불변식 ①). 붙은 뒤 절차는
     * lifecycle 이 발행 **직후** [kr.co.nucode.ble.internal.connection.LinkSetup] 입력(`onLinkUp` ·
     * `onServicesDiscovered` · `onMtuSettled`)을 불러 진행한다. 순서가 "발행 먼저, 진행 나중"
     * 이어야 앞 사건(예: `ServicesDiscovered`) 뒤 절차가 발행하는 다음 상태가 그 사건보다 먼저
     * 소비자에 닿지 않는다(설계 §6 런타임 flow).
     */
    fun emit(e: BleEvent) {
        if (isClosed()) return
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
    fun emit(observation: DiagnosticObservation) {
        if (isClosed()) return
        diagnosticIngress.report(observation.severity, observation.failure, observation::values)
    }

    /** Emits an already-projected compatibility event without entering the reporter again. */
    private fun emitCompatibilityDiagnostic(e: BleEvent.Diagnostic) = emitCompatibility(e)

    private fun emitCompatibility(e: BleEvent) {
        // Capture each edge on its owner before later transitions or collector
        // scheduling can change the Session values it describes.
        val official = when (e) {
            is BleEvent.ConnStateChanged -> sessionOf(e.deviceId)?.let {
                OfficialBleClientEvent.Connection(e.deviceId, e.state)
            }
            is BleEvent.ServicesDiscovered -> sessionOf(e.deviceId)?.let { session ->
                OfficialBleClientEvent.Services(e.deviceId, session.services.value)
            }
            is BleEvent.Diagnostic -> OfficialBleClientEvent.Diagnostic(e.severity, e.failure)
            else -> null
        }
        if (official != null && !_officialEvents.tryEmit(official)) dropped.incrementAndGet()
    }

    /** 닫힘 게이트 검사는 호출부(런타임)가 온기 기록 앞에서 한다 — 순서: 온기 mark → tryEmit. */
    fun emitScanObservation(observation: ScanObservation) {
        if (isClosed()) return
        if (!_scanObservations.tryEmit(observation)) dropped.incrementAndGet()
    }

    // ── 배선 ③ 복원 실패 공개 이벤트 — LinkSetup 에 주입된다 ──

    fun emitRestoreFailure(deviceId: PeripheralId, ref: CharacteristicRef, failure: BleFailure) {
        if (isClosed()) return
        if (!_officialEvents.tryEmit(
                OfficialBleClientEvent.SubscriptionRestoreFailed(
                    peripheralId = deviceId,
                    characteristic = ref,
                    failure = failure,
                ),
            )) dropped.incrementAndGet()
    }

    /** 진단 dispatcher 를 닫는다. 순서는 런타임 정리(runCloseCleanup)가 정한다. */
    fun close() {
        diagnosticDispatcher.close()
    }
}
