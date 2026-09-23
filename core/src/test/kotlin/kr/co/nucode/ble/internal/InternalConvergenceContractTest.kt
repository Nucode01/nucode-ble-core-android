package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Guards the single-owner lifecycle and GATT-operation model against legacy state reintroduction. */
class InternalConvergenceContractTest {
    @Test
    fun `ConnectionStage exposes only coordinator lifecycle variants`() {
        val variants = ConnectionStage.entries
            .map { it.name }
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
            "Session.stage must remain a read-only view of ConnectionCoordinator.stage",
            Session::class.java.declaredMethods.any { it.name == "setState" },
        )
    }
}
