package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.core.connection.ConnectAdmission
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent
import kr.co.nucode.ble.internal.core.connection.ConnectionStageDeadline
import kr.co.nucode.ble.internal.core.connection.LinkStartFailureKind
import kr.co.nucode.ble.internal.core.connection.TerminalCause
import kr.co.nucode.ble.internal.core.connection.TerminalCleanupReason
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.core.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionOwner
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [GattConnectionLifecycle] 의 정리·종료 칸(Runtime) — 기한·해제·close·어댑터 이탈·작업 무효화 사건을 Core 에 넣고,
 * Core 가 돌려준 할 일을 **목록 순서대로** driver·operation hook·진단·게시로 실행한다. 판단(어느 원인을 고정하고
 * 어느 단계로 가는가)은 Core([kr.co.nucode.ble.internal.core.connection.ConnectionCleanup])에 있고 여기엔 없다.
 * 상태는 갖지 않는다(세션·coordinator 가 소유). 실행 중 재진입한 사건은 Core 가 먼저 확정한 상태를 본다.
 */
internal class LinkEffects(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val rediscovery: PeripheralRediscovery?,
    private val operations: GattOperationHooks,
    private val publishState: (Session) -> Unit,
    private val emitDiagnostic: (Severity, BleFailure, Map<String, DiagnosticValue>) -> Unit,
    /** 붙은 뒤 절차([LinkSetup]). 호출 시점에 읽는다 — 없으면 절차를 진행하지 않는다. */
    private val linkSetup: () -> LinkSetup? = { null },
) {
    private val disconnectCleanupMillis: Long
        get() = readinessTimeouts.disconnectCleanupMillis

    /** Core 사건을 넣고 할 일을 실행한다. 반환은 사건이 상태에 적용됐는지다. */
    internal fun dispatch(session: Session, event: ConnectionEvent): Boolean =
        session.connectionCoordinator.dispatch(
            event,
            scope = scope + SessionOwner(session.deviceId),
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
            run = { execute(session, it) },
        )

    /**
     * 연결 시작 사건을 넣고, 호출 진행을 정하는 할 일(재발견·링크 시작·호출 실패·정상 종료)을 돌려준다.
     * 나머지 할 일은 [dispatch] 와 같이 목록 순서대로 실행한다. 호출 진행 할 일은 목록 끝에 오므로 그 앞의 게시·정리가
     * 끝난 뒤에 호출자가 진행한다.
     */
    internal fun dispatchCall(session: Session, event: ConnectionEvent): ConnectionEffect? =
        collect(session, event) { it.isCallProgress() }

    /** 이미 세션이 있는 connect 요청의 중복 판정. 상태는 바뀌지 않는다. */
    internal fun admitConnect(session: Session, event: ConnectionEvent.ConnectRequested): ConnectAdmission =
        (collect(session, event) { it is ConnectionEffect.ConnectAdmitted } as ConnectionEffect.ConnectAdmitted).admission

    private fun collect(session: Session, event: ConnectionEvent, wanted: (ConnectionEffect) -> Boolean): ConnectionEffect? {
        val seen = mutableListOf<ConnectionEffect>()
        session.connectionCoordinator.dispatch(
            event,
            scope = scope + SessionOwner(session.deviceId),
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
            run = { if (wanted(it)) seen += it else execute(session, it) },
        )
        return seen.lastOrNull()
    }

    /** logger 전용 값 — 공개 이벤트에는 실리지 않는다. 요청 뒤 경과는 lab 이 '연결 요청 → 133' 지연을 재는 데 쓴다. */
    private fun ConnectionEffect.Diagnostic.loggerValues(): Map<String, DiagnosticValue> =
        linkRequestElapsedMillis?.let { mapOf(LINK_REQUEST_ELAPSED_MS to DiagnosticValue.Number(it)) } ?: emptyMap()

    private fun ConnectionEffect.isCallProgress(): Boolean =
        this is ConnectionEffect.Rediscover || this is ConnectionEffect.ConnectLink ||
            this is ConnectionEffect.FailConnectCall || this is ConnectionEffect.EndConnectCall

    private fun execute(session: Session, effect: ConnectionEffect) {
        when (effect) {
            is ConnectionEffect.Diagnostic -> emitDiagnostic(effect.severity, effect.failure, effect.loggerValues())
            is ConnectionEffect.Announce -> publishState(session)
            is ConnectionEffect.InvalidateOperations ->
                operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
            is ConnectionEffect.DisconnectLink -> {
                val onStartFailure = effect.onStartFailure
                if (onStartFailure == null) {
                    driver.disconnect(session.deviceId, session.gatt)
                } else {
                    try {
                        driver.disconnect(session.deviceId, session.gatt)
                    } catch (_: Exception) {
                        dispatch(session, onStartFailure)
                    }
                }
            }
            is ConnectionEffect.ReleaseHandle -> {
                driver.close(session.deviceId, session.gatt)
                session.gatt = null
                dispatch(session, ConnectionEvent.HandleReleased)
            }
            is ConnectionEffect.ReleaseStaleHandle -> {
                session.gatt?.let { driver.close(session.deviceId, it) }
                session.gatt = null
            }
            is ConnectionEffect.RetryLink -> retryLink(session, effect.generation)
            // 호출 진행 할 일은 호출자가 진행한다 — 호출자가 모으지 않는 경로(시작 실패 등)에서는 이미 알고 있다.
            is ConnectionEffect.Rediscover,
            is ConnectionEffect.ConnectLink,
            is ConnectionEffect.FailConnectCall,
            is ConnectionEffect.EndConnectCall,
            is ConnectionEffect.ConnectAdmitted,
            -> Unit
            is ConnectionEffect.ClearLinkParameters -> {
                session.mtu = null
                session.chunkSize = null
            }
            is ConnectionEffect.EndRediscovery -> rediscovery?.endRediscovery(session.deviceId, RediscoveryOutcome.NotFound)
            is ConnectionEffect.BeginOperationGeneration -> operations.beginGenerationIfPoisoned(session)
            is ConnectionEffect.LinkUp -> linkSetup()?.onLinkUp(session, effect.generation)
            is ConnectionEffect.ApplyMtu -> {
                session.mtu = effect.mtu
                session.chunkSize = chunkSizeForMtu(effect.mtu)
            }
            is ConnectionEffect.RestoreSubscriptions -> linkSetup()?.onMtuSettled(session, effect.generation)
            else -> error("실행기가 다루지 않는 할 일: $effect")
        }
    }

    /**
     * driver.connect 로 링크를 연다. 성공하면 handle 을 세션에 두고 [ConnectionEvent.LinkStarted] 를 넣은 뒤 null 을,
     * 시작이 던지면 그 실패를 돌려준다(종결은 호출자가 사건으로 정한다).
     */
    fun openLink(session: Session, autoConnect: Boolean, generation: Long): LinkStartFailure? {
        val gatt = try {
            driver.connect(session.deviceId, autoConnect, generation)
        } catch (e: BleException) {
            return LinkStartFailure(e.failure, LinkStartFailureKind.Rejected)
        } catch (_: SecurityException) {
            val failure = GattFailureFactory.permissionDenied(session, FailurePhase.Connect, AndroidApiOperation.ConnectGatt)
            return LinkStartFailure(failure, LinkStartFailureKind.PermissionDenied)
        } catch (_: Exception) {
            val failure = GattFailureFactory.startRejected(session, FailurePhase.Connect, AndroidApiOperation.ConnectGatt)
            return LinkStartFailure(failure, LinkStartFailureKind.Rejected)
        }
        session.gatt = gatt
        dispatch(session, ConnectionEvent.LinkStarted(session.connectionCoordinator.nowMillis))
        return null
    }

    /** 빠른 133 재시도 — 같은 generation 에서 직접 연결을 다시 건다. 시작 실패는 Core 가 고정·게시한다. */
    private fun retryLink(session: Session, generation: Long) {
        val failed = openLink(session, autoConnect = false, generation) ?: return
        dispatch(session, ConnectionEvent.RetryStartFailed(generation, failed.failure, failed.kind))
    }

    /** 서비스 목록 게시가 끝났다 — 붙은 뒤 절차(MTU 요청)를 진행한다. */
    fun servicesPublished(session: Session, generation: Long) {
        linkSetup()?.onServicesDiscovered(session, generation)
    }

    /** 단계 기한 만료 — 만료된 기한이 아직 현재 단계의 것일 때만 Core 가 정리한다. */
    fun onLifecycleDeadline(deviceId: PeripheralId, deadline: ConnectionStageDeadline) {
        val session = registry.get(deviceId) ?: return
        dispatch(session, ConnectionEvent.LifecycleDeadline(deadline, disconnectCleanupMillis))
    }

    /** 정리를 시작한다. 이미 정리 중이거나 끝난 generation 이면 false. */
    fun beginCleanup(
        session: Session,
        reason: TerminalCleanupReason,
        cause: BleFailure?,
        suppressReconnect: Boolean,
    ): Boolean = dispatch(
        session,
        ConnectionEvent.CleanupRequested(TerminalCause(reason, cause), suppressReconnect, disconnectCleanupMillis),
    )

    /** 즉시 종결(시작 거부·재발견 실패 등). 이미 고정된 원인이 있으면 그 원인이 이긴다. */
    fun terminate(session: Session, generation: Long, cause: TerminalCause): Boolean =
        dispatch(session, ConnectionEvent.Terminate(generation, cause))

    /** 게시 없이 generation 만 종결한다(connect() 의 즉시 실패 — 게시는 throw 를 받은 호출자 몫). */
    fun completeWithoutPublish(session: Session, generation: Long, fallback: TerminalCause): Boolean =
        dispatch(session, ConnectionEvent.CompletedWithoutPublish(generation, fallback))

    /** 콜백 또는 정리 기한이 현재 generation 을 정확히 한 번 종결로 만든다. */
    fun completeLifecycleCleanup(
        session: Session,
        generation: Long,
        fallback: TerminalCause,
        status: Int = 0,
    ): Boolean = dispatch(session, ConnectionEvent.CleanupCompleted(generation, fallback, status))

    /** 사용자 해제. */
    fun disconnect(session: Session) {
        dispatch(session, ConnectionEvent.DisconnectRequested(hasHandle = session.gatt != null, disconnectCleanupMillis))
    }

    /** 어댑터 이탈 스윕의 세션 몫. */
    fun onAdapterLost(session: Session, cause: AdapterState) {
        dispatch(session, ConnectionEvent.AdapterLost(cause))
    }

    /** client close. */
    fun close(session: Session) {
        dispatch(session, ConnectionEvent.CloseRequested)
    }

    /** GATT 작업 쪽이 세션을 끝내야 하는 실패를 확정했다(작업 기한 초과 등). */
    fun operationInvalidated(session: Session, cause: BleFailure, suppressReconnect: Boolean) {
        dispatch(session, ConnectionEvent.OperationInvalidated(cause, suppressReconnect, Severity.Warning))
    }

    /** GATT 작업 시작에서 권한 상실이 확인됐다. */
    fun operationPermissionLost(session: Session, cause: BleFailure) {
        dispatch(session, ConnectionEvent.OperationPermissionLost(cause, disconnectCleanupMillis))
    }

    /** 세션 소유 코루틴에서 잡히지 않은 예외가 났다. */
    fun failInternal(session: Session, cause: BleFailure) {
        dispatch(session, ConnectionEvent.InternalFailure(cause))
    }

    internal companion object {
        /** 링크 실패 진단의 logger 값 이름 — 이번 시도의 연결 요청(connectGatt) 뒤 경과 ms. */
        const val LINK_REQUEST_ELAPSED_MS = "link_request_elapsed_ms"
    }
}

/** driver.connect 시작 실패 — 실패 값과 종결 원인 분류의 종류. */
internal data class LinkStartFailure(val failure: BleFailure, val kind: LinkStartFailureKind)
