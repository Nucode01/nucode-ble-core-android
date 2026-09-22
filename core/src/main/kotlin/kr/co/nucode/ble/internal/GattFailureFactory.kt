package kr.co.nucode.ble.internal

import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Retryability

/**
 * `BleFailure` 생성 전용 층. `Session` context·operation attribution·비플랫폼 code를
 * 조립한다 — `FailureMapper`(순수 Android status → shared 매핑, `Session` 모름) 위의 층이다.
 * 상태를 갖지 않는다(`object`) — 팩토리 메서드는 `Session`(+ operation 객체)을 받아
 * `BleFailure`만 반환한다.
 *
 * 이동 출처: `GattBridge.kt`(HEAD d19cf99) 1821~1999행 private helper + 인라인 11곳,
 * 그리고 `OperationQueue.kt`(HEAD 87b0c03)의 write 경로 helper 6개(`write*` 메서드).
 * `operationContext`의 `charRef` 파라미터는 원본에서도 `@Suppress("UNUSED_PARAMETER")`였다
 * (미사용) — 이 팩토리는 받지 않는다. 출력은 동일하다.
 */
internal object GattFailureFactory {

    private fun failure(
        category: String,
        code: String,
        phase: FailurePhase,
        retryability: Retryability,
        context: FailureContext,
        platformDetail: PlatformDetail? = null,
    ): BleFailure = BleFailure(
        category = FailureCategory(category),
        code = FailureCode(code),
        phase = phase,
        retryability = retryability,
        context = context,
        platformDetail = platformDetail,
    )

    private fun connectionContext(session: Session): FailureContext = session.lifecycleFailureContext()

    private fun operationContext(
        session: Session,
        kind: OperationKind? = null,
        operationId: String? = null,
    ): FailureContext = connectionContext(session).copy(
        operationId = operationId,
        operationKind = kind,
    )

    fun notConnected(session: Session): BleFailure = failure(
        category = "connection",
        code = "NOT_CONNECTED",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = connectionContext(session),
    )

    fun unknownDevice(): BleFailure = failure(
        category = "validation",
        code = "UNKNOWN_DEVICE",
        phase = FailurePhase.Validation,
        retryability = Retryability.Never,
        context = FailureContext(),
    )

    fun peripheralNotFound(session: Session): BleFailure = failure(
        category = "connection",
        code = "PERIPHERAL_NOT_FOUND",
        phase = FailurePhase.Connect,
        retryability = Retryability.TransientMayHelp,
        context = connectionContext(session),
    )

    fun characteristicNotFound(session: Session, kind: OperationKind? = null): BleFailure = failure(
        category = "validation",
        code = "ATTRIBUTE_NOT_FOUND",
        phase = FailurePhase.Operation,
        retryability = Retryability.Never,
        context = operationContext(session, kind),
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

    fun operationInvalidated(session: Session, operation: GattOperation<*>? = null): BleFailure =
        invalidated(session, operation?.kind?.let(::operationKind))

    private fun invalidated(session: Session, kind: OperationKind?): BleFailure = failure(
        category = "operation",
        code = "OPERATION_INVALIDATED",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = operationContext(session, kind),
    )

    fun operationInvalidated(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
    ): BleFailure = operationInvalidated(session, scheduled.operation).let { failureFor(session, scheduled, it) }

    fun timeoutFailure(session: Session, operation: GattOperation<*>): BleFailure =
        timedOut(session, operationKind(operation.kind))

    private fun timedOut(session: Session, kind: OperationKind?): BleFailure = failure(
        category = "operation",
        code = "OPERATION_TIMEOUT",
        phase = FailurePhase.Operation,
        retryability = Retryability.NewConnectionMayHelp,
        context = operationContext(session, kind),
    )

    fun timeoutFailure(session: Session, scheduled: ScheduledGattOperation<*>): BleFailure =
        timeoutFailure(session, scheduled.operation).let { failureFor(session, scheduled, it) }

    fun operationStatusFailure(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        status: Int,
    ): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.Operation,
        operation = androidOperation(scheduled.operation.kind, callback = true),
        context = operationContext(
            session = session,
            kind = operationKind(scheduled.operation.kind),
            operationId = scheduled.operationId,
        ),
    )

