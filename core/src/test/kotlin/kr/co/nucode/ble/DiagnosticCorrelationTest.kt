package kr.co.nucode.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticCorrelationTest {
    @Test
    fun `diagnostic projection retains opaque operation correlation and unknown platform status`() {
        val failure = BleFailure(
            category = FailureCategory("vendor"),
            code = FailureCode("UNKNOWN_STATUS"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Unknown,
            context = FailureContext(
                sessionId = "session-opaque",
                connectionGeneration = 4,
                operationId = "gatt-4-19",
                operationKind = OperationKind.WriteWithResponse,
            ),
            platformDetail = PlatformDetail.Android(
                domain = "vendor.gatt.callback",
                statusCode = 731,
                apiOperation = "vendorWriteCallback",
            ),
        )

        val projected = DiagnosticFailure(failure)

        assertEquals(failure.context, projected.context)
        assertEquals(failure.platformDetail, projected.platformDetail)
    }
}
