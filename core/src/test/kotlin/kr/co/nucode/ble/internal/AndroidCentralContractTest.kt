package kr.co.nucode.ble.internal

import java.lang.reflect.Modifier
import kr.co.nucode.ble.internal.adapter.AdapterMonitor
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the AndroidCentral wiring contract by structure — the JVM cannot instantiate
 * AndroidCentral (needs a real Context), so the cache-once and single-owner properties are
 * asserted through reflection instead of behaviour.
 */
class AndroidCentralContractTest {
    @Test
    fun `AndroidCentral caches declared permissions as a field, not a per-call method`() {
        val fields = AndroidCentral::class.java.declaredFields.map { it.name }
        val methods = AndroidCentral::class.java.declaredMethods.map { it.name }

        assertTrue("declaredPermissions must be an eagerly initialised field", "declaredPermissions" in fields)
        val cache = AndroidCentral::class.java.getDeclaredField("declaredPermissions")
        assertTrue("declaredPermissions must be final (val), not reassignable per call", Modifier.isFinal(cache.modifiers))
        assertFalse(
            "declaredPermissions must not be re-queried per call",
            methods.any { it.contains("declaredPermissions", ignoreCase = true) },
        )
        assertTrue("adapter() is the per-call adapter query", "adapter" in methods)
        assertTrue("permissionState() is the per-call permission query", "permissionState" in methods)
        assertFalse("state composition stays out of the platform adapter (AdapterMonitor owns it)", "adapterState" in methods)
    }

    @Test
    fun `BleRuntime no longer owns the manifest permission cache or permission evaluation`() {
        val fields = BleRuntime::class.java.declaredFields.map { it.name }
        val methods = BleRuntime::class.java.declaredMethods.map { it.name }

        assertFalse("legacy BleRuntime.declaredPermissions field remains", "declaredPermissions" in fields)
        assertFalse("legacy BleRuntime.permissionState() remains", "permissionState" in methods)
        assertFalse("legacy BleRuntime.appContext field remains", "appContext" in fields)
        assertFalse("BleRuntime must not hold a platform central", "central" in fields)
        // 플랫폼 central 은 Factory 조립이 하나만 만들어 Driver 셋에 나눠 준다(런타임은 포트만 받는다).
        val assembly = java.io.File("src/main/kotlin/kr/co/nucode/ble/android/AndroidRuntimeAssembly.kt").readText()
        assertEquals(
            "Factory assembly must create exactly one AndroidCentral",
            1,
            Regex("""(?<![\w.])AndroidCentral\(""").findAll(assembly).count(),
        )
        // 어댑터 상태 조립은 오케스트레이션 소유자 AdapterMonitor 에 있다(plan runtime-split task 2) — 플랫폼 어댑터
        // (AndroidCentral)나 런타임에 두 번째 계산이 생기지 않는다.
        assertFalse("adapter-state composition moved out of BleRuntime", "computeAdapterState" in methods)
        assertTrue(
            "adapter-state composition lives in AdapterMonitor",
            "computeAdapterState" in AdapterMonitor::class.java.declaredMethods.map { it.name },
        )
    }
}