    fun operationStartFailure(session: Session, operation: GattOperation<*>): BleFailure =
        FailureMapper.gattStartRejected(
            phase = FailurePhase.Operation,
            operation = androidOperation(operation.kind, callback = false),
            context = operationContext(
                session = session,
                kind = operationKind(operation.kind),
            ),
        )

    /** Adds only the admission snapshot; callback mismatches intentionally never call this. */
    fun failureFor(
        session: Session,
        scheduled: ScheduledGattOperation<*>,
        failure: BleFailure,
    ): BleFailure = failure.copy(
        context = session.lifecycleFailureContext().copy(
            connectionGeneration = scheduled.operation.generation,
            operationId = scheduled.operationId,
            operationKind = operationKind(scheduled.operation.kind),
            invalidationReason = failure.context.invalidationReason,
        ),
    )

    /** callback이 pending operation과 다른 attribute를 가리킬 때. */
    fun callbackMismatch(session: Session, status: Int, callbackKind: String): BleFailure =
        callbackDiagnosticFailure("GATT_OPERATION_CALLBACK_MISMATCH", session, status, callbackKind)

    /** 이미 완료된 operation에 같은 callback이 한 번 더 올 때. */
    fun duplicateCallback(session: Session, status: Int, callbackKind: String): BleFailure =
        callbackDiagnosticFailure("GATT_OPERATION_DUPLICATE_CALLBACK", session, status, callbackKind)

    private fun callbackDiagnosticFailure(
        code: String,
        session: Session,
        status: Int,
        callbackKind: String,
    ): BleFailure = failure(
        category = "operation",
        code = code,
        phase = FailurePhase.Operation,
        retryability = Retryability.Unknown,
        // A late or mismatched callback is not evidence about the pending operation.  Preserve
        // session/generation only; assigning its operation ID would misattribute the callback.
        context = session.lifecycleFailureContext().copy(
            connectionGeneration = session.gattOperationScheduler.currentGeneration,
        ),
        platformDetail = PlatformDetail.Android(
            domain = "android.bluetooth.gatt.callback",
            statusCode = status,
            apiOperation = callbackKind,
        ),
    )

    /** [ConnectionCoordinator]가 `Disconnecting` 이외 stage에서 deadline을 못 지킬 때. */
    fun lifecycleTimeout(session: Session, reason: TerminalCleanupReason): BleFailure {
        val phase = when (reason) {
            TerminalCleanupReason.LinkTimeout -> FailurePhase.Link
            TerminalCleanupReason.ServiceDiscoveryTimeout -> FailurePhase.DiscoverServices
            TerminalCleanupReason.ConfigurationTimeout -> FailurePhase.Configure
            else -> FailurePhase.Close
        }
        return failure(
            category = "connection",
            code = when (reason) {
                TerminalCleanupReason.LinkTimeout -> "LINK_TIMEOUT"
                TerminalCleanupReason.ServiceDiscoveryTimeout -> "SERVICE_DISCOVERY_TIMEOUT"
                TerminalCleanupReason.ConfigurationTimeout -> "CONFIGURATION_TIMEOUT"
                else -> "LIFECYCLE_TIMEOUT"
            },
            phase = phase,
            retryability = Retryability.NewConnectionMayHelp,
            context = connectionContext(session),
        )
    }

    /** `Disconnecting` stage deadline 자체가 지나면. */
    fun disconnectCleanupTimeout(session: Session): BleFailure = failure(
        category = "connection",
        code = "DISCONNECT_CLEANUP_TIMEOUT",
        phase = FailurePhase.Close,
        retryability = Retryability.NewConnectionMayHelp,
        context = connectionContext(session),
    )

