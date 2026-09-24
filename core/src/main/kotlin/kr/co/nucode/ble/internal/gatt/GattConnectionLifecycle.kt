package kr.co.nucode.ble.internal.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicProperty
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.internal.ConnectOptions
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.GattCharacteristic
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.ConnectSequenceHooks
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery
import kr.co.nucode.ble.internal.scan.RediscoveryOutcome
import kr.co.nucode.ble.internal.session.ConnectionAttemptOrigin
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.ConnectionStageDeadline
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import kr.co.nucode.ble.internal.session.toDisconnectReason

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
    private val linkCacheWarm: ((PeripheralId) -> Boolean)?,
    private val operations: GattOperationHooks,
    private val sequence: ConnectSequenceHooks = ConnectSequenceHooks.None,
    /**
     * 재연결 예산 소진 질의(Reconnector.exhaustedFailure 함수 참조). 종결 게시 직전에 물어,
     * 예산이 다해 재시도가 없을 세션이면 소진 실패를 돌려받는다 — 그 외(재시도함·suppress·
     * Never·autoConnect)는 null. Reconnector 타입에 의존하지 않도록 함수로 받는다.
     */
    private val reconnectExhaustion: (Session, BleFailure?) -> BleFailure? = { _, _ -> null },
) : GattLifecycleHooks {

    /** 상태 발행의 유일한 경로 — 발행 뒤 연결 절차 hook 을 부른다(plan connect-sequence 불변식 ②). */
    private fun publishState(session: Session) {
        emit(BleEvent.ConnStateChanged(session.deviceId, session.connectionState.value))
        sequence.onStatePublished(session.deviceId)
    }

    /** Retained `BluetoothGatt.connect()` 성공 직후 coordinator generation과 link deadline을 연다. */
    private fun beginRetainedReconnectLifecycle(session: Session): Long? {
        val timeouts = readinessTimeouts
        if (session.suppressReconnect) return null
        if (session.connectionCoordinator.stage != ConnectionStage.Disconnected) return null

        return session.connectionCoordinator.beginReconnect(
            scope = scope,
            linkTimeoutMillis = timeouts.linkMillis,
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
        )
    }

    /** Reconnector 가 platform start 직전에 부르는 lifecycle-only seam — 재연결 generation 을 여는 유일한 코어 경로. */
    internal fun beginRetainedReconnect(deviceId: PeripheralId): Long? =
        registry.get(deviceId)?.let(::beginRetainedReconnectLifecycle)

    private fun onLifecycleDeadline(
        deviceId: PeripheralId,
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
            // 콜백 없이 deadline 으로 끝난다 — beginCleanup 이 고정한 원인이 그대로 공개 이유가 된다.
            completeLifecycleCleanup(
                session = session,
                generation = deadline.generation,
                fallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
            )
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
            cause = TerminalCause(reason, cause),
            scope = scope,
            disconnectCleanupTimeoutMillis = timeouts.disconnectCleanupMillis,
            onTimeout = { onLifecycleDeadline(session.deviceId, it) },
        )
        if (!started) return false

        if (suppressReconnect) session.suppressReconnect = true
        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        session.reconnectJob?.cancel()
        session.reconnectJob = null
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        publishState(session)
        try {
            driver.disconnect(session.deviceId, session.gatt)
        } catch (_: Exception) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.disconnectStartFailed(session),
            )
            completeLifecycleCleanup(
                session = session,
                generation = generation,
                fallback = TerminalCause(reason, cause),
            )
        }
        return true
    }

    /**
     * 즉시 종결(시작 거부·재발견 실패 등) — [cause] 를 아직 고정된 원인이 없을 때만 고정하고
     * generation 을 Disconnected 로 확정한 뒤 한 번 게시한다. 이미 고정된 원인이 있으면 그 원인이 이긴다.
     */
    fun terminate(session: Session, generation: Long, cause: TerminalCause): Boolean {
        // adapter off 스윕은 재연결 대기 중(이미 Disconnected)인 세션도 끝낸다 — close 와 같은 경로.
        if (cause.reason == TerminalCleanupReason.AdapterUnavailable ||
            cause.reason == TerminalCleanupReason.PermissionDenied
        ) {
            if (endRetryPending(session, generation, cause)) return true
        }
        return completeLifecycleCleanup(
            session = session,
            generation = generation,
            fallback = cause,
        )
    }

    /**
     * 재연결 대기 중인 세션을 [cause] 로 끝낸다 — client close 와 adapter off 스윕의 공유 경로
     * ([ConnectionCoordinator.supersedeRetryPending] KDoc 의 두 소유자). 이미 Disconnected 인
     * generation 에서 reconnect job 이 예약됐거나 공개 상태가 Recovering 일 때만 동작한다.
     * suppressReconnect 를 세우고 job 을 취소한 뒤 coordinator 재게시와 ConnStateChanged 를 낸다.
     * 재시도 대기가 없으면 아무 것도 하지 않고 false(원래 원인 유지).
     */
    private fun endRetryPending(session: Session, generation: Long, cause: TerminalCause): Boolean {
        val coordinator = session.connectionCoordinator
        if (generation != coordinator.generation || coordinator.stage != ConnectionStage.Disconnected) return false
        // 취소 전에 판정한다 — 재연결 대기 중이던 세션은 앱 관점에서 아직 종착이 아니었다.
        if (!session.isRetryPending) return false
        session.suppressReconnect = true
        session.reconnectJob?.cancel()
        session.reconnectJob = null
        if (!coordinator.supersedeRetryPending(generation, cause)) return false
        emit(BleEvent.ConnStateChanged(session.deviceId, session.connectionState.value))
        return true
    }

    /**
     * 게시 없이 generation 만 종결한다(connect() 의 즉시 실패 — 게시는 throw 를 받은 호출자 몫).
     * 공개 이유는 고정된 원인에서만 파생한다.
     */
    private fun completeWithoutPublish(session: Session, generation: Long, fallback: TerminalCause): Boolean {
        finalizeTerminalCause(session, generation, fallback, status = 0)
        return session.connectionCoordinator.completeCleanup(generation, fallback)
    }

    /**
     * 재연결 소진 선판정(plan terminal-cause v3 §접근) — 게시 **전에** 이 종결 뒤 재시도가 있을지
     * 묻는다. ConnectSequence 의 Disconnected hook(scheduleReconnect)과 같은 질의(같은 retryCount·
     * policy·suppress·retryability)를 쓰므로 둘의 판정이 어긋나지 않는다.
     *
     * 순서(원인 → 결과): 원인 없는 플랫폼 해제면 status 를 담은 LINK_FAILED 를 먼저 고정하고
     * Diagnostic 으로 낸다. 그 뒤 예산 소진이면 [ConnectionCoordinator.latchExhaustion] 으로 failure 만
     * 소진 실패(원 실패를 감싼 것)로 바꾸고 소진 Diagnostic 을 낸 뒤 suppressReconnect 를 세워, 뒤따르는
     * 게시가 종착 한 번이 되게 한다. 사용자 해제·client close 는 latchExhaustion 이 거부한다.
     */
    private fun finalizeTerminalCause(session: Session, generation: Long, fallback: TerminalCause, status: Int) {
        val coordinator = session.connectionCoordinator
        if (generation != coordinator.generation || coordinator.stage == ConnectionStage.Disconnected) return
        val candidate = coordinator.terminalCause ?: fallback
        val derived = candidate.toDisconnectReason(
            generationStarted = true,
            unattributedFailure = { GattFailureFactory.unattributedLinkDisconnect(session, status) },
        )
        val derivedFailure = (derived as? DisconnectReason.Failed)?.failure
        if (candidate.failure == null && derivedFailure != null) {
            // 원인 없는 해제도 status 를 담은 실패 자체를 고정한다 — 게시 시점 파생에 status 가 필요 없다.
            coordinator.latchIfAbsent(generation, TerminalCause(candidate.reason, derivedFailure))
            // LINK_FAILED 는 State 와 함께 Event 로도 전달한다(AsyncResource 규칙) — 소진이어도 원인으로 먼저.
            emitDiagnostic(Severity.Warning, derivedFailure)
        }
        val exhausted = reconnectExhaustion(session, derivedFailure) ?: return
        if (!coordinator.latchExhaustion(generation, fallback, exhausted)) return
        emitDiagnostic(Severity.Warning, exhausted)
        // 포기는 종착이다 — 이 게시가 onStatePublished 로 scheduleReconnect 에 재진입하지 않고,
        // 완료 경로가 재사용하려 열어둔 handle 을 close 한다(슬롯 누수 방지, plan E).
        session.suppressReconnect = true
    }

    /** Callback 또는 cleanup deadline이 현재 generation을 정확히 한 번 terminal로 만든다. */
    private fun completeLifecycleCleanup(
        session: Session,
        generation: Long,
        fallback: TerminalCause,
        status: Int = 0,
    ): Boolean {
        finalizeTerminalCause(session, generation, fallback, status)
        val completed = session.connectionCoordinator.completeCleanup(generation, fallback)
        if (!completed) return false

        operations.invalidateAll(session, GattFailureFactory.operationInvalidated(session))
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        session.mtu = null
        session.chunkSize = null
        if (session.suppressReconnect) closeHandle(session)

        publishState(session)
        return true
    }

    // ── 콜백 → Session 갱신 + emit ──────────────────────────────────────────

    /**
     * [CallbackAttribution] 판정을 lifecycle 이 쓸 generation 으로 바꾼다. Bootstrap 이면 새
     * generation 을 연 뒤 그 generation 을 돌려준다. null 이면 콜백을 버린다.
     */
    private fun attributedGeneration(session: Session, event: GattDriverEvent): Long? =
        when (val attribution = CallbackAttribution.attribute(session, event)) {
            is Attribution.Current -> attribution.generation
            Attribution.Bootstrap -> beginRetainedReconnectLifecycle(session)
            is Attribution.Drop -> {
                // 끝난 generation 에 늦게 온 실패 status 는 lifecycle 을 건드리지 않지만 사실로는 남긴다
                // (late non-terminal link status 와 같은 AsyncInfo). 성공 status 는 조용히 버린다.
                if (
                    attribution.reason == DropReason.EndedGeneration &&
                    event is GattDriverEvent.ConnectionStateChanged &&
                    event.status != BluetoothGatt.GATT_SUCCESS
                ) {
                    emitDiagnostic(Severity.Warning, GattFailureFactory.linkStatusFailed(session, event.status))
                }
                null
            }
        }

    /**
     * 성공 link callback은 current source/generation/Connecting을 확인한 뒤
     * DiscoveringServices로 전이한다.
     */
    internal fun handleConnectionStateChange(event: GattDriverEvent.ConnectionStateChanged) {
        val deviceId = event.deviceId
        val status = event.status
        val newState = event.newState
        val session = registry.get(deviceId) ?: return

        run {
            val timeouts = readinessTimeouts
            val coordinator = session.connectionCoordinator
            val callbackGeneration = attributedGeneration(session, event) ?: return

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
                val failureReason = if (preReadyFailure) {
                    TerminalCleanupReason.LinkStatusFailure
                } else {
                    TerminalCleanupReason.PlatformDisconnected
                }
                // 먼저 고정된 원인(예: 사용자 해제)이 있으면 이 실패는 Diagnostic 으로만 남는다.
                coordinator.latchIfAbsent(callbackGeneration, TerminalCause(failureReason, cause))
                if (suppressReconnect) session.suppressReconnect = true
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    completeLifecycleCleanup(
                        session = session,
                        generation = callbackGeneration,
                        fallback = TerminalCause(failureReason, cause),
                        status = status,
                    )
                } else {
                    beginCleanup(
                        session = session,
                        reason = failureReason,
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
                    publishState(session)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // 고정된 원인을 지우지 않는다 — beginCleanup 이 고정한 원인이 fallback 을 이긴다.
                    completeLifecycleCleanup(
                        session = session,
                        generation = callbackGeneration,
                        fallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
                        status = status,
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
        val session = registry.get(deviceId) ?: return

        val callbackGeneration = attributedGeneration(session, event) ?: return
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

        val discoveredMap = mutableMapOf<CharacteristicRef, BluetoothGattCharacteristic>()
        val seen = mutableSetOf<CharacteristicRef>()
        val services = event.services.map { service ->
            val characteristics = service.characteristics.map { characteristic ->
                // A characteristic belongs to the enclosing discovery service; never trust a
                // separately supplied (or defaulted fake) service ordinal here.
                val scopedCharacteristic = characteristic.copy(serviceInstanceIndex = service.instanceIndex)
                val charRef = scopedCharacteristic.toCharacteristicRef()
                // review #11 — 같은 키로 조용히 덮어쓰지 않는다는 표시. 덮어쓰기 자체는
                // 유지한다.
                if (!seen.add(charRef)) {
                    emitDiagnostic(
                        Severity.Warning,
                        GattFailureFactory.duplicateCharacteristicReference(session),
                    )
                }
                scopedCharacteristic.platformCharacteristic?.let { discoveredMap[charRef] = it }
                GattCharacteristic(
                    ref = charRef,
                    properties = mapProperties(scopedCharacteristic.propertyMask),
                )
            }
            GattService(
                ref = ServiceRef(BleUuid(service.serviceUuid), service.instanceIndex),
                characteristics = characteristics,
            )
        }

        session.replaceDiscoveredTopology(services, discoveredMap)
        emit(BleEvent.ServicesDiscovered(deviceId, services))
        sequence.onServicesDiscovered(deviceId)
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
     * 발행한 뒤 성공 경로와 똑같이 `sequence.onMtuSettled` 를 불러 구독 복구 → Ready 로
     * 진행시킨다(발행 직후 hook — plan connect-sequence 불변식 ①).
     */
    internal fun handleMtuChanged(event: GattDriverEvent.MtuChanged) {
        val deviceId = event.deviceId
        val mtu = event.mtu
        val status = event.status
        val session = registry.get(deviceId) ?: return

        // MTU is only a fact for the active Configuring generation. Readiness completion
        // belongs to BleRuntime because it must wait for subscription restore, but stale MTU
        // callbacks must still be discarded at the bridge boundary.
        val callbackGeneration = attributedGeneration(session, event) ?: return
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
            sequence.onMtuSettled(deviceId)
            return
        }

        val chunkSize = chunkSizeForMtu(mtu)
        session.mtu = mtu
        session.chunkSize = chunkSize
        emit(BleEvent.MtuChanged(deviceId, mtu, chunkSize))
        sequence.onMtuSettled(deviceId)
        // public Ready 상태 발행은 onMtuSettled 이후 BleRuntime 구독 복구가 담당한다.
    }

    /**
     * `BluetoothGattCharacteristic.PROPERTY_*` 비트마스크 → `Set[CharacteristicProperty]`.
     * 플랫폼 상수가 모델 계층(`CharacteristicProperty`, PublicModels.kt)으로 새지 않게 막는 게 이
     * 함수의 유일한 목적이다.
     */
    private fun mapProperties(propertyMask: Int): Set<CharacteristicProperty> {
        val properties = mutableSetOf<CharacteristicProperty>()
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            properties += CharacteristicProperty.Read
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            properties += CharacteristicProperty.WriteWithResponse
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            properties += CharacteristicProperty.WriteWithoutResponse
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            properties += CharacteristicProperty.Notify
        }
        if (propertyMask and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            properties += CharacteristicProperty.Indicate
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
    suspend fun connect(deviceId: PeripheralId, options: ConnectOptions) {
        // 조용한 반환 금지(review L — KDoc "조용한 실패 경로를 없앤다" 와 코드 일치).
        // BleRuntime.connect 가 getOrCreate 직후 부르므로 정상 경로에선 도달 불가지만,
        // 도달하면 정확히 M-7 유령 Connecting 이다.
        val session = registry.get(deviceId) ?: throw BleException(GattFailureFactory.unknownDevice())
        val connectionGeneration = session.connectionCoordinator.beginConnection(
            scope = scope,
            linkTimeoutMillis = options.timeoutMillis ?: readinessTimeouts.linkMillis,
            onTimeout = { onLifecycleDeadline(deviceId, it) },
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
        // ConnectSequence.onServicesDiscovered 가 이 값을 그대로 읽어
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
                    completeWithoutPublish(
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
                    completeLifecycleCleanup(
                        session = session,
                        generation = connectionGeneration,
                        fallback = TerminalCause(TerminalCleanupReason.UserRequested, null),
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
                    completeWithoutPublish(
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
            completeWithoutPublish(
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
            completeWithoutPublish(
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
            completeWithoutPublish(
                session,
                connectionGeneration,
                TerminalCause(TerminalCleanupReason.LinkStartRejected, failure),
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
    fun disconnect(deviceId: PeripheralId) {
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
        driver.disconnect(deviceId, session.gatt)
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
     * MTU 협상 요청. ConnectSequence.onServicesDiscovered 가 `ServicesDiscovered` 발행 이후 부른다.
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
            sequence.onMtuSettled(deviceId)
        }
    }

    /**
     * Client close — 세션을 `Disconnected(ClientClosed)` 로 끝내고 platform handle 을 닫는다.
     *
     * 원인 대체 규칙은 [ConnectionCoordinator.supersedeForClose](종결되지 않은 generation)와
     * [endRetryPending]([ConnectionCoordinator.supersedeRetryPending]): 재연결 대기 중(공개 상태 Recovering 이거나 reconnect job 예약됨)인 generation 은 ClientClosed 로
     * 끝난다. 재시도 대기가 없는 이미 종결된 generation 은 원래 원인(예: Failed)을 유지한다.
     * 연결 시도 전(generation 0) 세션은 닫힘이 첫 원인이다.
     *
     * close 는 종착이다 — suppressReconnect 를 세우고 예약된 reconnect 를 취소한다. 공개 상태는
     * coordinator 게시(onStageChanged → Session.connectionState)로 한 번 바뀌고, 시작된 generation 은
     * ConnStateChanged 도 낸다(BleRuntime 경유 시 close 게이트 뒤라 이벤트 버스에서는 버려진다 —
     * 닫힌 클라이언트의 최종 상태는 connectionState·closed snapshot 이 전달한다). 연결 절차
     * hook(onStatePublished)은 부르지 않는다(닫힌 런타임에서 다음 단계를 진행할 이유가 없다).
     */
    fun close(session: Session) {
        val coordinator = session.connectionCoordinator
        val generation = coordinator.generation
        if (endRetryPending(session, generation, TerminalCause(TerminalCleanupReason.ClientClosed, null))) {
            closeHandle(session)
            return
        }
        session.suppressReconnect = true
        session.reconnectJob?.cancel()
        session.reconnectJob = null
        val superseded = coordinator.supersedeForClose(generation)
        val completed = completeWithoutPublish(
            session,
            generation,
            TerminalCause(TerminalCleanupReason.ClientClosed, null),
        )
        // 연결 시도 전(고정된 원인 없음) 세션은 종결할 generation 이 없다 — 닫힘이 첫 원인이다.
        // 이미 종결된 generation 의 원인은 바꾸지 않는다(first wins).
        if (!completed && !superseded) coordinator.closeIdle()
        if (generation > 0L && (superseded || completed)) {
            emit(BleEvent.ConnStateChanged(session.deviceId, session.connectionState.value))
        }
        closeHandle(session)
    }

    /** platform handle 만 닫는다(원인·공개 상태는 건드리지 않는다). 완료 경로의 suppress 정리에도 쓴다. */
    private fun closeHandle(session: Session) {
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
        // Platform disconnect/close보다 먼저 connection generation을 terminalize한다.
        // disconnect가 callback을 동기 재진입시키더라도 OperationInvalidated 원인이
        // PlatformDisconnected로 먼저 확정되거나 coordinator가 Ready에 남지 않는다.
        // 원인(무효화) Diagnostic 을 결과(소진 판정 Diagnostic)보다 먼저 낸다.
        emitDiagnostic(Severity.Warning, cause)
        completeWithoutPublish(
            session,
            connectionGeneration,
            TerminalCause(TerminalCleanupReason.OperationInvalidated, cause),
        )
        session.resubscribeJob?.cancel()
        session.resubscribeJob = null
        val oldGatt = session.gatt
        driver.disconnect(session.deviceId, oldGatt)
        driver.close(session.deviceId, oldGatt)
        session.gatt = null
        session.mtu = null
        session.chunkSize = null

        publishState(session)
    }

    private fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }


    private companion object {
        /**
         * MTU 협상/요청 실패 시 폴백 값 — BLE 표준 기본 ATT_MTU.
         * [DEFAULT_CHUNK_SIZE] 가 근거로 삼는 것과 같은 값(23)이다.
         */
        const val DEFAULT_MTU = 23
    }
}
