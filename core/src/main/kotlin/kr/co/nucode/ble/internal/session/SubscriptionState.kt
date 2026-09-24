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

    fun addDesired(characteristic: CharacteristicRef): Boolean = desired.add(characteristic)

    /** Removing intent must also prevent a late completion from restoring active state. */
    fun removeDesired(characteristic: CharacteristicRef): Boolean {
        if (active.remove(characteristic)) publishActive()
        return desired.remove(characteristic)
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
