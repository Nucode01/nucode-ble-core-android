package kr.co.nucode.ble.internal.core.gatt

import java.util.UUID
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnectionInvalidationReason
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.core.gatt.GattQueueEvent.Direct
import kr.co.nucode.ble.internal.model.GattAttributeIdentity
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattDescriptorIdentity
import kr.co.nucode.ble.internal.model.GattOperationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATT 큐 Core 의 순수 `step` 표 시험. 코루틴·가짜 driver 없이 값만 넣고 값만 본다. */
class GattQueueCoreTest {

    private val context = FailureContext(sessionId = "s-1", connectionGeneration = 0L)

    private fun char(n: Int) = GattCharacteristicIdentity(
        serviceUuid = UUID(0L, 1L),
        serviceInstanceIndex = 0,
        characteristicUuid = UUID(0L, 100L + n),
        characteristicInstanceIndex = 0,
    )

    private val charA = char(1)
    private val charB = char(2)
    private fun cccd(c: GattCharacteristicIdentity) = GattDescriptorIdentity(c, UUID(0L, 0x2902L))
    private val cccdA = cccd(charA)
    private val cccdB = cccd(charB)

    private fun initial() = GattQueueState(nextSequence = 1L, defaultTimeoutMillis = 5000L)

    private fun spec(
        kind: GattOperationKind,
        identity: GattAttributeIdentity?,
        generation: Long = 0L,
        tracked: Boolean = true,
        timeoutMillis: Long? = null,
    ) = GattQueueSpec(generation, kind, identity, tracked, timeoutMillis)

    private fun readSpec(id: GattCharacteristicIdentity = charA, generation: Long = 0L) =
        spec(GattOperationKind.Read, id, generation)

    private fun chunkSpec(id: GattCharacteristicIdentity = charA, timeout: Long? = 3000L) =
        spec(GattOperationKind.WriteWithResponse, id, timeoutMillis = timeout)

    private fun submit(vararg specs: GattQueueSpec, command: Boolean = false) = GattQueueEvent.Submit(specs.toList(), command)

    private fun started(seq: Long, kind: GattOperationKind, id: GattAttributeIdentity?) =
        GattQueueEvent.StartFinished(seq, kind, id, null, context)

    private fun startedRead(seq: Long, id: GattCharacteristicIdentity = charA) = started(seq, GattOperationKind.Read, id)

    private fun startedChunk(seq: Long, id: GattCharacteristicIdentity = charA) =
        started(seq, GattOperationKind.WriteWithResponse, id)

    private fun respondRead(id: GattAttributeIdentity? = charA, status: Int = 0, generation: Long = 0L) =
        GattQueueEvent.Responded(GattResponseCategory.Read, generation, id, status, context)

    private fun respondWrite(id: GattAttributeIdentity? = charA, status: Int = 0) =
        GattQueueEvent.Responded(GattResponseCategory.CharacteristicWrite, 0L, id, status, context)

    private fun respondDescriptor(id: GattAttributeIdentity? = cccdA, status: Int = 0) =
        GattQueueEvent.Responded(GattResponseCategory.DescriptorWrite, 0L, id, status, context)

    private fun judged(seq: Long) = GattQueueEvent.DeadlineJudged(seq, context)

