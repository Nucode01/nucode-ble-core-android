package kr.co.nucode.ble.internal

import kr.co.nucode.ble.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Guards the single-owner lifecycle and GATT-operation model against legacy state reintroduction. */
class InternalConvergenceContractTest {
    @Test
    fun `ConnState exposes only coordinator-derived lifecycle variants`() {
        val variants = ConnState::class.java.declaredClasses
            .map { it.simpleName }
            .toSet()

        assertEquals(
            setOf(
                "Disconnected",
                "Connecting",
                "DiscoveringServices",
                "Configuring",
                "Ready",
                "Disconnecting",
            ),
            variants,
        )
    }

    @Test
    fun `Session has no second lifecycle or GATT operation owner`() {
        val fields = Session::class.java.declaredFields
        val fieldNames = fields.map { it.name }.toSet()

        setOf("pendingWrite", "pendingCccd", "gattOpLock").forEach { legacyName ->
            assertFalse("legacy Session field remains: $legacyName", legacyName in fieldNames)
        }
        assertFalse(
            "Session must not own a Mutex in addition to GattOperationScheduler",
            fields.any { it.type.name == "kotlinx.coroutines.sync.Mutex" },
        )
        assertFalse(
            "Session.state must remain a read-only projection of ConnectionCoordinator.stage",
            Session::class.java.declaredMethods.any { it.name == "setState" },
        )
    }
}
