package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.port.GattDriver
import kotlinx.coroutines.plus
import kr.co.nucode.ble.internal.session.SessionOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.LinkSetup
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

/**
 * [GattConnectionLifecycle] 의 연결 시작 칸 — 중복 connect 허가 판정, connect/startConnect, 어댑터 복귀 재연결을 소유한다.
 * 실패 종결은 [LinkTeardown] 에 맡긴다.
 */
internal class ConnectStarter(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val rediscovery: PeripheralRediscovery?,
    private val linkCacheWarm: ((PeripheralId) -> Boolean)?,
    private val operations: GattOperationHooks,
    private val authority: StageAuthority,
    private val publishState: (Session) -> Unit,
    private val emitDiagnostic: (Severity, BleFailure) -> Unit,
    private val teardown: LinkTeardown,
) {

    /**
     * 이미 세션이 있을 때의 중복 connect 판정(순수 결정). 실패 구성·게시·throw 는 호출자(BleRuntime)가 한다.
     * - Disconnecting: 정리 중에는 새 connect 를 시작할 수 없다.
     * - Connecting/DiscoveringServices/Configuring/Ready: 같은 effective options 면 no-op, 아니면 충돌.
     * - Disconnected 이고 플랫폼 재연결 대기(autoConnect handle 열림) 중이면 같은 handle 위에 두 번째 연결을
     *   시작하지 않고 기존 대기를 유지한다(docs/v1 connection-lifecycle "duplicate connect").
     */
    fun admitConnect(session: Session, options: ConnectOptions): ConnectAdmission {
        return when (session.stage) {
            ConnectionStage.Disconnecting -> ConnectAdmission.Conflict(
                code = "CONNECT_DURING_DISCONNECT",
                // 정리는 곧 끝난다(Disconnected) — 그 뒤 다시 걸면 된다.
                retryability = Retryability.TransientMayHelp,
            )

            ConnectionStage.Connecting,
            ConnectionStage.DiscoveringServices,
            ConnectionStage.Configuring,
            ConnectionStage.Ready,
            -> if (session.hasSameEffectiveConnectOptions(options)) {
                ConnectAdmission.Joined
            } else {
                ConnectAdmission.Conflict(code = "CONNECTION_OPTIONS_CONFLICT", retryability = Retryability.Never)
            }

            ConnectionStage.Disconnected -> when {
                !session.awaitingPlatformRelink -> ConnectAdmission.Proceed
                session.hasSameEffectiveConnectOptions(options) -> ConnectAdmission.Joined
                else -> ConnectAdmission.Conflict(code = "CONNECTION_OPTIONS_CONFLICT", retryability = Retryability.Never)
            }
        }
    }

    // ── 진입 함수(실제 플랫폼 호출) ──────────────────────────────────────────

    /**
     * timeout은 link stage에서만 실제 lifecycle 동작을 바꾼다. discovery/configuration/Ready
     * 단계에서는 이미 소진된 timeout을 options mismatch로 취급하지 않는다. generation 0의
     * legacy seam에는 deadline snapshot이 없으므로 기존 autoConnect/MTU 의미만 보존한다.
     */
    fun Session.hasSameEffectiveConnectOptions(requested: ConnectOptions): Boolean {
        if (autoConnect != requested.autoConnect || requestedMtu != requested.requestedMtu) return false
        if (
            connectionCoordinator.stage != ConnectionStage.Connecting
        ) return true

        // autoConnect 첫 연결은 deadline 이 없다(null) — 같은 규칙(linkDeadlineMillis)으로 비교한다.
        return connectionCoordinator.activeDeadline?.timeoutMillis ==
            requested.linkDeadlineMillis(readinessTimeouts.linkMillis)
    }

    /**
     * 연결 시작. production [GattDriver]가 adapter를 매 호출 시 조회한다 — BT off/on 사이에도
     * connect() 시점의 최신 어댑터로 `getRemoteDevice` 한다. 어댑터가 아직 없으면(권한
     * 게이트를 통과했는데도 null 인 이례적 경우 — 게이트 통과 직후 어댑터가 사라진
     * 레이스)는 typed adapter failure로 던진다(D6, review M-7) — 예전에는 조용히
     * 반환해 호출자(`BleRuntime.connect`)가 `Connecting` 을 emit 한 채로 세션이 영구 잔류했다.
     * 조용한 실패 경로 자체를 없앤다 — 실패는 전부 두 갈래 규약대로 throw 한다. 권한/전원
     * 판정 자체는 여전히 BleRuntime.ensureAdapterReady 의 몫이라 여기서 다시 판정하지 않고,
     * 상태값은 [AdapterState.PoweredOff] 를 쓴다 — 방금 게이트를 통과했으므로 어댑터가
     * 사라졌다면 꺼졌다고 보는 쪽이 실체에 가장 가깝다.
     *
     * `getRemoteDevice` 에 잘못된 형식의 MAC 을 주면 `IllegalArgumentException` 이 그
     * 자리에서 터진다(플랫폼 문서) — 그대로 흘리면 호출자가 무슨 실패인지 알 수 없으므로
     * `UNKNOWN_DEVICE` typed failure로 변환해 던진다(즉시 알 수 있는 실패).
     *
     * cold-cache 재발견: [linkCacheWarm] 이 이 주소를 모른다고 하면 `driver.connect()` 앞에
     * [rediscovery] 로 주소 필터 스캔 1회를 선행해 BT 재시작으로 비워진 스택 캐시를 채운다.
     * 못 찾으면(NotFound) autoConnect 가 아닐 때만 PERIPHERAL_NOT_FOUND 로 던진다 — autoConnect 는
     * Diagnostic 을 남기고 OS 대기로 들어간다. 재발견 중 세션이 끝나면 고정된 종결 실패로 던진다.
     */
    suspend fun connect(deviceId: PeripheralId, options: ConnectOptions) {
        try {
            startConnect(deviceId, options)
        } catch (e: BleException) {
            // 유령 Connecting 방지(D6/M-7): 시작 자체가 실패했으면 상태·스트림 둘 다 종결시킨다.
            // throw 는 호출자에게 가지만(두 갈래 규약), events 만 보는 다른 소비자도
            // Connecting 이 끝났음을 알아야 한다.
            //
            // connect 시작 실패는 OS 대기 대상이 아니다(D6) — 게시 전에 suppressReconnect 를 세워
            // handle 을 남기지 않는다. 다음 connect() 가 startConnect 에서 모드로 다시 정한다.
            val session = registry.get(deviceId) ?: throw e
            session.suppressReconnect = true
            val failure = e.failure
            // 보통은 startConnect 가 throw 전에 이 generation 을 이미 종결(원인 고정)했으므로 게시만
            // 남는다. 아직 열려 있으면 종결 API 가 고정·게시까지 한다.
            val terminated = teardown.terminate(
                session,
                session.connectionCoordinator.generation,
                TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
            )
            if (!terminated) publishState(session)
            throw BleException(failure)
        }
    }

    suspend fun startConnect(deviceId: PeripheralId, options: ConnectOptions) {
        // 조용한 반환 금지(review L — KDoc "조용한 실패 경로를 없앤다" 와 코드 일치).
        // BleRuntime.connect 가 getOrCreate 직후 부르므로 정상 경로에선 도달 불가지만,
        // 도달하면 정확히 M-7 유령 Connecting 이다.
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        val connectionGeneration = session.connectionCoordinator.beginConnection(
            authority,
            scope = scope + SessionOwner(deviceId),
            linkTimeoutMillis = options.linkDeadlineMillis(readinessTimeouts.linkMillis),
            onTimeout = { teardown.onLifecycleDeadline(deviceId, it) },
        )
        publishState(session)

        // 이전 connection generation의 waiter를 먼저 끝낸 뒤에만 새 generation을 연다.
        // scheduler가 정상 상태였더라도 explicit connect는 별도 callback 귀속 세대다.
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        // invalidateAll이 항상 poison 상태로 남기므로(이미 poison된 경우 그대로, 아닌
        // 경우 방금 poison), 아래 hook은 사실상 무조건 새 generation을 연다 — 이전의
        // 무조건적인 generation 리셋 호출과 동치다.
        operations.beginGenerationIfPoisoned(session)

        // 기존 핸들·타임아웃 정리(review H-3): stale gatt 를 close 없이 덮어쓰면 프로세스당
        // GATT 클라이언트 상한(~32)을 소모해 결국 connectGatt 자체가 실패하기 시작한다.
        // BleRuntime.connect 의 상태 가드가 Connecting/Connected 를 이미 걸렀으므로,
        // 여기 도달한 기존 핸들은 전부 죽은(Disconnected) 것이다.
        session.gatt?.let { driver.close(deviceId, it) }
        session.gatt = null
        session.platformHandleOpen = false
        // 이전 handle 을 닫았으니 플랫폼 재연결 대기도 끝난다(이미 Connecting 이라 공개 상태는 그대로다).
        session.endPlatformWait()
        // D2/D6 — 이 설정은 connectGatt 직전이어야 한다. 이전 시도가 사용자 disconnect()
        // ·타임아웃 포기·connect 시작 실패(connect 의 catch) 중 무엇으로든 suppressReconnect 를 true 로
        // 남겼더라도, 지금 시작하는 이 시도는 새 시도라 그 상태를 물려받지 않는다 — 모드로 다시 정한다.
        // autoConnect 가 아니면 이 handle 은 처음부터 OS 대기용이 아니다(링크가 끊기면 닫고 Disconnected).
        // connectGatt 호출 뒤로 미루면 그 사이 콜백이 (이례적으로) 동기 재진입해 stale 한 값을 보는 창이 생긴다.
        session.suppressReconnect = !options.autoConnect
        session.autoConnect = options.autoConnect
        // 새 시도는 어댑터 복귀 재연결 대상이 아니다 — 이 시도 자체가 그 재연결이거나 사용자가 새로 건 것이다.
        session.relinkAfterAdapter = false
        // plan blecore-app-tunables 결정① — 이번 연결 시도의 옵션값을 Session 에 보존한다.
        // LinkSetup.onServicesDiscovered 가 이 값을 그대로 읽어
        // requestMtu 를 부른다(Session.requestedMtu KDoc, 구 상수 REQUESTED_MTU 대체).
        session.requestedMtu = options.requestedMtu

        // cold-cache 재발견: BT 재시작 뒤 스택이 주소 타입을 잊으면 getRemoteDevice 가 PUBLIC 으로 시도해
        // random static peripheral 에 닿지 않는다. 이 클라이언트가 마지막 Ready 이후 본 적 없는 주소면
        // 주소 필터 스캔 1회로 캐시를 채운 뒤 connectGatt 한다. Connecting 진입·generation·link deadline
        // 이 이미 시작된 뒤라 중복 connect 는 BleRuntime 의 기존 가드가 막고, 앱은 Connecting 을 본다.
        if (rediscovery != null && linkCacheWarm?.invoke(deviceId) == false) {
            val outcome = try {
                rediscovery.rediscover(deviceId, readinessTimeouts.rediscoveryMillis)
            } catch (e: CancellationException) {
                // 호출자 취소(review HIGH 1) — beginConnection 이 연 generation 을 여기서 닫지 않으면
                // gatt 없는 Connecting 이 link deadline 까지 남는다. NotFound 와 같은 정리 후
                // rethrow 한다(취소는 삼키지 않는다).
                if (session.connectionCoordinator.accepts(connectionGeneration, ConnectionStage.Connecting)) {
                    session.suppressReconnect = true
                    teardown.completeWithoutPublish(
                        session,
                        connectionGeneration,
                        TerminalCause(TerminalCleanupReason.UserRequested, null),
                    )
                    operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
                    publishState(session)
                }
                throw e
            }
            // 스캔 동안 disconnect()/sweep/close() 가 generation 을 끝냈으면 여기서 멈춘다 — 그쪽이 이미 정리했다.
            if (!session.connectionCoordinator.accepts(connectionGeneration, ConnectionStage.Connecting)) {
                // review MEDIUM 1 — 재발견 중 disconnect() 는 gatt 가 아직 없어 beginCleanup 의
                // driver.disconnect() 가 no-op 이라 플랫폼 콜백이 오지 않는다. Disconnecting 에 남아
                // disconnectCleanupMillis 까지 기다리지 않고, 재발견 resume 이 온 이 시점에 직접 끝낸다.
                val coordinator = session.connectionCoordinator
                if (coordinator.generation == connectionGeneration && coordinator.stage == ConnectionStage.Disconnecting) {
                    teardown.completeLifecycleCleanup(
                        session = session,
                        generation = connectionGeneration,
                        fallback = TerminalCause(TerminalCleanupReason.UserRequested, null),
                    )
                }
                // review LOW-1 — 연결이 시작되지 않았으니 정상 반환하지 않는다. 이 generation 에 고정된 종결 실패로
                // 던진다. 사용자 disconnect() 로 끝났으면(실패 없음) 이 connect 가 무효화된 것이라 OPERATION_INVALIDATED.
                // close 로 끝났으면 CLIENT_CLOSED 는 BleRuntime.connect 가 반환 직후 closed 검사로 던진다.
                val cause = coordinator.terminalCause.takeIf { coordinator.generation == connectionGeneration }
                cause?.failure?.let { throw BleException(it) }
                if (cause?.reason == TerminalCleanupReason.ClientClosed) return
                throw BleException(GattFailureFactory.operationInvalidated(session))
            }
            when (outcome) {
                RediscoveryOutcome.Found -> Unit
                is RediscoveryOutcome.Unavailable -> emitDiagnostic(Severity.Warning, outcome.failure) // 폴백: 직접 연결
                // review HIGH-1 — autoConnect 는 "무기한 대기" 계약이다. 스캔에 안 보였어도 OS 대기(connectGatt
                // autoConnect = true, deadline 없음)로 들어가고, 못 찾았다는 사실은 Diagnostic 으로만 알린다.
                RediscoveryOutcome.NotFound -> if (options.autoConnect) {
                    emitDiagnostic(Severity.Warning, GattFailureFactory.peripheralNotFound(session))
                } else {
                    val failure = GattFailureFactory.peripheralNotFound(session)
                    session.suppressReconnect = true
                    teardown.completeWithoutPublish(
                        session,
                        connectionGeneration,
                        TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
                    )
                    operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
                    throw BleException(failure)
                }
            }
        }

        // D7(M-8+#16) 벨트 — BleRuntime.ensureAdapterReady 의 검사와 이 호출 사이의 레이스
        // (예: 설정 앱에서 권한을 방금 회수)나 검사가 놓친 케이스를 막는다. connect() 는
        // 두 갈래 규약상 즉시 실패 = BleException throw 다. BleRuntime가 이를 받아
        // Connecting 을 Disconnected 로 정리하고 재던진다.
        val gatt = try {
            driver.connect(deviceId, options.autoConnect, connectionGeneration)
        } catch (e: BleException) {
            session.suppressReconnect = true
            teardown.completeWithoutPublish(
                session,
                connectionGeneration,
                TerminalCause(TerminalCleanupReason.LinkStartRejected, e.failure),
            )
            operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
            throw e
        } catch (_: SecurityException) {
            val failure = GattFailureFactory.permissionDenied(
                session,
                FailurePhase.Connect,
                AndroidApiOperation.ConnectGatt,
            )
            session.suppressReconnect = true
            teardown.completeWithoutPublish(
                session,
                connectionGeneration,
                TerminalCause(TerminalCleanupReason.PermissionDenied, failure),
            )
            operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
            throw BleException(failure)
        } catch (_: Exception) {
            val failure = GattFailureFactory.startRejected(
                session,
                FailurePhase.Connect,
                AndroidApiOperation.ConnectGatt,
            )
            session.suppressReconnect = true
            teardown.completeWithoutPublish(
                session,
                connectionGeneration,
                TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
            )
            operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
            throw BleException(failure)
        }
        session.gatt = gatt
        session.platformHandleOpen = true

        // #15 — 연결 타임아웃. STATE_CONNECTED 가 오면(위 handleConnectionStateChange) 취소된다.
    }

    /**
     * 어댑터 복귀 — 어댑터 꺼짐 스윕이 끝낸 autoConnect 세션([Session.relinkAfterAdapter], 사용자 해제·
     * close 가 아직 내리지 않음)에 connectGatt(autoConnect = true) 를 한 번 건다(새 handle). 예약·재시도는
     * 없다 — 시작이 실패하면 connect 의 즉시 실패 경로가 Disconnected(Failed) 로 끝내고, 그 실패를 Diagnostic 으로 알린다.
     * 전이 관측 경로가 명령 안(ensureAdapterReady)일 수도 있으므로 코어 디스패처에 한 홉 미룬 뒤 다시 판정한다
     * — 세션 조건과 함께 어댑터가 아직 Ready 인지([adapterReady])도 본다.
     */
    fun resumeAfterAdapter(deviceId: PeripheralId, adapterReady: () -> Boolean) {
        if (registry.get(deviceId)?.relinkAfterAdapter != true) return
        scope.launch(SessionOwner(deviceId)) {
            val session = registry.get(deviceId) ?: return@launch
            if (!session.relinkAfterAdapter || session.stage != ConnectionStage.Disconnected) return@launch
            // 홉 사이에 어댑터가 다시 꺼졌으면 걸지 않는다 — 다음 Ready 복귀가 다시 판정한다.
            if (!adapterReady()) return@launch
            try {
                connect(deviceId, ConnectOptions(autoConnect = true, requestedMtu = session.requestedMtu))
            } catch (e: BleException) {
                // 시작 실패는 connect 가 이미 종결·게시했다(재연결 대상 아님). 던질 호출자가 없으니
                // Diagnostic 으로 알린다(review MED-1 — 조용히 삼키지 않는다).
                emitDiagnostic(Severity.Warning, e.failure)
            }
        }
    }
}
