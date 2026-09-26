package kr.co.nucode.ble.internal.gatt

import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicProperty
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.GattCharacteristic
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.LinkSetup
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.StageAuthority
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason

/**
 * [GattConnectionLifecycle] 의 플랫폼 콜백 칸 — 연결 상태·서비스 탐색·MTU 콜백을 귀속 판정 뒤 Session 갱신·emit 으로 바꾼다.
 * 정리·종결은 [LinkTeardown] 에 맡긴다.
 */
internal class LinkCallbacks(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val scope: CoroutineScope,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val operations: GattOperationHooks,
    private val linkSetup: () -> LinkSetup?,
    private val authority: StageAuthority,
    private val publishState: (Session) -> Unit,
    private val emitDiagnostic: (Severity, BleFailure) -> Unit,
    private val teardown: LinkTeardown,
) {

    /**
     * 플랫폼 재연결 대기 중인 handle 에 OS 가 다시 붙였을 때(Bootstrap) 새 generation 을 연다.
     * 플랫폼 재연결 대기([Session.awaitingPlatformRelink])가 아닌 세션이면 열지 않는다 — 귀속 판정과 같은 술어다.
     */
    fun beginPlatformRelinkLifecycle(session: Session): Long? {
        if (!session.awaitingPlatformRelink) return null
        // 대기는 새 generation 시작과 함께 끝난다 — 이 generation 이 Ready 전에 실패하면 첫 연결 실패와 같은
        // 규칙으로 handle 을 닫고 Disconnected(Failed) 로 끝난다(대기로 돌아가지 않는다, review H3 결정 A).
        var generation = 0L
        session.endPlatformWait { generation = session.connectionCoordinator.beginPlatformRelink(authority, scope) }
        return generation
    }

    // ── 콜백 → Session 갱신 + emit ──────────────────────────────────────────

    /**
     * [CallbackAttribution] 판정을 lifecycle 이 쓸 generation 으로 바꾼다. Bootstrap 이면 새
     * generation 을 연 뒤 그 generation 을 돌려준다. null 이면 콜백을 버린다.
     */
    fun attributedGeneration(session: Session, event: GattDriverEvent): Long? =
        when (val attribution = CallbackAttribution.attribute(session, event)) {
            is Attribution.Current -> attribution.generation
            Attribution.Bootstrap -> beginPlatformRelinkLifecycle(session)
            is Attribution.Drop -> {
                // 끝난 generation 에 늦게 온 실패 status 는 lifecycle 을 건드리지 않지만 사실로는 남긴다
                // (late non-terminal link status 와 같은 AsyncInfo). 성공 status 는 조용히 버린다.
                if (
                    attribution.reason == DropReason.EndedGeneration &&
                    event is GattDriverEvent.ConnectionStateChanged &&
                    event.status != GattStatus.SUCCESS
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

            val cause = status.takeIf { it != GattStatus.SUCCESS }?.let {
                GattFailureFactory.linkStatusFailed(session, it)
            }
            if (status != GattStatus.SUCCESS) {
                emitDiagnostic(Severity.Error, requireNotNull(cause))
                // STATE_DISCONNECTED만 어느 active stage에서든 terminal fact다. 그 밖의
                // failure callback은 link attempt의 결과일 때만 lifecycle failure다. 같은
                // generation이어도 readiness가 진행된 뒤 늦게 온 non-terminal callback은
                // 현재 stage와 deadline을 되돌리거나 끝내면 안 된다.
                if (
                    newState != LinkState.DISCONNECTED &&
                    !coordinator.accepts(callbackGeneration, ConnectionStage.Connecting)
                ) {
                    return
                }
                val preReadyFailure = coordinator.stage != ConnectionStage.Ready &&
                    coordinator.stage != ConnectionStage.Disconnecting
                // Ready 전 실패는 첫 연결이든 플랫폼 재연결 generation 이든 handle 을 닫는다(review H3 결정 A).
                // status 0 인 Ready 전 해제도 같은 규칙이다 — 아래 성공 status 의 STATE_DISCONNECTED 가지(MED-2).
                val suppressReconnect = preReadyFailure
                val failureReason = if (preReadyFailure) {
                    TerminalCleanupReason.LinkStatusFailure
                } else {
                    TerminalCleanupReason.PlatformDisconnected
                }
                // 먼저 고정된 원인(예: 사용자 해제)이 있으면 이 실패는 Diagnostic 으로만 남는다.
                coordinator.latchIfAbsent(authority, callbackGeneration, TerminalCause(failureReason, cause))
                if (suppressReconnect) session.suppressReconnect = true
                if (newState == LinkState.DISCONNECTED) {
                    teardown.completeLifecycleCleanup(
                        session = session,
                        generation = callbackGeneration,
                        fallback = TerminalCause(failureReason, cause),
                        status = status,
                    )
                } else {
                    teardown.beginCleanup(
                        session = session,
                        reason = failureReason,
                        cause = cause,
                        suppressReconnect = suppressReconnect,
                    )
                }
                return
            }

            when (newState) {
                LinkState.CONNECTED -> {
                    val accepted = coordinator.transition(
                        authority,
                        callbackGeneration = callbackGeneration,
                        expectedStage = ConnectionStage.Connecting,
                        nextStage = ConnectionStage.DiscoveringServices,
                        scope = scope,
                        timeoutMillis = timeouts.discoveryMillis,
                        onTimeout = { teardown.onLifecycleDeadline(deviceId, it) },
                    )
                    if (!accepted) return
                    operations.beginGenerationIfPoisoned(session)
                    publishState(session)
                    linkSetup()?.onLinkUp(session, callbackGeneration)
                }

                LinkState.DISCONNECTED -> {
                    // status 0 이어도 Ready 전(Connecting..Configuring) 해제는 링크 실패다 — OS 대기로 돌아가지 않고
                    // handle 을 닫는다(결정 A, MED-2). 원인은 원인 없는 해제 매핑(LINK_FAILED)이 채운다.
                    // Disconnecting 은 사용자 해제 경로라 제외한다.
                    val stage = coordinator.stage
                    if (
                        callbackGeneration == coordinator.generation &&
                        stage != ConnectionStage.Ready &&
                        stage != ConnectionStage.Disconnecting &&
                        stage != ConnectionStage.Disconnected
                    ) {
                        session.suppressReconnect = true
                    }
                    // 고정된 원인을 지우지 않는다 — beginCleanup 이 고정한 원인이 fallback 을 이긴다.
                    teardown.completeLifecycleCleanup(
                        session = session,
                        generation = callbackGeneration,
                        fallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null),
                        status = status,
                    )
                }

                LinkState.CONNECTING -> {
                    if (!coordinator.accepts(callbackGeneration, ConnectionStage.Connecting)) return
                }

                LinkState.DISCONNECTING -> {
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
        // 규격상 GAP 은 필수 — GATT_SUCCESS 인데 서비스 0개도 status 실패와 같은 경로로 종결한다(Configuring 전이 전).
        val discoveryFailure = when {
            status != GattStatus.SUCCESS -> GattFailureFactory.serviceDiscoveryStatusFailed(session, status)
            event.services.isEmpty() -> GattFailureFactory.emptyServiceDiscovery(session)
            else -> null
        }
        if (discoveryFailure != null) {
            if (
                !session.connectionCoordinator.accepts(
                    callbackGeneration,
                    ConnectionStage.DiscoveringServices,
                )
            ) return
            emitDiagnostic(Severity.Error, discoveryFailure)
            teardown.beginCleanup(
                session = session,
                reason = TerminalCleanupReason.ServiceDiscoveryStatusFailure,
                cause = discoveryFailure,
                suppressReconnect = true,
            )
            return
        }
        val accepted = session.connectionCoordinator.transition(
            authority,
            callbackGeneration = callbackGeneration,
            expectedStage = ConnectionStage.DiscoveringServices,
            nextStage = ConnectionStage.Configuring,
            scope = scope,
            timeoutMillis = readinessTimeouts.configurationMillis,
            onTimeout = { teardown.onLifecycleDeadline(deviceId, it) },
        )
        if (!accepted) return

        val discoveredMap = mutableMapOf<CharacteristicRef, PlatformCharacteristic>()
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
        linkSetup()?.onServicesDiscovered(session, callbackGeneration)
    }

    /**
     * MTU 협상 결과 → chunkSize 계산 + 세션 반영.
     *
     * 계산은 [chunkSizeForMtu] 한 곳에만 둔다 — write 경로와 여기가
     * 다른 값을 쓰면 청크가 MTU 를 넘어 조용히 잘린다.
     *
     * 실패 분기(review #4): 예전에는 Diagnostic 만 내고 return 해 Discovering 에 영구
     * 정지했다(연결 시퀀스가 MTU 확정을 기다리는데 다시 오지 않으므로). 협상 실패라도
     * 링크 자체는 살아 있고 유효 MTU 가 BLE 기본값 23 이므로, 23/20 폴백을 세션에
     * 반영한 뒤 성공 경로와 똑같이 `LinkSetup.onMtuSettled` 를 불러 구독 복구 → Ready 로
     * 진행시킨다(반영 직후 — plan link-setup).
     */
    internal fun handleMtuChanged(event: GattDriverEvent.MtuChanged) {
        val deviceId = event.deviceId
        val mtu = event.mtu
        val status = event.status
        val session = registry.get(deviceId) ?: return

        // MTU is only a fact for the active Configuring generation. Readiness completion
        // belongs to LinkSetup/completeReady because it must wait for subscription restore, but stale MTU
        // callbacks must still be discarded at the bridge boundary.
        val callbackGeneration = attributedGeneration(session, event) ?: return
        if (!session.connectionCoordinator.accepts(callbackGeneration, ConnectionStage.Configuring)) return

        if (status != GattStatus.SUCCESS) {
            emitDiagnostic(
                Severity.Warning,
                GattFailureFactory.mtuStatusFailed(session, status),
            )
            val fallbackChunk = chunkSizeForMtu(GattConnectionLifecycle.DEFAULT_MTU)
            session.mtu = GattConnectionLifecycle.DEFAULT_MTU
            session.chunkSize = fallbackChunk
            linkSetup()?.onMtuSettled(session, callbackGeneration)
            return
        }

        val chunkSize = chunkSizeForMtu(mtu)
        session.mtu = mtu
        session.chunkSize = chunkSize
        linkSetup()?.onMtuSettled(session, callbackGeneration)
        // Ready 전이·게시는 LinkSetup 의 구독 복원 뒤 completeReady 가 한다.
    }

    /**
     * `GattProperties.*` 비트마스크 → `Set[CharacteristicProperty]`.
     * 플랫폼 상수가 모델 계층(`CharacteristicProperty`, PublicModels.kt)으로 새지 않게 막는 게 이
     * 함수의 유일한 목적이다.
     */
    fun mapProperties(propertyMask: Int): Set<CharacteristicProperty> {
        val properties = mutableSetOf<CharacteristicProperty>()
        if (propertyMask and GattProperties.READ != 0) {
            properties += CharacteristicProperty.Read
        }
        if (propertyMask and GattProperties.WRITE != 0) {
            properties += CharacteristicProperty.WriteWithResponse
        }
        if (propertyMask and GattProperties.WRITE_NO_RESPONSE != 0) {
            properties += CharacteristicProperty.WriteWithoutResponse
        }
        if (propertyMask and GattProperties.NOTIFY != 0) {
            properties += CharacteristicProperty.Notify
        }
        if (propertyMask and GattProperties.INDICATE != 0) {
            properties += CharacteristicProperty.Indicate
        }
        return properties
    }
}
