package kr.co.nucode.ble.descriptor

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
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleLogger
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DescriptorRef
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticRecord
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.harness.CallbackVariant
import kr.co.nucode.ble.harness.Harness
import kr.co.nucode.ble.harness.gapServiceSnapshot
import kr.co.nucode.ble.harness.harness
import kr.co.nucode.ble.internal.port.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.port.GattServiceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// ── 두 descriptor 시험 파일이 함께 쓰는 고정물 ───────────────────────────────

internal val DEVICE = PeripheralId("AA:BB:CC:DD:EE:D1")
internal val SERVICE_UUID: UUID = UUID.fromString("6e7a0001-5c3a-4b8e-9d1f-0a1b2c3d4e5f")
internal val CHAR_UUID: UUID = UUID.fromString("6e7a0002-5c3a-4b8e-9d1f-0a1b2c3d4e5f")
internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
internal val CPF_UUID: UUID = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb")
internal val CUD_UUID: UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")
internal val CHAR = CharacteristicRef(ServiceRef(BleUuid(SERVICE_UUID)), BleUuid(CHAR_UUID))
internal val CCCD = DescriptorRef(CHAR, BleUuid(CCCD_UUID))
internal val CPF0 = DescriptorRef(CHAR, BleUuid(CPF_UUID), 0)
internal val CPF1 = DescriptorRef(CHAR, BleUuid(CPF_UUID), 1)
internal val CUD = DescriptorRef(CHAR, BleUuid(CUD_UUID))

internal val TEST_CONFIG = BleConfig(
    operationTimeoutMillis = 50,
    linkTimeoutMillis = 100,
    discoveryTimeoutMillis = 100,
    configurationTimeoutMillis = 100,
    disconnectCleanupTimeoutMillis = 50,
    rediscoveryTimeoutMillis = 50,
)

/** 탐색 결과: characteristic 하나 아래 [CCCD, CPF, CPF, CUD](탐색 순서). */
internal fun TestScope.descriptorHarness(config: BleConfig = TEST_CONFIG): Harness {
    val h = harness(config)
    h.fake.services = listOf(
        gapServiceSnapshot(),
        GattServiceSnapshot(
            serviceUuid = SERVICE_UUID,
            characteristics = listOf(
                GattCharacteristicSnapshot(
                    serviceUuid = SERVICE_UUID,
                    charUuid = CHAR_UUID,
                    instanceIndex = 0,
                    propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    descriptorUuids = listOf(CCCD_UUID, CPF_UUID, CPF_UUID, CUD_UUID),
                ),
            ),
        ),
    )
    return h
}

