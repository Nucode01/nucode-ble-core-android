package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Retryability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * plan gatt-failure-factory v1 task 1 — [GattFailureFactory]가 `GattBridge.kt`(HEAD d19cf99)
 * 1821~1999행 helper + 인라인 11곳에서 전사한 code/category/phase/retryability 값을 그대로
 * 재생산하는지 고정한다.
 */
class GattFailureFactoryTest {

    private fun newSession(): Session = Session(DeviceId("AA:BB:CC:DD:EE:FF"))

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

    private data class Shape(
        val name: String,
        val code: String,
        val category: String,
        val phase: FailurePhase,
        val retryability: Retryability,
    )

    @Test
    fun `27 failure shapes pin code, category, phase, retryability exactly`() {
        val session = newSession()
        val scheduled = scheduledRead(session)

        val expectations = listOf(
            Shape("notConnected", "NOT_CONNECTED", "connection", FailurePhase.Operation, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.notConnected(session),
            Shape("unknownDevice", "UNKNOWN_DEVICE", "validation", FailurePhase.Validation, Retryability.Never)
                to GattFailureFactory.unknownDevice(),
            Shape("peripheralNotFound", "PERIPHERAL_NOT_FOUND", "connection", FailurePhase.Connect, Retryability.TransientMayHelp)
                to GattFailureFactory.peripheralNotFound(session),
            Shape("characteristicNotFound", "ATTRIBUTE_NOT_FOUND", "validation", FailurePhase.Operation, Retryability.Never)
                to GattFailureFactory.characteristicNotFound(session),
            Shape("operationInvalidated(operation)", "OPERATION_INVALIDATED", "operation", FailurePhase.Operation, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.operationInvalidated(session, scheduled.operation),
            Shape("operationInvalidated(scheduled)", "OPERATION_INVALIDATED", "operation", FailurePhase.Operation, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.operationInvalidated(session, scheduled),
            Shape("timeoutFailure(operation)", "OPERATION_TIMEOUT", "operation", FailurePhase.Operation, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.timeoutFailure(session, scheduled.operation),
            Shape("timeoutFailure(scheduled)", "OPERATION_TIMEOUT", "operation", FailurePhase.Operation, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.timeoutFailure(session, scheduled),
            Shape("operationStatusFailure", "GATT_STATUS_FAILED", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.operationStatusFailure(session, scheduled, status = 133),
            Shape("operationStartFailure", "GATT_OPERATION_START_FAILED", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.operationStartFailure(session, scheduled.operation),
            Shape("callbackMismatch", "GATT_OPERATION_CALLBACK_MISMATCH", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.callbackMismatch(session, 133, "onCharacteristicRead"),
            Shape("duplicateCallback", "GATT_OPERATION_DUPLICATE_CALLBACK", "operation", FailurePhase.Operation, Retryability.Unknown)
                to GattFailureFactory.duplicateCallback(session, 133, "onCharacteristicRead"),
            Shape("disconnectCleanupTimeout", "DISCONNECT_CLEANUP_TIMEOUT", "connection", FailurePhase.Close, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.disconnectCleanupTimeout(session),
            Shape("disconnectStartFailed", "DISCONNECT_START_FAILED", "connection", FailurePhase.Close, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.disconnectStartFailed(session),
            Shape("duplicateCharacteristicReference", "DUPLICATE_CHARACTERISTIC_REFERENCE", "connection", FailurePhase.DiscoverServices, Retryability.Never)
                to GattFailureFactory.duplicateCharacteristicReference(session),
            Shape("payloadObserved", "GATT_PAYLOAD_OBSERVED", "diagnostic", FailurePhase.Operation, Retryability.Never)
                to GattFailureFactory.payloadObserved(session, scheduled),
            // lifecycleTimeout — 4 branches
            Shape("lifecycleTimeout(LinkTimeout)", "LINK_TIMEOUT", "connection", FailurePhase.Link, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.LinkTimeout),
            Shape("lifecycleTimeout(ServiceDiscoveryTimeout)", "SERVICE_DISCOVERY_TIMEOUT", "connection", FailurePhase.DiscoverServices, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.ServiceDiscoveryTimeout),
            Shape("lifecycleTimeout(ConfigurationTimeout)", "CONFIGURATION_TIMEOUT", "connection", FailurePhase.Configure, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.ConfigurationTimeout),
            Shape("lifecycleTimeout(else)", "LIFECYCLE_TIMEOUT", "connection", FailurePhase.Close, Retryability.NewConnectionMayHelp)
                to GattFailureFactory.lifecycleTimeout(session, TerminalCleanupReason.UserRequested),
            // status-failed — 3 variants (non-zero status)
            Shape("linkStatusFailed", "LINK_FAILED", "connection", FailurePhase.Link, Retryability.Unknown)
                to GattFailureFactory.linkStatusFailed(session, status = 133),
            Shape("serviceDiscoveryStatusFailed", "SERVICE_DISCOVERY_FAILED", "connection", FailurePhase.DiscoverServices, Retryability.Unknown)
                to GattFailureFactory.serviceDiscoveryStatusFailed(session, status = 133),
            Shape("mtuStatusFailed", "CONFIGURATION_FAILED", "connection", FailurePhase.Configure, Retryability.Unknown)
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
        )

        assertEquals(27, expectations.size)
        expectations.forEach { (expected, actual) ->
            assertEquals("${expected.name} code", expected.code, actual.code.value)
            assertEquals("${expected.name} category", expected.category, actual.category.value)
            assertEquals("${expected.name} phase", expected.phase, actual.phase)
            assertEquals("${expected.name} retryability", expected.retryability, actual.retryability)
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