    /** `driver.disconnect()` 호출 자체가 던질 때(연결 정리 시작 실패). */
    fun disconnectStartFailed(session: Session): BleFailure = failure(
        category = "connection",
        code = "DISCONNECT_START_FAILED",
        phase = FailurePhase.Close,
        retryability = Retryability.NewConnectionMayHelp,
        context = connectionContext(session),
    )

    /** service discovery 응답에 같은 `CharRef` 키가 두 번 나올 때(경고, 비치명). */
    fun duplicateCharacteristicReference(session: Session): BleFailure = failure(
        category = "connection",
        code = "DUPLICATE_CHARACTERISTIC_REFERENCE",
        phase = FailurePhase.DiscoverServices,
        retryability = Retryability.Never,
        context = operationContext(session, kind = OperationKind.Subscribe),
    )

    fun linkStatusFailed(session: Session, status: Int): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.Link,
        operation = AndroidApiOperation.OnConnectionStateChange,
        context = connectionContext(session),
    )

    fun serviceDiscoveryStatusFailed(session: Session, status: Int): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.DiscoverServices,
        operation = AndroidApiOperation.OnServicesDiscovered,
        context = connectionContext(session),
    )

    fun mtuStatusFailed(session: Session, status: Int): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.Configure,
        operation = AndroidApiOperation.OnMtuChanged,
        context = connectionContext(session),
    )

    fun permissionDenied(
        session: Session,
        phase: FailurePhase,
        operation: AndroidApiOperation,
        kind: OperationKind? = null,
    ): BleFailure = FailureMapper.permissionDenied(
        phase = phase,
        operation = operation,
        context = if (kind == null) connectionContext(session) else operationContext(session, kind = kind),
    )

    fun startRejected(
        session: Session,
        phase: FailurePhase,
        operation: AndroidApiOperation,
        kind: OperationKind? = null,
    ): BleFailure = FailureMapper.gattStartRejected(
        phase = phase,
        operation = operation,
        context = if (kind == null) connectionContext(session) else operationContext(session, kind = kind),
    )

    /**
     * write 청크 하나가 resolve할 characteristic을 못 찾을 때(`OperationQueue.write`).
     * code는 의도적으로 [characteristicNotFound]의 `ATTRIBUTE_NOT_FOUND`와 통일하지 않는다 —
     * write 경로는 오래전부터 `CHARACTERISTIC_NOT_FOUND`를 썼고, 두 code를 합치는 건 이
     * 흡수(plan gatt-bridge-split task 3)의 범위 밖이다(plan E 사안).
     */
    fun writeCharacteristicNotFound(session: Session, kind: OperationKind): BleFailure = failure(
        category = "validation",
        code = "CHARACTERISTIC_NOT_FOUND",
        phase = FailurePhase.Operation,
        retryability = Retryability.Never,
        context = operationContext(session, kind = kind),
    )

    /** write 청크 하나가 [ackTimeoutMillis] 안에 ack를 못 받을 때(`OperationQueue.write`). */
    fun writeTimeout(session: Session, kind: OperationKind): BleFailure = timedOut(session, kind)

    /** write 청크 대기 중 연결이 끊겨 취소될 때(`OperationQueue.write`). */
    fun writeInvalidated(session: Session, kind: OperationKind): BleFailure = invalidated(session, kind)

    /** write 청크의 GATT status가 성공이 아닐 때(`OperationQueue.write`). */
    fun writeStatusFailed(session: Session, status: Int, kind: OperationKind): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.Operation,
        operation = AndroidApiOperation.OnCharacteristicWrite,
        context = operationContext(session, kind = kind),
    )

    /** notify/indicate payload 관찰 — 진단 전용, `DiagnosticPolicy` report 여부는 호출부가 결정한다. */
    fun payloadObserved(session: Session, scheduled: ScheduledGattOperation<*>?): BleFailure = failure(
        category = "diagnostic",
        code = "GATT_PAYLOAD_OBSERVED",
        phase = FailurePhase.Operation,
        retryability = Retryability.Never,
        context = operationContext(
            session = session,
            kind = scheduled?.operation?.kind?.let(::operationKind),
            operationId = scheduled?.operationId,
        ),
    )
}
