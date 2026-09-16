package kr.co.nucode.ble

import kotlinx.coroutines.Job
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.DefaultBleSession
import kr.co.nucode.ble.internal.SessionRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleClientContractTest {
    @Test
    fun `official facade and session are views not independent runtime owners`() {
        val forbiddenFieldTypes = setOf(SessionRegistry::class.java, Job::class.java, Map::class.java)
        listOf(DefaultBleClient::class.java, DefaultBleSession::class.java).forEach { view ->
            assertFalse(
                "view must not own registry map or coroutine job: ${view.name}",
                view.declaredFields.any { field -> forbiddenFieldTypes.any { it.isAssignableFrom(field.type) } },
            )
        }

        val clientSource = source("internal/DefaultBleClient.kt")
        val sessionSource = source("internal/DefaultBleSession.kt")
        val mappedFlowSource = source("internal/MappedStateFlow.kt")
        listOf(clientSource, sessionSource).forEach { viewSource ->
            assertFalse("view must not create a map", viewSource.contains("mutableMapOf(") || viewSource.contains("HashMap<"))
            assertFalse("view must not materialize a StateFlow", viewSource.contains("stateIn("))
        }
        assertFalse("projection must not create a second state owner", mappedFlowSource.contains("stateIn("))
    }

    @Test
    fun `deprecated and official facades converge on one runtime truth`() {
        val coreSource = source("BleCore.kt")
        val clientSource = source("internal/DefaultBleClient.kt")
        assertTrue(coreSource.contains("class BleCore private constructor(internal val runtime: BleRuntime)"))
        assertTrue(clientSource.contains("internal constructor(core: BleCore) : this(core.runtime)"))
        assertFalse("runtime must not retain the deprecated facade", coreSource.contains("BleRuntime private constructor(internal val core: BleCore)"))
    }

    private fun source(relativePath: String): String =
        java.io.File("src/main/kotlin/kr/co/nucode/ble/$relativePath").readText()
}
