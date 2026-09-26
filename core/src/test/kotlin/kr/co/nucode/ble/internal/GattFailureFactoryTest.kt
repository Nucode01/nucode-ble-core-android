package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.internal.model.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.model.GattOperation
import kr.co.nucode.ble.internal.model.GattOperationKind
import kr.co.nucode.ble.internal.session.ScheduledGattOperation
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * plan gatt-failure-factory v1 task 1 — [GattFailureFactory]가 `GattBridge.kt`(HEAD d19cf99)
 * 1821~1999행 helper + 인라인 11곳에서 전사한 code/category/phase/retryability 값을 그대로
 * 재생산하는지 고정한다.
 *
 * plan gatt-bridge-split v1 task 3 — `OperationQueue`의 write 전용 helper 4개
 * (`characteristicNotFound`/`writeTimeout`/`writeInvalidated`/`writeStatusFailed`) 흡수와
 * plan C advisory(표 8필드: `context.operationKind`/`context.operationId`/`platformDetail`/
 * `context.sessionId`)를 여기서 함께 고정한다.
 */
class GattFailureFactoryTest {

    private fun newSession(): Session = Session(PeripheralId("AA:BB:CC:DD:EE:FF"))

    private fun characteristicIdentity(): GattCharacteristicIdentity = GattCharacteristicIdentity(
        serviceUuid = UUID.randomUUID(),
        serviceInstanceIndex = 0,
        characteristicUuid = UUID.randomUUID(),
        characteristicInstanceIndex = 0,
    )

    private fun scheduledRead(session: Session): ScheduledGattOperation<ByteArray> =
        session.gattOperationScheduler.enqueue(
            GattOperation.Read(generation = 0L, attributeIdentity = characteristicIdentity()),
        )

    /** "이 필드는 검사하지 않는다" 와 "null 을 기대한다" 를 구분하기 위한 wrapper. */
    private sealed interface Check<out T> {
        data object Skip : Check<Nothing>
        data class Expect<T>(val value: T) : Check<T>
    }

    private data class Shape(
        val name: String,
        val code: String,
        val category: String,
        val phase: FailurePhase,
        val retryability: Retryability,
        val sessionAttributed: Boolean = true,
        val operationKind: Check<OperationKind?> = Check.Skip,
        val operationId: Check<String?> = Check.Skip,
        val platformDetail: Check<PlatformDetail?> = Check.Skip,
        val platformStatusCode: Check<Int?> = Check.Skip,
    )

