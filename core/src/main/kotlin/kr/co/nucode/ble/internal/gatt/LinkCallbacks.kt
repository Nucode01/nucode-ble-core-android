package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicProperty
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.DescriptorRef
import kr.co.nucode.ble.GattCharacteristic
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.core.connection.ConnectionEvent
import kr.co.nucode.ble.internal.core.connection.LinkFact
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.port.GattDriverEvent
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [GattConnectionLifecycle] 의 플랫폼 콜백 칸 — 연결 상태·서비스 탐색·MTU 콜백을 귀속 판정 뒤
 * 플랫폼 사실(실패 값·링크 사실)로 바꿔 Core 사건으로 넣는다. 어느 단계로 가고 어느 원인을 고정하는지는 Core
 * ([kr.co.nucode.ble.internal.core.connection.ConnectionLink])가 정하고, 할 일의 실행은 [LinkEffects] 가 한다.
 * 서비스 목록 조립·[mapProperties] 는 플랫폼 사실 변환이라 여기에 남는다.
 */
internal class LinkCallbacks(
    private val registry: SessionRegistry,
    private val emit: (BleEvent) -> Unit,
    private val readinessTimeouts: ConnectionReadinessTimeouts,
    private val emitDiagnostic: (Severity, BleFailure) -> Unit,
    private val effects: LinkEffects,
) {

    /**
     * 플랫폼 재연결 대기 중인 handle 에 OS 가 다시 붙였을 때(Bootstrap) 새 generation 을 연다.
     * 플랫폼 재연결 대기가 아닌 세션이면 Core 가 거절하고 열지 않는다 — 귀속 판정과 같은 술어다.
     */
    fun beginPlatformRelinkLifecycle(session: Session): Long? =
        if (effects.dispatch(session, ConnectionEvent.PlatformRelinkUp)) {
            session.connectionCoordinator.generation
        } else {
            null
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

    /** 링크 상태 콜백 → 링크 사실(성공 status 면 실패 없음)을 Core 사건으로 넣는다. */
    internal fun handleConnectionStateChange(event: GattDriverEvent.ConnectionStateChanged) {
        val status = event.status
        val session = registry.get(event.deviceId) ?: return
        val callbackGeneration = attributedGeneration(session, event) ?: return

        val failure = status.takeIf { it != GattStatus.SUCCESS }?.let {
            GattFailureFactory.linkStatusFailed(session, it)
        }
        effects.dispatch(
            session,
            ConnectionEvent.LinkStateChanged(
                generation = callbackGeneration,
                link = linkFactOf(event.newState),
                status = status,
                failure = failure,
                discoveryMillis = readinessTimeouts.discoveryMillis,
                disconnectCleanupMillis = readinessTimeouts.disconnectCleanupMillis,
                atMillis = session.connectionCoordinator.nowMillis,
            ),
        )
    }

    private fun linkFactOf(newState: Int): LinkFact = when (newState) {
        LinkState.CONNECTED -> LinkFact.Up
        LinkState.DISCONNECTED -> LinkFact.Down
        LinkState.CONNECTING -> LinkFact.Connecting
        LinkState.DISCONNECTING -> LinkFact.Disconnecting
        else -> LinkFact.Unknown
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
        val accepted = effects.dispatch(
            session,
            ConnectionEvent.ServicesDiscovered(
                generation = callbackGeneration,
                failure = discoveryFailure,
                configurationMillis = readinessTimeouts.configurationMillis,
                disconnectCleanupMillis = readinessTimeouts.disconnectCleanupMillis,
            ),
        )
        if (discoveryFailure != null || !accepted) return

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
                    descriptors = descriptorRefs(charRef, scopedCharacteristic.descriptorUuids),
                )
            }
            GattService(
                ref = ServiceRef(BleUuid(service.serviceUuid), service.instanceIndex),
                characteristics = characteristics,
            )
        }

        session.replaceDiscoveredTopology(services, discoveredMap)
        emit(BleEvent.ServicesDiscovered(deviceId, services))
        effects.servicesPublished(session, callbackGeneration)
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
        effects.dispatch(
            session,
            ConnectionEvent.MtuSettled(
                generation = callbackGeneration,
                mtu = mtu,
                failure = status.takeIf { it != GattStatus.SUCCESS }?.let {
                    GattFailureFactory.mtuStatusFailed(session, it)
                },
            ),
        )
        // Ready 전이·게시는 LinkSetup 의 구독 복원 뒤 RestoreFinished 사건이 한다.
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

    /** 탐색 순서의 descriptor UUID 목록 → [DescriptorRef] 목록. instanceIndex 는 같은 UUID 사이의 순번이다. */
    private fun descriptorRefs(charRef: CharacteristicRef, uuids: List<java.util.UUID>): List<DescriptorRef> {
        val ordinals = mutableMapOf<java.util.UUID, Int>()
        return uuids.map { uuid ->
            val index = ordinals.getOrDefault(uuid, 0)
            ordinals[uuid] = index + 1
            DescriptorRef(charRef, BleUuid(uuid), index)
        }
    }
}
