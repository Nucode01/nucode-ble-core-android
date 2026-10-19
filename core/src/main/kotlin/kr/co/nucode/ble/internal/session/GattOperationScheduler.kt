package kr.co.nucode.ble.internal.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kr.co.nucode.ble.internal.core.gatt.GattQueueCore
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect
import kr.co.nucode.ble.internal.core.gatt.GattQueueEntry
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent.Direct
import kr.co.nucode.ble.internal.core.gatt.GattQueueSpec
import kr.co.nucode.ble.internal.core.gatt.GattQueueState
import kr.co.nucode.ble.internal.model.GattAttributeIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.model.GattOperationKind

/** FIFO 항목과 caller completion을 함께 보존하는 internal handle. */
internal class ScheduledGattOperation<T> internal constructor(
    val sequence: Long,
    val operation: GattOperation<T>,
    val completion: CompletableDeferred<T>,
) {
    /** Android callbacks have no application operation ID; retain this admission snapshot. */
    val operationId: String = "gatt-${operation.generation}-$sequence"

    /**
     * 플랫폼 시작 때 해석된 종류·대상을 보이는 같은 항목. 해석 결과가 접수 때와 같으면 자기 자신이다.
     * 순번·completion 은 그대로라 [ScheduledGattOperation.sequence] 로 같은 항목임을 안다.
     */
    fun resolvedAs(kind: GattOperationKind, identity: GattAttributeIdentity?): ScheduledGattOperation<T> {
        if (operation.kind == kind && operation.attributeIdentity == identity) return this
        return ScheduledGattOperation(sequence, ResolvedGattOperation(operation.generation, kind, identity), completion)
    }
}

/** 접수한 명령 데이터는 그대로 두고 해석된 종류·대상만 보이는 값. */
private class ResolvedGattOperation<out T>(
    override val generation: Long,
    override val kind: GattOperationKind,
    override val attributeIdentity: GattAttributeIdentity?,
) : GattOperation<T>

/** [GattOperationScheduler.drain] 이 pending/queue를 한 번에 회수한 snapshot. */
internal data class DrainedGattOperations(
    val pending: ScheduledGattOperation<*>?,
    val queued: List<ScheduledGattOperation<*>>,
)

/**
 * Session 하나의 callback 기반 GATT operation 상태. 큐 규칙은 [GattQueueCore] 가 정하고, 이 셸은
 * Core 상태 값 하나와 순서 없는 handle 표(순번 → completion 을 든 handle)만 든다.
 *
 * 모든 메서드는 coreScope의 단일 스레드에서 호출한다. 외부 PeripheralId map이나 별도 lock을
 * 만들지 않고 [Session]과 생애주기를 같이 한다.
 *
 * 상태 쓰기는 [apply] 안에서만 한다. [apply] 는 새 상태를 먼저 확정한 뒤 할 일을 목록 순서대로 [run] 으로 실행하므로,
 * 실행 중 다시 들어온 사건(동기 콜백 재진입 등)은 확정된 상태를 본다.
 */
