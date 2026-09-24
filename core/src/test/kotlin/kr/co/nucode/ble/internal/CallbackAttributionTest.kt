package kr.co.nucode.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.Attribution
import kr.co.nucode.ble.internal.gatt.DropReason
import kr.co.nucode.ble.internal.gatt.CallbackAttribution
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.TerminalCause
import kr.co.nucode.ble.internal.session.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallbackAttributionTest {

    private val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")

    private enum class Kind { LinkUp, Down0, Down8, Services, Mtu }

    private enum class Gen { Same, Different, Null }

    private fun event(kind: Kind, source: GattCallbackSource, gen: Long?): GattDriverEvent = when (kind) {
        Kind.LinkUp -> GattDriverEvent.ConnectionStateChanged(
            deviceId, source, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED, gen,
        )
        Kind.Down0 -> GattDriverEvent.ConnectionStateChanged(
            deviceId, source, 0, BluetoothProfile.STATE_DISCONNECTED, gen,
        )
        Kind.Down8 -> GattDriverEvent.ConnectionStateChanged(
            deviceId, source, 8, BluetoothProfile.STATE_DISCONNECTED, gen,
        )
        Kind.Services -> GattDriverEvent.ServicesDiscovered(
            deviceId, source, BluetoothGatt.GATT_SUCCESS, emptyList(), gen,
        )
        Kind.Mtu -> GattDriverEvent.MtuChanged(deviceId, source, 185, BluetoothGatt.GATT_SUCCESS, gen)
    }

    /** generation 1 을 연 뒤 [stage] 에 도달한 세션. */
    private fun TestScope.sessionAt(stage: ConnectionStage, suppress: Boolean): Session {
        val session = Session(deviceId)
        val c = session.connectionCoordinator
        val g = c.beginConnection(this, 10_000) {}
        when (stage) {
            ConnectionStage.Connecting -> Unit
            ConnectionStage.Ready -> {
                c.transition(g, ConnectionStage.Connecting, ConnectionStage.DiscoveringServices, this, 10_000)
                c.transition(g, ConnectionStage.DiscoveringServices, ConnectionStage.Configuring, this, 10_000)
                c.transition(g, ConnectionStage.Configuring, ConnectionStage.Ready, this)
            }
            ConnectionStage.Disconnecting ->
                c.beginCleanup(g, cause(), this, 10_000) {}
            ConnectionStage.Disconnected -> c.completeCleanup(g, cause())
            else -> error("unused stage $stage")
        }
        assertEquals(stage, c.stage)
        session.suppressReconnect = suppress
        return session
    }

    private fun cause() = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null)

    private fun expected(
        sourceCurrent: Boolean,
        gen: Gen,
        stage: ConnectionStage,
        kind: Kind,
        suppress: Boolean,
    ): Attribution {
        if (!sourceCurrent) return Attribution.Drop(DropReason.StaleHandle)
        val ended = stage == ConnectionStage.Disconnected
        return when (gen) {
            Gen.Same -> if (ended) Attribution.Drop(DropReason.StaleGeneration) else Attribution.Current(1L)
            Gen.Different -> Attribution.Drop(DropReason.StaleGeneration)
            Gen.Null -> when {
                !ended -> Attribution.Current(1L)
                kind == Kind.LinkUp && !suppress -> Attribution.Bootstrap
                else -> Attribution.Drop(DropReason.EndedGeneration)
            }
        }
    }

    @Test
    fun `귀속 판정 표 — 출처 x generation x stage x 콜백 종류 x suppressReconnect`() = runTest {
        val stages = listOf(
            ConnectionStage.Connecting,
            ConnectionStage.Ready,
            ConnectionStage.Disconnecting,
            ConnectionStage.Disconnected,
        )
        var cases = 0
        for (sourceCurrent in listOf(true, false)) for (gen in Gen.values()) for (stage in stages)
            for (kind in Kind.values()) for (suppress in listOf(false, true)) {
                val session = sessionAt(stage, suppress)
                val explicit = when (gen) {
                    Gen.Same -> 1L
                    Gen.Different -> 7L
                    Gen.Null -> null
                }
                val actual = CallbackAttribution.attribute(
                    session,
                    event(kind, GattCallbackSource { sourceCurrent }, explicit),
                )
                assertEquals(
                    "source=$sourceCurrent gen=$gen stage=$stage kind=$kind suppress=$suppress",
                    expected(sourceCurrent, gen, stage, kind, suppress),
                    actual,
                )
                // 판정은 상태를 바꾸지 않는다.
                assertEquals(stage, session.connectionCoordinator.stage)
                assertEquals(1L, session.connectionCoordinator.generation)
                cases++
            }
        assertEquals(2 * 3 * 4 * 5 * 2, cases)
    }

    private fun operationEvents(source: GattCallbackSource): List<GattDriverEvent> = listOf(
        GattDriverEvent.CharacteristicChanged(deviceId, source, null, byteArrayOf(1)),
        GattDriverEvent.CharacteristicRead(deviceId, source, null, byteArrayOf(1), BluetoothGatt.GATT_SUCCESS),
        GattDriverEvent.CharacteristicWrite(deviceId, source, BluetoothGatt.GATT_SUCCESS),
        GattDriverEvent.DescriptorWrite(deviceId, source, BluetoothGatt.GATT_SUCCESS),
    )

    @Test
    fun `operation 콜백 — 이전 handle 은 StaleHandle, 끝난 세션은 EndedGeneration, 살아 있는 generation 은 Current`() = runTest {
        for (event in operationEvents(GattCallbackSource { false })) {
            assertEquals(
                "$event",
                Attribution.Drop(DropReason.StaleHandle),
                CallbackAttribution.attribute(sessionAt(ConnectionStage.Ready, false), event),
            )
        }
        for (suppress in listOf(false, true)) for (event in operationEvents(GattCallbackSource { true })) {
            assertEquals(
                "$event suppress=$suppress",
                Attribution.Drop(DropReason.EndedGeneration),
                CallbackAttribution.attribute(sessionAt(ConnectionStage.Disconnected, suppress), event),
            )
            assertEquals(
                "$event",
                Attribution.Current(1L),
                CallbackAttribution.attribute(sessionAt(ConnectionStage.Ready, suppress), event),
            )
        }
        // 끝난 세션이 새 재연결 generation 을 열어 Connecting 이면 그 generation 의 사실이다.
        val reconnecting = sessionAt(ConnectionStage.Disconnected, false)
        val next = reconnecting.connectionCoordinator.beginConnection(this, 10_000) {}
        assertEquals(ConnectionStage.Connecting, reconnecting.connectionCoordinator.stage)
        for (event in operationEvents(GattCallbackSource { true })) {
            assertEquals("$event", Attribution.Current(next), CallbackAttribution.attribute(reconnecting, event))
        }
    }

    @Test
    fun `callback 출처 판정은 CallbackAttribution 에만 있다`() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("source.matches(") }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertEquals(listOf("src/main/kotlin/kr/co/nucode/ble/internal/gatt/CallbackAttribution.kt"), offenders)
    }

    @Test
    fun `lifecycle 은 null generation 을 현재 generation 으로 추정하지 않는다`() {
        val source = File("src/main/kotlin/kr/co/nucode/ble/internal/gatt/GattConnectionLifecycle.kt").readText()
        assertFalse(source.contains("connectionGeneration ?:"))
    }
}
