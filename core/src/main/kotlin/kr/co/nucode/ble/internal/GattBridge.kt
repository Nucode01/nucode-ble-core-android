package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharProperty
import kr.co.nucode.ble.CharRef
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.ConnectOptions
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.DiscoveredCharacteristic
import kr.co.nucode.ble.DiscoveredService
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.WriteType
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver

/** Task 3 operation whose ACK clock starts only after the platform start call returns. */
private interface StartTrackedGattOperation {
    val started: CompletableDeferred<Unit>
}

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
    fun resolve(session: Session, charRef: CharRef): GattOperationTarget?
}

/** Read resolves its platform characteristic only when it reaches the FIFO head. */
private class QueuedRead(
    override val generation: Long,
    val charRef: CharRef,
) : GattOperation<ByteArray>, StartTrackedGattOperation {
    private var resolvedIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val kind: GattOperationKind = GattOperationKind.Read
    override val attributeIdentity: GattCharacteristicIdentity
        get() = resolvedIdentity

    fun prepare(identity: GattCharacteristicIdentity) {
        resolvedIdentity = identity
    }
}

/** A public write command reserves every chunk before its first chunk starts. */
private class QueuedWriteChunk(
    override val generation: Long,
    val charRef: CharRef,
    val requestedWriteType: WriteType,
    val commandIndex: Int,
    chunk: ByteArray,
) : GattOperation<Unit>, StartTrackedGattOperation {
    private val immutableChunk = chunk.copyOf()
    private var resolvedWriteType: Int? = null
    private var resolvedIdentity: GattCharacteristicIdentity = GattCharacteristicIdentity.from(charRef)

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val kind: GattOperationKind
        get() = if (resolvedWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            GattOperationKind.WriteWithoutResponse
        } else {
            GattOperationKind.WriteWithResponse
        }
    override val attributeIdentity: GattCharacteristicIdentity
        get() = resolvedIdentity

    lateinit var commandMembers: List<ScheduledGattOperation<Unit>>

    fun prepare(identity: GattCharacteristicIdentity, writeType: Int) {
        resolvedIdentity = identity
        resolvedWriteType = writeType
    }

    fun chunkCopy(): ByteArray = immutableChunk.copyOf()
}

/** Subscribe/unsubscribe direction is command intent, not a fact present in Android callback. */
private class QueuedCccdWrite(
    override val generation: Long,
    val charRef: CharRef,
    val enabled: Boolean,
    val restore: Boolean = false,
    descriptorIdentity: GattDescriptorIdentity,
) : GattOperation<Unit>, StartTrackedGattOperation {
    private var resolvedIdentity: GattDescriptorIdentity = descriptorIdentity

    override val started: CompletableDeferred<Unit> = CompletableDeferred()
    override val kind: GattOperationKind =
        if (enabled) GattOperationKind.Subscribe else GattOperationKind.Unsubscribe
    override val attributeIdentity: GattDescriptorIdentity
        get() = resolvedIdentity

    fun prepare(identity: GattDescriptorIdentity) {
        resolvedIdentity = identity
    }
}

/**
 * [GattDriver] callback fact → `BleEvent` 변환과 [Session] 갱신(설계 §6 구성요소).
 * 정책·판정을 넣지 않는다 — 재연결 여부·재시도 횟수 결정은 Reconnector(task 5) 몫이고,
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
 */
