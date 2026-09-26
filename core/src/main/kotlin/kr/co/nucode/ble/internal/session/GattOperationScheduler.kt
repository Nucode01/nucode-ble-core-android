package kr.co.nucode.ble.internal.session

import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
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
}

/** poison 또는 lifecycle 종료 때 pending/queue를 한 번에 유한 종결하기 위한 snapshot. */
internal data class DrainedGattOperations(
    val pending: ScheduledGattOperation<*>?,
    val queued: List<ScheduledGattOperation<*>>,
)

/**
 * Session 하나의 callback 기반 GATT operation 상태.
 *
 * 모든 메서드는 coreScope의 단일 스레드에서 호출한다. 외부 PeripheralId map이나 별도 lock을
 * 만들지 않고 [Session]과 생애주기를 같이 한다.
 */
internal class GattOperationScheduler {
    private val queue = ArrayDeque<ScheduledGattOperation<*>>()
    private var nextSequence: Long = 0L

    var currentGeneration: Long = 0L
        private set

    var isPoisoned: Boolean = false
        private set

    var pending: ScheduledGattOperation<*>? = null
        private set

    val queuedCount: Int
        get() = queue.size

    /** 현재 active generation에 FIFO 항목을 추가한다. */
    fun <T> enqueue(operation: GattOperation<T>): ScheduledGattOperation<T> {
        check(!isPoisoned) { "poison된 generation에는 operation을 enqueue할 수 없다" }
        check(operation.generation == currentGeneration) {
            "operation generation(${operation.generation})이 current generation($currentGeneration)과 다르다"
        }
        check(nextSequence != Long.MAX_VALUE) { "GATT operation sequence가 소진됐다" }

        val scheduled = ScheduledGattOperation(
            sequence = nextSequence++,
            operation = operation,
            completion = CompletableDeferred(),
        )
        queue.addLast(scheduled)
        return scheduled
    }

    /** outstanding operation이 없을 때 FIFO head 하나만 pending으로 전환한다. */
    fun startNext(): ScheduledGattOperation<*>? {
        if (isPoisoned || pending != null) return null
        return queue.pollFirst()?.also { pending = it }
    }

    /** 시작 전 cancellation용. 이미 pending이 된 항목은 이 경로로 제거할 수 없다. */
    fun removeQueued(scheduled: ScheduledGattOperation<*>): Boolean = queue.remove(scheduled)

    /**
     * generation, kind, attribute identity가 모두 맞는 callback만 pending을 꺼낸다.
     * mismatch에서는 pending을 그대로 보존해 잘못된 callback이 현재 요청을 완료하지 못하게 한다.
     */
    fun takePendingIfMatches(
        generation: Long,
        kind: GattOperationKind,
        attributeIdentity: GattAttributeIdentity?,
    ): ScheduledGattOperation<*>? {
        if (isPoisoned || generation != currentGeneration) return null
        val candidate = pending ?: return null
        val operation = candidate.operation
        if (operation.generation != generation) return null
        if (operation.kind != kind) return null
        if (operation.attributeIdentity != attributeIdentity) return null

        pending = null
        return candidate
    }

    /** pending과 queue를 비우되 현재 generation 자체는 유지한다. */
    fun drain(): DrainedGattOperations {
        val drained = DrainedGattOperations(
            pending = pending,
            queued = queue.toList(),
        )
        pending = null
        queue.clear()
        return drained
    }

    /** 현재 generation을 재사용 불가로 표시하고 모든 operation을 회수한다. */
    fun poisonCurrentGeneration(): DrainedGattOperations {
        isPoisoned = true
        return drain()
    }

    /** 이전 generation의 operation이 모두 회수된 뒤 새 active generation을 시작한다. */
    fun beginNextGeneration(): Long {
        check(pending == null && queue.isEmpty()) {
            "pending/queued operation을 drain하기 전에는 generation을 바꿀 수 없다"
        }
        check(currentGeneration != Long.MAX_VALUE) { "GATT connection generation이 소진됐다" }

        currentGeneration += 1
        isPoisoned = false
        return currentGeneration
    }
}
