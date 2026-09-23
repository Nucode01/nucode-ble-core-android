package kr.co.nucode.ble.internal

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.PeripheralId
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
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId).apply { setStateForTest(ConnState.Ready) }
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
        assertEquals(ConnectionAttemptOrigin.Explicit, session.connectionCoordinator.attemptOrigin)
        assertTrue(suppressReconnect)
        assertFalse(terminateForOperationInvalidationCalled)
    }

    private class FakeGattDriver : NoopGattDriver()
}
