package kr.co.nucode.ble.internal.core.gatt

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.ArmDeadline
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.CancelDeadline
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.Complete
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.Diagnostic
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.JudgeLater
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.PermissionLost
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.Poison
import kr.co.nucode.ble.internal.core.gatt.GattQueueEffect.Start
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent.Direct

/**
 * GATT 큐의 순수 상태 기계: `(상태, 사건) → (새 상태, 할 일 목록)`.
 *
 * 접수·차례·콜백 짝·작업 기한·연결 종료 무효화(`contract/states.md` §8·§10)를 정한다. 시간·입출력·스레드·코루틴을 쓰지 않는다.
 * 호출자 취소 사건은 없다 — 호출자는 완료만 기다리고 큐는 호출자와 무관하게 끝까지 간다(§10 떼어 내기).
 *
 * 우편함 규칙(기한 만료와 이미 도착한 콜백의 순서):
 * 1. 기한 타이머 사건 [GattQueueEvent.DeadlineFired] 는 판정하지 않고 [JudgeLater] 만 낸다(상태 그대로).
 * 2. Runtime 은 [JudgeLater] 를 우편함 끝에 [GattQueueEvent.DeadlineJudged] 로 넣는다.
 * 3. 그 사이 먼저 들어온 완료 콜백은 판정보다 먼저 step 되어 작업을 끝낸다. 판정 때 그 작업이 pending 이 아니면 무시한다.
 * 4. 판정 때 같은 작업이 아직 pending 이면 그것은 `OPERATION_TIMEOUT`, 대기 작업은 `OPERATION_INVALIDATED`, 큐는 poison, 마지막에 [Poison].
 */
internal object GattQueueCore {
    private const val GATT_SUCCESS = 0

    fun step(state: GattQueueState, event: GattQueueEvent): GattQueueStep = when (event) {
        is GattQueueEvent.Submit -> submit(state, event)
        is GattQueueEvent.StartFinished -> startFinished(state, event)
        is GattQueueEvent.Responded -> responded(state, event)
        is GattQueueEvent.DeadlineFired -> deadlineFired(state, event)
        is GattQueueEvent.DeadlineJudged -> deadlineJudged(state, event)
        is GattQueueEvent.Invalidate -> invalidate(state, event)
        GattQueueEvent.BeginGenerationIfPoisoned ->
            if (state.poisoned) direct(state, Direct.BeginNextGeneration) else quiet(state)
        is Direct -> direct(state, event)
    }

    fun isIdle(state: GattQueueState): Boolean = state.pending == null && state.waiting.isEmpty()

    fun pending(state: GattQueueState): GattQueueEntry? = state.pending

    /** Android 콜백에는 작업 id 가 없어 접수 시점 snapshot 으로 만든다. */
    fun operationId(entry: GattQueueEntry): String = GattQueueFailures.operationId(entry.generation, entry.sequence)

    // ── 접수·차례 ─────────────────────────────────────────────────────────────

    private fun quiet(state: GattQueueState) = GattQueueStep(state, emptyList())

    private fun enqueue(state: GattQueueState, spec: GattQueueSpec, command: Long?, index: Int): GattQueueState {
        check(!state.poisoned) { "poison된 generation에는 operation을 enqueue할 수 없다" }
        check(spec.generation == state.generation) {
            "operation generation(${spec.generation})이 current generation(${state.generation})과 다르다"
        }
        check(state.nextSequence != Long.MAX_VALUE) { "GATT operation sequence가 소진됐다" }
        val entry = GattQueueEntry(
            sequence = state.nextSequence,
            generation = spec.generation,
            kind = spec.kind,
            identity = spec.identity,
            command = command,
            commandIndex = index,
            tracked = spec.tracked,
            timeoutMillis = spec.timeoutMillis,
            platformStarted = false,
        )
        return state.copy(nextSequence = state.nextSequence + 1, waiting = state.waiting + entry)
    }

    /** pending 이 없고 poison 이 아니면 대기열 머리 하나를 pending 으로 올리고 [Start] 를 낸다. */
    private fun startHead(state: GattQueueState): GattQueueStep {
        val head = state.waiting.firstOrNull()
        if (state.poisoned || state.pending != null || head == null) return quiet(state)
        return GattQueueStep(state.copy(pending = head, waiting = state.waiting.drop(1)), listOf(Start(head.sequence)))
    }

