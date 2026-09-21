package kr.co.nucode.ble.android

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.PeripheralId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidBondControllerTest {
    private val peripheralId = PeripheralId("AA:BB:CC:DD:EE:FF")

    @Test
    fun `already bonded returns without observing or starting`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.Bonded)

        assertEquals(
            AndroidBondResult.AlreadyBonded,
            DefaultAndroidBondController(platform).createBond(peripheralId),
        )
        assertEquals(0, platform.beginCount)
        assertEquals(0, platform.observeCount)
    }

    @Test
    fun `none starts bonding and bonded event completes`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.None)
        val result = async { DefaultAndroidBondController(platform).createBond(peripheralId) }
        runCurrent()

        assertEquals(1, platform.beginCount)
        platform.emit(AndroidBondState.Bonded)
        assertEquals(AndroidBondResult.Bonded, result.await())
        assertEquals(1, platform.closeCount)
    }

    @Test
    fun `bonding returning to none fails`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.Bonding)
        val result = async { DefaultAndroidBondController(platform).createBond(peripheralId) }
        runCurrent()

        platform.emit(AndroidBondState.None)
        assertEquals(AndroidBondResult.Failed, result.await())
        assertEquals(1, platform.closeCount)
    }

    @Test
    fun `rejected start returns explicitly and closes observation`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.None, beginAccepted = false)

        assertEquals(
            AndroidBondResult.StartRejected,
            DefaultAndroidBondController(platform).createBond(peripheralId),
        )
        assertEquals(1, platform.closeCount)
    }

    @Test
    fun `timeout returns explicitly and closes observation`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.None)

        assertEquals(
            AndroidBondResult.TimedOut,
            DefaultAndroidBondController(platform).createBond(peripheralId, timeoutMillis = 100L),
        )
        assertEquals(1, platform.closeCount)
    }

    @Test
    fun `cancellation closes observation exactly once`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.None)
        val result = async { DefaultAndroidBondController(platform).createBond(peripheralId) }
        runCurrent()

        result.cancelAndJoin()
        assertEquals(1, platform.closeCount)
    }

    @Test
    fun `bond state delegates to platform`() {
        val platform = FakeBondPlatform(AndroidBondState.Bonding)

        assertEquals(
            AndroidBondState.Bonding,
            DefaultAndroidBondController(platform).bondState(peripheralId),
        )
        assertFalse(platform.closed)
        assertTrue(platform.observedIds.isEmpty())
    }

    private class FakeBondPlatform(
        var state: AndroidBondState,
        private val beginAccepted: Boolean = true,
    ) : AndroidBondPlatform {
        var beginCount = 0
        var observeCount = 0
        var closeCount = 0
        var closed = false
        val observedIds = mutableListOf<PeripheralId>()
        private var listener: ((AndroidBondState) -> Unit)? = null

        override fun bondState(peripheralId: PeripheralId): AndroidBondState = state

        override fun beginBond(peripheralId: PeripheralId): Boolean {
            beginCount += 1
            return beginAccepted
        }

        override fun observeBondState(
            peripheralId: PeripheralId,
            listener: (AndroidBondState) -> Unit,
        ): BondObservation {
            observeCount += 1
            observedIds += peripheralId
            this.listener = listener
            return BondObservation {
                if (!closed) {
                    closed = true
                    closeCount += 1
                }
            }
        }

        fun emit(state: AndroidBondState) {
            this.state = state
            listener?.invoke(state)
        }
    }
}
