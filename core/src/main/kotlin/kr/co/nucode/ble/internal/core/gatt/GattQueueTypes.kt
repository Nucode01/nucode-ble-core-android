package kr.co.nucode.ble.internal.core.gatt

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.GattAttributeIdentity
import kr.co.nucode.ble.internal.model.GattOperationKind

/** 접수할 항목 하나의 사실. 순번은 Core 가 매긴다. */
internal data class GattQueueSpec(
    val generation: Long,
    val kind: GattOperationKind,
    val identity: GattAttributeIdentity?,
    val tracked: Boolean,
    val timeoutMillis: Long? = null,
)

/** 큐 항목 하나(= write 조각 하나, read 하나, CCCD 하나). 값이다. */
internal data class GattQueueEntry(
    val sequence: Long,
    val generation: Long,
    val kind: GattOperationKind,
    val identity: GattAttributeIdentity?,
    /** 같은 write 호출의 조각 묶음 표시(첫 조각 순번). 조각이 아니면 null. */
    val command: Long?,
    val commandIndex: Int,
    /** 플랫폼 시작 뒤 작업 기한을 세는 항목인가. */
    val tracked: Boolean,
    val timeoutMillis: Long?,
    val platformStarted: Boolean,
)

/** GATT 큐 Core 의 상태 값. 갱신은 copy 로만 한다. */
internal data class GattQueueState(
    val generation: Long = 0L,
    val poisoned: Boolean = false,
    /** 기한 타이머가 깨어났고 판정([GattQueueEvent.DeadlineJudged])을 기다리는 중이다. 이 창에서는 큐를 poison 과 같게 본다. */
    val judging: Boolean = false,
    val nextSequence: Long = 0L,
    val pending: GattQueueEntry? = null,
    val waiting: List<GattQueueEntry> = emptyList(),
    val deadlineToken: Long? = null,
    val nextDeadlineToken: Long = 1L,
    val defaultTimeoutMillis: Long,
)

/** 플랫폼 응답 콜백의 종류. 콜백에는 방향(write 방식, 구독 방향) 사실이 없어 허용 종류 집합으로 짝을 짓는다. */
internal enum class GattResponseCategory(val callbackKind: String, val kinds: Set<GattOperationKind>) {
    Read("CharacteristicRead", setOf(GattOperationKind.Read)),
    CharacteristicWrite(
        "CharacteristicWrite",
        setOf(GattOperationKind.WriteWithResponse, GattOperationKind.WriteWithoutResponse),
    ),
    DescriptorRead("DescriptorRead", setOf(GattOperationKind.ReadDescriptor)),
    DescriptorWrite(
        "DescriptorWrite",
        setOf(GattOperationKind.Subscribe, GattOperationKind.Unsubscribe, GattOperationKind.WriteDescriptor),
    ),
}

/** Runtime 이 Core 에 넣는 사건. */
internal sealed interface GattQueueEvent {
    /** 한 호출의 항목 전부(write 는 조각 전부). [asCommand] 면 조각 묶음으로 표시한다. */
    data class Submit(val specs: List<GattQueueSpec>, val asCommand: Boolean = false) : GattQueueEvent

    /** 플랫폼 시작 결과. [kind]·[identity] 는 Runtime 이 해석한 값, [failure] 가 있으면 시작 거절/실패. */
    data class StartFinished(
        val sequence: Long,
        val kind: GattOperationKind,
        val identity: GattAttributeIdentity?,
        val failure: BleFailure?,
        val context: FailureContext,
    ) : GattQueueEvent

    data class Responded(
        val category: GattResponseCategory,
        val generation: Long,
        val identity: GattAttributeIdentity?,
        val status: Int,
        val context: FailureContext,
    ) : GattQueueEvent

    data class DeadlineFired(val token: Long) : GattQueueEvent

    data class DeadlineJudged(val sequence: Long, val context: FailureContext) : GattQueueEvent

    /** 연결 종료 무효화. */
    data class Invalidate(val failure: BleFailure, val context: FailureContext) : GattQueueEvent

    data object BeginGenerationIfPoisoned : GattQueueEvent

    /** 셸 호환용 옛 입구. 정책 없이 차례 규칙만 적용하고 할 일은 내지 않는다. */
    sealed interface Direct : GattQueueEvent {
        data class Enqueue(val spec: GattQueueSpec) : Direct

        data object StartNext : Direct

        data class RemoveQueued(val sequence: Long) : Direct

        data class TakePendingIfMatches(
            val generation: Long,
            val kind: GattOperationKind,
            val identity: GattAttributeIdentity?,
        ) : Direct

        data object Drain : Direct

        data object PoisonCurrentGeneration : Direct

        data object BeginNextGeneration : Direct
    }
}

/** 작업 하나의 끝. */
internal sealed interface GattQueueOutcome {
    data object Success : GattQueueOutcome

    data class Failed(val failure: BleFailure) : GattQueueOutcome
}

/** Runtime 이 목록 순서대로 실행할 할 일. */
internal sealed interface GattQueueEffect {
    data class Start(val sequence: Long) : GattQueueEffect

    data class ArmDeadline(val token: Long, val sequence: Long, val millis: Long) : GattQueueEffect

    data class CancelDeadline(val token: Long) : GattQueueEffect

    data class Complete(val sequence: Long, val outcome: GattQueueOutcome) : GattQueueEffect

    /** 우편함 끝에 [GattQueueEvent.DeadlineJudged] 로 다시 넣는다. */
    data class JudgeLater(val sequence: Long) : GattQueueEffect

    data class Poison(val failure: BleFailure) : GattQueueEffect

    data class PermissionLost(val failure: BleFailure) : GattQueueEffect

    data class Diagnostic(val severity: Severity, val failure: BleFailure) : GattQueueEffect
}

internal data class GattQueueStep(val state: GattQueueState, val effects: List<GattQueueEffect>)
