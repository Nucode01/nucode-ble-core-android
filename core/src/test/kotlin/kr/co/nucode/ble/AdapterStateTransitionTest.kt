package kr.co.nucode.ble

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.charRef
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `advisory-cleanup/v1` task 1(G1) — 어댑터 상태 probe seam 회귀. `BleRuntime` 의
 * 테스트 전용 보조 생성자 `adapterState: (() -> AdapterState)?` 로 [AdapterState] 를 직접
 * 주입해, [BleRuntime.computeAdapterState] 가 실제 어댑터/권한 판정을 완전히 건너뛰고 그
 * 값을 그대로 쓰는지, 리시버 본문을 추출한 `refreshAdapterState()`(internal seam)가
 * Ready→non-Ready 전이에서만 정확히 1회 스윕을 도는지(D4/M-6) 검증한다.
 *
 * `refreshAdapterState()` 를 [BleCoreWiringTest] 의 `core.sweepSessions(...)` 직접 호출
 * 전례와 동일하게 테스트 스레드에서 곧장 부른다 — 프로덕션에서는 항상
 * `coreScope.launch { refreshAdapterState() }` 로 코어 단일 스레드 위에서만 불린다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdapterStateTransitionTest {

    /** [BleRuntime] 테스트 생성자를 이 [TestScope] 의 스케줄러 위에서 조립한다(BleCoreWiringTest 전례). */
    private fun TestScope.newCore(probe: () -> AdapterState): BleRuntime = BleRuntime(
        config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
        adapter = { null },
        adapterState = probe,
    )

    private fun testDeviceId(suffix: String = "FF") = PeripheralId("AA:BB:CC:DD:EE:$suffix")

    private fun assertFailureCode(expected: String, actual: Throwable?) {
        assertEquals(expected, (actual as? BleException)?.failure?.code?.value)
    }

    /** `probe` 값을 테스트에서 자유롭게 플립할 수 있게 감싸는 홀더 — 람다는 이 인스턴스를 캡처한다. */
    private class ProbeHolder(initial: AdapterState) {
        var state: AdapterState = initial
    }

    private class Fixture(
        val core: BleRuntime,
        val id: PeripheralId,
        val session: Session,
        val events: MutableList<BleEvent>,
        val probe: ProbeHolder,
    )

    /**
     * probe=Ready 로 세션 하나를 Ready 로 세운 뒤(setStateForTest — SessionLifecycleTestFixtures
     * 전례, 실 coordinator 구동), probe 를 PoweredOff 로 플립하고 [BleRuntime.refreshAdapterState]
     * 를 한 번 호출한 상태로 fixture 를 반환한다. (b)(c)(d) 케이스가 공유하는 공통 준비 단계다.
     */
    private fun TestScope.fixtureAtPoweredOff(): Fixture {
        val probe = ProbeHolder(AdapterState.Ready)
        val core = newCore { probe.state }
        val id = testDeviceId()
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }

        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()

        probe.state = AdapterState.PoweredOff
        core.refreshAdapterState()
        runCurrent()

        return Fixture(core, id, session, events, probe)
    }

    @Test
    fun `a Ready에서 PoweredOff로 전이하면 세션이 ADAPTER_UNAVAILABLE로 종결되고 ConnStateChanged가 1회 나온다`() = runTest {
        val fx = fixtureAtPoweredOff()

        val internalState = fx.session.connectionState.value
        assertTrue(
            "내부 state 는 Disconnected(ADAPTER_UNAVAILABLE) 여야 한다: $internalState",
            internalState is ConnectionState.Disconnected &&
                (internalState.reason as? BleDisconnectReason.Failed)?.failure?.code == FailureCode("ADAPTER_UNAVAILABLE"),
        )

        // 공개 투영도 같은 결론이어야 한다. Session.connectionState 로 읽는다 —
        // DefaultBleClient.session() 은 runBlocking 으로 코어 디스패처를 기다려 runTest 안에서 데드락.
        val publicState = fx.session.connectionState.value
        assertTrue("공개 connectionState 는 Disconnected 여야 한다: $publicState", publicState is ConnectionState.Disconnected)
        val publicReason = (publicState as ConnectionState.Disconnected).reason
        assertTrue("공개 reason 은 Failed 여야 한다: $publicReason", publicReason is BleDisconnectReason.Failed)
        assertEquals(FailureCode("ADAPTER_UNAVAILABLE"), (publicReason as BleDisconnectReason.Failed).failure.code)

        assertEquals(
            "플립 이후 그 device 의 ConnStateChanged 는 정확히 1회여야 한다",
            1,
            fx.events.filterIsInstance<BleEvent.ConnStateChanged>().count { it.deviceId == fx.id },
        )
        assertEquals(AdapterState.PoweredOff, fx.core.adapterState.value)
    }

    /**
     * (b) — connect/startScan 뿐 아니라 read/write/subscribe 도 첫 문장에서
     * [BleRuntime.ensureAdapterReady] 를 거친다. 어댑터가 꺼져 있으면 세션 상태와 무관하게
     * ADAPTER_UNAVAILABLE 로 거부된다.
     */
    @Test
    fun `b PoweredOff에서 connect·startScan·read·write·subscribe 모두 ADAPTER_UNAVAILABLE을 던진다`() = runTest {
        val fx = fixtureAtPoweredOff()
        val ref = charRef(UUID.randomUUID(), UUID.randomUUID())

        val connectFailure = runCatching {
            fx.core.connectOfficial(fx.id, BleConnectOptions())
        }.exceptionOrNull()
        assertFailureCode("ADAPTER_UNAVAILABLE", connectFailure)

        val scanFailure = runCatching { fx.core.startScan() }.exceptionOrNull()
        assertFailureCode("ADAPTER_UNAVAILABLE", scanFailure)

        assertFailureCode("ADAPTER_UNAVAILABLE", runCatching { fx.core.read(fx.id, ref) }.exceptionOrNull())
        assertFailureCode(
            "ADAPTER_UNAVAILABLE",
            runCatching { fx.core.write(fx.id, ref, byteArrayOf(1)) }.exceptionOrNull(),
        )
        assertFailureCode("ADAPTER_UNAVAILABLE", runCatching { fx.core.subscribe(fx.id, ref) }.exceptionOrNull())
    }

    @Test
    fun `unsubscribe는 어댑터가 꺼져도 throw하지 않고 로컬 intent만 지운다`() = runTest {
        val probe = ProbeHolder(AdapterState.Ready)
        val core = newCore { probe.state }
        val id = testDeviceId()
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }
        val ref = charRef(UUID.randomUUID(), UUID.randomUUID())
        session.subscriptionState.addDesired(ref)
        runCurrent()

        probe.state = AdapterState.PoweredOff
        core.refreshAdapterState()
        runCurrent()

        assertNull(runCatching { core.unsubscribe(id, ref) }.exceptionOrNull())
        assertFalse("unsubscribe 뒤 desired intent 가 남으면 안 된다", session.subscriptionState.containsDesired(ref))
    }

    @Test
    fun `e Ready 세션에서 op가 BT off를 처음 관측하면 스윕하고 ADAPTER_UNAVAILABLE을 던지며 이후 refresh는 재스윕하지 않는다`() = runTest {
        val probe = ProbeHolder(AdapterState.Ready)
        val core = newCore { probe.state }
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnectionState.Ready) }
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()

        probe.state = AdapterState.PoweredOff
        val failure = runCatching { core.read(id, charRef(UUID.randomUUID(), UUID.randomUUID())) }.exceptionOrNull()
        runCurrent()
        assertFailureCode("ADAPTER_UNAVAILABLE", failure)

        val changes = events.filterIsInstance<BleEvent.ConnStateChanged>().filter { it.deviceId == id }
        assertEquals("op 관측 스윕은 ConnStateChanged 를 정확히 1회 낸다: $changes", 1, changes.size)
        val state = changes.single().state
        assertTrue(
            "Disconnected(Failed ADAPTER_UNAVAILABLE) 여야 한다: $state",
            state is ConnectionState.Disconnected &&
                (state.reason as? BleDisconnectReason.Failed)?.failure?.code == FailureCode("ADAPTER_UNAVAILABLE"),
        )
        assertEquals(
            1,
            events.filterIsInstance<BleEvent.Diagnostic>().count { it.failure.code == FailureCode("ADAPTER_UNAVAILABLE") },
        )
        assertEquals(AdapterState.PoweredOff, core.adapterState.value)

        core.refreshAdapterState()
        runCurrent()
        assertEquals(
            "이미 op 가 non-Ready 를 기록했으므로 refresh 는 재스윕하지 않는다",
            1,
            events.filterIsInstance<BleEvent.ConnStateChanged>().count { it.deviceId == id },
        )
    }

    @Test
    fun `op는 Unauthorized 어댑터에서 PERMISSION_DENIED를 던진다`() = runTest {
        val core = newCore { AdapterState.Unauthorized(canRequest = true) }
        val failure = runCatching {
            core.read(testDeviceId(), charRef(UUID.randomUUID(), UUID.randomUUID()))
        }.exceptionOrNull()
        assertFailureCode("PERMISSION_DENIED", failure)
    }

    @Test
    fun `op는 Unsupported 어댑터에서 BLE_UNSUPPORTED를 던진다`() = runTest {
        val core = newCore { AdapterState.Unsupported }
        val failure = runCatching {
            core.write(testDeviceId(), charRef(UUID.randomUUID(), UUID.randomUUID()), byteArrayOf(1))
        }.exceptionOrNull()
        assertFailureCode("BLE_UNSUPPORTED", failure)
    }

    @Test
    fun `c PoweredOff에서 Resetting으로 전이해도 스윕이 다시 돌지 않는다`() = runTest {
        val fx = fixtureAtPoweredOff()
        val beforeCount = fx.events.filterIsInstance<BleEvent.ConnStateChanged>().size

        fx.probe.state = AdapterState.Resetting
        fx.core.refreshAdapterState()
        runCurrent()

        assertEquals(
            "이미 non-Ready 였으므로 추가 ConnStateChanged 가 없어야 한다(D4 — Ready→non-Ready 전이만 스윕)",
            beforeCount,
            fx.events.filterIsInstance<BleEvent.ConnStateChanged>().size,
        )
        assertEquals(AdapterState.Resetting, fx.core.adapterState.value)
    }

    @Test
    fun `d PoweredOff에서 Ready로 전이하면 스윕 없이 회복되고 다음 connect는 ADAPTER_UNAVAILABLE로 막히지 않는다`() = runTest {
        val fx = fixtureAtPoweredOff()
        val beforeCount = fx.events.filterIsInstance<BleEvent.ConnStateChanged>().size

        fx.probe.state = AdapterState.Ready
        fx.core.refreshAdapterState()
        runCurrent()

        assertEquals(
            "non-Ready 사이/복귀 전이는 스윕 대상이 아니다(D4/M-6)",
            beforeCount,
            fx.events.filterIsInstance<BleEvent.ConnStateChanged>().size,
        )
        assertEquals(AdapterState.Ready, fx.core.adapterState.value)

        val connectFailure = runCatching {
            fx.core.connectOfficial(fx.id, BleConnectOptions())
        }.exceptionOrNull()
        assertNotEquals(
            "어댑터 게이트는 통과해야 한다 — 이후 실패(플랫폼 gatt 없음)는 별개 관심사다: $connectFailure",
            "ADAPTER_UNAVAILABLE",
            (connectFailure as? BleException)?.failure?.code?.value,
        )
    }
}
