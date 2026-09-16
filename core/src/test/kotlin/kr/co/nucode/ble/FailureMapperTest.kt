package kr.co.nucode.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FailureMapperGattStatusTest(
    private val statusCode: Int,
    private val expectedRetryability: Retryability,
    private val expectedSymbolicName: String?,
) {
    @Test
    fun `GATT callback status keeps raw status and maps only stable meaning`() {
        val failure = FailureMapper.gattStatusFailed(
            statusCode = statusCode,
            phase = FailurePhase.Operation,
            operation = AndroidApiOperation.OnCharacteristicWrite,
        )

        assertEquals(FailureCategory("operation"), failure.category)
        assertEquals(FailureCode("GATT_STATUS_FAILED"), failure.code)
        assertEquals(FailurePhase.Operation, failure.phase)
        assertEquals(expectedRetryability, failure.retryability)
        val detail = failure.platformDetail as PlatformDetail.Android
        assertEquals("android.bluetooth.gatt.status", detail.domain)
        assertEquals(statusCode, detail.statusCode)
        assertEquals("onCharacteristicWrite", detail.apiOperation)
        assertEquals(expectedSymbolicName, detail.symbolicName)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "status={0}")
        fun statuses(): Collection<Array<Any?>> = listOf(
            arrayOf(8, Retryability.AfterEnvironmentChange, "GATT_INSUFFICIENT_AUTHORIZATION"),
            arrayOf(133, Retryability.Unknown, null),
            arrayOf(9_999, Retryability.Unknown, null),
        )
    }
}

class FailureMapperTest {
    @Test
    fun `synthetic start rejection does not masquerade as Android status`() {
        val failure = FailureMapper.gattStartRejected(
            phase = FailurePhase.Operation,
            operation = AndroidApiOperation.WriteCharacteristic,
        )

        assertEquals(FailureCode("GATT_OPERATION_START_FAILED"), failure.code)
        assertEquals(Retryability.Unknown, failure.retryability)
        val detail = failure.platformDetail as PlatformDetail.Android
        assertEquals("android.bluetooth.gatt.start", detail.domain)
        assertNull(detail.statusCode)
        assertEquals("writeCharacteristic", detail.apiOperation)
    }

    @Test
    fun `synthetic negative status is rejected instead of becoming platform detail`() {
        assertThrows(IllegalArgumentException::class.java) {
            FailureMapper.gattStartRejected(
                phase = FailurePhase.Operation,
                operation = AndroidApiOperation.WriteCharacteristic,
                statusCode = -1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FailureMapper.gattStatusFailed(
                statusCode = -1,
                phase = FailurePhase.Operation,
                operation = AndroidApiOperation.OnCharacteristicWrite,
            )
        }
    }

    @Test
    fun `success status and invalid callback phase are rejected as mapper misuse`() {
        assertThrows(IllegalArgumentException::class.java) {
            FailureMapper.gattStatusFailed(
                statusCode = 0,
                phase = FailurePhase.Operation,
                operation = AndroidApiOperation.OnCharacteristicWrite,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FailureMapper.gattStatusFailed(
                statusCode = 133,
                phase = FailurePhase.Scan,
                operation = AndroidApiOperation.OnCharacteristicWrite,
            )
        }
    }

    @Test
    fun `environment states preserve stable retry policy without a throwable`() {
        val unsupported = FailureMapper.fromAdapterState(AdapterState.Unsupported)
        val requestablePermission = FailureMapper.fromAdapterState(AdapterState.Unauthorized(canRequest = true))
        val missingManifestPermission = FailureMapper.fromAdapterState(AdapterState.Unauthorized(canRequest = false))
        val poweredOff = FailureMapper.fromAdapterState(AdapterState.PoweredOff)

        assertEquals(FailureCode("BLE_UNSUPPORTED"), unsupported.code)
        assertEquals(Retryability.Never, unsupported.retryability)
        assertEquals(FailureCode("PERMISSION_DENIED"), requestablePermission.code)
        assertEquals(Retryability.AfterEnvironmentChange, requestablePermission.retryability)
        assertEquals(Retryability.Never, missingManifestPermission.retryability)
        assertEquals(FailureCode("ADAPTER_UNAVAILABLE"), poweredOff.code)
        assertEquals(Retryability.AfterEnvironmentChange, poweredOff.retryability)
    }

    @Test
    fun `mapper platform detail is limited to approved technical fields`() {
        val scanFailure = FailureMapper.scanRuntimeFailed(errorCode = 7)
        val detail = scanFailure.platformDetail as PlatformDetail.Android

        assertEquals("android.bluetooth.le.scan.error", detail.domain)
        assertEquals(7, detail.statusCode)
        assertNull(detail.reasonCode)
        assertEquals("onScanFailed", detail.apiOperation)
        assertNull(detail.symbolicName)
        assertTrue(AndroidApiOperation.entries.map { it.wireName }.contains(detail.apiOperation))
        assertFalse(PlatformDetail.Android::class.java.declaredFields.any { it.type == Throwable::class.java })
    }

    @Test
    fun `public failure context contains only opaque correlation metadata`() {
        val fieldNames = FailureContext::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(
            setOf(
                "sessionId",
                "connectionGeneration",
                "operationId",
                "operationKind",
                "invalidationReason",
            ),
            fieldNames,
        )
        assertFalse(fieldNames.any { it.contains("device", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("uuid", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("characteristic", ignoreCase = true) })
    }

    @Test
    fun `unknown future category and code wire tokens round-trip losslessly`() {
        val failure = BleFailure(
            category = FailureCategory("vendor_future_category"),
            code = FailureCode("FUTURE_TRANSPORT_CODE"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Unknown,
        )
        // Platform bridges serialize these two stable tokens as strings, then reconstruct their
        // value classes. They must not decode through a closed enum or discard a future value.
        val decoded = failure.copy(
            category = FailureCategory(failure.category.value),
            code = FailureCode(failure.code.value),
        )

        assertEquals("vendor_future_category", decoded.category.value)
        assertEquals("FUTURE_TRANSPORT_CODE", decoded.code.value)
        assertEquals(failure, decoded)
        assertEquals("FUTURE_TRANSPORT_CODE", BleException(failure).message)
        assertNull(BleException(failure).cause)
    }
}
