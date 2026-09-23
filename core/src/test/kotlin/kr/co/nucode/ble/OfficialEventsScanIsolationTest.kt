package kr.co.nucode.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.scan.ScanObservation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * plan L task 1 — 스캔 관측은 `client.events` 버퍼를 채우지 않는다.
 * `events` 수집자가 본문에서 막힌 동안 스캔 관측이 용량 이상 들어와도,
 * 그 뒤의 Connection 이벤트는 수집자가 풀린 뒤 도착해야 한다.
 *
 * Unconfined + UNDISPATCHED 수집자와 CompletableDeferred 게이트로 순서를 결정적으로 만든다.
 */
class OfficialEventsScanIsolationTest {

    @Test(timeout = 5_000)
    fun `events 수집자가 막힌 동안 스캔이 몰려도 Connection 이벤트는 유실되지 않는다`() {
        val core = BleRuntime(
            config = BleConfig(eventBufferCapacity = 1),
            adapter = { null },
            adapterState = { AdapterState.Ready },
        )
        val client = DefaultBleClient(core)
        val id = PeripheralId("AA:BB:CC:DD:EE:31")
        client.session(id)
        val backing = requireNotNull(core.registry.get(PeripheralId(id.value)))
        val scanner = core.scanner
        scanner.startOverride = { _, _ -> null }
        scanner.startScan(BleScanFilter(), BleScanOptions())
        val observation = ScanObservation(
            PeripheralId("AA:BB:CC:DD:EE:32"),
            -50,
            AdvertisementData(null, emptySet(), emptyMap(), emptyMap(), null, null),
        )

        val states = mutableListOf<ConnectionState>()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event is OfficialBleClientEvent.Connection) {
                    states += event.state
                    gate.await() // 첫 Connection 을 쥔 채 본문에서 막힌다
                }
            }
        }
        try {
            backing.setStateForTest(ConnectionState.Connecting)
            core.emit(BleEvent.ConnStateChanged(backing.deviceId, ConnectionState.Connecting))

            repeat(5) { scanner.handle(observation) }

            backing.setStateForTest(ConnectionState.Disconnecting)
            core.emit(BleEvent.ConnStateChanged(backing.deviceId, ConnectionState.Disconnecting))
            gate.complete(Unit)

            assertEquals(
                listOf(ConnectionState.Connecting, ConnectionState.Disconnecting),
                states,
            )
        } finally {
            scope.cancel()
            client.close()
        }
    }
}
