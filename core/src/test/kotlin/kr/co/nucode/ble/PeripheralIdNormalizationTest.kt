package kr.co.nucode.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.android.AndroidBondState
import kr.co.nucode.ble.android.AndroidBondPlatform
import kr.co.nucode.ble.android.BondObservation
import kr.co.nucode.ble.android.DefaultAndroidBondController
import kr.co.nucode.ble.harness.harness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralIdNormalizationTest {
    private val lower = PeripheralId("aa:bb:cc:dd:ee:ff")
    private val upper = PeripheralId("AA:BB:CC:DD:EE:FF")

    @Test
    fun `lower and upper case ids resolve to the same upper case session`() = runTest {
        val h = harness()

        val a = h.client.session(lower)
        val b = h.client.session(upper)

        assertEquals(upper, a.peripheralId)
        assertEquals(upper, b.peripheralId)
        assertSame(h.core.registry.get(lower), h.core.registry.get(upper))
        assertEquals(1, h.core.registry.all().size)
    }

    @Test
    fun `platform events and snapshot carry the upper case id for a lower case connect`() = runTest {
        val h = harness()

        assertEquals(ConnectionState.Ready, h.connectToReady(lower))

        val connectionEvents = h.events.filterIsInstance<OfficialBleClientEvent.Connection>()
        assertTrue(connectionEvents.isNotEmpty())
        assertTrue(connectionEvents.all { it.peripheralId == upper })
        val snapshot = h.client.snapshot()
        assertEquals(listOf(upper), snapshot.sessions.map { it.peripheralId })
    }

    @Test
    fun `bond controller passes the upper case id to the platform`() = runTest {
        val platform = RecordingBondPlatform()
        val controller = DefaultAndroidBondController(platform)

        controller.bondState(lower)
        controller.createBond(lower)

        assertTrue(platform.ids.isNotEmpty())
        assertTrue(platform.ids.toString(), platform.ids.all { it == upper })
    }

    private class RecordingBondPlatform : AndroidBondPlatform {
        val ids = mutableListOf<PeripheralId>()

        override fun bondState(peripheralId: PeripheralId): AndroidBondState {
            ids += peripheralId
            return AndroidBondState.Bonded
        }

        override fun beginBond(peripheralId: PeripheralId): Boolean {
            ids += peripheralId
            return true
        }

        override fun observeBondState(
            peripheralId: PeripheralId,
            listener: (AndroidBondState) -> Unit,
        ): BondObservation {
            ids += peripheralId
            return BondObservation { }
        }
    }
}
