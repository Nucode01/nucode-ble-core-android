package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattServiceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * read·write·subscribe 진입 검사 순서(contract/states.md §5 명령 진입 검사 순서) 표 시험:
 * 닫힘(`CLIENT_CLOSED`) → 어댑터(§6 판정표, Unknown 은 최대 2000ms 대기) → `NOT_READY`(세션 단계 무관, 정착 창 포함)
 * → `CHARACTERISTIC_NOT_FOUND`. 진입 검사는 `NOT_CONNECTED` 를 내지 않는다. unsubscribe 는 닫힘만 본다(best-effort).
 * iOS 는 같은 표를 `ConnectionCoreTests.test_gattAdmissionAndRegistration`(C22) 이 판정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattAdmissionOrderTest {

    private val id = PeripheralId("AA:BB:CC:DD:EE:A5")
    private val serviceUuid = UUID.fromString("6e7a0001-5c3a-4b8e-9d1f-0a1b2c3d4e5f")
    private val charUuid = UUID.fromString("6e7a0002-5c3a-4b8e-9d1f-0a1b2c3d4e5f")
    private val ref = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))

    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private val manual = BleConnectOptions(autoReconnect = false)
    private val auto = BleConnectOptions(autoReconnect = true)

    /** 세션 단계를 만든다. 이름은 표 표기용 */
    private enum class Phase { Initial, Connecting, Ready, Settle, Recovering, Failed, Requested, Closed }

    private enum class Op { Read, Write, Subscribe }

    /** 세션 view 는 닫기 전에 얻어 둔다(닫힌 뒤 `session(id)` 는 CLIENT_CLOSED 를 던진다) */
    private class Rig(val h: Harness, val session: BleSession)

    private fun TestScope.setUp(phase: Phase): Rig {
        val h = harness(config = config)
        h.fake.services = listOf(h.fake.services.first(), serviceSnapshot())
        val session = h.session(id)
        when (phase) {
            Phase.Initial -> Unit
            Phase.Connecting -> {
                h.fake.onConnect = CallbackVariant.Missing
                h.connect(id, manual)
            }
            Phase.Ready -> h.connectToReady(id, manual)
            Phase.Settle -> {
                h.connectToReady(id, manual)
                h.fake.linkLoss(id, status = 19)
                runCurrent()
            }
            Phase.Recovering -> {
                h.connectToReady(id, auto)
                h.fake.linkLoss(id, status = 19)
                h.waitUntil("Recovering") { h.session(id).connectionState.value is ConnectionState.Recovering }
            }
            Phase.Failed -> {
                h.connectToReady(id, manual)
                h.fake.linkLoss(id, status = 19)
                h.waitUntil("정착 창 뒤 Disconnected") { h.session(id).connectionState.value is ConnectionState.Disconnected }
            }
            Phase.Requested -> {
                h.connectToReady(id, manual)
                h.disconnect(id)
                h.advancePastAllDeadlines()
            }
            Phase.Closed -> {
                h.connectToReady(id, manual)
                h.client.close()
                runCurrent()
            }
        }
        return Rig(h, session)
    }

    private fun TestScope.setAdapter(r: Rig, state: AdapterState) {
        r.h.fake.setAdapter(state)
        runCurrent()
    }

    /** 명령 하나의 결과: "ok" 또는 실패 코드. Unknown 대기(2000ms)를 넘길 만큼만 가상 시간을 보낸다 */
    private fun TestScope.outcome(r: Rig, op: Op): String {
        val session = r.session
        val job = async {
            runCatching {
                when (op) {
                    Op.Read -> session.read(ref)
                    Op.Write -> session.write(ref, byteArrayOf(1))
                    Op.Subscribe -> session.subscribe(ref)
                }
            }
        }
        runCurrent()
        var steps = 0
        while (!job.isCompleted && steps++ < 300) {
            advanceTimeBy(10)
            runCurrent()
        }
        val result = job.getCompleted()
        val error = result.exceptionOrNull() ?: return "ok"
        return (error as? BleException)?.failure?.code?.value ?: error::class.simpleName.orEmpty()
    }

    private fun TestScope.unsubscribeOutcome(r: Rig): String {
        val session = r.session
        val job = async { runCatching { session.unsubscribe(ref) } }
        runCurrent()
        val error = job.getCompleted().exceptionOrNull() ?: return "ok"
        return (error as? BleException)?.failure?.code?.value ?: error::class.simpleName.orEmpty()
    }

    private fun serviceSnapshot() = GattServiceSnapshot(
        serviceUuid = serviceUuid,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                instanceIndex = 0,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            ),
        ),
    )

    @Test
    fun `어댑터가 Ready 면 세션이 Ready 가 아닌 모든 단계에서 NOT_READY 다`() = runTest {
        val rows = listOf(
            Phase.Initial to "NOT_READY",
            Phase.Connecting to "NOT_READY",
            Phase.Settle to "NOT_READY",
            Phase.Recovering to "NOT_READY",
            Phase.Failed to "NOT_READY",
            Phase.Requested to "NOT_READY",
            Phase.Closed to "CLIENT_CLOSED",
        )
        val actual = mutableListOf<String>()
        val expected = mutableListOf<String>()
        for ((phase, code) in rows) {
            for (op in Op.values()) {
                val r = setUp(phase)
                if (phase == Phase.Settle) {
                    assertEquals("정착 창 동안 공개 상태는 Ready", ConnectionState.Ready, r.session.connectionState.value)
                }
                actual += "$phase×$op=${outcome(r, op)}"
                expected += "$phase×$op=$code"
                r.h.close()
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `어댑터가 Ready 가 아니면 세션 단계와 무관하게 어댑터 판정이 먼저다`() = runTest {
        val adapters = listOf(
            AdapterState.PoweredOff to "ADAPTER_UNAVAILABLE",
            AdapterState.Unauthorized(canRequest = true) to "PERMISSION_DENIED",
            AdapterState.Unauthorized(canRequest = false) to "PERMISSION_DENIED",
            AdapterState.Unsupported to "BLE_UNSUPPORTED",
            // Unknown 은 connect 와 같이 최대 2000ms 기다린 뒤 판정한다(states.md §6)
            AdapterState.Unknown to "ADAPTER_UNAVAILABLE",
        )
        val actual = mutableListOf<String>()
        val expected = mutableListOf<String>()
        for (phase in listOf(Phase.Initial, Phase.Ready, Phase.Settle, Phase.Recovering)) {
            for ((adapter, code) in adapters) {
                for (op in Op.values()) {
                    val r = setUp(phase)
                    setAdapter(r, adapter)
                    actual += "$phase×$adapter×$op=${outcome(r, op)}"
                    expected += "$phase×$adapter×$op=$code"
                    r.h.close()
                }
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `닫힘은 어댑터보다 먼저다`() = runTest {
        for (op in Op.values()) {
            val r = setUp(Phase.Ready)
            setAdapter(r, AdapterState.PoweredOff)
            r.h.client.close()
            runCurrent()
            assertEquals("$op", "CLIENT_CLOSED", outcome(r, op))
            r.h.close()
        }
    }

    @Test
    fun `Unknown 대기 중 Ready 로 풀리면 세션 검사로 넘어가 NOT_READY 다`() = runTest {
        val r = setUp(Phase.Initial)
        setAdapter(r, AdapterState.Unknown)
        val session = r.session
        val job = async { runCatching { session.read(ref) } }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("아직 대기 중", false, job.isCompleted)
        setAdapter(r, AdapterState.Ready)
        val error = job.getCompleted().exceptionOrNull()
        assertEquals("NOT_READY", (error as? BleException)?.failure?.code?.value)
        r.h.close()
    }

    // 4 단계(CHARACTERISTIC_NOT_FOUND)는 Android 에서 큐가 작업을 시작할 때 대상 해석으로 판정한다(OperationExecutor.startPlatformOperation).
    // 이 하네스의 가짜 resolver 는 모든 참조를 해석하므로 여기서 보지 않는다 — BleCoreWiringTest
    // "Slice1 public read는 내부 start rejection을 그대로 전달하고 scheduler를 비운다"(FailureRouteVerificationTest 경로표)가 판정한다.

    @Test
    fun `unsubscribe 는 닫힘만 보고 어댑터·세션 단계로 거절하지 않는다`() = runTest {
        val rows = listOf(
            Triple(Phase.Initial, AdapterState.Ready, "ok"),
            Triple(Phase.Settle, AdapterState.Ready, "ok"),
            Triple(Phase.Recovering, AdapterState.Ready, "ok"),
            Triple(Phase.Ready, AdapterState.PoweredOff, "ok"),
            Triple(Phase.Initial, AdapterState.Unauthorized(canRequest = false), "ok"),
            Triple(Phase.Closed, AdapterState.Ready, "CLIENT_CLOSED"),
        )
        for ((phase, adapter, code) in rows) {
            val r = setUp(phase)
            if (adapter != AdapterState.Ready) setAdapter(r, adapter)
            assertEquals("$phase×$adapter", code, unsubscribeOutcome(r))
            r.h.close()
        }
    }
}
