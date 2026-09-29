package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.connection.LinkSteps
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.ConnectOptions
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.StageAuthority
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

/** 이 파일만 연결 단계 전이 토큰을 만든다 — lifecycle 칸(ConnectStarter·LinkCallbacks·LinkTeardown)에는 생성자로만 넘긴다. */
private object LifecycleStageAuthority : StageAuthority()

/** operation → lifecycle. */
internal interface GattLifecycleHooks {
    fun beginCleanup(session: Session, reason: TerminalCleanupReason, cause: BleFailure?, suppressReconnect: Boolean): Boolean
    fun terminateForOperationInvalidation(session: Session, cause: BleFailure, suppressReconnect: Boolean)
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
 * [ConnectStarter], 플랫폼 콜백은 [LinkCallbacks], 정리·종료는 [LinkTeardown] 에 위임한다.
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

    /** 복원이 끝난 [generation] 을 Ready 로 — generation·stage 를 다시 확인하고 전이한 뒤 게시한다. */
    override fun completeReady(session: Session, generation: Long): Boolean {
        val accepted = session.connectionCoordinator.transition(
            LifecycleStageAuthority,
            callbackGeneration = generation,
            expectedStage = ConnectionStage.Configuring,
            nextStage = ConnectionStage.Ready,
            scope = scope,
        )
        if (!accepted) return false
        publishState(session)
        return true
    }

    // ── 연결 시작 · 콜백 · 정리 칸 — 본문은 각 클래스, 여기서는 위임만 ─────────────────

    private val teardown = LinkTeardown(
        registry, scope, driver, readinessTimeouts, operations, LifecycleStageAuthority, ::publishState, ::emitDiagnostic,
    )
    private val callbacks = LinkCallbacks(
        registry, emit, scope, readinessTimeouts, operations, linkSetup, LifecycleStageAuthority,
        ::publishState, ::emitDiagnostic, teardown,
    )
    private val starter = ConnectStarter(
        registry, scope, driver, readinessTimeouts, rediscovery, linkCacheWarm, operations, LifecycleStageAuthority,
        ::publishState, ::emitDiagnostic, teardown,
    )

    override fun beginCleanup(
        session: Session,
        reason: TerminalCleanupReason,
        cause: BleFailure?,
        suppressReconnect: Boolean,
    ): Boolean = teardown.beginCleanup(session, reason, cause, suppressReconnect)

    override fun terminateForOperationInvalidation(session: Session, cause: BleFailure, suppressReconnect: Boolean) =
        teardown.terminateForOperationInvalidation(session, cause, suppressReconnect)

    fun terminate(session: Session, generation: Long, cause: TerminalCause): Boolean = teardown.terminate(session, generation, cause)

    fun onAdapterLost(session: Session, cause: AdapterState) = teardown.onAdapterLost(session, cause)

    fun close(session: Session) = teardown.close(session)

    fun failInternal(session: Session, cause: BleFailure) = teardown.failInternal(session, cause)

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
     * 플랫폼 재연결 대기 중이면 [endPlatformWait] 가 대기를 끝내고 handle 을 닫는다.
     */
    fun disconnect(deviceId: PeripheralId) {
        val session = registry.get(deviceId) ?: return
        session.relinkAfterAdapter = false
        // 플랫폼 재연결 대기 중(이미 Disconnected)이면 사용자 해제가 대기를 끝낸다 — Disconnected(Requested).
        val coordinator = session.connectionCoordinator
        if (teardown.endPlatformWait(session, coordinator.generation, TerminalCause(TerminalCleanupReason.UserRequested, null))) {
            return
        }
        if (coordinator.stage == ConnectionStage.Disconnected) {
            // 이미 끝났고 대기도 없다 — 대기가 아닌 종결 generation 의 handle 은 lifecycle 이 이미 닫았다.
            // 재구독 job 은 Disconnected 이벤트에서 이미 끊겼다.
            session.suppressReconnect = true
            return
        }
        teardown.beginCleanup(
            session = session,
            reason = TerminalCleanupReason.UserRequested,
            cause = null,
            suppressReconnect = true,
        )
        // review LOW-4 — connect 가 cold-cache 재발견을 기다리는 중이면 그 재발견을 지금 끝낸다. gatt 가 없어
        // 플랫폼 콜백이 오지 않으니, 끝내지 않으면 rediscoveryMillis 까지 Disconnecting·스캔이 남는다.
        // 재발견 resume 이 Disconnecting 을 보고 정리를 마친다(connect 의 재발견 뒤 판정).
        if (session.gatt == null) rediscovery?.endRediscovery(deviceId, RediscoveryOutcome.NotFound)
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
            emitDiagnostic(Severity.Error, failure)
            teardown.beginCleanup(
                session = session,
                reason = if (failure.code.value == "PERMISSION_DENIED") {
                    TerminalCleanupReason.PermissionDenied
                } else {
                    TerminalCleanupReason.ServiceDiscoveryStartRejected
                },
                cause = failure,
                suppressReconnect = true,
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
            emitDiagnostic(Severity.Warning, failure)
            if (failure.code.value == "PERMISSION_DENIED") {
                teardown.beginCleanup(
                    session = session,
                    reason = TerminalCleanupReason.PermissionDenied,
                    cause = failure,
                    suppressReconnect = true,
                )
                return
            }
            val fallbackChunk = chunkSizeForMtu(DEFAULT_MTU)
            session.mtu = DEFAULT_MTU
            session.chunkSize = fallbackChunk
            linkSetup()?.onMtuSettled(session, session.connectionCoordinator.generation)
        }
    }


    private fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }


    internal companion object {
        /**
         * MTU 협상/요청 실패 시 폴백 값 — BLE 표준 기본 ATT_MTU.
         * [DEFAULT_CHUNK_SIZE] 가 근거로 삼는 것과 같은 값(23)이다.
         */
        const val DEFAULT_MTU = 23
    }
}

/**
 * 이번 연결의 link deadline. 명시 [ConnectOptions.timeoutMillis] 가 있으면 그 값, 없으면 autoConnect 는
 * deadline 없음(null — OS 무기한 대기가 autoConnect 의 의미, 취소는 disconnect()), 아니면 [defaultLinkMillis].
 * 중복 connect 판정([GattConnectionLifecycle.admitConnect])도 이 값으로 비교한다.
 */
internal fun ConnectOptions.linkDeadlineMillis(defaultLinkMillis: Long): Long? =
    timeoutMillis ?: if (autoConnect) null else defaultLinkMillis

/** 이미 세션이 있을 때의 중복 connect 판정 결과([GattConnectionLifecycle.admitConnect]). */
internal sealed interface ConnectAdmission {
    /** 새 연결을 시작한다. */
    data object Proceed : ConnectAdmission

    /** 같은 effective options 로 이미 진행·대기 중 — 기존 연결·대기에 합류한다(no-op). */
    data object Joined : ConnectAdmission

    /** 충돌 — 호출자가 [code]·[retryability] 로 실패를 만들어 게시하고 던진다. */
    data class Conflict(val code: String, val retryability: Retryability) : ConnectAdmission
}
