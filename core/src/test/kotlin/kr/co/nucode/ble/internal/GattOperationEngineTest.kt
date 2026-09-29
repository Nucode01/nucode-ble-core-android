package kr.co.nucode.ble.internal

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress
import kr.co.nucode.ble.internal.gatt.GattLifecycleHooks
import kr.co.nucode.ble.internal.gatt.GattOperationEngine
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan advisory-cleanup task 3 — [GattOperationEngine] hook 계약(lifecycle 방향)만 고정한다.
 * 128개 GattBridge/GattDriverSeamTest 시나리오와 중복하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationEngineTest {
    @Test
    fun `permission denied at operation start enters lifecycle cleanup through the hook`() = runTest {
        val registry = SessionRegistry(TestClock)
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnectionState.Ready) }
        val beginCleanupCalls = mutableListOf<Triple<TerminalCleanupReason, String?, Boolean>>()
        var terminateForOperationInvalidationCalled = false
        val hooks = object : GattLifecycleHooks {
            override fun beginCleanup(
                session: Session,
                reason: TerminalCleanupReason,
                cause: BleFailure?,
                suppressReconnect: Boolean,
            ): Boolean {
                beginCleanupCalls += Triple(reason, cause?.code?.value, suppressReconnect)
                return false
            }

            override fun terminateForOperationInvalidation(
                session: Session,
                cause: BleFailure,
                suppressReconnect: Boolean,
            ) {
                terminateForOperationInvalidationCalled = true
            }
        }
        val engine = GattOperationEngine(
            registry = registry,
            emit = {},
            scope = backgroundScope,
            driver = FakeGattDriver(),
            ackTimeoutMillis = 5_000,
            operationStarter = { _, _ -> throw SecurityException("no BLUETOOTH_CONNECT") },
            operationWriteTypeResolver = null,
            operationTargetResolver = null,
            diagnosticIngress = DiagnosticIngress.None,
            lifecycle = hooks,
        )

        val failure = runCatching {
            engine.read(deviceId, charRef(UUID.randomUUID(), UUID.randomUUID()))
        }.exceptionOrNull()

        assertTrue(failure is BleException)
        assertEquals("PERMISSION_DENIED", (failure as BleException).failure.code.value)
        assertEquals(1, beginCleanupCalls.size)
        val (reason, causeCode, suppressReconnect) = beginCleanupCalls.single()
        assertEquals(TerminalCleanupReason.PermissionDenied, reason)
        assertEquals("PERMISSION_DENIED", causeCode)
        assertTrue(suppressReconnect)
        assertFalse(terminateForOperationInvalidationCalled)
    }

    private class FakeGattDriver : NoopGattDriver()
}
