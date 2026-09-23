package kr.co.nucode.ble.internal.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * 연결 절차 진행 hook. 상태·서비스·MTU 사실을 발행한 소유자가 발행 **직후** 부른다 —
 * `emit` 은 발행만 하고 제어를 일으키지 않는다(plan connect-sequence §구조 불변식 ①).
 */
internal interface ConnectSequenceHooks {
    /** 상태 발행 직후. 소유자의 `publishState` 도우미만 부른다(불변식 ②). */
    fun onStatePublished(deviceId: PeripheralId)

    /** `ServicesDiscovered` 발행 직후. */
    fun onServicesDiscovered(deviceId: PeripheralId)

    /** `MtuChanged` 발행 직후(협상 성공·폴백 모두). */
    fun onMtuSettled(deviceId: PeripheralId)

    /** 아무 것도 하지 않는다 — 런타임 없이 bridge/reconnector 를 만드는 테스트의 기본값. */
    object None : ConnectSequenceHooks {
        override fun onStatePublished(deviceId: PeripheralId) = Unit
        override fun onServicesDiscovered(deviceId: PeripheralId) = Unit
        override fun onMtuSettled(deviceId: PeripheralId) = Unit
    }
}

/**
 * GATT 표준 연결 절차의 호출 순서(이전에는 `BleRuntime.emit` 이 이벤트를 재해석해 구동했다).
 * ★ 상태 기계가 아니다(규칙 1) — 이벤트를 해석하지 않고 발행 시점의 세션 상태·stage 만 보고
 *   "이 절차 다음에 뭘 부르는가"를 정한다. 재시도 횟수·백오프 정책은 [Reconnector] 몫이다.
 *
 * 체인: DiscoveringServices 발행 → [discoverServices]
 *      → `ServicesDiscovered` → (Configuring 이면 상태 발행) → [requestMtu] (`Session.requestedMtu`)
 *      → `MtuChanged` → (Configuring 이면) [restoreSubscriptionsBeforeReady] → Ready 발행
 *      → Ready 발행 시 retryCount 리셋. Disconnected 발행 시 `suppressReconnect` 가 아니면 재연결 예약.
 *
 * 런타임이 닫혔으면 모든 hook 은 아무 것도 하지 않는다(닫힌 뒤 emit 이 발행을 버리던 것과 대칭).
 * BleRuntime 이 소유하고, 협력자는 함수 참조로 받는다(순환 조립 회피).
 */
internal class ConnectSequence(
    private val registry: SessionRegistry,
    private val isClosed: () -> Boolean,
    private val markWarm: (PeripheralId) -> Unit,
    private val discoverServices: (PeripheralId) -> Unit,
    private val requestMtu: (PeripheralId, Int) -> Unit,
    private val scheduleReconnect: (PeripheralId, BleFailure?) -> Unit,
    private val publishState: (Session) -> Unit,
    private val restoreSubscriptionsBeforeReady: (Session) -> Unit,
) : ConnectSequenceHooks {

    override fun onStatePublished(deviceId: PeripheralId) {
        if (isClosed()) return
        val session = registry.get(deviceId) ?: return
        when (session.stage) {
            ConnectionStage.DiscoveringServices -> {
                markWarm(deviceId)
                discoverServices(deviceId)
            }

            // 방어적 중복: BleRuntime 이 Ready 발행 전에 이미 리셋한다.
            ConnectionStage.Ready -> session.retryCount = 0

            ConnectionStage.Disconnected -> if (!session.suppressReconnect) {
                scheduleReconnect(deviceId, (session.disconnectReason as? DisconnectReason.Failed)?.failure)
            }

            else -> Unit
        }
    }

    override fun onServicesDiscovered(deviceId: PeripheralId) {
        if (isClosed()) return
        // 세션이 이미 사라졌으면(예: 그 사이 close()) 요청할 대상 자체가 없다.
        val session = registry.get(deviceId) ?: return
        if (session.connectionCoordinator.stage == ConnectionStage.Configuring) {
            publishState(session)
        }
        requestMtu(deviceId, session.requestedMtu)
    }

    override fun onMtuSettled(deviceId: PeripheralId) {
        if (isClosed()) return
        val session = registry.get(deviceId) ?: return
        if (session.connectionCoordinator.stage == ConnectionStage.Configuring) {
            restoreSubscriptionsBeforeReady(session)
        }
    }
}
