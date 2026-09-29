package kr.co.nucode.ble.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코어 스코프의 잡히지 않은 예외 처리기 — 세션 귀속·귀속 불명·취소 제외. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreScopeExceptionTest {

    private val a = PeripheralId("AA:BB:CC:DD:EE:A1")
    private val b = PeripheralId("AA:BB:CC:DD:EE:A2")

    private fun failedCode(state: ConnectionState?): String? =
        ((state as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value

    private fun Harness.internalDiagnostics() =
        events.filterIsInstance<OfficialBleClientEvent.Diagnostic>()
            .filter { it.failure.code.value == "INTERNAL_TRANSPORT_ERROR" }

    @Test
    fun `세션 소유 코루틴의 잡히지 않은 예외는 그 세션만 INTERNAL_TRANSPORT_ERROR 로 끝내고 handle 을 한 번 닫는다`() = runTest {
        val h = harness()
        h.connectToReady(a)
        h.connectToReady(b)
        assertEquals(0, h.fake.calls.count { it == "close" })

        h.core.launchOwned(a) { error("boom") }
        h.advancePastAllDeadlines()

        assertEquals(h.states(a).toString(), "INTERNAL_TRANSPORT_ERROR", failedCode(h.states(a).last()))
        assertEquals(h.fake.calls.toString(), 1, h.fake.calls.count { it == "close" })
        assertEquals(ConnectionState.Ready, h.session(b).connectionState.value)
        assertTrue(h.internalDiagnostics().toString(), h.internalDiagnostics().isNotEmpty())

        // 종결된 세션에 같은 예외가 다시 나도 handle 을 다시 닫지 않고, 다른 세션은 계속 쓸 수 있다.
        h.core.launchOwned(a) { error("again") }
        h.advancePastAllDeadlines()
        assertEquals(h.fake.calls.toString(), 1, h.fake.calls.count { it == "close" })
        assertEquals(ConnectionState.Ready, h.session(b).connectionState.value)
        h.close()
    }

    @Test
    fun `mailbox post 안의 예외는 그 기기 세션만 INTERNAL_TRANSPORT_ERROR 로 끝낸다`() = runTest {
        val h = harness()
        h.connectToReady(a)
        h.connectToReady(b)
        assertEquals(0, h.fake.calls.count { it == "close" })

        h.core.mailbox.post(a) { error("boom") }
        h.advancePastAllDeadlines()

        assertEquals(h.states(a).toString(), "INTERNAL_TRANSPORT_ERROR", failedCode(h.states(a).last()))
        assertEquals(h.fake.calls.toString(), 1, h.fake.calls.count { it == "close" })
        assertEquals(ConnectionState.Ready, h.session(b).connectionState.value)
        assertTrue(h.internalDiagnostics().toString(), h.internalDiagnostics().isNotEmpty())
        h.close()
    }

    @Test
    fun `귀속 불명 예외는 Diagnostic 만 내고 어떤 세션도 끝내지 않는다`() = runTest {
        val h = harness()
        h.connectToReady(a)

        h.core.launchOwned(null) { error("boom") }
        h.advancePastAllDeadlines()

        assertEquals(ConnectionState.Ready, h.session(a).connectionState.value)
        assertEquals(0, h.fake.calls.count { it == "close" })
        val diag = h.internalDiagnostics()
        assertEquals(h.events.toString(), 1, diag.size)
        assertEquals("INTERNAL_TRANSPORT_ERROR", diag.single().failure.code.value)
        h.close()
    }

    @Test
    fun `CancellationException 은 처리기 대상이 아니다`() = runTest {
        val h = harness()
        h.connectToReady(a)

        h.core.launchOwned(a) { throw CancellationException("cancelled") }
        h.advancePastAllDeadlines()

        assertEquals(ConnectionState.Ready, h.session(a).connectionState.value)
        assertTrue(h.internalDiagnostics().isEmpty())
        h.close()
    }
}
