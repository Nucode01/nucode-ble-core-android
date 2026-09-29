package kr.co.nucode.ble.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.PeripheralId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `exception before start returns start rejected`() = runTest {
        for (failure in listOf(SecurityException(), IllegalArgumentException(), IllegalStateException())) {
            val platform = FakeBondPlatform(AndroidBondState.None, beginFailure = failure)

            assertEquals(
                AndroidBondResult.StartRejected,
                DefaultAndroidBondController(platform).createBond(peripheralId),
            )
            assertEquals(1, platform.closeCount)
        }
    }

    @Test
    fun `state lookup exception returns start rejected`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.None, stateFailure = IllegalArgumentException("bad id"))

        assertEquals(
            AndroidBondResult.StartRejected,
            DefaultAndroidBondController(platform).createBond(peripheralId),
        )
        assertEquals(0, platform.observeCount)
    }

    @Test
    fun `exception after start attempt returns failed`() = runTest {
        val platform = FakeBondPlatform(
            AndroidBondState.None,
            beginAccepted = false,
            stateFailureAfterBegin = SecurityException(),
        )

        assertEquals(
            AndroidBondResult.Failed,
            DefaultAndroidBondController(platform).createBond(peripheralId),
        )
        assertEquals(1, platform.closeCount)
    }

    @Test
    fun `cancellation exception from platform propagates`() = runTest {
        val platform = FakeBondPlatform(AndroidBondState.None, beginFailure = CancellationException("x"))

        try {
            DefaultAndroidBondController(platform).createBond(peripheralId)
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(1, platform.closeCount)
        }
    }

    private class FakeBondPlatform(
        var state: AndroidBondState,
        private val beginAccepted: Boolean = true,
        private val beginFailure: RuntimeException? = null,
        private val stateFailure: RuntimeException? = null,
        private val stateFailureAfterBegin: RuntimeException? = null,
    ) : AndroidBondPlatform {
        var beginCount = 0
        var observeCount = 0
        var closeCount = 0
        var closed = false
        val observedIds = mutableListOf<PeripheralId>()
        private var listener: ((AndroidBondState) -> Unit)? = null

        override fun bondState(peripheralId: PeripheralId): AndroidBondState =
            stateFailure?.let { throw it }
                ?: if (beginCount > 0) stateFailureAfterBegin?.let { throw it } ?: state else state

        override fun beginBond(peripheralId: PeripheralId): Boolean {
            beginCount += 1
            beginFailure?.let { throw it }
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
