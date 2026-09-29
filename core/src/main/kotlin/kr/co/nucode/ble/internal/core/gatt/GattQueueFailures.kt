package kr.co.nucode.ble.internal.core.gatt

import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureCategories
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.model.GattOperationKind

/**
 * GATT 큐 Core 가 만드는 실패 값(순수). 세션 상관 문맥은 Runtime 이 [FailureContext] 로 준다.
 * 값은 0.2.0 `GattFailureFactory` 의 timeout·invalidated·status·귀속과 같다.
 */
internal object GattQueueFailures {
    private fun failure(
        code: String,
        phase: FailurePhase,
        retryability: Retryability,
        context: FailureContext,
        platformDetail: PlatformDetail? = null,
    ): BleFailure = BleFailure(
        category = FailureCategories.of(code),
        code = FailureCode(code),
        phase = phase,
        retryability = retryability,
        context = context,
        platformDetail = platformDetail,
    )

    fun operationKind(kind: GattOperationKind): OperationKind = when (kind) {
        GattOperationKind.Read -> OperationKind.Read
        GattOperationKind.WriteWithResponse -> OperationKind.WriteWithResponse
        GattOperationKind.WriteWithoutResponse -> OperationKind.WriteWithoutResponse
        GattOperationKind.Subscribe -> OperationKind.Subscribe
        GattOperationKind.Unsubscribe -> OperationKind.Unsubscribe
        GattOperationKind.RequestMtu -> OperationKind.RequestMtu
    }

    fun androidOperation(kind: GattOperationKind, callback: Boolean): AndroidApiOperation = when (kind) {
        GattOperationKind.Read -> if (callback) AndroidApiOperation.OnCharacteristicRead else AndroidApiOperation.ReadCharacteristic
        GattOperationKind.WriteWithResponse,
        GattOperationKind.WriteWithoutResponse,
        -> if (callback) AndroidApiOperation.OnCharacteristicWrite else AndroidApiOperation.WriteCharacteristic
        GattOperationKind.Subscribe,
        GattOperationKind.Unsubscribe,
        -> if (callback) AndroidApiOperation.OnDescriptorWrite else AndroidApiOperation.WriteDescriptor
        GattOperationKind.RequestMtu -> if (callback) AndroidApiOperation.OnMtuChanged else AndroidApiOperation.RequestMtu
    }

    private fun operationContext(context: FailureContext, kind: OperationKind?, operationId: String? = null): FailureContext =
        context.copy(operationId = operationId, operationKind = kind)

    /** 작업 기한 초과(귀속 전). */
    fun timedOut(context: FailureContext, kind: OperationKind?): BleFailure = failure(
        code = "OPERATION_TIMEOUT",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = operationContext(context, kind),
    )

    /** 연결 종료·poison 으로 회수된 작업(귀속 전). */
    fun invalidated(context: FailureContext, kind: OperationKind?): BleFailure = failure(
        code = "OPERATION_INVALIDATED",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = operationContext(context, kind),
    )

    /** 항목에 귀속된 기한 초과. */
    fun timeoutFor(context: FailureContext, entry: GattQueueEntry): BleFailure =
        attribute(context, entry, timedOut(context, operationKind(entry.kind)))

    /** 항목에 귀속된 무효화(poison 때 대기 작업). */
    fun invalidatedFor(context: FailureContext, entry: GattQueueEntry): BleFailure =
        invalidatedFor(context, entry.generation, entry.sequence, entry.kind)

    /** 접수 시점 snapshot(연결 generation·순번)과 종류로 귀속한 무효화. */
    fun invalidatedFor(context: FailureContext, generation: Long, sequence: Long, kind: GattOperationKind): BleFailure =
        attribute(context, generation, sequence, kind, invalidated(context, operationKind(kind)))

    /** 완료 콜백 status 실패. 귀속 필드는 작업 id·종류만 더한다. */
    fun statusFailed(context: FailureContext, entry: GattQueueEntry, status: Int): BleFailure =
        statusFailed(context, entry.generation, entry.sequence, entry.kind, status)

    /** 접수 시점 snapshot(연결 generation·순번)과 종류로 만든 status 실패. */
    fun statusFailed(context: FailureContext, generation: Long, sequence: Long, kind: GattOperationKind, status: Int): BleFailure =
        FailureMapper.gattStatusFailed(
            statusCode = status,
            phase = FailurePhase.Operation,
            operation = androidOperation(kind, callback = true),
            context = operationContext(context, operationKind(kind), operationId(generation, sequence)),
        )

    /** 플랫폼이 시작을 거절했을 때(귀속 전). */
    fun startRejected(context: FailureContext, kind: GattOperationKind): BleFailure =
        FailureMapper.gattStartRejected(
            phase = FailurePhase.Operation,
            operation = androidOperation(kind, callback = false),
            context = operationContext(context, operationKind(kind)),
        )

    /** 접수 시점의 항목(작업 id·연결 generation·종류)을 실패에 더한다. 콜백 불일치 진단에는 쓰지 않는다. */
    fun attribute(context: FailureContext, entry: GattQueueEntry, failure: BleFailure): BleFailure =
        attribute(context, entry.generation, entry.sequence, entry.kind, failure)

    /** 접수 시점 snapshot(연결 generation·순번)과 종류를 실패에 더한다. */
    fun attribute(
        context: FailureContext,
        generation: Long,
        sequence: Long,
        kind: GattOperationKind,
        failure: BleFailure,
    ): BleFailure = failure.copy(
        context = context.copy(
            connectionGeneration = generation,
            operationId = operationId(generation, sequence),
            operationKind = operationKind(kind),
            invalidationReason = failure.context.invalidationReason,
        ),
    )

    /** Android 콜백에는 작업 id 가 없어 접수 시점 snapshot 으로 만든다. */
    fun operationId(generation: Long, sequence: Long): String = "gatt-$generation-$sequence"

    /** 콜백이 pending 과 다른 대상을 가리킬 때. */
    fun callbackMismatch(context: FailureContext, generation: Long, status: Int, callbackKind: String): BleFailure =
        callbackDiagnostic("GATT_OPERATION_CALLBACK_MISMATCH", context, generation, status, callbackKind)

    /** 이미 끝난 작업의 콜백이 한 번 더 올 때. */
    fun duplicateCallback(context: FailureContext, generation: Long, status: Int, callbackKind: String): BleFailure =
        callbackDiagnostic("GATT_OPERATION_DUPLICATE_CALLBACK", context, generation, status, callbackKind)

    private fun callbackDiagnostic(
        code: String,
        context: FailureContext,
        generation: Long,
        status: Int,
        callbackKind: String,
    ): BleFailure = failure(
        code = code,
        phase = FailurePhase.Operation,
        retryability = Retryability.Unknown,
        // 늦거나 어긋난 콜백은 pending 작업의 증거가 아니다 — 작업 id 를 붙이면 잘못 귀속된다.
        context = context.copy(connectionGeneration = generation),
        platformDetail = PlatformDetail.Android(
            domain = "android.bluetooth.gatt.callback",
            statusCode = status,
            apiOperation = callbackKind,
        ),
    )
}
