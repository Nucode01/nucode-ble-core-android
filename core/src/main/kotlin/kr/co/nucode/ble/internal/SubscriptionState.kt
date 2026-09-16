package kr.co.nucode.ble.internal

import java.util.Collections
import kr.co.nucode.ble.CharRef

/**
 * Session-owned subscription truth.
 *
 * Desired subscriptions survive a link generation; active subscriptions describe only the
 * current generation. Callers receive snapshots, never the mutable backing collections.
 */
internal class SubscriptionState {
    private val desired = linkedSetOf<CharRef>()
    private val active = linkedSetOf<CharRef>()

    var currentGeneration: Long? = null
        private set

    val desiredSnapshot: Set<CharRef>
        get() = desired.immutableSnapshot()

    val activeSnapshot: Set<CharRef>
        get() = active.immutableSnapshot()

    fun addDesired(characteristic: CharRef): Boolean = desired.add(characteristic)

    /** Removing intent must also prevent a late completion from restoring active state. */
    fun removeDesired(characteristic: CharRef): Boolean {
        active.remove(characteristic)
        return desired.remove(characteristic)
    }

    fun containsDesired(characteristic: CharRef): Boolean = characteristic in desired

    /** A fresh connection has no active CCCD state, even if its desired intent is unchanged. */
    fun beginGeneration(generation: Long) {
        require(generation >= 0) { "generation must be non-negative" }
        currentGeneration = generation
        active.clear()
    }

    fun clearActive() {
        active.clear()
        currentGeneration = null
    }

    /** Returns false for stale callbacks and for subscriptions no longer desired. */
    fun markActive(characteristic: CharRef, generation: Long): Boolean {
        if (generation != currentGeneration || characteristic !in desired) return false
        return active.add(characteristic)
    }

}

private fun <T> Set<T>.immutableSnapshot(): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(this))