    private fun submit(state: GattQueueState, event: GattQueueEvent.Submit): GattQueueStep {
        val firstSequence = state.nextSequence
        val queued = event.specs.foldIndexed(state) { index, acc, spec ->
            enqueue(acc, spec, command = if (event.asCommand) firstSequence else null, index = index)
        }
        return startHead(queued)
    }

    // ── 플랫폼 시작 결과 ──────────────────────────────────────────────────────

    private fun startFinished(state: GattQueueState, event: GattQueueEvent.StartFinished): GattQueueStep {
        val pending = state.pending
        if (state.poisoned || pending == null || pending.sequence != event.sequence || pending.platformStarted) return quiet(state)
        val resolved = pending.copy(kind = event.kind, identity = event.identity)
        val rejected = event.failure
        if (rejected == null) {
            val started = resolved.copy(platformStarted = true)
            if (!started.tracked) return quiet(state.copy(pending = started))
            val token = state.nextDeadlineToken
            val millis = started.timeoutMillis ?: state.defaultTimeoutMillis
            return GattQueueStep(
                state.copy(pending = started, deadlineToken = token, nextDeadlineToken = token + 1),
                listOf(ArmDeadline(token, started.sequence, millis)),
            )
        }

        val failure = GattQueueFailures.attribute(event.context, resolved, rejected)
        val aborted = abortRemainingChunks(state.waiting, resolved, failure, event.context)
        val after = state.copy(pending = null, waiting = aborted.remaining, deadlineToken = null, judging = false)
        val done = listOf<GattQueueEffect>(Complete(resolved.sequence, GattQueueOutcome.Failed(failure))) + aborted.effects
        if (failure.code.value == "PERMISSION_DENIED") {
            // 권한 상실은 세션 자원 실패다 — 다음 작업을 시작하지 않는다.
            return GattQueueStep(after, done + Diagnostic(Severity.Error, failure) + PermissionLost(failure))
        }
        val next = startHead(after)
        return GattQueueStep(next.state, done + next.effects)
    }

    private class Aborted(val remaining: List<GattQueueEntry>, val effects: List<GattQueueEffect>)

    /** 실패한 write 조각과 같은 호출의 남은 조각은 플랫폼에 나가지 않고 같은 실패로 끝난다. */
    private fun abortRemainingChunks(
        waiting: List<GattQueueEntry>,
        failed: GattQueueEntry,
        failure: BleFailure,
        context: FailureContext,
    ): Aborted {
        val command = failed.command ?: return Aborted(waiting, emptyList())
        val (dropped, remaining) = waiting.partition { it.command == command && it.commandIndex > failed.commandIndex }
        return Aborted(
            remaining,
            dropped.map { Complete(it.sequence, GattQueueOutcome.Failed(GattQueueFailures.attribute(context, it, failure))) },
        )
    }

    // ── 콜백 짝 ───────────────────────────────────────────────────────────────

    private fun responded(state: GattQueueState, event: GattQueueEvent.Responded): GattQueueStep {
        val label = event.category.callbackKind
        val pending = state.pending
            ?: return diagnostic(state, GattQueueFailures.duplicateCallback(event.context, state.generation, event.status, label))
        val matches = event.identity != null && !state.poisoned && event.generation == state.generation &&
            pending.generation == event.generation && pending.kind in event.category.kinds &&
            pending.identity == event.identity
        if (!matches) {
            return diagnostic(state, GattQueueFailures.callbackMismatch(event.context, state.generation, event.status, label))
        }

        val cancel = state.deadlineToken?.let { listOf<GattQueueEffect>(CancelDeadline(it)) } ?: emptyList()
        val settled = state.copy(pending = null, deadlineToken = null, judging = false)
        if (event.status == GATT_SUCCESS) {
            val next = startHead(settled)
            return GattQueueStep(next.state, cancel + Complete(pending.sequence, GattQueueOutcome.Success) + next.effects)
        }
        val failure = GattQueueFailures.statusFailed(event.context, pending, event.status)
        val aborted = if (event.category == GattResponseCategory.CharacteristicWrite) {
            abortRemainingChunks(settled.waiting, pending, failure, event.context)
        } else {
            Aborted(settled.waiting, emptyList())
        }
        val next = startHead(settled.copy(waiting = aborted.remaining))
        return GattQueueStep(
            next.state,
            cancel + Complete(pending.sequence, GattQueueOutcome.Failed(failure)) + aborted.effects + next.effects,
        )
    }

    private fun diagnostic(state: GattQueueState, failure: BleFailure) =
        GattQueueStep(state, listOf(Diagnostic(Severity.Warning, failure)))

    // ── 작업 기한 (우편함 규칙 1~4) ────────────────────────────────────────────

