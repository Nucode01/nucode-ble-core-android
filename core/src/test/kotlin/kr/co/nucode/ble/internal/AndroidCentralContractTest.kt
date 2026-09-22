package kr.co.nucode.ble.internal

import java.lang.reflect.Modifier
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
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
        assertFalse("state composition stays in BleRuntime", "adapterState" in methods)
    }

    @Test
    fun `BleRuntime no longer owns the manifest permission cache or permission evaluation`() {
        val fields = BleRuntime::class.java.declaredFields.map { it.name }
        val methods = BleRuntime::class.java.declaredMethods.map { it.name }

        assertFalse("legacy BleRuntime.declaredPermissions field remains", "declaredPermissions" in fields)
        assertFalse("legacy BleRuntime.permissionState() remains", "permissionState" in methods)
        assertFalse("legacy BleRuntime.appContext field remains", "appContext" in fields)
        assertTrue("BleRuntime delegates through a single AndroidCentral handle", "central" in fields)
        assertTrue("adapter-state composition stays in BleRuntime", "computeAdapterState" in methods)
    }
}
