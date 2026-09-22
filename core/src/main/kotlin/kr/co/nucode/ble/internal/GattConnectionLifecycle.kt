package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharProperty
import kr.co.nucode.ble.CharRef
import kr.co.nucode.ble.ConnectOptions
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.DiscoveredCharacteristic
import kr.co.nucode.ble.DiscoveredService
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Severity

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
 * `GattBridge`의 연결-생애주기 절반(plan D task 1) — retained reconnect, connect/disconnect,
 * service discovery, MTU 협상, lifecycle deadline/cleanup을 소유한다.
 *
 * operation 절반(scheduler FIFO)과는 [GattOperationHooks]로만 통신한다 — scheduler 필드를
 * 직접 참조하지 않는다(게이트: 해당 식별자 참조 0회). 반대 방향은 [GattLifecycleHooks]를
 * 이 클래스가 구현해 제공한다.
 */
internal class GattConnectionLifecycle(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val rediscovery: PeripheralRediscovery?,
    private val linkCacheWarm: ((DeviceId) -> Boolean)?,
    private val operations: GattOperationHooks,
) : GattLifecycleHooks {

    /** Retained `BluetoothGatt.connect()` 성공 직후 coordinator generation과 link deadline을 연다. */
    private fun beginRetainedReconnectLifecycle(session: Session): Long? {
        val timeouts = readinessTimeouts
        if (session.suppressReconnect) return null
        if (session.connectionCoordinator.stage != ConnectionStage.Disconnected) return null

        session.terminalLifecycleCause = null
        return session.connectionCoordinator.beginReconnect(
            scope = scope,
            linkTimeoutMillis = timeouts.linkMillis,
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
        )
    }

    /** Reconnector의 platform start 직후 호출되는 lifecycle-only seam. */
    internal fun beginRetainedReconnect(deviceId: DeviceId): Long? =
        registry.get(deviceId)?.let(::beginRetainedReconnectLifecycle)

    /**
     * Reconnector 정책을 복제하지 않고, 그 기존 backoff job이 platform reconnect 시작을 끝낸
     * 시점에 lifecycle만 귀속한다. Android callback이 매우 빨리 오면 callback 경로의 동일
     * idempotent bootstrap이 먼저 실행된다.
     */
    private fun armRetainedReconnectLifecycle(session: Session) {
        val reconnectJob = session.reconnectJob ?: return
        reconnectJob.invokeOnCompletion { failure ->
            if (failure != null) return@invokeOnCompletion
            scope.launch { beginRetainedReconnect(session.deviceId) }
        }
    }

    private fun onLifecycleDeadline(
        deviceId: DeviceId,
        deadline: ConnectionStageDeadline,
    ) {
        val session = registry.get(deviceId) ?: return
        val coordinator = session.connectionCoordinator
        if (deadline.generation != coordinator.generation || deadline.stage != coordinator.stage) return

        if (deadline.stage == ConnectionStage.Disconnecting) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.disconnectCleanupTimeout(session),
            )
            completeLifecycleCleanup(session, deadline.generation)
            return
        }

        val lifecycleFailure = GattFailureFactory.lifecycleTimeout(session, deadline.timeoutReason)
        emitDiagnostic(Severity.Warning, lifecycleFailure)
        beginCleanup(
            session = session,
            reason = deadline.timeoutReason,
            cause = lifecycleFailure,
            suppressReconnect = coordinator.attemptOrigin != ConnectionAttemptOrigin.Reconnect,
        )
    }

    /** Lifecycle failure/user intent가 scheduler와 platform handle을 한 bounded 경로로 정리한다. */
    override fun beginCleanup(
        session: Session,
        reason: TerminalCleanupReason,
        cause: BleFailure?,
        suppressReconnect: Boolean,
    ): Boolean {
        val timeouts = readinessTimeouts
        val generation = session.connectionCoordinator.generation
        val started = session.connectionCoordinator.beginCleanup(
            targetGeneration = generation,
            reason = reason,
            scope = scope,
            disconnectCleanupTimeoutMillis = timeouts.disconnectCleanupMillis,
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
        )
        if (!started) return false

        session.terminalLifecycleCause = cause
        if (suppressReconnect) session.suppressReconnect = true
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        session.reconnectJob?.cancel()
        session.reconnectJob = null
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        session.timeoutJob?.cancel()
        session.timeoutJob = null
        emit(BleEvent.ConnStateChanged(session.deviceId, session.state))
        try {
            driver.disconnect(session.deviceId, session.gatt)
        } catch (_: Exception) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.disconnectStartFailed(session),
            )
            completeLifecycleCleanup(session, generation)
        }
        return true
    }

    /** Callback 또는 cleanup deadline이 현재 generation을 정확히 한 번 terminal로 만든다. */
    private fun completeLifecycleCleanup(
        session: Session,
        generation: Long,
        reason: TerminalCleanupReason? = null,
        cause: BleFailure? = session.terminalLifecycleCause,
    ): Boolean {
        val disconnectedReason = disconnectReason(reason, cause)
        val completed = session.connectionCoordinator.completeCleanup(generation, reason) {
            session.prepareDisconnect(disconnectedReason)
        }
        if (!completed) return false

        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        session.timeoutJob?.cancel()
        session.timeoutJob = null
        session.mtu = null
        session.chunkSize = null
        if (session.suppressReconnect) close(session)

        emit(BleEvent.ConnStateChanged(session.deviceId, session.state))
        if (!session.suppressReconnect) armRetainedReconnectLifecycle(session)
        return true
    }

    // ── 콜백 → Session 갱신 + emit ──────────────────────────────────────────

    /**
     * 성공 link callback은 current source/generation/Connecting을 확인한 뒤
     * DiscoveringServices로 전이한다.
     */
    internal fun handleConnectionStateChange(event: GattDriverEvent.ConnectionStateChanged) {
        val deviceId = event.deviceId
        val status = event.status
        val newState = event.newState
        val session = registry.sessionFor(event) ?: return

        run {
            val timeouts = readinessTimeouts
            val coordinator = session.connectionCoordinator
            if (event.connectionGeneration == null) {
                beginRetainedReconnect(session.deviceId)
            }
            val callbackGeneration = event.connectionGeneration ?: coordinator.generation
            if (callbackGeneration != coordinator.generation || coordinator.stage == ConnectionStage.Disconnected) {
                return
            }

            val cause = status.takeIf { it != BluetoothGatt.GATT_SUCCESS }?.let {
                GattFailureFactory.linkStatusFailed(session, it)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitDiagnostic(Severity.Error, requireNotNull(cause))
                // STATE_DISCONNECTED만 어느 active stage에서든 terminal fact다. 그 밖의
                // failure callback은 link attempt의 결과일 때만 lifecycle failure다. 같은
                // generation이어도 readiness가 진행된 뒤 늦게 온 non-terminal callback은
                // 현재 stage와 deadline을 되돌리거나 끝내면 안 된다.
                if (
                    newState != BluetoothProfile.STATE_DISCONNECTED &&
                    !coordinator.accepts(callbackGeneration, ConnectionStage.Connecting)
                ) {
                    return
                }
                val preReadyFailure = coordinator.stage != ConnectionStage.Ready &&
                    coordinator.stage != ConnectionStage.Disconnecting
                val suppressReconnect = preReadyFailure &&
                    coordinator.attemptOrigin != ConnectionAttemptOrigin.Reconnect
                session.terminalLifecycleCause = cause
                if (suppressReconnect) session.suppressReconnect = true
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    completeLifecycleCleanup(
                        session = session,
                        generation = callbackGeneration,
                        reason = if (preReadyFailure) {
                            TerminalCleanupReason.LinkStatusFailure
                        } else {
                            TerminalCleanupReason.PlatformDisconnected
                        },
                        cause = cause,
                    )
                } else {
                    beginCleanup(
                        session = session,
                        reason = if (preReadyFailure) {
                            TerminalCleanupReason.LinkStatusFailure
                        } else {
                            TerminalCleanupReason.PlatformDisconnected
                        },
                        cause = cause,
                        suppressReconnect = suppressReconnect,
                    )
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val accepted = coordinator.transition(
                        callbackGeneration = callbackGeneration,
                        expectedStage = ConnectionStage.Connecting,
                        nextStage = ConnectionStage.DiscoveringServices,
                        scope = scope,
                        timeoutMillis = timeouts.discoveryMillis,
                        onTimeout = { onLifecycleDeadline(deviceId, it) },
                    )
                    if (!accepted) return
                    operations.beginGenerationIfPoisoned(session)
                    session.timeoutJob?.cancel()
                    session.timeoutJob = null
                    emit(BleEvent.ConnStateChanged(deviceId, session.state))
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    session.terminalLifecycleCause = null
                    completeLifecycleCleanup(
                        session = session,
                        generation = callbackGeneration,
                        reason = TerminalCleanupReason.PlatformDisconnected,
                        cause = null,
                    )
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    if (!coordinator.accepts(callbackGeneration, ConnectionStage.Connecting)) return
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    if (!coordinator.accepts(callbackGeneration, ConnectionStage.Disconnecting)) return
                }
            }
            return
        }
    }

    /** 서비스 탐색 결과 → Session topology/target snapshot 교체 + ServicesDiscovered emit. */
    internal fun handleServicesDiscovered(event: GattDriverEvent.ServicesDiscovered) {
        val deviceId = event.deviceId
        val status = event.status
        val session = registry.sessionFor(event) ?: return

        val callbackGeneration = event.connectionGeneration
            ?: session.connectionCoordinator.generation
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val accepted = session.connectionCoordinator.transition(
                callbackGeneration = callbackGeneration,
                expectedStage = ConnectionStage.DiscoveringServices,
                nextStage = ConnectionStage.Configuring,
                scope = scope,
                timeoutMillis = readinessTimeouts.configurationMillis,
                onTimeout = { onLifecycleDeadline(deviceId, it) },
            )
            if (!accepted) return
        } else {
            if (
                !session.connectionCoordinator.accepts(
                    callbackGeneration,
                    ConnectionStage.DiscoveringServices,
                )
            ) return
            val failure = GattFailureFactory.serviceDiscoveryStatusFailed(session, status)
            emitDiagnostic(Severity.Error, failure)
            beginCleanup(
                session = session,
                reason = TerminalCleanupReason.ServiceDiscoveryStatusFailure,
                cause = failure,
                suppressReconnect = session.connectionCoordinator.attemptOrigin !=
                    ConnectionAttemptOrigin.Reconnect,
            )
            return
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitDiagnostic(
                Severity.Error,
                GattFailureFactory.serviceDiscoveryStatusFailed(session, status),
            )
            return
        }

        val discoveredMap = mutableMapOf<CharRef, BluetoothGattCharacteristic>()
        val seen = mutableSetOf<CharRef>()
        val services = event.services.map { service ->
            val characteristics = service.characteristics.map { characteristic ->
                // A characteristic belongs to the enclosing discovery service; never trust a
                // separately supplied (or defaulted fake) service ordinal here.
                val scopedCharacteristic = characteristic.copy(serviceInstanceIndex = service.instanceIndex)
                val charRef = scopedCharacteristic.toCharRef()
                // review #11 — 같은 키로 조용히 덮어쓰지 않는다는 표시. 덮어쓰기 자체는
                // 유지한다(CharRef 시그니처 변경은 plan 사안, 이 task 범위 밖).
                if (!seen.add(charRef)) {
                    emitDiagnostic(
                        Severity.Warning,
                        GattFailureFactory.duplicateCharacteristicReference(session),
                    )
                }
                scopedCharacteristic.platformCharacteristic?.let { discoveredMap[charRef] = it }
                DiscoveredCharacteristic(
                    charRef = charRef,
                    properties = mapProperties(scopedCharacteristic.propertyMask),
                )
            }
            DiscoveredService(
                serviceUuid = service.serviceUuid,
                characteristics = characteristics,
                instanceIndex = service.instanceIndex,
            )
        }

        session.replaceDiscoveredTopology(services, discoveredMap)
        emit(BleEvent.ServicesDiscovered(deviceId, services))
    }

    /**
     * MTU 협상 결과 → chunkSize 계산 + MtuChanged emit.
     *
     * 계산은 [chunkSizeForMtu] 한 곳에만 둔다 — write 경로와 여기가
     * 다른 값을 쓰면 청크가 MTU 를 넘어 조용히 잘린다.
     *
     * 실패 분기(review #4): 예전에는 Diagnostic 만 내고 return 해 Discovering 에 영구
     * 정지했다(연결 시퀀스가 MtuChanged 를 기다리는데 다시 오지 않으므로). 협상 실패라도
     * 링크 자체는 살아 있고 유효 MTU 가 BLE 기본값 23 이므로, 23/20 폴백을 그대로
     * 방출해 legacy direct seam의 기존 경로를 유지한다. coordinator readiness의 실패/fallback
     * terminal 처리는 Task 3이 이관한다.
     */
    internal fun handleMtuChanged(event: GattDriverEvent.MtuChanged) {
        val deviceId = event.deviceId
        val mtu = event.mtu
        val status = event.status
        val session = registry.sessionFor(event) ?: return

        // MTU is only a fact for the active Configuring generation. Readiness completion
        // belongs to BleRuntime because it must wait for subscription restore, but stale MTU
        // callbacks must still be discarded at the bridge boundary.
        val callbackGeneration = event.connectionGeneration ?: session.connectionCoordinator.generation
        if (!session.connectionCoordinator.accepts(callbackGeneration, ConnectionStage.Configuring)) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.mtuStatusFailed(session, status),
            )
            val fallbackChunk = chunkSizeForMtu(DEFAULT_MTU)
            session.mtu = DEFAULT_MTU
            session.chunkSize = fallbackChunk
            emit(BleEvent.MtuChanged(deviceId, DEFAULT_MTU, fallbackChunk))
            return
        }

        val chunkSize = chunkSizeForMtu(mtu)
        session.mtu = mtu
        session.chunkSize = chunkSize
        emit(BleEvent.MtuChanged(deviceId, mtu, chunkSize))
        // public Ready 상태 발행은 이 callback fact를 받은 BleRuntime orchestration이 담당한다.
    }

    /**
     * `BluetoothGattCharacteristic.PROPERTY_*` 비트마스크 → `Set[CharProperty]`.
     * 플랫폼 상수가 모델 계층(`CharProperty`, InternalModels.kt)으로 새지 않게 막는 게 이 함수의 유일한 목적이다.
     */
    private fun mapProperties(propertyMask: Int): Set<CharProperty> {
        val properties = mutableSetOf<CharProperty>()
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            properties += CharProperty.Read
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            properties += CharProperty.Write
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            properties += CharProperty.WriteNoResponse
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            properties += CharProperty.Notify
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            properties += CharProperty.Indicate
        }
        return properties
    }

    // ── 진입 함수(실제 플랫폼 호출) ──────────────────────────────────────────

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
     */
    suspend fun connect(deviceId: DeviceId, options: ConnectOptions) {
        // 조용한 반환 금지(review L — KDoc "조용한 실패 경로를 없앤다" 와 코드 일치).
        // BleRuntime.connect 가 getOrCreate 직후 부르므로 정상 경로에선 도달 불가지만,
        // 도달하면 정확히 M-7 유령 Connecting 이다.
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        val connectionGeneration = session.connectionCoordinator.beginConnection(
            scope = scope,
            linkTimeoutMillis = options.timeoutMillis ?: readinessTimeouts.linkMillis,
            onTimeout = { onLifecycleDeadline(deviceId, it) },
        )
        session.subscriptionState.beginGeneration(connectionGeneration)
        emit(BleEvent.ConnStateChanged(deviceId, session.state))
        session.terminalLifecycleCause = null

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
        session.timeoutJob?.cancel()
        session.timeoutJob = null
        session.gatt?.let { driver.close(deviceId, it) }
        session.gatt = null
        // D2/D6 — 이 재설정은 connectGatt 직전이어야 한다. 이전 시도가 사용자 disconnect()
        // ·타임아웃 포기·connect 시작 실패(BleRuntime.connect 의 catch) 중 무엇으로든
        // suppressReconnect 를 true 로 남겼더라도, 지금 시작하는 이 시도는 새 시도라
        // "포기 상태"를 물려받으면 안 된다. connectGatt 호출 뒤로 미루면 그 사이 콜백이
        // (이례적으로) 동기 재진입해 stale 한 suppressReconnect 값을 보는 창이 생긴다.
        session.suppressReconnect = false
        // 배타(plan v3): 사용자 connect 는 이전 생애주기의 예약 재연결을 대체한다 —
        // 취소 없이 두면 대기 중이던 백오프 job 이 새 gatt 위에서 뒤늦게 발화한다.
        // 모드도 여기서 확정 — 이후 scheduleReconnect/reconnect 분기의 유일한 근거.
        session.reconnectJob?.cancel()
        session.reconnectJob = null
        // resubscribeJob 정리(plan B) — 새 연결 시도가 시작되는데 이전 생애주기의 재구독
        // 루프가 살아 있으면 안 된다. reconnectJob 과 같은 취소 지점 3곳 중 하나.
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        // retryCount 리셋(plan D) — 새 생애주기 = 새 재연결 예산. give-up 잔재(이전 시도가
        // maxAttempts 까지 소진했던 값)가 이번 시도로 넘어오면 안 된다.
        session.retryCount = 0
        session.autoConnect = options.autoConnect
        // plan blecore-app-tunables 결정① — 이번 연결 시도의 옵션값을 Session 에 보존한다.
        // BleRuntime.advanceConnectSequence 의 ServicesDiscovered 분기가 이 값을 그대로 읽어
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
                    session.terminalLifecycleCause = null
                    session.prepareDisconnect(DisconnectReason.Requested)
                    session.connectionCoordinator.completeCleanup(
                        connectionGeneration,
                        TerminalCleanupReason.LinkStartRejected,
                    )
                    operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
                    emit(BleEvent.ConnStateChanged(deviceId, session.state))
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
                    completeLifecycleCleanup(
                        session = session,
                        generation = connectionGeneration,
                        reason = coordinator.terminalCleanupReason ?: TerminalCleanupReason.UserRequested,
                        cause = session.terminalLifecycleCause,
                    )
                }
                return
            }
            when (outcome) {
                RediscoveryOutcome.Found -> Unit
                is RediscoveryOutcome.Unavailable -> emitDiagnostic(Severity.Warning, outcome.failure) // 폴백: 직접 연결
                RediscoveryOutcome.NotFound -> {
                    val failure = GattFailureFactory.peripheralNotFound(session)
                    session.suppressReconnect = true
                    session.terminalLifecycleCause = failure
                    session.prepareDisconnect(DisconnectReason.Failed(failure))
                    session.connectionCoordinator.completeCleanup(
                        connectionGeneration,
                        TerminalCleanupReason.LinkStartRejected,
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
            session.terminalLifecycleCause = e.failure
            session.prepareDisconnect(DisconnectReason.Failed(e.failure))
            session.connectionCoordinator.completeCleanup(
                connectionGeneration,
                TerminalCleanupReason.LinkStartRejected,
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
            session.terminalLifecycleCause = failure
            session.prepareDisconnect(DisconnectReason.Failed(failure))
            session.connectionCoordinator.completeCleanup(
                connectionGeneration,
                TerminalCleanupReason.PermissionDenied,
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
            session.terminalLifecycleCause = failure
            session.prepareDisconnect(DisconnectReason.Failed(failure))
            session.connectionCoordinator.completeCleanup(
                connectionGeneration,
                TerminalCleanupReason.LinkStartRejected,
            )
            operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
            throw BleException(failure)
        }
        session.gatt = gatt

        // #15 — 연결 타임아웃. STATE_CONNECTED 가 오면(위 handleConnectionStateChange) 취소된다.
    }

    /**
     * 연결 해제. `gatt.close()` 는 여기서 하지 않는다 — `onConnectionStateChange` 로
     * `STATE_DISCONNECTED` 가 도착한 뒤 [handleConnectionStateChange] 에서
     * [Session.suppressReconnect] == true 일 때만 정리한다(plan §잠재에러 — close() 를
     * 놓치면 핸들이 누수된다). 재연결 후보(suppressReconnect == false)라면 여기서 close 하지
     * 않는다 — Reconnector(task 5)가 같은 gatt 로 `gatt.connect()` 재사용을 시도한다.
     */
    fun disconnect(deviceId: DeviceId) {
        val session = registry.get(deviceId) ?: return
        val started = beginCleanup(
            session = session,
            reason = TerminalCleanupReason.UserRequested,
            cause = null,
            suppressReconnect = true,
        )
        if (started || session.connectionCoordinator.stage != ConnectionStage.Disconnected) return
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        session.suppressReconnect = true // Reconnector 가 "의도적 해제"를 구분하는 근거(task 5 가 소비)
        session.reconnectJob?.cancel() // 배타(plan v3) — 백오프 대기 중이던 예약도 즉시 종료
        session.reconnectJob = null
        session.resubscribeJob?.cancel() // plan B — 사용자 해제 의도에 진행 중 재구독이 남으면 안 된다.
        session.resubscribeJob = null
        session.timeoutJob?.cancel()
        driver.disconnect(deviceId, session.gatt)
    }

    /**
     * 서비스 탐색 시작. `discoverServices()` 가 `false` 를 돌려주면 시작 자체가 실패한
     * 것이라 `onServicesDiscovered` 콜백이 영원히 오지 않는다 — 연결 시퀀스가
     * Discovering 에서 영구 정지한다. 회복 불가로 보고 즉시 Disconnected 로 전이한다
     * (plan §접근 ④).
     */
    fun discoverServices(deviceId: DeviceId) {
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
            beginCleanup(
                session = session,
                reason = if (failure.code.value == "PERMISSION_DENIED") {
                    TerminalCleanupReason.PermissionDenied
                } else {
                    TerminalCleanupReason.ServiceDiscoveryStartRejected
                },
                cause = failure,
                suppressReconnect = session.connectionCoordinator.attemptOrigin !=
                    ConnectionAttemptOrigin.Reconnect,
            )
        }
    }

    /**
     * MTU 협상 요청. BleRuntime.advanceConnectSequence 가 `ServicesDiscovered` 이후 부른다.
     * `requestMtu()` 가 `false` 를 돌려주면(호출 자체 실패) [handleMtuChanged] 콜백이 오지
     * 않으므로, requestMtu 실패도 handleMtuChanged 의 협상 실패와 같은 23/20 폴백 경로로
     * 처리해 Discovering 영구 정지를 막는다(review #4, plan §접근 ④).
     */
    fun requestMtu(deviceId: DeviceId, mtu: Int) {
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
                beginCleanup(
                    session = session,
                    reason = TerminalCleanupReason.PermissionDenied,
                    cause = failure,
                    suppressReconnect = session.connectionCoordinator.attemptOrigin !=
                        ConnectionAttemptOrigin.Reconnect,
                )
                return
            }
            val fallbackChunk = chunkSizeForMtu(DEFAULT_MTU)
            session.mtu = DEFAULT_MTU
            session.chunkSize = fallbackChunk
            emit(BleEvent.MtuChanged(deviceId, DEFAULT_MTU, fallbackChunk))
        }
    }

    /** Close the current platform handle while keeping ownership in the existing [Session]. */
    fun close(session: Session) {
        session.connectionCoordinator.completeCleanup(
            session.connectionCoordinator.generation,
            TerminalCleanupReason.ClientClosed,
        )
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        driver.close(session.deviceId, session.gatt)
        session.gatt = null
    }

    /** Operation 절반의 poison 꼬리(plan §접근) — completeCleanup이 driver.disconnect/close보다
     * 먼저다(callback 동기 재진입 대비, 위 완료 순서 주석과 동일 근거). */
    override fun terminateForOperationInvalidation(
        session: Session,
        cause: BleFailure,
        suppressReconnect: Boolean,
    ) {
        val connectionGeneration = session.connectionCoordinator.generation
        session.suppressReconnect = suppressReconnect
        session.terminalLifecycleCause = cause
        session.prepareDisconnect(DisconnectReason.Failed(cause))
        // Platform disconnect/close보다 먼저 connection generation을 terminalize한다.
        // disconnect가 callback을 동기 재진입시키더라도 OperationInvalidated 원인이
        // PlatformDisconnected로 먼저 확정되거나 coordinator가 Ready에 남지 않는다.
        session.connectionCoordinator.completeCleanup(
            connectionGeneration,
            TerminalCleanupReason.OperationInvalidated,
        )
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        session.timeoutJob?.cancel()
        session.timeoutJob = null
        val oldGatt = session.gatt
        driver.disconnect(session.deviceId, oldGatt)
        driver.close(session.deviceId, oldGatt)
        session.gatt = null
        session.mtu = null
        session.chunkSize = null

        emitDiagnostic(Severity.Warning, cause)
        emit(BleEvent.ConnStateChanged(session.deviceId, session.state))
    }

    private fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }

    private fun disconnectReason(
        terminalReason: TerminalCleanupReason?,
        failure: BleFailure?,
    ): DisconnectReason = when {
        failure != null -> DisconnectReason.Failed(failure)
        terminalReason == TerminalCleanupReason.UserRequested -> DisconnectReason.Requested
        terminalReason == TerminalCleanupReason.ClientClosed -> DisconnectReason.ClientClosed
        else -> DisconnectReason.Initial
    }

    private companion object {
        /**
         * MTU 협상/요청 실패 시 폴백 값 — BLE 표준 기본 ATT_MTU.
         * [DEFAULT_CHUNK_SIZE] 가 근거로 삼는 것과 같은 값(23)이다.
         */
        const val DEFAULT_MTU = 23
    }
}