/** 공개 호출 하나를 걸고 지금 시점까지 돌린다. 끝나지 않았으면 null. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> TestScope.call(block: suspend () -> T): Result<T>? {
    val job = async { runCatching { block() } }
    runCurrent()
    return if (job.isCompleted) job.getCompleted() else null
}

internal fun Result<*>?.failureCode(): String =
    ((this ?: fail("호출이 끝나지 않음") as Nothing).exceptionOrNull() as? BleException)?.failure?.code?.value ?: "ok"

/** logger 기록은 진단 worker 스레드에서 온다 — 실제 시간으로 잠깐 기다린다. */
internal fun awaitRecord(records: List<DiagnosticRecord>, predicate: (DiagnosticRecord) -> Boolean): DiagnosticRecord {
    repeat(200) {
        synchronized(records) { records.firstOrNull(predicate) }?.let { return it }
        Thread.sleep(10)
    }
    fail("기록이 오지 않음: ${synchronized(records) { records.toList() }}")
    error("unreachable")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DescriptorOperationTest {

    @Test
    fun `탐색 결과의 descriptor 는 탐색 순서이고 같은 UUID 는 순번을 가진다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)

        val characteristic = h.session(DEVICE).services.value.flatMap { it.characteristics }.single { it.ref == CHAR }
        assertEquals(listOf(CCCD, CPF0, CPF1, CUD), characteristic.descriptors)
        h.close()
    }

    @Test
    fun `readDescriptor 는 순번으로 고른 descriptor 의 값 사본을 돌려준다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.descriptorValue = { id -> if (id.descriptorUuid == CPF_UUID && id.descriptorInstanceIndex == 1) byteArrayOf(9) else byteArrayOf(1) }

        val result = call { h.session(DEVICE).readDescriptor(CPF1) }

        assertTrue(result!!.getOrThrow().contentEquals(byteArrayOf(9)))
        assertEquals(1, h.fake.calls.count { it == "readDescriptor" })
        h.close()
    }

    @Test
    fun `writeDescriptor 는 MTU 23 에서도 나누지 않고 한 번 쓴다`() = runTest {
        val h = descriptorHarness()
        h.fake.mtuResult = 23
        h.connectToReady(DEVICE)
        val before = h.fake.descriptorWrites.size

        val result = call { h.session(DEVICE).writeDescriptor(CUD, ByteArray(200) { it.toByte() }) }

        assertEquals("ok", result.failureCode())
        val writes = h.fake.descriptorWrites.drop(before)
        assertEquals(1, writes.size)
        assertEquals(200, writes.single().second.size)
        assertEquals(CUD_UUID, writes.single().first?.descriptorUuid)
        h.close()
    }

    @Test
    fun `513바이트는 PAYLOAD_TOO_LARGE 이고 아무것도 보내지 않는다, 512 는 보낸다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        val before = h.fake.descriptorWrites.size

        val tooLarge = call { h.session(DEVICE).writeDescriptor(CUD, ByteArray(513)) }
        assertEquals("PAYLOAD_TOO_LARGE", tooLarge.failureCode())
        assertEquals(before, h.fake.descriptorWrites.size)

        assertEquals("ok", call { h.session(DEVICE).writeDescriptor(CUD, ByteArray(512)) }.failureCode())
        assertEquals(before + 1, h.fake.descriptorWrites.size)
        h.close()
    }

    @Test
    fun `없는 descriptor 는 CHARACTERISTIC_NOT_FOUND 이고 문맥에 종류와 descriptor 가 있다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        val missing = DescriptorRef(CHAR, BleUuid(CPF_UUID), 2)

        val failure = (call { h.session(DEVICE).readDescriptor(missing) }!!.exceptionOrNull() as BleException).failure

        assertEquals("CHARACTERISTIC_NOT_FOUND", failure.code.value)
        assertEquals(OperationKind.ReadDescriptor, failure.context.operationKind)
        assertEquals(missing, failure.context.descriptor)
        assertEquals(ConnectionState.Ready, h.session(DEVICE).connectionState.value)
        h.close()
    }

    @Test
    fun `플랫폼이 동기로 거절하면 GATT_OPERATION_START_FAILED 다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.readStarts = false
        assertEquals("GATT_OPERATION_START_FAILED", call { h.session(DEVICE).readDescriptor(CUD) }.failureCode())
        h.fake.descriptorStarts = false
        assertEquals("GATT_OPERATION_START_FAILED", call { h.session(DEVICE).writeDescriptor(CUD, byteArrayOf(1)) }.failureCode())
        h.close()
    }

    @Test
    fun `응답이 없으면 작업 기한 뒤 OPERATION_TIMEOUT 이고 세션이 끝난다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.onRead = CallbackVariant.Missing
        val job = async { runCatching { h.session(DEVICE).readDescriptor(CUD) } }
        runCurrent()

        h.advancePastAllDeadlines()

        assertEquals("OPERATION_TIMEOUT", (job.getCompleted().exceptionOrNull() as BleException).failure.code.value)
        assertTrue(h.session(DEVICE).connectionState.value !is ConnectionState.Ready)
        h.close()
    }

    @Test
    fun `접수 뒤 링크를 잃으면 OPERATION_INVALIDATED 로 끝난다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.onRead = CallbackVariant.Missing
        val job = async { runCatching { h.session(DEVICE).readDescriptor(CUD) } }
        runCurrent()

        h.fake.linkLoss(DEVICE)
        h.advancePastAllDeadlines()

        assertEquals("OPERATION_INVALIDATED", (job.getCompleted().exceptionOrNull() as BleException).failure.code.value)
        h.close()
    }

    @Test
    fun `같은 큐에서 앞선 characteristic read 가 끝난 뒤에 descriptor read 가 시작된다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.onRead = CallbackVariant.Late(20)
        val read = async { h.session(DEVICE).read(CHAR) }
        val descriptorRead = async { h.session(DEVICE).readDescriptor(CUD) }
        runCurrent()
        assertTrue("read" in h.fake.calls)
        assertTrue("readDescriptor" !in h.fake.calls)

        advanceTimeBy(25)
        runCurrent()
        assertTrue("readDescriptor" in h.fake.calls)
        advanceTimeBy(25)
        runCurrent()
        assertTrue(read.isCompleted && descriptorRead.isCompleted)
        h.close()
    }

    @Test
    fun `호출자를 취소해도 접수된 descriptor read 는 끝까지 가고 세션은 Ready 다`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.onRead = CallbackVariant.Late(20)
        val job = async { h.session(DEVICE).readDescriptor(CUD) }
        runCurrent()

        job.cancel()
        advanceTimeBy(30)
        runCurrent()

        assertEquals(1, h.fake.calls.count { it == "readDescriptor" })
        assertEquals(ConnectionState.Ready, h.session(DEVICE).connectionState.value)
        h.close()
    }

    @Test
    fun `진입 검사 순서 - 닫힘이 어댑터보다 먼저`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        val session = h.session(DEVICE)
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        h.client.close()
        runCurrent()
        assertEquals("CLIENT_CLOSED", call { session.readDescriptor(CUD) }.failureCode())
    }

    @Test
    fun `진입 검사 순서 - 어댑터가 Ready 아님보다 먼저`() = runTest {
        val h = descriptorHarness()
        h.connectToReady(DEVICE)
        h.fake.setAdapter(AdapterState.PoweredOff)
        runCurrent()
        assertEquals("ADAPTER_UNAVAILABLE", call { h.session(DEVICE).writeDescriptor(CUD, ByteArray(513)) }.failureCode())
        h.close()
    }

    @Test
    fun `진입 검사 순서 - Ready 아님이 크기보다, 크기가 참조보다 먼저`() = runTest {
        val h = descriptorHarness()
        h.session(DEVICE)
        assertEquals("NOT_READY", call { h.session(DEVICE).writeDescriptor(CUD, ByteArray(513)) }.failureCode())

        h.connectToReady(DEVICE)
        val missing = DescriptorRef(CHAR, BleUuid(UUID.randomUUID()))
        assertEquals("PAYLOAD_TOO_LARGE", call { h.session(DEVICE).writeDescriptor(missing, ByteArray(513)) }.failureCode())
        h.close()
    }

    @Test
    fun `descriptor payload 진단은 문맥에 descriptor 를 싣고 characteristic 의 safe 판정을 따른다`() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        val logger = BleLogger { synchronized(records) { records += it } }
        val options = DiagnosticOptions(rawPayloadsEnabled = true, safeCharacteristics = setOf(CHAR))
        val h = descriptorHarness(TEST_CONFIG.copy(logger = logger, diagnosticOptions = options))
        h.connectToReady(DEVICE)
        h.fake.descriptorValue = { byteArrayOf(7, 8) }

        call { h.session(DEVICE).readDescriptor(CUD) }
        call { h.session(DEVICE).writeDescriptor(CPF0, byteArrayOf(5)) }

        val rx = awaitRecord(records) { it.failure.context.descriptor == CUD }
        assertEquals(OperationKind.ReadDescriptor, rx.failure.context.operationKind)
        assertEquals(DiagnosticValue.Text("rx"), rx.values["payload_direction"])
        assertTrue((rx.values["payload"] as DiagnosticValue.Payload).bytes.contentEquals(byteArrayOf(7, 8)))
        val tx = awaitRecord(records) { it.failure.context.descriptor == CPF0 }
        assertEquals(OperationKind.WriteDescriptor, tx.failure.context.operationKind)
        assertEquals(DiagnosticValue.Text("tx"), tx.values["payload_direction"])
        h.close()
    }

    @Test
    fun `sensitive characteristic 의 descriptor payload 는 가린다`() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        val logger = BleLogger { synchronized(records) { records += it } }
        val options = DiagnosticOptions(rawPayloadsEnabled = true, sensitiveCharacteristics = setOf(CHAR))
        val h = descriptorHarness(TEST_CONFIG.copy(logger = logger, diagnosticOptions = options))
        h.connectToReady(DEVICE)

        call { h.session(DEVICE).readDescriptor(CUD) }

        val rx = awaitRecord(records) { it.failure.context.descriptor == CUD }
        assertEquals(DiagnosticValue.Redacted, rx.values["payload"])
        h.close()
    }
}
