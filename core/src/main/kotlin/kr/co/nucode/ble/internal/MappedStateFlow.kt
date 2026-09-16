package kr.co.nucode.ble.internal

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * A synchronous StateFlow projection.  Unlike `stateIn`, this does not create a
 * scope, a job, or a second state owner; its value is always derived from source.
 */
internal class MappedStateFlow<S, T>(
    private val source: StateFlow<S>,
    private val map: (S) -> T,
) : StateFlow<T> {
    override val replayCache: List<T> get() = listOf(value)
    override val value: T get() = map(source.value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing =
        source.collect { collector.emit(map(it)) }
}
