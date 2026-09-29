package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.core.connection.ConnectAdmission
import kr.co.nucode.ble.internal.core.connection.ConnectionEffect
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent
import kr.co.nucode.ble.internal.core.connection.LinkStartFailureKind
import kr.co.nucode.ble.internal.core.connection.RediscoveryFact
import kr.co.nucode.ble.internal.model.ConnectOptions
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionOwner
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [GattConnectionLifecycle] 의 연결 시작 칸(Runtime) — connect 의 suspend 흐름(재발견 대기·driver.connect)과
 * 예외 변환만 갖는다. 중복 connect 판정·시도 시작·재발견 결과·시작 실패 정책은 각 await 뒤 사실을 Core 사건으로
 * 넣어 돌려받은 할 일([ConnectionEffect.ConnectLink]·[ConnectionEffect.FailConnectCall] 등)대로 진행한다.
 * 실행은 [LinkEffects] 가 한다.
 */
internal class ConnectStarter(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val rediscovery: PeripheralRediscovery?,
    private val linkCacheWarm: ((PeripheralId) -> Boolean)?,
    private val emitDiagnostic: (Severity, BleFailure) -> Unit,
    private val effects: LinkEffects,
) {

    /** 이미 세션이 있을 때의 중복 connect 판정. 실패 구성·게시·throw 는 호출자(BleRuntime)가 한다. */
    fun admitConnect(session: Session, options: ConnectOptions): ConnectAdmission =
        effects.admitConnect(session, ConnectionEvent.ConnectRequested(options, readinessTimeouts.linkMillis))

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
        guarded(deviceId) {
            // 조용한 반환 금지(review L — KDoc "조용한 실패 경로를 없앤다" 와 코드 일치).
            // BleRuntime.connect 가 getOrCreate 직후 부르므로 정상 경로에선 도달 불가지만,
            // 도달하면 정확히 M-7 유령 Connecting 이다.
            val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
            val scanner = rediscoveryFor(deviceId)
            val progress = effects.dispatchCall(
                session,
                ConnectionEvent.BeginConnect(options, readinessTimeouts.linkMillis, rediscover = scanner != null),
            )
            proceed(session, options, scanner, progress)
        }
    }

    /**
     * 유령 Connecting 방지(D6/M-7): 시작 자체가 실패했으면 상태·스트림 둘 다 종결시킨다. throw 는 호출자에게
     * 가지만(두 갈래 규약), events 만 보는 다른 소비자도 Connecting 이 끝났음을 알아야 한다. 종결·게시 정책은 Core 가 정한다.
     */
    private suspend fun guarded(deviceId: PeripheralId, start: suspend () -> Unit) {
        try {
            start()
        } catch (e: BleException) {
            val session = registry.get(deviceId) ?: throw e
            effects.dispatch(session, ConnectionEvent.ConnectCallFailed(e.failure))
            throw BleException(e.failure)
        }
    }

    /** cold-cache 재발견이 필요하면 그 스캐너. 이 클라이언트가 마지막 Ready 이후 본 적 없는 주소면 필요하다. */
    private fun rediscoveryFor(deviceId: PeripheralId): PeripheralRediscovery? =
        rediscovery?.takeIf { linkCacheWarm?.invoke(deviceId) == false }

    /** 시도가 열린 뒤의 진행 — Core 가 돌려준 할 일([progress])이 재발견·링크 시작·호출 종료를 정한다. */
    private suspend fun proceed(
        session: Session,
        options: ConnectOptions,
        scanner: PeripheralRediscovery?,
        progress: ConnectionEffect?,
    ) {
        val deviceId = session.deviceId
        val generation = session.connectionCoordinator.generation
        var next = progress
        if (next is ConnectionEffect.Rediscover) {
            // 재발견: BT 재시작 뒤 스택이 주소 타입을 잊으면 getRemoteDevice 가 PUBLIC 으로 시도해
            // random static peripheral 에 닿지 않는다. 주소 필터 스캔 1회로 캐시를 채운 뒤 connectGatt 한다.
            // Connecting 진입·generation·link deadline 이 이미 시작된 뒤라 중복 connect 는 BleRuntime 의 기존 가드가 막고,
            // 앱은 Connecting 을 본다.
            val outcome = try {
                checkNotNull(scanner).rediscover(deviceId, readinessTimeouts.rediscoveryMillis)
            } catch (e: CancellationException) {
                // 호출자 취소(review HIGH 1) — 열린 generation 정리는 Core 가 정한다. 취소는 삼키지 않는다.
                effects.dispatch(session, ConnectionEvent.ConnectCallCancelled(generation))
                throw e
            }
            next = effects.dispatchCall(session, rediscoveryFinished(session, generation, outcome))
        }
        when (next) {
            is ConnectionEffect.FailConnectCall -> throw BleException(next.failure)
            is ConnectionEffect.EndConnectCall -> return
            else -> Unit
        }
        startLink(session, options, generation)
    }

    private fun rediscoveryFinished(session: Session, generation: Long, outcome: RediscoveryOutcome): ConnectionEvent =
        when (outcome) {
            RediscoveryOutcome.Found -> ConnectionEvent.RediscoveryFinished(
                generation, RediscoveryFact.Found, null, GattFailureFactory.operationInvalidated(session),
            )
            is RediscoveryOutcome.Unavailable -> ConnectionEvent.RediscoveryFinished(
                generation, RediscoveryFact.Unavailable, outcome.failure, GattFailureFactory.operationInvalidated(session),
            )
            RediscoveryOutcome.NotFound -> ConnectionEvent.RediscoveryFinished(
                generation, RediscoveryFact.NotFound, GattFailureFactory.peripheralNotFound(session),
                GattFailureFactory.operationInvalidated(session),
            )
        }

    /**
     * D7(M-8+#16) 벨트 — BleRuntime.ensureAdapterReady 의 검사와 이 호출 사이의 레이스
     * (예: 설정 앱에서 권한을 방금 회수)나 검사가 놓친 케이스를 막는다. connect() 는
     * 두 갈래 규약상 즉시 실패 = BleException throw 다. BleRuntime가 이를 받아
     * Connecting 을 Disconnected 로 정리하고 재던진다.
     */
    private fun startLink(session: Session, options: ConnectOptions, generation: Long) {
        val gatt = try {
            driver.connect(session.deviceId, options.autoConnect, generation)
        } catch (e: BleException) {
            linkStartFailed(session, generation, e.failure, LinkStartFailureKind.Rejected)
            throw e
        } catch (_: SecurityException) {
            val failure = GattFailureFactory.permissionDenied(
                session,
                FailurePhase.Connect,
                AndroidApiOperation.ConnectGatt,
            )
            linkStartFailed(session, generation, failure, LinkStartFailureKind.PermissionDenied)
            throw BleException(failure)
        } catch (_: Exception) {
            val failure = GattFailureFactory.startRejected(
                session,
                FailurePhase.Connect,
                AndroidApiOperation.ConnectGatt,
            )
            linkStartFailed(session, generation, failure, LinkStartFailureKind.Rejected)
            throw BleException(failure)
        }
        session.gatt = gatt
        effects.dispatch(session, ConnectionEvent.LinkStarted)

        // #15 — 연결 타임아웃. STATE_CONNECTED 가 오면(LinkCallbacks) 취소된다.
    }

    private fun linkStartFailed(session: Session, generation: Long, failure: BleFailure, kind: LinkStartFailureKind) {
        effects.dispatch(session, ConnectionEvent.LinkStartFailed(generation, failure, kind))
    }

    /**
     * 어댑터 복귀 — 어댑터 꺼짐 스윕이 끝낸 autoConnect 세션([Session.relinkAfterAdapter], 사용자 해제·
     * close 가 아직 내리지 않음)에 connectGatt(autoConnect = true) 를 한 번 건다(새 handle). 예약·재시도는
     * 없다 — 시작이 실패하면 connect 의 즉시 실패 경로가 Disconnected(Failed) 로 끝내고, 그 실패를 Diagnostic 으로 알린다.
     * 전이 관측 경로가 명령 안(ensureAdapterReady)일 수도 있으므로 코어 디스패처에 한 홉 미룬 뒤 다시 판정한다
     * — 세션 조건과 함께 어댑터가 아직 Ready 인지([adapterReady])도 사건에 싣는다.
     */
    fun resumeAfterAdapter(deviceId: PeripheralId, adapterReady: () -> Boolean) {
        if (registry.get(deviceId)?.relinkAfterAdapter != true) return
        scope.launch(SessionOwner(deviceId)) {
            val session = registry.get(deviceId) ?: return@launch
            try {
                guarded(deviceId) {
                    val scanner = rediscoveryFor(deviceId)
                    // 홉 사이에 어댑터가 다시 꺼졌으면 걸지 않는다 — 다음 Ready 복귀가 다시 판정한다.
                    val progress = effects.dispatchCall(
                        session,
                        ConnectionEvent.AdapterReturned(adapterReady(), readinessTimeouts.linkMillis, rediscover = scanner != null),
                    ) ?: return@guarded
                    proceed(session, ConnectOptions(autoConnect = true, requestedMtu = session.requestedMtu), scanner, progress)
                }
            } catch (e: BleException) {
                // 시작 실패는 connect 가 이미 종결·게시했다(재연결 대상 아님). 던질 호출자가 없으니
                // Diagnostic 으로 알린다(review MED-1 — 조용히 삼키지 않는다).
                emitDiagnostic(Severity.Warning, e.failure)
            }
        }
    }
}
