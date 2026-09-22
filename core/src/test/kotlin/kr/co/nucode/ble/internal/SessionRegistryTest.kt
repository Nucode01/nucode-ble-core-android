package kr.co.nucode.ble.internal

import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.PeripheralId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 배선 테스트(plan task 8). [SessionRegistry] 가 설계 §3 규칙 2(PeripheralId 키 맵은 이거 하나)를
 * 실제로 지키는지 확인한다 — getOrCreate 멱등성과 Session 소유 scheduler 생애주기가 핵심이다.
 */
class SessionRegistryTest {

    // ── getOrCreate ──────────────────────────────────────────────

    @Test
    fun `getOrCreate 같은 PeripheralId로 두 번 부르면 같은 인스턴스가 온다`() {
        // 다른 인스턴스가 오면 state·gatt·retryCount 가 갈라져 규칙 2(상태는 객체 하나)가 무의미해진다.
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")

        val first = registry.getOrCreate(deviceId)
        val second = registry.getOrCreate(deviceId)

        assertSame(first, second)
    }

    // ── get ──────────────────────────────────────────────────────

    @Test
    fun `get 없는 기기는 null`() {
        val registry = SessionRegistry()

        assertNull(registry.get(PeripheralId("00:00:00:00:00:00")))
    }

    // ── remove ───────────────────────────────────────────────────

    @Test
    fun `remove 제거된 Session을 돌려주고 이후 get은 null`() {
        // 호출부(BleCore.close 등)가 반환된 Session 으로 gatt.close() 를 할 수 있어야 한다.
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId)

        val removed = registry.remove(deviceId)

        assertSame(session, removed)
        assertNull(registry.get(deviceId))
    }

    // ── all ──────────────────────────────────────────────────────

    @Test
    fun `all은 등록된 Session 전부를 돌려준다`() {
        val registry = SessionRegistry()
        val a = registry.getOrCreate(PeripheralId("A"))
        val b = registry.getOrCreate(PeripheralId("B"))

        val all = registry.all()

        assertEquals(2, all.size)
        assertTrue(all.containsAll(listOf(a, b)))
    }

    // ── state/mtu/chunkSize ────────────────────────────────────────

    @Test
    fun `session은 state mtu chunkSize를 그대로 싣는다`() {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId)
        session.setStateForTest(ConnState.Ready)
        session.mtu = 247
        session.chunkSize = 244

        assertEquals(deviceId, session.deviceId)
        assertEquals(ConnState.Ready, session.state)
        assertEquals(247, session.mtu)
        assertEquals(244, session.chunkSize)
    }

    @Test
    fun `mtu 미협상이면 mtu는 null`() {
        // 23 바이트 기본값을 임의로 채우지 않는다
        val registry = SessionRegistry()
        val session = registry.getOrCreate(PeripheralId("AA:BB:CC:DD:EE:FF"))

        assertNull(session.mtu)
        assertNull(session.chunkSize)
    }

    // ── GATT operation scheduler ──────────────────────────────────

    @Test
    fun `Session마다 GATT scheduler는 다른 인스턴스다`() {
        val registry = SessionRegistry()
        val a = registry.getOrCreate(PeripheralId("A"))
        val b = registry.getOrCreate(PeripheralId("B"))

        assertNotSame(a.gattOperationScheduler, b.gattOperationScheduler)
    }

    @Test
    fun `remove하면 GATT scheduler도 같이 사라진다`() {
        // scheduler는 Session과 생애주기를 같이 하므로 remove 후 새 세션에 재사용되지 않는다.
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        val session = registry.getOrCreate(deviceId)
        val originalScheduler = session.gattOperationScheduler

        registry.remove(deviceId)
        val recreated = registry.getOrCreate(deviceId)

        assertNotSame(originalScheduler, recreated.gattOperationScheduler)
    }
}
