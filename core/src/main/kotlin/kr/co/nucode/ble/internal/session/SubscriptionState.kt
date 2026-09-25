package kr.co.nucode.ble.internal.session

import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.co.nucode.ble.CharacteristicRef

/**
 * Session-owned subscription truth.
 *
 * Desired subscriptions survive a link generation; active subscriptions describe only the
 * current generation. Callers receive snapshots, never the mutable backing collections.
 */
internal class SubscriptionState {
    private val desired = linkedSetOf<CharacteristicRef>()
    private val active = linkedSetOf<CharacteristicRef>()
    private val _activeFlow = MutableStateFlow<Set<CharacteristicRef>>(emptySet())

    /** Published copy of [activeSnapshot]; republished on every mutation of `active`. */
    val activeFlow: StateFlow<Set<CharacteristicRef>> = _activeFlow.asStateFlow()

    var currentGeneration: Long? = null
        private set

    val desiredSnapshot: Set<CharacteristicRef>
        get() = desired.immutableSnapshot()

    val activeSnapshot: Set<CharacteristicRef>
        get() = active.immutableSnapshot()

    /**
     * 테스트 seam — 의도만 심는다("의도는 있고 활성은 없음" 상태 재현). 프로덕션의 의도 추가는
     * [confirmSubscribed] 하나다.
     */
    fun addDesired(characteristic: CharacteristicRef): Boolean = desired.add(characteristic)

    /**
     * CCCD 켜기 ack 뒤 사용자 구독을 한 번에 기록한다 — 의도 추가와 [generation] 의 활성 표시.
     * 둘을 따로 부르면 하나를 빠뜨려도 컴파일된다. 반환값은 [markActive] 와 같다.
     */
    fun confirmSubscribed(characteristic: CharacteristicRef, generation: Long): Boolean {
        desired.add(characteristic)
        return markActive(characteristic, generation)
    }

    /** Removing intent must also prevent a late completion from restoring active state. */
    fun removeDesired(characteristic: CharacteristicRef): Boolean {
        if (active.remove(characteristic)) publishActive()
        return desired.remove(characteristic)
    }

    /**
     * 사용자 명시 해제 — 구독 의도까지 버린다. 이미 ack 된 subscribe 의 늦은 재개는 여기서 막지 못한다
     * ([confirmSubscribed] 가 의도를 다시 넣을 수 있다) — `BleRuntime.subscribe` 가 재개 시 시작 generation 이
     * 아직 Ready 인지 확인해 거부한다.
     */
    fun clearDesired() {
        desired.clear()
        active.clear()
        publishActive()
    }

    fun containsDesired(characteristic: CharacteristicRef): Boolean = characteristic in desired

    /** A fresh connection has no active CCCD state, even if its desired intent is unchanged. */
    fun beginGeneration(generation: Long) {
        require(generation >= 0) { "generation must be non-negative" }
        currentGeneration = generation
        active.clear()
        publishActive()
    }

    fun clearActive() {
        active.clear()
        publishActive()
        currentGeneration = null
    }

    /** Returns false for stale callbacks and for subscriptions no longer desired. */
    fun markActive(characteristic: CharacteristicRef, generation: Long): Boolean {
        if (generation != currentGeneration || characteristic !in desired) return false
        val added = active.add(characteristic)
        if (added) publishActive()
        return added
    }

    private fun publishActive() {
        _activeFlow.value = active.immutableSnapshot()
    }
}

private fun <T> Set<T>.immutableSnapshot(): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(this))
