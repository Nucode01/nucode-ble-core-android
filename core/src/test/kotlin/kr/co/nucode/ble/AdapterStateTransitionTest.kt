package kr.co.nucode.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.Session
import kr.co.nucode.ble.internal.charRef
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Ready) }

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

        val internalState = fx.session.state
        assertTrue(
            "내부 state 는 Disconnected(ADAPTER_UNAVAILABLE) 여야 한다: $internalState",
            internalState is ConnState.Disconnected &&
                (internalState.reason as? DisconnectReason.Failed)?.failure?.code == FailureCode("ADAPTER_UNAVAILABLE"),
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
     * (b) — read()는 [BleRuntime.ensureAdapterReady] 를 거치지 않는다(connect/startScan 만
     * 그 게이트를 탄다, BleRuntime.kt 실측). 여기서는 이미 (a)의 스윕으로 세션이
     * Disconnected 가 된 상태라 [Session.canStartPublicGattOperation] 이 false 를 반환해
     * NOT_READY 로 거부된다 — plan 원문이 read 도 ADAPTER_UNAVAILABLE 을 던진다고 적었지만
     * 실제 게이트 지점이 다르다(반환 리포트 참고, plan 수정은 이 task 권한 밖).
     */
    @Test
    fun `b PoweredOff에서 connect와 startScan은 ADAPTER_UNAVAILABLE을 던지고 read는 게이트가 달라 NOT_READY를 던진다`() = runTest {
        val fx = fixtureAtPoweredOff()

        val connectFailure = runCatching {
            fx.core.connectOfficial(fx.id, BleConnectOptions())
        }.exceptionOrNull()
        assertFailureCode("ADAPTER_UNAVAILABLE", connectFailure)

        val scanFailure = runCatching { fx.core.startScan() }.exceptionOrNull()
        assertFailureCode("ADAPTER_UNAVAILABLE", scanFailure)

        val readFailure = runCatching {
            fx.core.read(fx.id, charRef(UUID.randomUUID(), UUID.randomUUID()))
        }.exceptionOrNull()
        assertFailureCode("NOT_READY", readFailure)
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
