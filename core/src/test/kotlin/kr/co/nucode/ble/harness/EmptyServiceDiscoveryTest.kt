package kr.co.nucode.ble.harness

import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattServiceSnapshot
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * plan empty-service-discovery v1 task 1 — `onServicesDiscovered(GATT_SUCCESS)` 가 서비스 0개면
 * 탐색 status 실패와 같은 경로(Error Diagnostic + 종결)로 끝난다. 첫 연결·플랫폼 재연결 generation 모두.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmptyServiceDiscoveryTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:51")

    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private val gapUuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")

    private fun gapService() = GattServiceSnapshot(
        serviceUuid = gapUuid,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                serviceUuid = gapUuid,
                charUuid = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
                instanceIndex = 0,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
            ),
        ),
    )

    private fun discoveryDiagnostics(h: Harness) =
        h.events.filterIsInstance<OfficialBleClientEvent.Diagnostic>()
            .filter { it.failure.code.value == "SERVICE_DISCOVERY_FAILED" }

    private fun assertDiscoveryFailed(h: Harness) {
        val reason = (h.session(id).connectionState.value as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed
            ?: fail("Disconnected(Failed) 아님: ${h.states(id)}").let { return }
        assertEquals("SERVICE_DISCOVERY_FAILED", reason.failure.code.value)
        assertEquals(FailurePhase.DiscoverServices, reason.failure.phase)
        val diagnostics = discoveryDiagnostics(h)
        assertEquals("Diagnostic: ${h.events}", 1, diagnostics.size)
        assertEquals(Severity.Error, diagnostics.single().severity)
        assertTrue("handle 을 닫지 않았다: ${h.fake.calls}", "close" in h.fake.calls)
    }

    @Test
    fun `a 첫 연결에서 서비스 0개면 Ready 없이 Disconnected(Failed SERVICE_DISCOVERY_FAILED)`() = runTest {
        val h = harness(config)
        h.fake.services = emptyList()
        h.connect(id)
        h.advancePastAllDeadlines()
        assertTrue("Ready 를 거쳤다: ${h.states(id)}", ConnectionState.Ready !in h.states(id))
        assertDiscoveryFailed(h)
        h.close()
    }

    @Test
    fun `b 플랫폼 재연결 generation 에서 서비스 0개면 Recovering 에 머물지 않고 같은 종결`() = runTest {
        val h = harness(config)
        h.fake.services = listOf(gapService())
        h.connectToReady(id, BleConnectOptions(autoReconnect = true))
        h.fake.linkLoss(id, 8)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: Recovering 아님 ${h.states(id)}")
        h.fake.services = emptyList()
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.advancePastAllDeadlines()
        val afterRelink = h.states(id).dropWhile { it !is ConnectionState.Recovering }
        assertTrue("재연결 뒤 Ready 를 거쳤다: ${h.states(id)}", ConnectionState.Ready !in afterRelink)
        assertDiscoveryFailed(h)
        h.close()
    }
}