    private fun invalidation(): BleFailure = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode("LINK_LOST"),
        phase = FailurePhase.Link,
        retryability = Retryability.NewConnectionMayHelp,
        context = FailureContext(invalidationReason = ConnectionInvalidationReason.LinkLost),
    )

    /** 상태를 들고 사건을 차례로 넣는 시험 도우미. 할 일은 표기 문자열로 바꿔 비교한다. */
    private class Run(var state: GattQueueState) {
        fun send(event: GattQueueEvent): List<String> {
            val step = GattQueueCore.step(state, event)
            state = step.state
            return step.effects.map(::show)
        }

        fun sendAll(vararg events: GattQueueEvent): List<String> = events.flatMap { send(it) }
    }

    private companion object {
        fun show(effect: GattQueueEffect): String = when (effect) {
            is GattQueueEffect.Start -> "start(#${effect.sequence})"
            is GattQueueEffect.ArmDeadline -> "arm(t${effect.token},#${effect.sequence},${effect.millis})"
            is GattQueueEffect.CancelDeadline -> "cancel(t${effect.token})"
            is GattQueueEffect.Complete -> when (val o = effect.outcome) {
                GattQueueOutcome.Success -> "complete(#${effect.sequence},ok)"
                is GattQueueOutcome.Failed -> "complete(#${effect.sequence},${o.failure.code.value})"
            }
            is GattQueueEffect.JudgeLater -> "judgeLater(#${effect.sequence})"
            is GattQueueEffect.Poison -> "poison(${effect.failure.code.value})"
            is GattQueueEffect.PermissionLost -> "permissionLost(${effect.failure.code.value})"
            is GattQueueEffect.Diagnostic -> "diagnostic(${effect.severity},${effect.failure.code.value})"
        }
    }

    private fun failureOf(effects: List<GattQueueEffect>, sequence: Long): BleFailure =
        effects.filterIsInstance<GattQueueEffect.Complete>().first { it.sequence == sequence }
            .let { (it.outcome as GattQueueOutcome.Failed).failure }

    private fun stepOf(state: GattQueueState, event: GattQueueEvent) = GattQueueCore.step(state, event)

    // ── Q 행: iOS 계획과 같은 id ──────────────────────────────────────────────

    @Test
    fun `Q01_초기 상태는 idle 이고 할 일이 없다`() {
        val s = initial()
        assertTrue(GattQueueCore.isIdle(s))
        assertNull(GattQueueCore.pending(s))
        assertEquals(emptyList<String>(), Run(s).send(GattQueueEvent.BeginGenerationIfPoisoned))
    }

    @Test
    fun `Q02_접수한 read 는 pending 이 되어 start 되고 플랫폼이 받은 뒤 기한을 센다`() {
        val run = Run(initial())
        assertEquals(listOf("start(#1)"), run.send(submit(readSpec())))
        assertEquals(1L, run.state.pending?.sequence)
        assertEquals(listOf("arm(t1,#1,5000)"), run.send(startedRead(1)))
        assertEquals(1L, run.state.deadlineToken)
    }

    @Test
    fun `Q03_차례는 FIFO 이고 완료 step 이 다음을 바로 시작한다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA)), startedRead(1))
        assertEquals(emptyList<String>(), run.send(submit(readSpec(charB))))
        assertEquals(emptyList<String>(), run.send(submit(readSpec(char(3)))))
        assertEquals(listOf(2L, 3L), run.state.waiting.map { it.sequence })
        assertEquals(
            listOf("cancel(t1)", "complete(#1,ok)", "start(#2)"),
            run.send(respondRead(charA)),
        )
        assertEquals(listOf("arm(t2,#2,5000)"), run.send(startedRead(2, charB)))
        assertEquals(listOf("cancel(t2)", "complete(#2,ok)", "start(#3)"), run.send(respondRead(charB)))
    }

    @Test
    fun `Q04_read 짝 - 성공, 실패 status, 다른 대상, 끝난 뒤 콜백`() {
        fun ready() = Run(initial()).also { it.sendAll(submit(readSpec()), startedRead(1)) }

        assertEquals(listOf("cancel(t1)", "complete(#1,ok)"), ready().send(respondRead(charA, 0)))
        assertEquals(listOf("cancel(t1)", "complete(#1,GATT_STATUS_FAILED)"), ready().send(respondRead(charA, 133)))

        val mismatch = ready()
        val before = mismatch.state
        assertEquals(listOf("diagnostic(Warning,GATT_OPERATION_CALLBACK_MISMATCH)"), mismatch.send(respondRead(charB)))
        assertEquals(before, mismatch.state)
        assertEquals(listOf("diagnostic(Warning,GATT_OPERATION_CALLBACK_MISMATCH)"), mismatch.send(respondRead(null)))

        assertEquals(
            listOf("diagnostic(Warning,GATT_OPERATION_DUPLICATE_CALLBACK)"),
            Run(initial()).send(respondRead(charA)),
        )
    }

    @Test
    fun `Q05_CCCD 짝은 방향과 무관하게 같은 descriptor 면 짝이다`() {
        for (kind in listOf(GattOperationKind.Subscribe, GattOperationKind.Unsubscribe)) {
            fun ready() = Run(initial()).also { it.sendAll(submit(spec(kind, cccdA)), started(1, kind, cccdA)) }
            assertEquals(listOf("cancel(t1)", "complete(#1,ok)"), ready().send(respondDescriptor(cccdA, 0)))
            assertEquals(listOf("cancel(t1)", "complete(#1,GATT_STATUS_FAILED)"), ready().send(respondDescriptor(cccdA, 5)))
            val run = ready()
            val before = run.state
            assertEquals(
                listOf("diagnostic(Warning,GATT_OPERATION_CALLBACK_MISMATCH)"),
                run.send(respondDescriptor(cccdB)),
            )
            assertEquals(before, run.state)
        }
    }

    @Test
    fun `Q06_write 조각이 곧 작업이고 응답 방식과 무관하게 짝이다`() {
        for (kind in listOf(GattOperationKind.WriteWithResponse, GattOperationKind.WriteWithoutResponse)) {
            val run = Run(initial())
            run.sendAll(submit(spec(kind, charA), spec(kind, charA), command = true), started(1, kind, charA))
            assertEquals(listOf("cancel(t1)", "complete(#1,ok)", "start(#2)"), run.send(respondWrite(charA)))
        }
        val run = Run(initial())
        run.sendAll(submit(chunkSpec()), startedChunk(1))
        assertEquals(
            listOf("diagnostic(Warning,GATT_OPERATION_CALLBACK_MISMATCH)"),
            run.send(respondWrite(charB)),
        )
    }

    @Test
    fun `Q07_조각마다 새 기한을 센다`() {
        val run = Run(initial())
        assertEquals(listOf("start(#1)"), run.send(submit(chunkSpec(), chunkSpec(), command = true)))
        assertEquals(listOf("arm(t1,#1,3000)"), run.send(startedChunk(1)))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)", "start(#2)"), run.send(respondWrite()))
        assertEquals(listOf("arm(t2,#2,3000)"), run.send(startedChunk(2)))
    }

    @Test
    fun `Q08_기한 타이머는 판정하지 않고 한 번 미룬다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec()), startedRead(1))
        val before = run.state
        assertEquals(listOf("judgeLater(#1)"), run.send(GattQueueEvent.DeadlineFired(1)))
        assertEquals(before.copy(judging = true), run.state)
        assertEquals(emptyList<String>(), run.send(GattQueueEvent.DeadlineFired(1)))
        assertEquals(emptyList<String>(), run.send(GattQueueEvent.DeadlineFired(99)))
        assertEquals(emptyList<String>(), Run(initial()).send(GattQueueEvent.DeadlineFired(1)))
    }

    @Test
    fun `Q09_이미 도착한 완료 콜백이 기한을 이긴다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec()), startedRead(1), GattQueueEvent.DeadlineFired(1))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)"), run.send(respondRead(charA)))
        assertEquals(emptyList<String>(), run.send(judged(1)))
        assertFalse(run.state.poisoned)
    }

    @Test
    fun `Q08b_판정 창 안의 연결 종료는 작업을 시간 초과로 끝내고 Poison 은 내지 않는다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charB)), startedRead(1), GattQueueEvent.DeadlineFired(1))
        assertTrue(run.state.judging)
        val out = run.send(GattQueueEvent.Invalidate(invalidation(), context))
        assertEquals(listOf("cancel(t1)", "complete(#1,OPERATION_TIMEOUT)", "complete(#2,OPERATION_INVALIDATED)"), out)
        assertTrue(run.state.poisoned)
        assertFalse(run.state.judging)
        assertEquals(emptyList<String>(), run.send(judged(1)))
    }

    @Test
    fun `Q10_판정 때 같은 작업이 pending 이면 시간 초과이고 대기는 무효화 poison 이 마지막이다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charB), readSpec(char(3))), startedRead(1))
        assertEquals(listOf("judgeLater(#1)"), run.send(GattQueueEvent.DeadlineFired(1)))
        assertEquals(
            listOf(
                "complete(#1,OPERATION_TIMEOUT)",
                "complete(#2,OPERATION_INVALIDATED)",
                "complete(#3,OPERATION_INVALIDATED)",
                "poison(OPERATION_TIMEOUT)",
            ),
            run.send(judged(1)),
        )
        assertTrue(run.state.poisoned)
        assertTrue(GattQueueCore.isIdle(run.state))
        assertEquals(0L, run.state.generation)
    }

    @Test
    fun `Q11_호출자 취소 사건이 없어도 작업은 끝까지 간다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charB)), startedRead(1))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)", "start(#2)"), run.send(respondRead(charA)))
        run.send(startedRead(2, charB))
        assertEquals(listOf("cancel(t2)", "complete(#2,ok)"), run.send(respondRead(charB)))

        // else 없는 when 식: 사건 하위 타입이 늘면(취소류 사건 포함) 이 행이 컴파일에서 다시 보인다.
        fun name(e: GattQueueEvent): String = when (e) {
            is GattQueueEvent.Submit -> "submit"
            is GattQueueEvent.StartFinished -> "startFinished"
            is GattQueueEvent.Responded -> "responded"
            is GattQueueEvent.DeadlineFired -> "deadlineFired"
            is GattQueueEvent.DeadlineJudged -> "deadlineJudged"
            is GattQueueEvent.Invalidate -> "invalidate"
            GattQueueEvent.BeginGenerationIfPoisoned -> "beginGeneration"
            is Direct.Enqueue -> "enqueue"
            Direct.StartNext -> "startNext"
            is Direct.RemoveQueued -> "removeQueued"
            is Direct.TakePendingIfMatches -> "takePendingIfMatches"
            Direct.Drain -> "drain"
            Direct.PoisonCurrentGeneration -> "poisonCurrentGeneration"
            Direct.BeginNextGeneration -> "beginNextGeneration"
        }
        val names = listOf(
            submit(), startedRead(1), respondRead(), GattQueueEvent.DeadlineFired(1), judged(1),
            GattQueueEvent.Invalidate(invalidation(), context), GattQueueEvent.BeginGenerationIfPoisoned,
            Direct.Enqueue(readSpec()), Direct.StartNext, Direct.RemoveQueued(1),
            Direct.TakePendingIfMatches(0, GattOperationKind.Read, charA), Direct.Drain,
            Direct.PoisonCurrentGeneration, Direct.BeginNextGeneration,
        ).map(::name)
        assertEquals(14, names.distinct().size)
        assertFalse(names.any { "cancel" in it.lowercase() && "deadline" !in it.lowercase() })
    }

    @Test
    fun `Q12_연결 종료 무효화는 모두 같은 실패로 끝내고 poison 은 내지 않는다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charB), readSpec(char(3))), startedRead(1))
        val step = stepOf(run.state, GattQueueEvent.Invalidate(invalidation(), context))
        assertEquals(
            listOf("cancel(t1)", "complete(#1,LINK_LOST)", "complete(#2,LINK_LOST)", "complete(#3,LINK_LOST)"),
            step.effects.map(::show),
        )
        assertTrue(GattQueueCore.isIdle(step.state))
        assertTrue(step.state.poisoned)

        val empty = stepOf(initial(), GattQueueEvent.Invalidate(invalidation(), context))
        assertEquals(emptyList<GattQueueEffect>(), empty.effects)
        assertTrue(empty.state.poisoned)
    }

    @Test
    fun `Q13_다음 시작은 완료, 시작 실패, idle 접수 step 이 바로 낸다`() {
        val run = Run(initial())
        assertEquals(listOf("start(#1)"), run.send(submit(readSpec(charA))))
        assertEquals(emptyList<String>(), run.send(submit(readSpec(charB))))
        assertEquals(1, run.state.waiting.size)
        assertEquals(1L, run.state.pending?.sequence)

        val failed = Run(run.state)
        val rejected = GattQueueEvent.StartFinished(
            1, GattOperationKind.Read, charA, GattQueueFailures.startRejected(context, GattOperationKind.Read), context,
        )
        assertEquals(listOf("complete(#1,GATT_OPERATION_START_FAILED)", "start(#2)"), failed.send(rejected))

        val lone = Run(initial())
        lone.sendAll(submit(readSpec()), startedRead(1))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)"), lone.send(respondRead()))
    }

    @Test
    fun `Q14_실행 중 재진입한 접수는 확정된 상태 뒤 줄에 선다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charB)), startedRead(1))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)", "start(#2)"), run.send(respondRead(charA)))
        // 위 할 일을 실행하는 도중 completion 처리기가 접수한다 — #2 는 이미 pending 이다.
        assertEquals(emptyList<String>(), run.send(submit(readSpec(char(3)))))
        assertEquals(2L, run.state.pending?.sequence)
        assertEquals(listOf(3L), run.state.waiting.map { it.sequence })
    }

    @Test
    fun `Q15_같은 대상의 read 두 개는 응답마다 하나씩 끝난다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charA)), startedRead(1))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)", "start(#2)"), run.send(respondRead(charA)))
        run.send(startedRead(2))
        assertEquals(listOf("cancel(t2)", "complete(#2,ok)"), run.send(respondRead(charA)))
    }

    @Test
    fun `Q16_질의 - isIdle 과 pending`() {
        val run = Run(initial())
        assertTrue(GattQueueCore.isIdle(run.state))
        run.send(submit(readSpec(charA), readSpec(charB)))
        assertFalse(GattQueueCore.isIdle(run.state))
        val entry = GattQueueCore.pending(run.state)!!
        assertEquals(1L, entry.sequence)
        assertEquals(GattOperationKind.Read, entry.kind)
        assertEquals(charA, entry.identity)
        assertEquals("gatt-0-1", GattQueueCore.operationId(entry))
        assertFalse(GattQueueCore.isIdle(initial().copy(waiting = listOf(entry))))
        assertFalse(GattQueueCore.isIdle(initial().copy(pending = entry)))
    }

    @Test
    fun `Q17_판정 뒤 늦은 사건은 진단만 남기고 완료하지 않는다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec()), startedRead(1), GattQueueEvent.DeadlineFired(1), judged(1))
        assertEquals(
            listOf("diagnostic(Warning,GATT_OPERATION_DUPLICATE_CALLBACK)"),
            run.send(respondRead(charA)),
        )
        assertEquals(emptyList<String>(), run.send(GattQueueEvent.DeadlineFired(1)))
        assertEquals(emptyList<String>(), run.send(judged(1)))
    }

    @Test
    fun `Q18_write 조각이 실패하면 남은 조각은 플랫폼에 나가지 않고 같은 실패로 끝난다`() {
        val run = Run(initial())
        run.sendAll(submit(chunkSpec(), chunkSpec(), chunkSpec(), command = true), startedChunk(1))
        run.send(submit(readSpec(charB)))
        val step = stepOf(run.state, respondWrite(charA, status = 133))
        assertEquals(
            listOf(
                "cancel(t1)",
                "complete(#1,GATT_STATUS_FAILED)",
                "complete(#2,GATT_STATUS_FAILED)",
                "complete(#3,GATT_STATUS_FAILED)",
                "start(#4)",
            ),
            step.effects.map(::show),
        )
        val second = failureOf(step.effects, 2)
        assertEquals("gatt-0-2", second.context.operationId)
        assertEquals(OperationKind.WriteWithResponse, second.context.operationKind)

        val ok = Run(initial())
        ok.sendAll(submit(chunkSpec(), command = true), startedChunk(1))
        assertEquals(listOf("cancel(t1)", "complete(#1,ok)"), ok.send(respondWrite(charA)))
    }

    // ── P 행: Android 전용 ────────────────────────────────────────────────────

    @Test
    fun `P01_시작 거절은 그 조각과 남은 조각을 실패시키고 기한 없이 다음을 시작한다`() {
        val run = Run(initial())
        run.send(submit(chunkSpec(), chunkSpec(), command = true))
        run.send(submit(readSpec(charB)))
        val rejection = GattQueueFailures.startRejected(context, GattOperationKind.WriteWithResponse)
        val step = stepOf(
            run.state,
            GattQueueEvent.StartFinished(1, GattOperationKind.WriteWithResponse, charA, rejection, context),
        )
        assertEquals(
            listOf("complete(#1,GATT_OPERATION_START_FAILED)", "complete(#2,GATT_OPERATION_START_FAILED)", "start(#3)"),
            step.effects.map(::show),
        )
        assertNull(step.state.deadlineToken)
        assertEquals(3L, step.state.pending?.sequence)
    }

    @Test
    fun `P02_시작 실패가 PERMISSION_DENIED 면 진단과 권한 상실을 내고 다음을 시작하지 않는다`() {
        val run = Run(initial())
        run.send(submit(readSpec(charA), readSpec(charB)))
        val denied = FailureMapper.permissionDenied(FailurePhase.Operation, AndroidApiOperation.ReadCharacteristic, context = context)
        val step = stepOf(run.state, GattQueueEvent.StartFinished(1, GattOperationKind.Read, charA, denied, context))
        assertEquals(
            listOf("complete(#1,PERMISSION_DENIED)", "diagnostic(Error,PERMISSION_DENIED)", "permissionLost(PERMISSION_DENIED)"),
            step.effects.map(::show),
        )
        assertNull(step.state.pending)
        assertEquals(listOf(2L), step.state.waiting.map { it.sequence })
    }

    @Test
    fun `P03_이전 generation 항목은 콜백 짝이 아니다`() {
        val old = GattQueueEntry(
            sequence = 1, generation = 0, kind = GattOperationKind.Read, identity = charA, command = null,
            commandIndex = 0, tracked = true, timeoutMillis = null, platformStarted = true,
        )
        val state = initial().copy(generation = 1L, pending = old, deadlineToken = 1L)
        for (generation in listOf(0L, 1L)) {
            val step = stepOf(state, respondRead(charA, generation = generation))
            assertEquals(listOf("diagnostic(Warning,GATT_OPERATION_CALLBACK_MISMATCH)"), step.effects.map(::show))
            assertEquals(state, step.state)
        }
    }

    @Test
    fun `P04_poison 뒤 접수는 IllegalState 이고 새 generation 은 poison 일 때만 열린다`() {
        val poisoned = stepOf(initial(), GattQueueEvent.Invalidate(invalidation(), context)).state
        assertThrows(IllegalStateException::class.java) { stepOf(poisoned, submit(readSpec())) }
        val reopened = stepOf(poisoned, GattQueueEvent.BeginGenerationIfPoisoned).state
        assertEquals(1L, reopened.generation)
        assertFalse(reopened.poisoned)
        assertEquals(reopened, stepOf(reopened, GattQueueEvent.BeginGenerationIfPoisoned).state)
        assertThrows(IllegalStateException::class.java) { stepOf(reopened, submit(readSpec(generation = 0L))) }

        val entry = Run(initial()).also { it.send(submit(readSpec())) }.state.pending!!
        val busy = poisoned.copy(waiting = listOf(entry))
        assertThrows(IllegalStateException::class.java) { stepOf(busy, GattQueueEvent.BeginGenerationIfPoisoned) }
    }

    @Test
    fun `P05_순번과 generation 소진은 IllegalState`() {
        val exhausted = GattQueueState(nextSequence = Long.MAX_VALUE, defaultTimeoutMillis = 5000L)
        assertThrows(IllegalStateException::class.java) { stepOf(exhausted, submit(readSpec())) }
        val lastGeneration = GattQueueState(generation = Long.MAX_VALUE, poisoned = true, defaultTimeoutMillis = 5000L)
        assertThrows(IllegalStateException::class.java) { stepOf(lastGeneration, GattQueueEvent.BeginGenerationIfPoisoned) }
    }

    @Test
    fun `P06_실패 귀속 값은 작업 id 연결 generation 종류 무효화 사유다`() {
        val ctx = FailureContext(sessionId = "s-9", connectionGeneration = 7L)
        val run = Run(GattQueueState(generation = 3L, nextSequence = 5L, defaultTimeoutMillis = 5000L))
        run.send(submit(readSpec(charA, generation = 3L), readSpec(charB, generation = 3L)))
        run.send(GattQueueEvent.StartFinished(5, GattOperationKind.Read, charA, null, ctx))

        val fired = stepOf(run.state, GattQueueEvent.DeadlineFired(1)).state
        val timeout = stepOf(fired, GattQueueEvent.DeadlineJudged(5, ctx)).effects
        val t = failureOf(timeout, 5)
        assertEquals("OPERATION_TIMEOUT", t.code.value)
        assertEquals(FailureContext("s-9", 3L, "gatt-3-5", OperationKind.Read, null), t.context)
        val w = failureOf(timeout, 6)
        assertEquals("OPERATION_INVALIDATED", w.code.value)
        assertEquals(FailureContext("s-9", 3L, "gatt-3-6", OperationKind.Read, null), w.context)

        val responded = GattQueueEvent.Responded(GattResponseCategory.Read, 3L, charA, 133, ctx)
        val status = failureOf(stepOf(run.state, responded).effects, 5)
        assertEquals("GATT_STATUS_FAILED", status.code.value)
        assertEquals("gatt-3-5", status.context.operationId)
        assertEquals(OperationKind.Read, status.context.operationKind)
        assertEquals("s-9", status.context.sessionId)

        val invalidated = failureOf(stepOf(run.state, GattQueueEvent.Invalidate(invalidation(), ctx)).effects, 6)
        assertEquals(
            FailureContext("s-9", 3L, "gatt-3-6", OperationKind.Read, ConnectionInvalidationReason.LinkLost),
            invalidated.context,
        )

        val mismatch = GattQueueFailures.callbackMismatch(ctx, 3L, 5, "CharacteristicRead")
        assertNull(mismatch.context.operationId)
        assertEquals(3L, mismatch.context.connectionGeneration)
    }

    @Test
    fun `P07_추적하지 않는 항목은 시작 성공에도 기한 할 일이 없다`() {
        val run = Run(initial())
        run.send(submit(spec(GattOperationKind.Read, charA, tracked = false)))
        assertEquals(emptyList<String>(), run.send(startedRead(1)))
        assertNull(run.state.deadlineToken)
        assertEquals(emptyList<String>(), run.send(GattQueueEvent.DeadlineFired(1)))
        assertEquals(listOf("complete(#1,ok)"), run.send(respondRead(charA)))
    }

    @Test
    fun `P08_이미 poison 인 큐의 Invalidate 는 비우기만 한다`() {
        val run = Run(initial())
        run.sendAll(submit(readSpec(charA), readSpec(charB)), startedRead(1), GattQueueEvent.DeadlineFired(1), judged(1))
        assertTrue(run.state.poisoned)
        val leftover = GattQueueEntry(
            sequence = 9, generation = 0, kind = GattOperationKind.Read, identity = charA, command = null,
            commandIndex = 0, tracked = true, timeoutMillis = null, platformStarted = false,
        )
        val step = stepOf(run.state.copy(waiting = listOf(leftover)), GattQueueEvent.Invalidate(invalidation(), context))
        assertEquals(listOf("complete(#9,LINK_LOST)"), step.effects.map(::show))
        assertTrue(step.state.poisoned)
        assertTrue(GattQueueCore.isIdle(step.state))
    }

    @Test
    fun `P09_Direct 입구는 옛 scheduler 와 같은 뜻이다`() {
        val run = Run(initial())

        run.sendAll(Direct.Enqueue(readSpec(charA)), Direct.Enqueue(readSpec(charB)))
        assertNull(run.state.pending)
        assertEquals(2, run.state.waiting.size)

        assertEquals(emptyList<String>(), run.send(Direct.StartNext))
        assertEquals(1L, run.state.pending?.sequence)
        assertEquals(1, run.state.waiting.size)
        val held = run.state
        run.send(Direct.StartNext)
        assertEquals(held, run.state)

        run.send(Direct.TakePendingIfMatches(0L, GattOperationKind.Read, charB))
        assertEquals(1L, run.state.pending?.sequence)
        run.send(Direct.TakePendingIfMatches(0L, GattOperationKind.WriteWithResponse, charA))
        assertEquals(1L, run.state.pending?.sequence)
        run.send(Direct.TakePendingIfMatches(0L, GattOperationKind.Read, charA))
        assertNull(run.state.pending)

        run.send(Direct.RemoveQueued(2))
        assertTrue(GattQueueCore.isIdle(run.state))

        run.sendAll(Direct.Enqueue(readSpec(charA)), Direct.StartNext, Direct.Enqueue(readSpec(charB)), Direct.Drain)
        assertTrue(GattQueueCore.isIdle(run.state))
        assertFalse(run.state.poisoned)

        run.send(Direct.Enqueue(readSpec(charA)))
        assertThrows(IllegalStateException::class.java) { stepOf(run.state, Direct.BeginNextGeneration) }
        run.send(Direct.PoisonCurrentGeneration)
        assertTrue(run.state.poisoned)
        assertThrows(IllegalStateException::class.java) { stepOf(run.state, Direct.Enqueue(readSpec(charA))) }
        run.send(Direct.StartNext)
        assertNull(run.state.pending)
        run.send(Direct.BeginNextGeneration)
        assertEquals(1L, run.state.generation)
        assertFalse(run.state.poisoned)
        assertThrows(IllegalStateException::class.java) { stepOf(run.state, Direct.Enqueue(readSpec(charA, generation = 0L))) }
    }
}
