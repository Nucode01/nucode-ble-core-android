package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattConnectionLifecycle
import kr.co.nucode.ble.internal.gatt.GattOperationHooks
import kr.co.nucode.ble.internal.session.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * plan advisory-cleanup task 3 — [GattConnectionLifecycle] hook 계약(operations 방향)만 고정한다.
 * 128개 GattBridge/GattDriverSeamTest 시나리오와 중복하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattConnectionLifecycleTest {
    @Test
    fun `connect invalidates operations then opens a new generation through the hooks`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId)
        val calls = mutableListOf<String>()
        val hooks = object : GattOperationHooks {
            override fun invalidateAll(session: Session, failure: BleFailure) {
                calls += "invalidateAll"
            }

            override fun beginGenerationIfPoisoned(session: Session) {
                calls += "beginGenerationIfPoisoned"
            }
        }
        val lifecycle = GattConnectionLifecycle(
            registry = registry,
            emit = {},
            scope = this,
            driver = FakeGattDriver(),
            readinessTimeouts = ConnectionReadinessTimeouts(
                linkMillis = 15_000,
                discoveryMillis = 10_000,
                configurationMillis = 10_000,
            ),
            rediscovery = null,
            linkCacheWarm = null,
            operations = hooks,
        )

        lifecycle.connect(deviceId, ConnectOptions(autoConnect = false, timeoutMillis = null, requestedMtu = 185))

        assertEquals(listOf("invalidateAll", "beginGenerationIfPoisoned"), calls)
    }

    /**
     * plan link-setup 불변식 ① — 연결 단계(stage) 전이 호출은 lifecycle 에만 있다. 허용 파일은
     * GattConnectionLifecycle.kt 하나다(ConnectionCoordinator 는 정의만 하고 자기 자신을 이 형태로 부르지 않는다).
     * 한계: 수신자 이름 `connectionCoordinator`/`coordinator` 로 부르는 호출만 잡는다.
     */
    @Test
    fun `stage 전이 호출은 GattConnectionLifecycle 에만 있다`() {
        val call = Regex("""\b(connectionCoordinator|coordinator)\.(transition|beginCleanup|completeCleanup|beginConnection|beginPlatformRelink|supersedeForClose|supersedePlatformWait|closeIdle|latchIfAbsent)\(""")
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { call.containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertEquals(listOf("src/main/kotlin/kr/co/nucode/ble/internal/gatt/GattConnectionLifecycle.kt"), offenders)
    }

    /** plan link-setup 불변식 ② — `ConnStateChanged` 는 GattConnectionLifecycle.publishState 한 곳에서만 만든다. */
    @Test
    fun `ConnStateChanged 는 lifecycle publishState 에서만 만든다`() {
        val construct = Regex("""(?<!class )\bConnStateChanged\(""")
        val sites = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (construct.containsMatchIn(line)) "${file.invariantSeparatorsPath}:${index + 1}" to file else null
                }
            }
            .toList()
        val lifecycle = "src/main/kotlin/kr/co/nucode/ble/internal/gatt/GattConnectionLifecycle.kt"
        assertEquals(listOf(lifecycle), sites.map { it.first.substringBeforeLast(':') })
        val source = File(lifecycle).readText()
        val body = source.substringAfter("private fun publishState(session: Session) {").substringBefore("\n    }")
        assertTrue("ConnStateChanged 가 publishState 본문 밖에 있다", construct.containsMatchIn(body))
    }

    /**
     * 복원 job 취소(LinkSetup.cancel)는 main 에서 한 곳 — generation 이 링크를 떠나는 stage 이벤트
     * listener(Session.onLeaveLink) 등록 — 에서만 부른다. 한계: `linkSetup()?.cancel(`/`linkSetup.cancel(` 형태만 잡는다.
     */
    @Test
    fun `LinkSetup cancel 호출은 main 에 한 곳뿐이다`() {
        val call = Regex("""\blinkSetup(\(\)\?)?\.cancel\(""")
        val sites = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (call.containsMatchIn(line)) "${file.invariantSeparatorsPath}:${index + 1}" else null
                }
            }
            .toList()
        assertEquals(sites.toString(), 1, sites.size)
        assertTrue(sites.single(), sites.single().startsWith("src/main/kotlin/kr/co/nucode/ble/internal/gatt/GattConnectionLifecycle.kt:"))
    }

    private class FakeGattDriver : NoopGattDriver()
}
