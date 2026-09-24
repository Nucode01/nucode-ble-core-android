package kr.co.nucode.ble.internal

import java.io.File
import java.util.UUID
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SubscriptionState
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
    fun `구독 generation 시작은 Session publishStage 에만 있다`() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("subscriptionState.beginGeneration(") }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertEquals(listOf("src/main/kotlin/kr/co/nucode/ble/internal/session/Session.kt"), offenders)
    }

    /**
     * 공개 연결 상태를 쓰는 경로는 Session.publishStage 하나다(Recovering 포함 — plan autoconnect-reconnect v2
     * 불변식 ①). 다른 파일이나 Session 의 다른 함수에서 `_connectionState.value =` 를 쓰면 red.
     */
    @Test
    fun `공개 연결 상태 쓰기는 Session publishStage 에만 있다`() {
        val write = "_connectionState.value ="
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(write) }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertEquals(listOf("src/main/kotlin/kr/co/nucode/ble/internal/session/Session.kt"), offenders)

        val session = File(offenders.single()).readText()
        val start = session.indexOf("    private fun publishStage(")
        assertTrue("Session.publishStage 선언을 찾지 못함", start >= 0)
        val end = session.indexOf("\n    }\n", start)
        val body = session.substring(start, end)
        assertEquals("publishStage 밖의 공개 상태 쓰기", session.split(write).size - 1, body.split(write).size - 1)
        assertEquals(1, body.split(write).size - 1)
    }
}
