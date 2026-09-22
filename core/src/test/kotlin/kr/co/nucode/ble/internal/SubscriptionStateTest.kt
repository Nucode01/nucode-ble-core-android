package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionStateTest {
    private fun characteristic(): CharacteristicRef = charRef(UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `active state is limited to desired subscriptions in its current generation`() {
        val state = SubscriptionState()
        val desired = characteristic()
        val removed = characteristic()
        state.addDesired(desired)
        state.addDesired(removed)
        state.beginGeneration(4)

        assertTrue(state.markActive(desired, 4))
        assertFalse(state.markActive(desired, 3))
        state.removeDesired(removed)
        assertFalse(state.markActive(removed, 4))
        state.removeDesired(desired)

        assertTrue(state.desiredSnapshot.isEmpty())
        assertTrue(state.activeSnapshot.isEmpty())
    }

    @Test
    fun `new generation clears active state while preserving desired intent`() {
        val state = SubscriptionState()
        val desired = characteristic()
        state.addDesired(desired)
        state.beginGeneration(1)
        state.markActive(desired, 1)

        state.beginGeneration(2)

        assertTrue(desired in state.desiredSnapshot)
        assertTrue(state.activeSnapshot.isEmpty())
        assertFalse(state.markActive(desired, 1))
    }

    @Test
    fun `snapshots are not mutable backing collections`() {
        val state = SubscriptionState()
        val desired = characteristic()
        state.addDesired(desired)

        assertTrue(runCatching { (state.desiredSnapshot as MutableSet<CharacteristicRef>).clear() }.isFailure)
        state.beginGeneration(1)
        state.markActive(desired, 1)
        state.removeDesired(desired)

        assertTrue(state.desiredSnapshot.isEmpty())
        assertTrue(state.activeSnapshot.isEmpty())
    }

    @Test
    fun `session owns its effective official reconnect policy`() {
        val session = Session(PeripheralId("peripheral"))
        val policy = BleReconnectPolicy.ExponentialBackoff(3, 10, 100)

        assertEquals(BleReconnectPolicy.Disabled, session.effectiveReconnectPolicy)
        session.effectiveReconnectPolicy = policy

        assertEquals(policy, session.effectiveReconnectPolicy)
    }

    @Test
    fun `recovering projection is owned by session flow and next legacy state replaces it`() {
        val session = Session(PeripheralId("peripheral"))
        val failure = BleFailure(
            category = FailureCategory("link"),
            code = FailureCode("LINK_LOST"),
            phase = FailurePhase.Reconnect,
            retryability = Retryability.NewConnectionMayHelp,
        )

        session.publishRecovering(attempt = 2, retryAfterMillis = 50, lastFailure = failure)

        assertEquals(
            ConnectionState.Recovering(attempt = 2, retryAfterMillis = 50, lastFailure = failure),
            session.connectionState.value,
        )
        session.setStateForTest(ConnState.Connecting)

        assertEquals(ConnectionState.Connecting, session.connectionState.value)
    }
}
