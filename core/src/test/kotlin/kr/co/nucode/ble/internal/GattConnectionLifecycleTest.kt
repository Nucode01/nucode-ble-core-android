package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.ConnectOptions
import kr.co.nucode.ble.PeripheralId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * plan advisory-cleanup task 3 — [GattConnectionLifecycle] hook 계약(operations 방향)만 고정한다.
 * 128개 GattBridge/GattDriverSeamTest 시나리오와 중복하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattConnectionLifecycleTest {
    @Test
    fun `connect invalidates operations then opens a new generation through the hooks`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId)
        val calls = mutableListOf<String>()
        val hooks = object : GattOperationHooks {
            override fun invalidateAll(session: Session, failure: BleFailure) {
                calls += "invalidateAll"
            }

            override fun beginGenerationIfPoisoned(session: Session) {
                calls += "beginGenerationIfPoisoned"
            }
        }
        val lifecycle = GattConnectionLifecycle(
            registry = registry,
            emit = {},
            scope = this,
            driver = FakeGattDriver(),
            readinessTimeouts = ConnectionReadinessTimeouts(
                linkMillis = 15_000,
                discoveryMillis = 10_000,
                configurationMillis = 10_000,
            ),
            rediscovery = null,
            linkCacheWarm = null,
            operations = hooks,
        )

        lifecycle.connect(deviceId, ConnectOptions(autoConnect = false, timeoutMillis = null, requestedMtu = 185))

        assertEquals(listOf("invalidateAll", "beginGenerationIfPoisoned"), calls)
    }

    private class FakeGattDriver : NoopGattDriver()
}