    @Test
    fun `failure shapes pin code, category, phase, retryability and the 8-field context table`() {
        val session = newSession()
        val scheduled = scheduledRead(session)

        val expectations = listOf(
            Shape("notConnected", "NOT_CONNECTED", "connection", FailurePhase.Operation, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.notConnected(session),
            Shape(
                "unknownDevice", "UNKNOWN_DEVICE", "validation", FailurePhase.Validation, Retryability.Never,
                sessionAttributed = false,
            )
                to GattFailureFactory.unknownDevice(),
            Shape("peripheralNotFound", "PERIPHERAL_NOT_FOUND", "connection", FailurePhase.Connect, Retryability.TransientMayHelp)
                to GattFailureFactory.peripheralNotFound(session),
            Shape("characteristicNotFound", "CHARACTERISTIC_NOT_FOUND", "attribute", FailurePhase.Operation, Retryability.Never)
                to GattFailureFactory.characteristicNotFound(session),
            Shape(
                "operationInvalidated(operation)", "OPERATION_INVALIDATED", "operation", FailurePhase.Operation,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(OperationKind.Read),
            )
                to GattFailureFactory.operationInvalidated(session, scheduled.operation),
            Shape(
                "operationInvalidated(operation=null)", "OPERATION_INVALIDATED", "operation", FailurePhase.Operation,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(null),
            )
                to GattFailureFactory.operationInvalidated(session, operation = null),
            Shape(
                "operationInvalidated(scheduled)", "OPERATION_INVALIDATED", "operation", FailurePhase.Operation,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(OperationKind.Read),
            )
                to GattFailureFactory.operationInvalidated(session, scheduled),
            Shape(
                "timeoutFailure(operation)", "OPERATION_TIMEOUT", "operation", FailurePhase.Operation,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(OperationKind.Read),
            )
                to GattFailureFactory.timeoutFailure(session, scheduled.operation),
            Shape(
                "timeoutFailure(scheduled)", "OPERATION_TIMEOUT", "operation", FailurePhase.Operation,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(OperationKind.Read),
            )
                to GattFailureFactory.timeoutFailure(session, scheduled),
            Shape(
                "operationStatusFailure", "GATT_STATUS_FAILED", "operation", FailurePhase.Operation, Retryability.Unknown,
                operationKind = Check.Expect(OperationKind.Read),
                operationId = Check.Expect(scheduled.operationId),
                platformDetail = Check.Expect(
                    PlatformDetail.Android(
                        domain = "android.bluetooth.gatt.status",
                        statusCode = 133,
                        apiOperation = "onCharacteristicRead",
                    ),
                ),
            )
                to GattFailureFactory.operationStatusFailure(session, scheduled, status = 133),
            Shape(
                "operationStartFailure", "GATT_OPERATION_START_FAILED", "operation", FailurePhase.Operation, Retryability.Unknown,
                operationKind = Check.Expect(OperationKind.Read),
                platformDetail = Check.Expect(
                    PlatformDetail.Android(
                        domain = "android.bluetooth.gatt.start",
                        apiOperation = "readCharacteristic",
                    ),
                ),
            )
                to GattFailureFactory.operationStartFailure(session, scheduled.operation),
            Shape("callbackMismatch", "GATT_OPERATION_CALLBACK_MISMATCH", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.callbackMismatch(session, 133, "onCharacteristicRead"),
            Shape("duplicateCallback", "GATT_OPERATION_DUPLICATE_CALLBACK", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.duplicateCallback(session, 133, "onCharacteristicRead"),
            Shape(
                "disconnectCleanupTimeout", "DISCONNECT_CLEANUP_TIMEOUT", "connection", FailurePhase.Close,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(null),
            )
                to GattFailureFactory.disconnectCleanupTimeout(session),
            Shape(
                "disconnectStartFailed", "DISCONNECT_START_FAILED", "connection", FailurePhase.Close,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(null),
            )
                to GattFailureFactory.disconnectStartFailed(session),
            Shape(
                "duplicateCharacteristicReference", "DUPLICATE_CHARACTERISTIC_REFERENCE", "connection",
                FailurePhase.DiscoverServices, Retryability.Never, operationKind = Check.Expect(OperationKind.Subscribe),
            )
                to GattFailureFactory.duplicateCharacteristicReference(session),
            Shape(
                "payloadObserved(scheduled)", "GATT_PAYLOAD_OBSERVED", "diagnostic", FailurePhase.Operation, Retryability.Never,
                operationKind = Check.Expect(OperationKind.Read),
                operationId = Check.Expect(scheduled.operationId),
            )
                to GattFailureFactory.payloadObserved(session, scheduled),
            Shape(
                "payloadObserved(null)", "GATT_PAYLOAD_OBSERVED", "diagnostic", FailurePhase.Operation, Retryability.Never,
                operationKind = Check.Expect(null),
                operationId = Check.Expect(null),
            )
                to GattFailureFactory.payloadObserved(session, scheduled = null),
            // lifecycleTimeout — 3 branches
            Shape(
                "lifecycleTimeout(LinkTimeout)", "LINK_TIMEOUT", "connection", FailurePhase.Link,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(null),
            )
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.LinkTimeout),
            Shape(
                "lifecycleTimeout(ServiceDiscoveryTimeout)", "SERVICE_DISCOVERY_TIMEOUT", "connection",
                FailurePhase.DiscoverServices, Retryability.NewConnectionMayHelp, operationKind = Check.Expect(null),
            )
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.ServiceDiscoveryTimeout),
            Shape(
                "lifecycleTimeout(ConfigurationTimeout)", "CONFIGURATION_TIMEOUT", "connection", FailurePhase.Configure,
                Retryability.NewConnectionMayHelp, operationKind = Check.Expect(null),
            )
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.ConfigurationTimeout),
            // status-failed — 3 variants (non-zero status)
            Shape(
                "linkStatusFailed", "LINK_FAILED", "connection", FailurePhase.Link, Retryability.Unknown,
                operationKind = Check.Expect(null), platformStatusCode = Check.Expect(133),
            )
                to GattFailureFactory.linkStatusFailed(session, status = 133),
            Shape(
                "serviceDiscoveryStatusFailed", "SERVICE_DISCOVERY_FAILED", "connection", FailurePhase.DiscoverServices,
                Retryability.Unknown, operationKind = Check.Expect(null), platformStatusCode = Check.Expect(133),
            )
                to GattFailureFactory.serviceDiscoveryStatusFailed(session, status = 133),
            Shape(
                "mtuStatusFailed", "CONFIGURATION_FAILED", "connection", FailurePhase.Configure, Retryability.Unknown,
                operationKind = Check.Expect(null), platformStatusCode = Check.Expect(133),
            )
                to GattFailureFactory.mtuStatusFailed(session, status = 133),
            // startRejected — Connect/DiscoverServices/Configure/Operation
            Shape("startRejected(Connect)", "LINK_START_FAILED", "connection", FailurePhase.Connect, Retryability.Unknown)
                to GattFailureFactory.startRejected(session, FailurePhase.Connect, AndroidApiOperation.ConnectGatt),
            Shape("startRejected(DiscoverServices)", "SERVICE_DISCOVERY_START_FAILED", "connection", FailurePhase.DiscoverServices, Retryability.Unknown)
                to GattFailureFactory.startRejected(session, FailurePhase.DiscoverServices, AndroidApiOperation.DiscoverServices),
            Shape("startRejected(Configure)", "GATT_OPERATION_START_FAILED", "operation", FailurePhase.Configure, Retryability.Unknown)
                to GattFailureFactory.startRejected(session, FailurePhase.Configure, AndroidApiOperation.RequestMtu),
            Shape("startRejected(Operation)", "GATT_OPERATION_START_FAILED", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.startRejected(session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = OperationKind.Subscribe),
            // OperationQueue write helpers 흡수(plan gatt-bridge-split task 3) — kind는 항상 WriteWithResponse.
            Shape(
                "writeTimeout", "OPERATION_TIMEOUT", "operation", FailurePhase.Operation, Retryability.NewConnectionMayHelp,
                operationKind = Check.Expect(OperationKind.WriteWithResponse),
            )
                to GattFailureFactory.writeTimeout(session, OperationKind.WriteWithResponse),
            Shape(
                "writeInvalidated", "OPERATION_INVALIDATED", "operation", FailurePhase.Operation, Retryability.NewConnectionMayHelp,
                operationKind = Check.Expect(OperationKind.WriteWithResponse),
            )
                to GattFailureFactory.writeInvalidated(session, OperationKind.WriteWithResponse),
            Shape(
                "writeStatusFailed", "GATT_STATUS_FAILED", "operation", FailurePhase.Operation, Retryability.Unknown,
                operationKind = Check.Expect(OperationKind.WriteWithResponse), platformStatusCode = Check.Expect(133),
            )
                to GattFailureFactory.writeStatusFailed(session, status = 133, kind = OperationKind.WriteWithResponse),
        )

        assertEquals("표 행 수 — 행이 조용히 빠지면 여기서 잡힌다", 31, expectations.size)
        expectations.forEach { (expected, actual) ->
            assertEquals("${expected.name} code", expected.code, actual.code.value)
            assertEquals("${expected.name} category", expected.category, actual.category.value)
            assertEquals("${expected.name} phase", expected.phase, actual.phase)
            assertEquals("${expected.name} retryability", expected.retryability, actual.retryability)

            if (expected.sessionAttributed) {
                assertEquals("${expected.name} sessionId", session.failureSessionId, actual.context.sessionId)
            } else {
                assertNull("${expected.name} sessionId", actual.context.sessionId)
            }

            when (val check = expected.operationKind) {
                Check.Skip -> Unit
                is Check.Expect -> assertEquals("${expected.name} operationKind", check.value, actual.context.operationKind)
            }
            when (val check = expected.operationId) {
                Check.Skip -> Unit
                is Check.Expect -> assertEquals("${expected.name} operationId", check.value, actual.context.operationId)
            }
            when (val check = expected.platformDetail) {
                Check.Skip -> Unit
                is Check.Expect -> assertEquals("${expected.name} platformDetail", check.value, actual.platformDetail)
            }
            when (val check = expected.platformStatusCode) {
                Check.Skip -> Unit
                is Check.Expect -> assertEquals(
                    "${expected.name} platformDetail.statusCode",
                    check.value,
                    (actual.platformDetail as PlatformDetail.Android).statusCode,
                )
            }
        }
    }

    @Test
    fun `androidOperation maps all 6 kinds x callback true-false to their wire names`() {
        data class Case(val kind: GattOperationKind, val callback: Boolean, val expected: AndroidApiOperation)

        val cases = listOf(
            Case(GattOperationKind.Read, true, AndroidApiOperation.OnCharacteristicRead),
            Case(GattOperationKind.Read, false, AndroidApiOperation.ReadCharacteristic),
            Case(GattOperationKind.WriteWithResponse, true, AndroidApiOperation.OnCharacteristicWrite),
            Case(GattOperationKind.WriteWithResponse, false, AndroidApiOperation.WriteCharacteristic),
            Case(GattOperationKind.WriteWithoutResponse, true, AndroidApiOperation.OnCharacteristicWrite),
            Case(GattOperationKind.WriteWithoutResponse, false, AndroidApiOperation.WriteCharacteristic),
            Case(GattOperationKind.Subscribe, true, AndroidApiOperation.OnDescriptorWrite),
            Case(GattOperationKind.Subscribe, false, AndroidApiOperation.WriteDescriptor),
            Case(GattOperationKind.Unsubscribe, true, AndroidApiOperation.OnDescriptorWrite),
            Case(GattOperationKind.Unsubscribe, false, AndroidApiOperation.WriteDescriptor),
            Case(GattOperationKind.RequestMtu, true, AndroidApiOperation.OnMtuChanged),
            Case(GattOperationKind.RequestMtu, false, AndroidApiOperation.RequestMtu),
        )

        assertEquals(12, cases.size)
        cases.forEach { case ->
            assertEquals(
                "${case.kind} callback=${case.callback}",
                case.expected,
                GattFailureFactory.androidOperation(case.kind, case.callback),
            )
        }
    }

    @Test
    fun `failureFor attributes context from scheduled operation and preserves invalidation reason`() {
        val session = newSession()
        val scheduled = scheduledRead(session)
        val input = GattFailureFactory.timeoutFailure(session, scheduled.operation).copy(
            context = FailureContext(invalidationReason = kr.co.nucode.ble.ConnectionInvalidationReason.LinkLost),
        )

        val attributed = GattFailureFactory.failureFor(session, scheduled, input)

        assertEquals(session.failureSessionId, attributed.context.sessionId)
        assertEquals(scheduled.operation.generation, attributed.context.connectionGeneration)
        assertEquals(scheduled.operationId, attributed.context.operationId)
        assertEquals(OperationKind.Read, attributed.context.operationKind)
        assertEquals(kr.co.nucode.ble.ConnectionInvalidationReason.LinkLost, attributed.context.invalidationReason)
    }

    @Test
    fun `callbackMismatch never attributes an operationId and preserves scheduler generation`() {
        val session = newSession()
        val scheduler = session.gattOperationScheduler
        scheduledRead(session) // scheduler.currentGeneration stays 0, but exercised via a real enqueue

        val failure = GattFailureFactory.callbackMismatch(
            session = session,
            status = 133,
            callbackKind = "onCharacteristicRead",
        )

        assertNull(failure.context.operationId)
        assertEquals(scheduler.currentGeneration, failure.context.connectionGeneration)
        assertEquals(
            PlatformDetail.Android(
                domain = "android.bluetooth.gatt.callback",
                statusCode = 133,
                apiOperation = "onCharacteristicRead",
            ),
            failure.platformDetail,
        )
    }

    @Test
    fun `unknownDevice carries an empty FailureContext`() {
        val failure = GattFailureFactory.unknownDevice()

        assertEquals(FailureContext(), failure.context)
    }

    @Test
    fun `permissionDenied and startRejected set operationKind only when kind is supplied`() {
        val session = newSession()

        val permissionWithoutKind = GattFailureFactory.permissionDenied(
            session,
            FailurePhase.Connect,
            AndroidApiOperation.ConnectGatt,
        )
        val permissionWithKind = GattFailureFactory.permissionDenied(
            session,
            FailurePhase.Operation,
            AndroidApiOperation.ReadCharacteristic,
            kind = OperationKind.Read,
        )
        val startWithoutKind = GattFailureFactory.startRejected(
            session,
            FailurePhase.Connect,
            AndroidApiOperation.ConnectGatt,
        )
        val startWithKind = GattFailureFactory.startRejected(
            session,
            FailurePhase.Operation,
            AndroidApiOperation.WriteDescriptor,
            kind = OperationKind.Subscribe,
        )

        assertNull(permissionWithoutKind.context.operationKind)
        assertEquals(OperationKind.Read, permissionWithKind.context.operationKind)
        assertNull(startWithoutKind.context.operationKind)
        assertEquals(OperationKind.Subscribe, startWithKind.context.operationKind)
    }
}
