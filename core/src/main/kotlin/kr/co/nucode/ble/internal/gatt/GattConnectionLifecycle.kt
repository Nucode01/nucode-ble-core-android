package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.port.GattDriverEvent
import kr.co.nucode.ble.internal.port.GattDriver
import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.connection.LinkSteps
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.ConnectOptions
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.core.connection.ConnectAdmission
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.core.connection.TerminalCause

/**
 * operation → lifecycle. 작업 쪽은 사실만 알린다 — 어느 종결 원인으로 끝낼지는 Core 가 고른다.
 */
internal interface GattLifecycleHooks {
    /** 작업 시작에서 권한 상실이 확인됐다. */
    fun onOperationPermissionLost(session: Session, cause: BleFailure)

    /** 시작된 작업이 기한 안에 끝나지 않아 scheduler 를 poison 했다. */
    fun onOperationPoisoned(session: Session, cause: BleFailure)
}

/** lifecycle → operation. */
internal interface GattOperationHooks {
    fun invalidateAll(session: Session, failure: BleFailure)
    fun beginGenerationIfPoisoned(session: Session)
}

/**
 * `GattBridge`의 연결-생애주기 절반(plan D task 1) — 플랫폼 재연결 귀속, connect/disconnect,
 * service discovery, MTU 협상, lifecycle deadline/cleanup을 소유한다.
 *
 * operation 절반(scheduler FIFO)과는 [GattOperationHooks]로만 통신한다 — scheduler 필드를
 * 직접 참조하지 않는다(게이트: 해당 식별자 참조 0회). 반대 방향은 [GattLifecycleHooks]를
 * 이 클래스가 구현해 제공한다.
 *
 * 이 클래스는 상태 게시([publishState])와 명령(disconnect·discoverServices·requestMtu)을 갖고, 연결 시작은
 * [ConnectStarter], 플랫폼 콜백은 [LinkCallbacks], 정리·종료 할 일의 실행은 [LinkEffects] 에 위임한다.
 */