internal class GattBridge(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val ackTimeoutMillis: Long,
    private val operationStarter: ((Session, ScheduledGattOperation<*>) -> BleFailure?)? = null,
    private val operationWriteTypeResolver: ((Session, CharRef, WriteType) -> Int)? = null,
    private val operationTargetResolver: GattOperationTargetResolver? = null,
    private val readinessTimeouts: ConnectionReadinessTimeouts = ConnectionReadinessTimeouts(
        linkMillis = 15_000,
        discoveryMillis = 10_000,
        configurationMillis = 10_000,
    ),
    private val diagnosticIngress: DiagnosticIngress = DiagnosticIngress { _, _, _ -> },
    private val rediscovery: PeripheralRediscovery? = null,
    private val linkCacheWarm: ((DeviceId) -> Boolean)? = null,
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

    init {
        driver.setEventSink(::handleEvent)
    }

    private fun handleEvent(event: GattDriverEvent) {
        when (event) {
            is GattDriverEvent.ConnectionStateChanged -> handleConnectionStateChange(event)
            is GattDriverEvent.ServicesDiscovered -> handleServicesDiscovered(event)
            is GattDriverEvent.MtuChanged -> handleMtuChanged(event)
            is GattDriverEvent.CharacteristicChanged -> handleCharacteristicChanged(event)
            is GattDriverEvent.CharacteristicRead -> handleCharacteristicRead(event)
            is GattDriverEvent.CharacteristicWrite -> handleCharacteristicWrite(event)
            is GattDriverEvent.DescriptorWrite -> handleDescriptorWrite(event)
        }
    }

    private fun currentSession(event: GattDriverEvent): Session? {
        val session = registry.get(event.deviceId) ?: return null
        return session.takeIf { event.source.matches(it.gatt) }
    }

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
                failure(
                    category = "connection",
                    code = "DISCONNECT_CLEANUP_TIMEOUT",
                    phase = FailurePhase.Close,
                    retryability = Retryability.NewConnectionMayHelp,
                    context = connectionContext(session),
                ),
            )
            completeLifecycleCleanup(session, deadline.generation)
            return
        }

        val phase = when (deadline.timeoutReason) {
            TerminalCleanupReason.LinkTimeout -> FailurePhase.Link
            TerminalCleanupReason.ServiceDiscoveryTimeout -> FailurePhase.DiscoverServices
            TerminalCleanupReason.ConfigurationTimeout -> FailurePhase.Configure
            else -> FailurePhase.Close
        }
        val lifecycleFailure = failure(
            category = "connection",
            code = when (deadline.timeoutReason) {
                TerminalCleanupReason.LinkTimeout -> "LINK_TIMEOUT"
                TerminalCleanupReason.ServiceDiscoveryTimeout -> "SERVICE_DISCOVERY_TIMEOUT"
                TerminalCleanupReason.ConfigurationTimeout -> "CONFIGURATION_TIMEOUT"
                else -> "LIFECYCLE_TIMEOUT"
            },
            phase = phase,
            retryability = Retryability.NewConnectionMayHelp,
            context = connectionContext(session),
        )
        emitDiagnostic(Severity.Warning, lifecycleFailure)
        beginLifecycleCleanup(
            session = session,
            reason = deadline.timeoutReason,
            cause = lifecycleFailure,
            suppressReconnect = coordinator.attemptOrigin != ConnectionAttemptOrigin.Reconnect,
        )
    }

    /** Lifecycle failure/user intent가 scheduler와 platform handle을 한 bounded 경로로 정리한다. */
    private fun beginLifecycleCleanup(
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
        invalidateScheduledOperations(session, operationInvalidated(session))
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
                failure(
                    category = "connection",
                    code = "DISCONNECT_START_FAILED",
                    phase = FailurePhase.Close,
                    retryability = Retryability.NewConnectionMayHelp,
                    context = connectionContext(session),
                ),
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

        invalidateScheduledOperations(session, operationInvalidated(session))
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
    private fun handleConnectionStateChange(event: GattDriverEvent.ConnectionStateChanged) {
        val deviceId = event.deviceId
        val status = event.status
        val newState = event.newState
        val session = currentSession(event) ?: return

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
                FailureMapper.gattStatusFailed(
                    statusCode = it,
                    phase = FailurePhase.Link,
                    operation = AndroidApiOperation.OnConnectionStateChange,
                    context = connectionContext(session),
                )
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
                    beginLifecycleCleanup(
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
                    if (session.gattOperationScheduler.isPoisoned) {
                        session.gattOperationScheduler.beginNextGeneration()
                    }
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
    private fun handleServicesDiscovered(event: GattDriverEvent.ServicesDiscovered) {
        val deviceId = event.deviceId
        val status = event.status
        val session = currentSession(event) ?: return

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
            val failure = FailureMapper.gattStatusFailed(
                statusCode = status,
                phase = FailurePhase.DiscoverServices,
                operation = AndroidApiOperation.OnServicesDiscovered,
                context = connectionContext(session),
            )
            emitDiagnostic(Severity.Error, failure)
            beginLifecycleCleanup(
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
                FailureMapper.gattStatusFailed(
                    statusCode = status,
                    phase = FailurePhase.DiscoverServices,
                    operation = AndroidApiOperation.OnServicesDiscovered,
                    context = connectionContext(session),
                ),
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
                        failure(
                            category = "connection",
                            code = "DUPLICATE_CHARACTERISTIC_REFERENCE",
                            phase = FailurePhase.DiscoverServices,
                            retryability = Retryability.Never,
                            context = operationContext(session, kind = OperationKind.Subscribe),
                        ),
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
    private fun handleMtuChanged(event: GattDriverEvent.MtuChanged) {
        val deviceId = event.deviceId
        val mtu = event.mtu
        val status = event.status
        val session = currentSession(event) ?: return

        // MTU is only a fact for the active Configuring generation. Readiness completion
        // belongs to BleRuntime because it must wait for subscription restore, but stale MTU
        // callbacks must still be discarded at the bridge boundary.
        val callbackGeneration = event.connectionGeneration ?: session.connectionCoordinator.generation
        if (!session.connectionCoordinator.accepts(callbackGeneration, ConnectionStage.Configuring)) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitDiagnostic(
                Severity.Warning,
                FailureMapper.gattStatusFailed(
                    statusCode = status,
                    phase = FailurePhase.Configure,
                    operation = AndroidApiOperation.OnMtuChanged,
                    context = connectionContext(session),
                ),
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

    /** notify/indicate 수신 → CharValue emit. Session 갱신은 없다(구독 목록은 subscribe()가 관리). */
    private fun handleCharacteristicChanged(event: GattDriverEvent.CharacteristicChanged) {
        val session = currentSession(event) ?: return
        val characteristic = event.characteristic ?: return
        emitPayloadObservation(session, PayloadDirection.Rx, characteristic.toCharRef(), event.value)
        emit(BleEvent.CharValue(event.deviceId, characteristic.toCharRef(), event.value))
    }

    /** Read callback은 현재 generation의 동일 characteristic read만 완료한다. */
    private fun handleCharacteristicRead(event: GattDriverEvent.CharacteristicRead) {
        val session = currentSession(event) ?: return
        val scheduler = session.gattOperationScheduler
        if (scheduler.pending == null) {
            emitDuplicateCallback(
                event.deviceId,
                event.status,
                "CharacteristicRead",
                event.characteristic?.toIdentity(),
                session,
                scheduler,
            )
            return
        }
        val characteristic = event.characteristic
        if (characteristic == null) {
            emitCallbackMismatch(event.deviceId, event.status, "CharacteristicRead", null, session, scheduler)
            return
        }
        val scheduled = scheduler.takePendingIfMatches(
            generation = scheduler.currentGeneration,
            kind = GattOperationKind.Read,
            attributeIdentity = characteristic.toIdentity(),
        )
        if (scheduled == null) {
            emitCallbackMismatch(
                event.deviceId,
                event.status,
                "CharacteristicRead",
                characteristic.toIdentity(),
                session,
                scheduler,
            )
            return
        }

        @Suppress("UNCHECKED_CAST")
        val completion = scheduled.completion as CompletableDeferred<ByteArray>
        if (event.status == BluetoothGatt.GATT_SUCCESS) {
            emitPayloadObservation(session, PayloadDirection.Rx, characteristic.toCharRef(), event.value, scheduled)
            completion.complete(event.value.copyOf())
        } else {
            completion.completeExceptionally(BleException(operationStatusFailure(session, scheduled, event.status)))
        }
        startNextOperation(session)
    }

    /** 동일 generation/kind/characteristic write callback만 완료한다. */
    private fun handleCharacteristicWrite(event: GattDriverEvent.CharacteristicWrite) {
        val session = currentSession(event) ?: return
        val scheduler = session.gattOperationScheduler
        if (scheduler.pending == null) {
            emitDuplicateCallback(event.deviceId, event.status, "CharacteristicWrite", event.attributeIdentity, session, scheduler)
            return
        }
        val identity = event.attributeIdentity
        if (identity == null) {
            emitCallbackMismatch(event.deviceId, event.status, "CharacteristicWrite", null, session, scheduler)
            return
        }
        // Android callback은 characteristic-write category만 제공한다. 현재 pending의 subtype을
        // 그대로 callback kind로 복사하지 않고, category가 허용하는 두 operation kind를
        // 독립적으로 대조한다. mode는 시작 시 driver에 준 값이지 callback 사실이 아니다.
        val scheduled = scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.WriteWithResponse,
            identity,
        ) ?: scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.WriteWithoutResponse,
            identity,
        )
        if (scheduled == null) {
            emitCallbackMismatch(event.deviceId, event.status, "CharacteristicWrite", identity, session, scheduler)
            return
        }

        completeUnitOperation(session, scheduled, event.status)
        if (event.status != BluetoothGatt.GATT_SUCCESS) {
            abortRemainingWriteChunks(session, scheduled, operationStatusFailure(session, scheduled, event.status))
        }
        startNextOperation(session)
    }

    /** 동일 generation/kind/descriptor callback만 CCCD operation을 완료한다. */
    private fun handleDescriptorWrite(event: GattDriverEvent.DescriptorWrite) {
        val session = currentSession(event) ?: return
        val scheduler = session.gattOperationScheduler
        if (scheduler.pending == null) {
            emitDuplicateCallback(event.deviceId, event.status, "DescriptorWrite", event.attributeIdentity, session, scheduler)
            return
        }
        val identity = event.attributeIdentity
        if (identity == null) {
            emitCallbackMismatch(event.deviceId, event.status, "DescriptorWrite", null, session, scheduler)
            return
        }
        // Android callback은 descriptor-write category만 제공하고 subscribe 방향은 제공하지
        // 않는다. 두 방향을 callback category의 허용 집합으로 대조한다.
        val scheduled = scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.Subscribe,
            identity,
        ) ?: scheduler.takePendingIfMatches(
            scheduler.currentGeneration,
            GattOperationKind.Unsubscribe,
            identity,
        )
        if (scheduled == null) {
            emitCallbackMismatch(event.deviceId, event.status, "DescriptorWrite", identity, session, scheduler)
            return
        }

        completeUnitOperation(session, scheduled, event.status)
        startNextOperation(session)
    }

    private fun abortRemainingWriteChunks(
        session: Session,
        failed: ScheduledGattOperation<*>,
        failure: BleFailure,
    ) {
        val operation = failed.operation as? QueuedWriteChunk ?: return
        operation.commandMembers
            .drop(operation.commandIndex + 1)
            .forEach { queued ->
                if (session.gattOperationScheduler.removeQueued(queued)) {
                    queued.completion.completeExceptionally(BleException(failureFor(session, queued, failure)))
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun completeUnitOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        status: Int,
    ) {
        val completion = scheduled.completion as CompletableDeferred<Unit>
        if (status == BluetoothGatt.GATT_SUCCESS) {
            completion.complete(Unit)
        } else {
            completion.completeExceptionally(BleException(operationStatusFailure(session, scheduled, status)))
        }
    }

    private fun emitCallbackMismatch(
        deviceId: DeviceId,
        status: Int,
        callbackKind: String,
        actualIdentity: GattAttributeIdentity?,
        session: Session,
        scheduler: GattOperationScheduler,
    ) {
        val expected = scheduler.pending?.operation
        emitDiagnostic(
            Severity.Warning,
            callbackDiagnosticFailure(
                code = "GATT_OPERATION_CALLBACK_MISMATCH",
                deviceId = deviceId,
                status = status,
                callbackKind = callbackKind,
                session = session,
                scheduler = scheduler,
            ),
        )
    }

    private fun emitDuplicateCallback(
        deviceId: DeviceId,
        status: Int,
        callbackKind: String,
        actualIdentity: GattAttributeIdentity?,
        session: Session,
        scheduler: GattOperationScheduler,
    ) {
        emitDiagnostic(
            Severity.Warning,
            callbackDiagnosticFailure(
                code = "GATT_OPERATION_DUPLICATE_CALLBACK",
                deviceId = deviceId,
                status = status,
                callbackKind = callbackKind,
                session = session,
                scheduler = scheduler,
            ),
        )
    }

    // ── 변환 유틸 ────────────────────────────────────────────────────────────

    private fun GattCharacteristicSnapshot.toCharRef(): CharRef = CharRef(
        serviceUuid = serviceUuid,
        charUuid = charUuid,
        instanceIndex = instanceIndex,
        serviceInstanceIndex = serviceInstanceIndex,
    )

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

    /** [WriteType.Auto] 를 characteristic 의 실제 properties 로 해석해 플랫폼 write 타입 상수로 바꾼다. */
    private fun writeTypeInt(characteristic: BluetoothGattCharacteristic, writeType: WriteType): Int =
        writeTypeInt(characteristic.properties, writeType)

    private fun writeTypeInt(propertyMask: Int, writeType: WriteType): Int =
        when (writeType) {
            WriteType.WithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteType.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            WriteType.Auto ->
                if (propertyMask and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
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
        val session = registry.get(deviceId) ?: throw BleException(unknownDevice(deviceId))
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
        invalidateScheduledOperations(session, operationInvalidated(session))
        session.gattOperationScheduler.beginNextGeneration()

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
                    invalidateScheduledOperations(session, operationInvalidated(session))
                    emit(BleEvent.ConnStateChanged(deviceId, session.state))
                }
                throw e
            }
            // 스캔 동안 disconnect()/sweep/close() 가 generation 을 끝냈으면 여기서 멈춘다 — 그쪽이 이미 정리했다.
            if (!session.connectionCoordinator.accepts(connectionGeneration, ConnectionStage.Connecting)) {
                // review MEDIUM 1 — 재발견 중 disconnect() 는 gatt 가 아직 없어 beginLifecycleCleanup 의
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
                    val failure = peripheralNotFound(session)
                    session.suppressReconnect = true
                    session.terminalLifecycleCause = failure
                    session.prepareDisconnect(DisconnectReason.Failed(failure))
                    session.connectionCoordinator.completeCleanup(
                        connectionGeneration,
                        TerminalCleanupReason.LinkStartRejected,
                    )
                    invalidateScheduledOperations(session, operationInvalidated(session))
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
            invalidateScheduledOperations(session, operationInvalidated(session))
            throw e
        } catch (_: SecurityException) {
            val failure = FailureMapper.permissionDenied(
                phase = FailurePhase.Connect,
                operation = AndroidApiOperation.ConnectGatt,
                context = connectionContext(session),
            )
            session.suppressReconnect = true
            session.terminalLifecycleCause = failure
            session.prepareDisconnect(DisconnectReason.Failed(failure))
            session.connectionCoordinator.completeCleanup(
                connectionGeneration,
                TerminalCleanupReason.PermissionDenied,
            )
            invalidateScheduledOperations(session, operationInvalidated(session))
            throw BleException(failure)
        } catch (_: Exception) {
            val failure = FailureMapper.gattStartRejected(
                phase = FailurePhase.Connect,
                operation = AndroidApiOperation.ConnectGatt,
                context = connectionContext(session),
            )
            session.suppressReconnect = true
            session.terminalLifecycleCause = failure
            session.prepareDisconnect(DisconnectReason.Failed(failure))
            session.connectionCoordinator.completeCleanup(
                connectionGeneration,
                TerminalCleanupReason.LinkStartRejected,
            )
            invalidateScheduledOperations(session, operationInvalidated(session))
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
        val started = beginLifecycleCleanup(
            session = session,
            reason = TerminalCleanupReason.UserRequested,
            cause = null,
            suppressReconnect = true,
        )
        if (started || session.connectionCoordinator.stage != ConnectionStage.Disconnected) return
        invalidateScheduledOperations(session, operationInvalidated(session))
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
            if (driver.discoverServices(deviceId, session.gatt)) null else FailureMapper.gattStartRejected(
                phase = FailurePhase.DiscoverServices,
                operation = AndroidApiOperation.DiscoverServices,
                context = connectionContext(session),
            )
        } catch (e: BleException) {
            e.failure
        } catch (_: SecurityException) {
            FailureMapper.permissionDenied(
                phase = FailurePhase.DiscoverServices,
                operation = AndroidApiOperation.DiscoverServices,
                context = connectionContext(session),
            )
        }
        if (failure != null) {
            emitDiagnostic(Severity.Error, failure)
            beginLifecycleCleanup(
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
            if (driver.requestMtu(deviceId, session.gatt, mtu)) null else FailureMapper.gattStartRejected(
                phase = FailurePhase.Configure,
                operation = AndroidApiOperation.RequestMtu,
                context = connectionContext(session),
            )
        } catch (e: BleException) {
            e.failure
        } catch (_: SecurityException) {
            FailureMapper.permissionDenied(
                phase = FailurePhase.Configure,
                operation = AndroidApiOperation.RequestMtu,
                context = connectionContext(session),
            )
        }
        if (failure != null) {
            emitDiagnostic(Severity.Warning, failure)
            if (failure.code.value == "PERMISSION_DENIED") {
                beginLifecycleCleanup(
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

    /**
     * characteristic read를 common scheduler FIFO에 예약하고, platform start 뒤부터
     * [ackTimeoutMillis] 동안 정확히 일치하는 read callback을 기다린다.
     */
    suspend fun read(deviceId: DeviceId, charRef: CharRef): ByteArray {
        val session = registry.get(deviceId) ?: throw BleException(unknownDevice(deviceId))
        if (!session.canStartPublicGattOperation()) throw BleException(notConnected(session))
        val scheduler = session.gattOperationScheduler
        val scheduled = scheduler.enqueue(
            QueuedRead(
                generation = scheduler.currentGeneration,
                charRef = charRef,
            ),
        )
        startNextOperation(session)
        return awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    /**
     * notify/indicate 구독. CCCD write 의 `onDescriptorWrite` ack 를 기다린 뒤에만
     * 반환한다(plan §접근 ③, review #18) — ack 전에 반환하면 기기가 아직 통지를
     * 시작하지 않았을 수 있는데도 호출자는 구독이 끝났다고 믿게 된다.
     *
     * write와 같은 [GattOperationScheduler] FIFO에 들어간다. 플랫폼 호출 직전에 resolve해
     * queue 대기 중 서비스 재탐색으로 바뀐 characteristic handle도 따라간다.
     *
     * 반환값은 없다 — 실패는 전부 BleException으로 throw 한다. subscribe
     * (켜는 방향)는 실패가 소비자에게 보여야 한다 — 못 켰는데 조용하면 notify 를 기다리다
     * 하염없다. [unsubscribe](끄는 방향, D8 best-effort)와 비대칭인 이유가 이것이다.
     */
    suspend fun subscribe(deviceId: DeviceId, charRef: CharRef) {
        subscribe(deviceId, charRef, restore = false)
    }

    /** Internal lifecycle path. It shares the FIFO but its timeout may recover via reconnect. */
    suspend fun restoreSubscription(deviceId: DeviceId, charRef: CharRef) {
        subscribe(deviceId, charRef, restore = true)
    }

    private suspend fun subscribe(deviceId: DeviceId, charRef: CharRef, restore: Boolean) {
        val session = registry.get(deviceId) ?: throw BleException(unknownDevice(deviceId))
        val characteristicIdentity = when {
            operationStarter != null -> GattCharacteristicIdentity.from(charRef)
            operationTargetResolver != null -> resolveOperationTarget(session, charRef)?.identity
                ?: throw BleException(characteristicNotFound(session, charRef, OperationKind.Subscribe))
            else -> {
                val characteristic = session.resolve(charRef)
                    ?: throw BleException(characteristicNotFound(session, charRef, OperationKind.Subscribe))
                exactIdentity(session, characteristic, charRef)
            }
        }
        val identity = GattDescriptorIdentity(
            characteristic = characteristicIdentity,
            descriptorUuid = CLIENT_CHARACTERISTIC_CONFIG_UUID,
        )
        val scheduled = session.gattOperationScheduler.enqueue(
            QueuedCccdWrite(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
                enabled = true,
                restore = restore,
                descriptorIdentity = identity,
            ),
        )
        startNextOperation(session)
        awaitAfterStart(session, scheduled, ackTimeoutMillis)
    }

    /**
     * 구독 해제 — D8 best-effort(plan design decisions v1, review L-1). `subscribe` 와
     * 비대칭이다: 소비자 의도는 "이벤트 그만"이고 로컬 라우팅만 꺼도 달성된다 — CCCD
     * write 실패로 throw 하면 소비자가 불필요한 재시도 상태 기계를 만든다(규칙 1 유도).
     * 그래서 이 함수는 **throw 하지 않는다** — 실패는 전부 [Diagnostic][BleEvent.Diagnostic]
     * 으로만 알린다. [Session.subscriptions] 정리는 호출자([BleRuntime.unsubscribe])
     * 가 이 함수 성패와 무관하게 항상 수행한다.
     *
     * subscribe/write와 동일한 scheduler FIFO를 사용한다.
     */
    suspend fun unsubscribe(deviceId: DeviceId, charRef: CharRef) {
        val session = registry.get(deviceId) ?: return
        val characteristicIdentity = when {
            operationStarter != null -> GattCharacteristicIdentity.from(charRef)
            operationTargetResolver != null -> resolveOperationTarget(session, charRef)?.identity
                ?: run {
                    emitUnsubscribeFailure(
                        session,
                        charRef,
                        characteristicNotFound(session, charRef, OperationKind.Unsubscribe),
                    )
                    return
                }
            else -> {
            val characteristic = session.resolve(charRef)
            if (characteristic == null) {
                emitUnsubscribeFailure(session, charRef, characteristicNotFound(session, charRef, OperationKind.Unsubscribe))
                return
            }
            if (session.gatt == null) return
            try {
                exactIdentity(session, characteristic, charRef)
            } catch (e: BleException) {
                emitUnsubscribeFailure(session, charRef, e.failure)
                return
            }
            }
        }
        val identity = GattDescriptorIdentity(
            characteristic = characteristicIdentity,
            descriptorUuid = CLIENT_CHARACTERISTIC_CONFIG_UUID,
        )
        val scheduled = session.gattOperationScheduler.enqueue(
            QueuedCccdWrite(
                generation = session.gattOperationScheduler.currentGeneration,
                charRef = charRef,
                enabled = false,
                descriptorIdentity = identity,
            ),
        )
        startNextOperation(session)

        try {
            awaitAfterStart(session, scheduled, ackTimeoutMillis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlin.coroutines.coroutineContext.ensureActive()
            emitDiagnostic(
                Severity.Warning,
                operationInvalidated(session, scheduled),
            )
        } catch (e: BleException) {
            emitUnsubscribeFailure(session, charRef, e.failure)
        }
    }

    private fun emitUnsubscribeFailure(session: Session, charRef: CharRef, failure: BleFailure) {
        emitDiagnostic(
            Severity.Warning,
            failure.copy(
                context = failure.context.copy(operationKind = OperationKind.Unsubscribe),
            ),
        )
    }

    /**
     * write 청크 하나를 실제로 내보내고 ack 를 기다린다. OperationQueue(task 3)가 write
     * 직렬화 루프 안에서 이 함수를 람다로 받아 부른다(plan §접근 ②) — GattBridge 가
     * OperationQueue 를 참조하지 않는다(워커 간 참조 금지, plan §접근 ②).
     *
     * 타임아웃은 여기서 걸지 않는다 — 청크 단위 타임아웃 정책은 호출자(OperationQueue)의
     * 몫이다. 여기서도 걸면 같은 정책이 두 곳에 흩어진다.
     */
    suspend fun writeChunk(
        session: Session,
        characteristic: BluetoothGattCharacteristic,
        charRef: CharRef,
        bytes: ByteArray,
        writeType: WriteType,
    ): Int {
        val resolvedType = if (operationStarter != null) {
            testWriteType(session, charRef, writeType)
        } else {
            writeTypeInt(characteristic, writeType)
        }
        val attributeIdentity = if (operationStarter != null) {
            GattCharacteristicIdentity.from(charRef)
        } else {
            try {
                exactIdentity(session, characteristic, charRef)
            } catch (_: BleException) {
                return BluetoothGatt.GATT_FAILURE
            }
        }
        val operation = if (resolvedType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            GattOperation.WriteWithResponse(
                generation = session.gattOperationScheduler.currentGeneration,
                attributeIdentity = attributeIdentity,
                chunk = bytes,
            )
        } else {
            GattOperation.WriteWithoutResponse(
                generation = session.gattOperationScheduler.currentGeneration,
                attributeIdentity = attributeIdentity,
                chunk = bytes,
            )
        }
        val scheduled = session.gattOperationScheduler.enqueue(operation)
        startNextOperation(session)

        return try {
            awaitScheduled(session, scheduled)
            BluetoothGatt.GATT_SUCCESS
        } catch (e: BleException) {
            (e.failure.platformDetail as? PlatformDetail.Android)?.statusCode ?: BluetoothGatt.GATT_FAILURE
        } catch (_: Exception) {
            BluetoothGatt.GATT_FAILURE
        }
    }

    /**
     * 한 public write의 모든 청크를 FIFO에 연속 예약한다. 각 청크는 실제 start 시점에
     * characteristic을 다시 resolve하고 [WriteType.Auto]를 다시 판정한다.
     */
    suspend fun writeCommand(
        session: Session,
        charRef: CharRef,
        chunks: List<ByteArray>,
        writeType: WriteType,
        timeoutMillis: Long,
    ): List<Int> {
        val scheduler = session.gattOperationScheduler
        val scheduled = chunks.mapIndexed { index, chunk ->
            scheduler.enqueue(
                QueuedWriteChunk(
                    generation = scheduler.currentGeneration,
                    charRef = charRef,
                    requestedWriteType = writeType,
                    commandIndex = index,
                    chunk = chunk,
                ),
            )
        }
        scheduled.forEach { item ->
            (item.operation as QueuedWriteChunk).commandMembers = scheduled
        }
        startNextOperation(session)

        val statuses = mutableListOf<Int>()
        for ((index, item) in scheduled.withIndex()) {
            val status = try {
                awaitAfterStart(session, item, timeoutMillis)
                BluetoothGatt.GATT_SUCCESS
            } catch (e: BleException) {
                // A status integer cannot carry callback domain/API or the admission token.
                throw GattWriteCommandFailure(index, e.failure)
            } catch (e: CancellationException) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val failure = if (e is TimeoutCancellationException) {
                    timeoutFailure(session, item)
                } else {
                    operationInvalidated(session, item)
                }
                throw GattWriteCommandFailure(index, failure)
            } catch (_: Exception) {
                throw GattWriteCommandFailure(index, operationInvalidated(session, item))
            }
            statuses += status
            if (status != BluetoothGatt.GATT_SUCCESS) break
        }
        return statuses
    }

    private suspend fun <T> awaitAfterStart(
        session: Session,
        scheduled: ScheduledGattOperation<T>,
        timeoutMillis: Long,
    ): T {
        val startTracked = scheduled.operation as? StartTrackedGattOperation
            ?: return withTimeout(timeoutMillis) { awaitScheduled(session, scheduled) }
        return try {
            select {
                startTracked.started.onAwait {
                    withTimeout(timeoutMillis) { awaitScheduled(session, scheduled) }
                }
                // Task 4 poison/lifecycle drain can complete or cancel a queued item before it
                // ever starts. Observe that terminal signal as well, otherwise waiting only on
                // `started` would strand the caller forever.
                scheduled.completion.onAwait { it }
            }
        } catch (e: TimeoutCancellationException) {
            if (session.gattOperationScheduler.removeQueued(scheduled)) {
                scheduled.completion.cancel(e)
                abortRemainingWriteChunks(session, scheduled, operationInvalidated(session, scheduled))
            } else if (session.gattOperationScheduler.pending === scheduled) {
                poisonStartedOperation(session, scheduled, e)
            }
            throw BleException(timeoutFailure(session, scheduled))
        } catch (e: CancellationException) {
            if (session.gattOperationScheduler.removeQueued(scheduled)) {
                scheduled.completion.cancel(e)
                abortRemainingWriteChunks(session, scheduled, operationInvalidated(session, scheduled))
            } else if (session.gattOperationScheduler.pending === scheduled) {
                // Platform start 뒤에는 callback operation ID를 다시 받을 방법이 없다.
                // timeout/caller cancellation이 pending의 수신자를 없애면 같은 generation을
                // 재사용하지 않고 모든 waiter와 handle을 한 번에 종결한다.
                poisonStartedOperation(session, scheduled, e)
            }
            throw e
        }
    }

    private fun poisonStartedOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        failure: CancellationException,
    ) {
        val scheduler = session.gattOperationScheduler
        if (scheduler.isPoisoned || scheduler.pending !== scheduled) return

        val drained = scheduler.poisonCurrentGeneration()
        drained.pending?.completion?.completeExceptionally(failure)
        drained.queued.forEach { queued ->
            queued.completion.completeExceptionally(BleException(operationInvalidated(session, queued)))
        }

        val connectionGeneration = session.connectionCoordinator.generation
        val invalidationCause = if (failure is TimeoutCancellationException) {
            timeoutFailure(session, scheduled)
        } else {
            operationInvalidated(session, scheduled)
        }
        session.suppressReconnect = (scheduled.operation as? QueuedCccdWrite)?.restore != true
        session.terminalLifecycleCause = invalidationCause
        session.prepareDisconnect(DisconnectReason.Failed(invalidationCause))
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

        emitDiagnostic(Severity.Warning, invalidationCause)
        emit(BleEvent.ConnStateChanged(session.deviceId, session.state))
    }

    /** Lifecycle 종료가 pending/queued waiter를 callback 없이 유한 종결하는 단일 경로. */
    private fun invalidateScheduledOperations(session: Session, failure: BleFailure) {
        val scheduler = session.gattOperationScheduler
        val drained = if (scheduler.isPoisoned) scheduler.drain() else scheduler.poisonCurrentGeneration()
        drained.pending?.let { pending ->
            pending.completion.completeExceptionally(BleException(failureFor(session, pending, failure)))
        }
        drained.queued.forEach { queued ->
            queued.completion.completeExceptionally(BleException(failureFor(session, queued, failure)))
        }
    }

    private suspend fun <T> awaitScheduled(
        session: Session,
        scheduled: ScheduledGattOperation<T>,
    ): T = try {
        scheduled.completion.await()
    } catch (e: kotlinx.coroutines.CancellationException) {
        val scheduler = session.gattOperationScheduler
        if (scheduler.removeQueued(scheduled)) {
            scheduled.completion.cancel(e)
        }
        throw e
    }

    /** pending이 없을 때 FIFO head 하나를 플랫폼에 시작한다. 시작 실패는 즉시 종결한다. */
    private fun startNextOperation(session: Session) {
        val scheduler = session.gattOperationScheduler
        while (true) {
            val scheduled = scheduler.startNext() ?: return
            val failure = try {
                if (operationStarter != null) {
                    prepareTestOperation(session, scheduled.operation)
                    operationStarter.invoke(session, scheduled)
                } else {
                    startPlatformOperation(session, scheduled)
                }
            } catch (e: BleException) {
                e.failure
            } catch (_: SecurityException) {
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Operation,
                    operation = androidOperation(scheduled.operation.kind, callback = false),
                    context = operationContext(session, kind = operationKind(scheduled.operation.kind)),
                )
            } catch (_: Exception) {
                operationStartFailure(session, scheduled.operation)
            }
            val attributedFailure = failure?.let { failureFor(session, scheduled, it) }
            (scheduled.operation as? StartTrackedGattOperation)?.started?.complete(Unit)
            if (attributedFailure == null) return

            val operation = scheduled.operation
            scheduler.takePendingIfMatches(
                generation = operation.generation,
                kind = operation.kind,
                attributeIdentity = operation.attributeIdentity,
            )
            scheduled.completion.completeExceptionally(BleException(attributedFailure))
            abortRemainingWriteChunks(session, scheduled, attributedFailure)
            if (attributedFailure.code.value == "PERMISSION_DENIED") {
                beginLifecycleCleanup(
                    session = session,
                    reason = TerminalCleanupReason.PermissionDenied,
                    cause = attributedFailure,
                    suppressReconnect = session.connectionCoordinator.attemptOrigin !=
                        ConnectionAttemptOrigin.Reconnect,
                )
                return
            }
        }
    }

    private fun prepareTestOperation(session: Session, operation: GattOperation<*>) {
        when (operation) {
            is QueuedRead -> operation.prepare(GattCharacteristicIdentity.from(operation.charRef))
            is QueuedWriteChunk -> {
                val resolved = testWriteType(session, operation.charRef, operation.requestedWriteType)
                operation.prepare(GattCharacteristicIdentity.from(operation.charRef), resolved)
            }
            is QueuedCccdWrite -> operation.prepare(
                GattDescriptorIdentity(
                    GattCharacteristicIdentity.from(operation.charRef),
                    CLIENT_CHARACTERISTIC_CONFIG_UUID,
                ),
            )
            else -> Unit
        }
    }

    private fun testWriteType(session: Session, charRef: CharRef, writeType: WriteType): Int =
        operationWriteTypeResolver?.invoke(session, charRef, writeType) ?: when (writeType) {
            WriteType.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            WriteType.WithResponse,
            WriteType.Auto,
            -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

    private fun startPlatformOperation(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
    ): BleFailure? {
        val operation = scheduled.operation
        if (operation is QueuedRead) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return characteristicNotFound(session, operation.charRef, OperationKind.Read)
            if (target.propertyMask and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                return operationStartFailure(session, operation)
            }
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return notConnected(session)
            operation.prepare(target.identity)
            return if (driver.readCharacteristic(
                    deviceId = session.deviceId,
                    gatt = gatt,
                    characteristic = target.characteristic,
                )
            ) null else operationStartFailure(session, operation)
        }
        if (operation is QueuedWriteChunk) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return characteristicNotFound(session, operation.charRef, operationKind(operation.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return notConnected(session)
            val resolvedType = writeTypeInt(target.propertyMask, operation.requestedWriteType)
            operation.prepare(target.identity, resolvedType)
            return if (driver.writeCharacteristic(
                    deviceId = session.deviceId,
                    gatt = gatt,
                    characteristic = target.characteristic,
                value = operation.chunkCopy(),
                writeType = resolvedType,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, operation.charRef, operation.chunkCopy(), scheduled)
            }
            ) null else operationStartFailure(session, operation)
        }
        if (operation is QueuedCccdWrite) {
            val target = resolveOperationTarget(session, operation.charRef)
                ?: return characteristicNotFound(session, operation.charRef, operationKind(operation.kind))
            val gatt = session.gatt
            if (gatt == null && operationTargetResolver == null) return notConnected(session)
            operation.prepare(GattDescriptorIdentity(target.identity, CLIENT_CHARACTERISTIC_CONFIG_UUID))
            return startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = target.characteristic,
                charRef = operation.charRef,
                enabled = operation.enabled,
                descriptor = target.cccdDescriptor,
                propertyMask = target.propertyMask,
            )
        }
        val identity = operation.attributeIdentity as? GattCharacteristicIdentity
            ?: (operation.attributeIdentity as? GattDescriptorIdentity)?.characteristic
            ?: return operationStartFailure(session, operation)
        val charRef = identity.toCharRef()
        val characteristic = session.resolve(charRef)
            ?: return characteristicNotFound(session, charRef, operationKind(operation.kind))
        val gatt = session.gatt ?: return notConnected(session)
        val currentIdentity = try {
            exactIdentity(session, characteristic, charRef)
        } catch (e: BleException) {
            return e.failure
        }
        if (currentIdentity != identity) return characteristicNotFound(session, charRef, operationKind(operation.kind))

        return when (operation) {
            is GattOperation.WriteWithResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else operationStartFailure(session, operation)
            is GattOperation.WriteWithoutResponse -> if (driver.writeCharacteristic(
                deviceId = session.deviceId,
                gatt = gatt,
                characteristic = characteristic,
                value = operation.chunkCopy(),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ).also { started ->
                if (started) emitPayloadObservation(session, PayloadDirection.Tx, charRef, operation.chunkCopy(), scheduled)
            }) null else operationStartFailure(session, operation)
            is GattOperation.Subscribe -> startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = characteristic,
                charRef = charRef,
                enabled = true,
            )
            is GattOperation.Unsubscribe -> startCccdOperation(
                session = session,
                gatt = gatt,
                characteristic = characteristic,
                charRef = charRef,
                enabled = false,
            )
            else -> operationStartFailure(session, operation)
        }
    }

    private fun startCccdOperation(
        session: Session,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        charRef: CharRef,
        enabled: Boolean,
        descriptor: BluetoothGattDescriptor? = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID),
        propertyMask: Int = characteristic.properties,
    ): BleFailure? {
        val routingStarted = driver.setCharacteristicNotification(
            session.deviceId,
            gatt,
            characteristic,
            enabled,
        )
        if (enabled && !routingStarted) {
            return FailureMapper.gattStartRejected(
                phase = FailurePhase.Operation,
                operation = AndroidApiOperation.WriteDescriptor,
                context = operationContext(session, charRef, if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe),
            )
        }

        val resolvedDescriptor = descriptor
            ?: return characteristicNotFound(session, charRef, if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe)
        val value = when {
            !enabled -> byteArrayOf(0x00, 0x00)
            propertyMask and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> byteArrayOf(0x01, 0x00)
            else -> byteArrayOf(0x02, 0x00)
        }
        return if (driver.writeDescriptor(session.deviceId, gatt, resolvedDescriptor, value)) {
            null
        } else {
            FailureMapper.gattStartRejected(
                phase = FailurePhase.Operation,
                operation = AndroidApiOperation.WriteDescriptor,
                context = operationContext(session, charRef, if (enabled) OperationKind.Subscribe else OperationKind.Unsubscribe),
            )
        }
    }

    private fun resolveOperationTarget(session: Session, charRef: CharRef): GattOperationTarget? {
        operationTargetResolver?.let { resolver ->
            return resolver.resolve(session, charRef)?.takeIf { it.identity.toCharRef() == charRef }
        }
        val characteristic = session.resolve(charRef) ?: return null
        return GattOperationTarget(
            characteristic = characteristic,
            identity = exactIdentity(session, characteristic, charRef),
            propertyMask = characteristic.properties,
            cccdDescriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID),
        )
    }

    private fun GattCharacteristicIdentity.toCharRef(): CharRef = CharRef(
        serviceUuid = serviceUuid,
        charUuid = characteristicUuid,
        instanceIndex = characteristicInstanceIndex,
        serviceInstanceIndex = serviceInstanceIndex,
    )

    private fun exactIdentity(
        session: Session,
        characteristic: BluetoothGattCharacteristic,
        charRef: CharRef,
    ): GattCharacteristicIdentity {
        val gatt = session.gatt ?: throw BleException(notConnected(session))
        val matchingServices = gatt.services.filter { it.uuid == charRef.serviceUuid }
        val service = matchingServices.getOrNull(charRef.serviceInstanceIndex)
            ?: throw BleException(characteristicNotFound(session, charRef))
        val matchingCharacteristics = service.characteristics.filter { it.uuid == charRef.charUuid }
        if (matchingCharacteristics.getOrNull(charRef.instanceIndex) !== characteristic) {
            throw BleException(characteristicNotFound(session, charRef))
        }
        return GattCharacteristicIdentity.from(charRef, charRef.serviceInstanceIndex)
    }

    /** Close the current platform handle while keeping ownership in the existing [Session]. */
    fun close(session: Session) {
        session.connectionCoordinator.completeCleanup(
            session.connectionCoordinator.generation,
            TerminalCleanupReason.ClientClosed,
        )
        invalidateScheduledOperations(session, operationInvalidated(session))
        driver.close(session.deviceId, session.gatt)
        session.gatt = null
    }

    private fun emitDiagnostic(severity: Severity, failure: BleFailure) {
        emit(BleEvent.Diagnostic(severity = severity, failure = failure))
    }

    /** Payload is only copied if the reporter admits a logger record and then only by policy. */
    private fun emitPayloadObservation(
        session: Session,
        direction: PayloadDirection,
        characteristic: CharRef,
        payload: ByteArray,
        scheduled: ScheduledGattOperation<*>? = null,
    ) {
        val context = operationContext(
            session = session,
            charRef = characteristic,
            kind = scheduled?.operation?.kind?.let(::operationKind),
            operationId = scheduled?.operationId,
        )
        val observation = BleFailure(
            category = FailureCategory("diagnostic"),
            code = FailureCode("GATT_PAYLOAD_OBSERVED"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Never,
            context = context,
        )
        diagnosticIngress.report(Severity.Info, observation) { options ->
            DiagnosticPolicy().payloadValues(options, direction, characteristic, payload)
        }
    }

    private fun connectionContext(session: Session): FailureContext = session.lifecycleFailureContext()

    private fun operationContext(
        session: Session,
        @Suppress("UNUSED_PARAMETER") charRef: CharRef? = null,
        kind: OperationKind? = null,
        operationId: String? = null,
    ): FailureContext = connectionContext(session).copy(
        operationId = operationId,
        operationKind = kind,
    )

    private fun failure(
        category: String,
        code: String,
        phase: FailurePhase,
        retryability: Retryability,
        context: FailureContext,
        platformDetail: PlatformDetail? = null,
    ): BleFailure = BleFailure(
        category = FailureCategory(category),
        code = FailureCode(code),
        phase = phase,
        retryability = retryability,
        context = context,
        platformDetail = platformDetail,
    )

    private fun notConnected(session: Session): BleFailure = failure(
        category = "connection",
        code = "NOT_CONNECTED",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = connectionContext(session),
    )

    private fun unknownDevice(deviceId: DeviceId): BleFailure = failure(
        category = "validation",
        code = "UNKNOWN_DEVICE",
        phase = FailurePhase.Validation,
        retryability = Retryability.Never,
        context = FailureContext(),
    )

    private fun peripheralNotFound(session: Session): BleFailure = failure(
        category = "connection",
        code = "PERIPHERAL_NOT_FOUND",
        phase = FailurePhase.Connect,
        retryability = Retryability.TransientMayHelp,
        context = connectionContext(session),
    )

    private fun characteristicNotFound(session: Session, charRef: CharRef, kind: OperationKind? = null): BleFailure =
        failure(
            category = "validation",
            code = "ATTRIBUTE_NOT_FOUND",
            phase = FailurePhase.Operation,
            retryability = Retryability.Never,
            context = operationContext(session, charRef, kind),
        )

    private fun operationKind(kind: GattOperationKind): OperationKind = when (kind) {
        GattOperationKind.Read -> OperationKind.Read
        GattOperationKind.WriteWithResponse -> OperationKind.WriteWithResponse
        GattOperationKind.WriteWithoutResponse -> OperationKind.WriteWithoutResponse
        GattOperationKind.Subscribe -> OperationKind.Subscribe
        GattOperationKind.Unsubscribe -> OperationKind.Unsubscribe
        GattOperationKind.RequestMtu -> OperationKind.RequestMtu
    }

    private fun androidOperation(kind: GattOperationKind, callback: Boolean): AndroidApiOperation = when (kind) {
        GattOperationKind.Read -> if (callback) AndroidApiOperation.OnCharacteristicRead else AndroidApiOperation.ReadCharacteristic
        GattOperationKind.WriteWithResponse,
        GattOperationKind.WriteWithoutResponse,
        -> if (callback) AndroidApiOperation.OnCharacteristicWrite else AndroidApiOperation.WriteCharacteristic
        GattOperationKind.Subscribe,
        GattOperationKind.Unsubscribe,
        -> if (callback) AndroidApiOperation.OnDescriptorWrite else AndroidApiOperation.WriteDescriptor
        GattOperationKind.RequestMtu -> if (callback) AndroidApiOperation.OnMtuChanged else AndroidApiOperation.RequestMtu
    }

    private fun operationStatusFailure(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        status: Int,
    ): BleFailure =
        FailureMapper.gattStatusFailed(
            statusCode = status,
            phase = FailurePhase.Operation,
            operation = androidOperation(scheduled.operation.kind, callback = true),
            context = operationContext(
                session = session,
                charRef = (scheduled.operation.attributeIdentity as? GattCharacteristicIdentity)?.toCharRef()
                    ?: (scheduled.operation.attributeIdentity as? GattDescriptorIdentity)?.characteristic?.toCharRef(),
                kind = operationKind(scheduled.operation.kind),
                operationId = scheduled.operationId,
            ),
        )

    private fun operationStartFailure(session: Session, operation: GattOperation<*>): BleFailure =
        FailureMapper.gattStartRejected(
            phase = FailurePhase.Operation,
            operation = androidOperation(operation.kind, callback = false),
            context = operationContext(
                session = session,
                charRef = (operation.attributeIdentity as? GattCharacteristicIdentity)?.toCharRef()
                    ?: (operation.attributeIdentity as? GattDescriptorIdentity)?.characteristic?.toCharRef(),
                kind = operationKind(operation.kind),
            ),
        )

    private fun operationInvalidated(session: Session, operation: GattOperation<*>? = null): BleFailure = failure(
        category = "operation",
        code = "OPERATION_INVALIDATED",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = operationContext(
            session,
            (operation?.attributeIdentity as? GattCharacteristicIdentity)?.toCharRef()
                ?: (operation?.attributeIdentity as? GattDescriptorIdentity)?.characteristic?.toCharRef(),
            operation?.kind?.let(::operationKind),
        ),
    )

    private fun operationInvalidated(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
    ): BleFailure = operationInvalidated(session, scheduled.operation).let { failureFor(session, scheduled, it) }

    private fun timeoutFailure(session: Session, operation: GattOperation<*>): BleFailure = failure(
        category = "operation",
        code = "OPERATION_TIMEOUT",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = operationContext(
            session,
            (operation.attributeIdentity as? GattCharacteristicIdentity)?.toCharRef()
                ?: (operation.attributeIdentity as? GattDescriptorIdentity)?.characteristic?.toCharRef(),
            operationKind(operation.kind),
        ),
    )

    private fun timeoutFailure(session: Session, scheduled: ScheduledGattOperation<*>): BleFailure =
        timeoutFailure(session, scheduled.operation).let { failureFor(session, scheduled, it) }

    /** Adds only the admission snapshot; callback mismatches intentionally never call this. */
    private fun failureFor(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        failure: BleFailure,
    ): BleFailure = failure.copy(
        context = session.lifecycleFailureContext().copy(
            connectionGeneration = scheduled.operation.generation,
            operationId = scheduled.operationId,
            operationKind = operationKind(scheduled.operation.kind),
            invalidationReason = failure.context.invalidationReason,
        ),
    )

    private fun callbackDiagnosticFailure(
        code: String,
        deviceId: DeviceId,
        status: Int,
        callbackKind: String,
        session: Session,
        scheduler: GattOperationScheduler,
    ): BleFailure = failure(
        category = "operation",
        code = code,
        phase = FailurePhase.Operation,
        retryability = Retryability.Unknown,
        // A late or mismatched callback is not evidence about the pending operation.  Preserve
        // session/generation only; assigning its operation ID would misattribute the callback.
        context = session.lifecycleFailureContext().copy(
            connectionGeneration = scheduler.currentGeneration,
        ),
        platformDetail = PlatformDetail.Android(
            domain = "android.bluetooth.gatt.callback",
            statusCode = status,
            apiOperation = callbackKind,
        ),
    )

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
         * 플랫폼 status 가 없는 합성 코드(시작 호출 자체가 실패). 설계 §5 "platformCode 원본
         * 보존" 계약과 구분되도록 실제 GATT 스택 코드 범위(0~255) 밖 음수를 쓴다.
         */
        const val SYNTHETIC_START_FAILURE = -1

        /** timeout/cancellation로 안전하지 않게 된 generation을 나타내는 내부 lifecycle 코드. */
        const val SYNTHETIC_OPERATION_INVALIDATION = -2

        /** lifecycle callback deadline이 만료된 합성 코드. */
        const val SYNTHETIC_LIFECYCLE_TIMEOUT = -3

        /** CCCD(00002902-...) 표준 UUID. BLE 스펙 고정값 — 기기마다 다르지 않다. */
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * MTU 협상/요청 실패 시 폴백 값 — BLE 표준 기본 ATT_MTU.
         * [DEFAULT_CHUNK_SIZE] 가 근거로 삼는 것과 같은 값(23)이다.
         */
        const val DEFAULT_MTU = 23
    }
}
