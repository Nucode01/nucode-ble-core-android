package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.model.ConnectOptions
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
     * 보장: 토큰 없이는 ConnectionCoordinator 전이 API 가 컴파일되지 않는다. 이 가드는 main 에서 식별자
     * `StageAuthority` 가 나오는 코드 줄을 허용 목록으로 고정한다 — 정의(abstract class)·전이 API 파라미터
     * (`authority: StageAuthority`)·lifecycle 의 import 와 file-private object 하나. 그래서 하위 타입 선언은 형태
     * (보조 생성자 `super()`·공백·줄바꿈·주석·typealias)와 무관하게, 토큰을 상위 타입으로 내보내는 선언도 막힌다.
     * 한계: 주석으로 시작하는 줄(KDoc 본문·줄 주석·블록 주석 시작)은 보지 않는다.
     */
    @Test
    fun `StageAuthority 는 정의·전이 파라미터·lifecycle 토큰에만 나온다`() {
        val coordinator = "src/main/kotlin/kr/co/nucode/ble/internal/session/ConnectionCoordinator.kt"
        val lifecycle = "src/main/kotlin/kr/co/nucode/ble/internal/gatt/GattConnectionLifecycle.kt"
        val lifecycleParts = listOf("ConnectStarter.kt", "LinkCallbacks.kt", "LinkTeardown.kt")
            .map { "src/main/kotlin/kr/co/nucode/ble/internal/gatt/$it" }
        val identifier = Regex("""\bStageAuthority\b""")
        val uses = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.trim()
                    val comment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                    if (!comment && identifier.containsMatchIn(code)) Triple(file.invariantSeparatorsPath, index + 1, code) else null
                }
            }
            .toList()
        val unexpected = uses.filterNot { (path, _, code) ->
            when (path) {
                coordinator -> code == "internal abstract class StageAuthority protected constructor()" ||
                    !identifier.containsMatchIn(code.replace("authority: StageAuthority", ""))
                lifecycle -> code == "import kr.co.nucode.ble.internal.session.StageAuthority" ||
                    code == "private object LifecycleStageAuthority : StageAuthority()"
                // lifecycle 에서 나뉜 칸 — 토큰은 생성자 주입으로만 받는다.
                in lifecycleParts -> code == "import kr.co.nucode.ble.internal.session.StageAuthority" ||
                    code == "private val authority: StageAuthority,"
                else -> false
            }
        }
        assertEquals(unexpected.joinToString("\n") { "${it.first}:${it.second}: ${it.third}" }, emptyList<Any>(), unexpected)
        assertEquals(1, uses.count { it.third == "private object LifecycleStageAuthority : StageAuthority()" })
    }

    /**
     * 중복 connect 판정 코드("CONNECTION_OPTIONS_CONFLICT"/"CONNECT_DURING_DISCONNECT") 문자열 리터럴은 main 에서
     * ConnectStarter.kt(admitConnect, lifecycle 의 연결 시작 칸) 에만 있다 — FailureCatalog.kt 등록 항목은 예외.
     */
    @Test
    fun `중복 connect 판정 코드는 GattConnectionLifecycle 에만 있다`() {
        val literal = Regex("\"(CONNECTION_OPTIONS_CONFLICT|CONNECT_DURING_DISCONNECT)\"")
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FailureCatalog.kt" }
            .filter { literal.containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertEquals(listOf("src/main/kotlin/kr/co/nucode/ble/internal/gatt/ConnectStarter.kt"), offenders)
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
     * 복원 job 취소(LinkSetup.cancel)는 private 이라 호출 위치는 컴파일러가 막는다. 남은 구멍은 취소를 부르는
     * 링크 이탈 listener(Session.onLeaveLink)를 다른 곳이 덮어쓰거나 직접 부르는 것 — main 에서 대입(참조 `::` 포함)은
     * LinkSetup 한 곳, 호출은 Session(publishStage) 한 곳뿐이다. 파일 전체 텍스트로 찾으므로 대입·호출 사이 줄바꿈도 잡는다.
     */
    @Test
    fun `onLeaveLink 대입은 LinkSetup, 호출은 Session 에만 있다`() {
        fun sites(pattern: Regex): List<String> = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                pattern.findAll(text).map { match ->
                    "${file.invariantSeparatorsPath}:${text.substring(0, match.range.first).count { it == '\n' } + 1}"
                }
            }
            .toList()
        val assigns = sites(Regex("""\bonLeaveLink\s*=(?!=)|::onLeaveLink\b"""))
        assertEquals(assigns.toString(), 1, assigns.size)
        assertTrue(assigns.single(), assigns.single().startsWith("src/main/kotlin/kr/co/nucode/ble/internal/connection/LinkSetup.kt:"))
        val calls = sites(Regex("""\bonLeaveLink\s*(\(|\.invoke\b)"""))
        assertEquals(calls.toString(), 1, calls.size)
        assertTrue(calls.single(), calls.single().startsWith("src/main/kotlin/kr/co/nucode/ble/internal/session/Session.kt:"))
    }

    private class FakeGattDriver : NoopGattDriver()
}
