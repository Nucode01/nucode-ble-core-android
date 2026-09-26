package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session

/** GATT 콜백 하나가 어느 connection generation 에 속하는지에 대한 판정. */
internal sealed interface Attribution {
    /** 현재 generation 의 사실이다. */
    data class Current(val generation: Long) : Attribution

    /** 종결된 세션에서 온 링크 성공 — 새 generation 을 연 뒤 그 generation 의 사실로 처리한다. */
    object Bootstrap : Attribution

    /** 어느 살아 있는 generation 에도 속하지 않는다 — 버린다. */
    data class Drop(val reason: DropReason) : Attribution
}

/**
 * GATT 콜백(lifecycle·operation 모두) 귀속의 유일한 판정처. 출처(handle) 확인과 generation 귀속을
 * 한 곳에서 한다.
 *
 * Android 콜백은 generation 을 싣지 않는다(null). null 을 "현재 generation" 으로 추정하는 것은
 * 세션이 살아 있을 때(stage != Disconnected)뿐이다. 종결된 세션에 온 null 콜백은 링크 성공
 * (STATE_CONNECTED, GATT_SUCCESS)이고 플랫폼 재연결 대기 중인 세션(awaitingPlatformRelink)일 때만
 * 새 generation 을 연다 — 그 밖의 늦은 콜백(예: 끝난 링크의 STATE_DISCONNECTED, 사용자 해제 뒤 도착한
 * STATE_CONNECTED)이 phantom generation 을 여는 것을 막는다.
 * 상태를 갖지 않는다(`object`).
 */
internal object CallbackAttribution {

    fun attribute(session: Session, event: GattDriverEvent): Attribution {
        if (!event.source.matches(session.gatt)) return Attribution.Drop(DropReason.StaleHandle)

        val coordinator = session.connectionCoordinator
        val ended = coordinator.stage == ConnectionStage.Disconnected
        val explicit = event.explicitGeneration()
        if (explicit != null) {
            return if (explicit == coordinator.generation && !ended) {
                Attribution.Current(explicit)
            } else {
                Attribution.Drop(DropReason.StaleGeneration)
            }
        }

        if (!ended) return Attribution.Current(coordinator.generation)
        val linkUp = event is GattDriverEvent.ConnectionStateChanged &&
            event.newState == LinkState.CONNECTED &&
            event.status == GattStatus.SUCCESS
        return if (linkUp && session.awaitingPlatformRelink) {
            Attribution.Bootstrap
        } else {
            Attribution.Drop(DropReason.EndedGeneration)
        }
    }

    private fun GattDriverEvent.explicitGeneration(): Long? = when (this) {
        is GattDriverEvent.ConnectionStateChanged -> connectionGeneration
        is GattDriverEvent.ServicesDiscovered -> connectionGeneration
        is GattDriverEvent.MtuChanged -> connectionGeneration
        else -> null
    }
}

/** 콜백을 버린 이유. 소비자가 이유로 분기하므로 문자열이 아니라 타입으로 둔다. */
internal enum class DropReason {
    /** 닫혔거나 교체된 이전 handle 의 콜백. */
    StaleHandle,
    /** 명시 generation 이 현재와 다르거나 이미 끝난 generation. */
    StaleGeneration,
    /** generation 없는 콜백인데 세션이 이미 끝났고 부트스트랩 대상도 아님. */
    EndedGeneration,
}