internal class GattConnectionLifecycle(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val rediscovery: PeripheralRediscovery?,
    private val linkCacheWarm: ((PeripheralId) -> Boolean)?,
    private val operations: GattOperationHooks,
    /** 붙은 뒤 절차([LinkSetup]). 호출 시점에 읽는다 — 조립 순환은 GattBridge 가 끊는다. 없으면 절차를 진행하지 않는다. */
    private val linkSetup: () -> LinkSetup? = { null },
) : GattLifecycleHooks, LinkSteps {

    /** `ConnStateChanged` 발행의 유일한 경로(plan link-setup 불변식 ②). 게시만 한다 — 다음 단계는 호출자가 [LinkSetup] 에 직접 알린다. */
    private fun publishState(session: Session) {
        emit(BleEvent.ConnStateChanged(session.deviceId, session.connectionState.value))
    }

    // ── LinkSteps — LinkSetup 이 부르는 좁은 역방향 hook ─────────────────────────

    override fun discoverServices(session: Session) = discoverServices(session.deviceId)

    override fun requestMtu(session: Session, mtu: Int) = requestMtu(session.deviceId, mtu)

    override fun publish(session: Session) = publishState(session)

    /** 복원이 끝난 [generation] 을 Ready 로 — 판단(generation·단계 재확인, 전이, 게시)은 Core 가 한다. */
    override fun completeReady(session: Session, generation: Long): Boolean =
        effects.dispatch(session, ConnectionEvent.RestoreFinished(generation))

    // ── 연결 시작 · 콜백 · 정리 칸 — 본문은 각 클래스, 여기서는 위임만 ─────────────────

    private val effects = LinkEffects(
        registry, scope, driver, readinessTimeouts, rediscovery, operations,
        ::publishState, ::emitDiagnostic, linkSetup,
    )
    private val callbacks = LinkCallbacks(registry, emit, readinessTimeouts, ::emitDiagnostic, effects)
    private val starter = ConnectStarter(
        registry, scope, driver, readinessTimeouts, rediscovery, linkCacheWarm, ::emitDiagnostic, effects,
    )

    override fun onOperationPermissionLost(session: Session, cause: BleFailure) =
        effects.operationPermissionLost(session, cause)

    override fun onOperationPoisoned(session: Session, cause: BleFailure) =
        effects.operationInvalidated(session, cause, suppressReconnect = true)

    fun terminate(session: Session, generation: Long, cause: TerminalCause): Boolean = effects.terminate(session, generation, cause)

    fun onAdapterLost(session: Session, cause: AdapterState) = effects.onAdapterLost(session, cause)

    fun close(session: Session) = effects.close(session)

    fun failInternal(session: Session, cause: BleFailure) = effects.failInternal(session, cause)

    internal fun handleConnectionStateChange(event: GattDriverEvent.ConnectionStateChanged) = callbacks.handleConnectionStateChange(event)

    internal fun handleServicesDiscovered(event: GattDriverEvent.ServicesDiscovered) = callbacks.handleServicesDiscovered(event)

    internal fun handleMtuChanged(event: GattDriverEvent.MtuChanged) = callbacks.handleMtuChanged(event)

    fun admitConnect(session: Session, options: ConnectOptions): ConnectAdmission = starter.admitConnect(session, options)

    suspend fun connect(deviceId: PeripheralId, options: ConnectOptions) = starter.connect(deviceId, options)

    fun resumeAfterAdapter(deviceId: PeripheralId, adapterReady: () -> Boolean) = starter.resumeAfterAdapter(deviceId, adapterReady)

    /**
     * 연결 해제. `gatt.close()` 는 여기서 하지 않는다 — `onConnectionStateChange` 로
     * `STATE_DISCONNECTED` 가 도착한 뒤 [handleConnectionStateChange] 에서
     * [Session.suppressReconnect] == true 일 때 정리한다(plan §잠재에러 — close() 를
     * 놓치면 핸들이 누수된다). 사용자 해제는 suppressReconnect 를 세우므로 항상 닫힌다.
     * 플랫폼 재연결 대기 중이면 Core 가 대기를 끝내고 handle 을 닫는다. 판단은 Core(사용자 해제 사건),
     * 실행은 [LinkEffects] 다.
     */
    fun disconnect(deviceId: PeripheralId) {
        val session = registry.get(deviceId) ?: return
        effects.disconnect(session)
    }

    /**
     * 서비스 탐색 시작. `discoverServices()` 가 `false` 를 돌려주면 시작 자체가 실패한
     * 것이라 `onServicesDiscovered` 콜백이 영원히 오지 않는다 — 연결 시퀀스가
     * Discovering 에서 영구 정지한다. 회복 불가로 보고 즉시 Disconnected 로 전이한다
     * (plan §접근 ④).
     */
    fun discoverServices(deviceId: PeripheralId) {
        val session = registry.get(deviceId) ?: return
        val failure = try {
            if (driver.discoverServices(deviceId, session.gatt)) null else GattFailureFactory.startRejected(
                session,
                FailurePhase.DiscoverServices,
                AndroidApiOperation.DiscoverServices,
            )
        } catch (e: BleException) {
            e.failure
        } catch (_: SecurityException) {
            GattFailureFactory.permissionDenied(
                session,
                FailurePhase.DiscoverServices,
                AndroidApiOperation.DiscoverServices,
            )
        }
        if (failure != null) {
            effects.dispatch(
                session,
                ConnectionEvent.DiscoveryStartFailed(failure, readinessTimeouts.disconnectCleanupMillis),
            )
        }
    }

    /**
     * MTU 협상 요청. LinkSetup.onServicesDiscovered 가 `ServicesDiscovered` 발행 이후 부른다.
     * `requestMtu()` 가 `false` 를 돌려주면(호출 자체 실패) [handleMtuChanged] 콜백이 오지
     * 않으므로, requestMtu 실패도 handleMtuChanged 의 협상 실패와 같은 23/20 폴백 경로로
     * 처리해 Discovering 영구 정지를 막는다(review #4, plan §접근 ④).
     */
    fun requestMtu(deviceId: PeripheralId, mtu: Int) {
        val session = registry.get(deviceId) ?: return
        val failure = try {
            if (driver.requestMtu(deviceId, session.gatt, mtu)) null else GattFailureFactory.startRejected(
                session,
                FailurePhase.Configure,
                AndroidApiOperation.RequestMtu,
            )
        } catch (e: BleException) {
            e.failure
        } catch (_: SecurityException) {
            GattFailureFactory.permissionDenied(
                session,
                FailurePhase.Configure,
                AndroidApiOperation.RequestMtu,
            )
        }
        if (failure != null) {
            effects.dispatch(
                session,
                ConnectionEvent.MtuRequestFailed(failure, readinessTimeouts.disconnectCleanupMillis),
            )
        }
    }

    private fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }
}