internal class GattOperationScheduler(defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
    private var state = GattQueueState(defaultTimeoutMillis = defaultTimeoutMillis)
    private val handles = HashMap<Long, ScheduledGattOperation<*>>()
    private val deadlineTimers = HashMap<Long, Job>()

    val currentGeneration: Long
        get() = state.generation

    val isPoisoned: Boolean
        get() = state.poisoned || state.judging

    /** 현재 항목. 플랫폼 시작 때 해석된 종류·대상을 보인다. */
    val pending: ScheduledGattOperation<*>?
        get() = state.pending?.let(::view)

    val queuedCount: Int
        get() = state.waiting.size

    /** 상태를 한 걸음 옮기고 할 일을 순서대로 [run] 한다. [registered] 는 상태 확정 뒤 할 일 실행 전에 부른다. */
    fun apply(
        event: GattQueueEvent,
        registered: () -> Unit = {},
        run: (GattQueueEffect) -> Unit = {},
    ) {
        val step = GattQueueCore.step(state, event)
        state = step.state
        registered()
        step.effects.forEach(run)
    }

    /**
     * 한 호출의 항목 전부(write 는 조각 전부)를 접수한다. [beforeStart] 는 handle 이 표에 오른 뒤 첫 할 일 실행 전에 부른다.
     * [operations] 와 [specs] 는 순서가 같다.
     */
    fun <T> submit(
        specs: List<GattQueueSpec>,
        operations: List<GattOperation<T>>,
        asCommand: Boolean,
        beforeStart: (List<ScheduledGattOperation<T>>) -> Unit = {},
        run: (GattQueueEffect) -> Unit,
    ): List<ScheduledGattOperation<T>> {
        require(specs.size == operations.size) { "spec(${specs.size})과 operation(${operations.size}) 수가 다르다" }
        val first = state.nextSequence
        val submitted = operations.mapIndexed { index, operation ->
            ScheduledGattOperation(first + index, operation, CompletableDeferred<T>())
        }
        apply(
            GattQueueEvent.Submit(specs, asCommand),
            registered = {
                submitted.forEach { handles[it.sequence] = it }
                beforeStart(submitted)
            },
            run = run,
        )
        return submitted
    }

    /** 순번의 handle. 끝난 항목은 [release] 뒤 없다. */
    fun handleOf(sequence: Long): ScheduledGattOperation<*>? = handles[sequence]

    /** 기한 번호의 타이머를 든다. Core 가 [GattQueueEffect.CancelDeadline] 을 내면 [cancelDeadlineTimer] 가 취소한다. */
    fun holdDeadlineTimer(token: Long, timer: Job) {
        deadlineTimers[token] = timer
    }

    /** 타이머가 깨어났다 — 더 취소할 것이 없다. */
    fun releaseDeadlineTimer(token: Long) {
        deadlineTimers.remove(token)
    }

    fun cancelDeadlineTimer(token: Long) {
        deadlineTimers.remove(token)?.cancel()
    }

    /** 끝난 항목의 handle 을 표에서 꺼낸다. */
    fun release(sequence: Long): ScheduledGattOperation<*>? = handles.remove(sequence)

    // ── 옛 입구(시험 호환). 정책 없이 차례 규칙만 적용한다 ──────────────────────

    /** 현재 active generation에 FIFO 항목을 추가한다. */
    fun <T> enqueue(operation: GattOperation<T>): ScheduledGattOperation<T> {
        val sequence = state.nextSequence
        apply(
            Direct.Enqueue(GattQueueSpec(operation.generation, operation.kind, operation.attributeIdentity, tracked = false)),
        )
        return ScheduledGattOperation(sequence, operation, CompletableDeferred<T>()).also { handles[sequence] = it }
    }

    /** outstanding operation이 없을 때 FIFO head 하나만 pending으로 전환한다. */
    fun startNext(): ScheduledGattOperation<*>? {
        val before = state.pending
        apply(Direct.StartNext)
        val started = state.pending
        return if (before == null && started != null) handles[started.sequence] else null
    }

    /** pending과 queue를 비우되 현재 generation 자체는 유지한다. */
    fun drain(): DrainedGattOperations {
        val pending = state.pending
        val waiting = state.waiting
        apply(Direct.Drain)
        return DrainedGattOperations(
            pending = pending?.let { handles.remove(it.sequence) },
            queued = waiting.mapNotNull { handles.remove(it.sequence) },
        )
    }

    /** 이전 generation의 operation이 모두 회수된 뒤 새 active generation을 시작한다. */
    fun beginNextGeneration(): Long {
        apply(Direct.BeginNextGeneration)
        return state.generation
    }

    private fun view(entry: GattQueueEntry): ScheduledGattOperation<*>? =
        handles[entry.sequence]?.resolvedAs(entry.kind, entry.identity)

    private companion object {
        /** 접수 때 기한을 싣지 않은 항목의 기본 작업 기한(`BleConfig.operationTimeoutMillis` 기본값과 같다). */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