    private fun deadlineFired(state: GattQueueState, event: GattQueueEvent.DeadlineFired): GattQueueStep {
        val pending = state.pending
        if (state.poisoned || state.judging || pending == null || state.deadlineToken != event.token) return quiet(state)
        return GattQueueStep(state.copy(judging = true), listOf(JudgeLater(pending.sequence)))
    }

    private fun deadlineJudged(state: GattQueueState, event: GattQueueEvent.DeadlineJudged): GattQueueStep {
        val pending = state.pending
        if (state.poisoned || !state.judging || pending == null || pending.sequence != event.sequence) return quiet(state)
        val timeout = GattQueueFailures.timeoutFor(event.context, pending)
        return GattQueueStep(
            state.copy(poisoned = true, judging = false, pending = null, waiting = emptyList(), deadlineToken = null),
            timedOutCompletions(state, pending, timeout, event.context) + Poison(timeout),
        )
    }

    private fun timedOutCompletions(
        state: GattQueueState,
        pending: GattQueueEntry,
        timeout: BleFailure,
        context: FailureContext,
    ): List<GattQueueEffect> = listOf<GattQueueEffect>(Complete(pending.sequence, GattQueueOutcome.Failed(timeout))) +
        state.waiting.map { Complete(it.sequence, GattQueueOutcome.Failed(GattQueueFailures.invalidatedFor(context, it))) }

    // ── 연결 종료 무효화 ──────────────────────────────────────────────────────

    private fun invalidate(state: GattQueueState, event: GattQueueEvent.Invalidate): GattQueueStep {
        val judged = state.pending
        if (state.judging && judged != null) {
            // 판정 창 안의 연결 종료: 작업은 이미 기한을 넘겼다 — 시간 초과로 끝낸다. 연결은 이미 끝나는 중이라 Poison 은 내지 않는다.
            val timeout = GattQueueFailures.timeoutFor(event.context, judged)
            return GattQueueStep(
                state.copy(poisoned = true, judging = false, pending = null, waiting = emptyList(), deadlineToken = null),
                listOfNotNull(state.deadlineToken?.let { CancelDeadline(it) }) +
                    timedOutCompletions(state, judged, timeout, event.context),
            )
        }
        val cancel = state.deadlineToken?.takeIf { state.pending != null }?.let { listOf<GattQueueEffect>(CancelDeadline(it)) }
            ?: emptyList()
        val failed = listOfNotNull(state.pending) + state.waiting
        return GattQueueStep(
            state.copy(poisoned = true, judging = false, pending = null, waiting = emptyList(), deadlineToken = null),
            cancel + failed.map {
                Complete(it.sequence, GattQueueOutcome.Failed(GattQueueFailures.attribute(event.context, it, event.failure)))
            },
        )
    }

    // ── 셸 호환 입구 ──────────────────────────────────────────────────────────

    private fun direct(state: GattQueueState, event: Direct): GattQueueStep = when (event) {
        is Direct.Enqueue -> quiet(enqueue(state, event.spec, command = null, index = 0))
        Direct.StartNext -> {
            val head = state.waiting.firstOrNull()
            if (state.poisoned || state.pending != null || head == null) {
                quiet(state)
            } else {
                quiet(state.copy(pending = head, waiting = state.waiting.drop(1), judging = false))
            }
        }
        is Direct.RemoveQueued -> quiet(state.copy(waiting = state.waiting.filterNot { it.sequence == event.sequence }))
        is Direct.TakePendingIfMatches -> {
            val candidate = state.pending
            val matches = candidate != null && !state.poisoned && event.generation == state.generation &&
                candidate.generation == event.generation && candidate.kind == event.kind &&
                candidate.identity == event.identity
            quiet(if (matches) state.copy(pending = null, deadlineToken = null, judging = false) else state)
        }
        Direct.Drain -> quiet(state.copy(pending = null, waiting = emptyList(), deadlineToken = null, judging = false))
        Direct.PoisonCurrentGeneration ->
            quiet(state.copy(poisoned = true, judging = false, pending = null, waiting = emptyList(), deadlineToken = null))
        Direct.BeginNextGeneration -> {
            check(state.pending == null && state.waiting.isEmpty()) {
                "pending/queued operation을 drain하기 전에는 generation을 바꿀 수 없다"
            }
            check(state.generation != Long.MAX_VALUE) { "GATT connection generation이 소진됐다" }
            quiet(state.copy(generation = state.generation + 1, poisoned = false, judging = false))
        }
    }
}
