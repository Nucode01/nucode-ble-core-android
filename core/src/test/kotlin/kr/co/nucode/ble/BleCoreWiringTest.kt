package kr.co.nucode.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.GattCallbackSource
import kr.co.nucode.ble.internal.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.GattDescriptorIdentity
import kr.co.nucode.ble.internal.GattDriver
import kr.co.nucode.ble.internal.GattDriverEvent
import kr.co.nucode.ble.internal.GattOperationKind
import kr.co.nucode.ble.internal.GattOperationTarget
import kr.co.nucode.ble.internal.GattOperationTargetResolver
import kr.co.nucode.ble.internal.ConnectionReadinessTimeouts
import kr.co.nucode.ble.internal.ConnectionStage
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.evaluatePermissionState
import kr.co.nucode.ble.internal.Session
import kr.co.nucode.ble.internal.SessionRegistry
import kr.co.nucode.ble.internal.TerminalCleanupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan blecore-wiring v2 task 6 — review #14("28개 green 이지만 배선 자체는 테스트 0줄")의
 * 회귀 테스트. [EventEmissionTest] 는 `MutableSharedFlow` 를 새로 만들어 코루틴 라이브러리
 * 자체를 검증했을 뿐 [BleCore] 배선을 건드리지 않았다 — 이 파일은 task 5 가 만든 internal
 * 테스트 seam(`BleRuntime(BleConfig, adapter)` 보조 생성자 + `emit` + `registry`)으로 실제
 * `advanceConnectSequence` 분기 · emit 순서 · dropped 카운터를 직접 두드린다.
 *
 * `adapter = { null }` 이라 [kr.co.nucode.ble.internal.Scanner]/[kr.co.nucode.ble.internal.GattBridge]
 * 의 실제 플랫폼 호출 경로는 이 테스트가 두드리는 명령(`emit`/`registry` 직접 조작)에서는
 * 애초에 진입하지 않는다 — 검증 대상은 `BleCore.advanceConnectSequence` 자체이지 워커
 * 배선이 아니다(plan §테스트가능성).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleCoreWiringTest {

    /**
     * `coreScope` 가 이 [TestScope]와 같은 [kotlinx.coroutines.test.TestCoroutineScheduler] 위에서
     * 돌게 만든다 — 그래야 `runCurrent()`/`advanceUntilIdle()` 로 `Reconnector` 의 `delay()`까지
     * 가상 시간으로 진행시킬 수 있다(plan task 6 지시 — "runTest + StandardTestDispatcher 를
     * BleConfig.dispatcher 에 주입해라").
     */
    private fun TestScope.newCore(
        eventBufferCapacity: Int = 64,
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        operationTimeoutMillis: Long = 5_000,
        linkTimeoutMillis: Long = 15_000,
        discoveryTimeoutMillis: Long = 10_000,
        configurationTimeoutMillis: Long = 10_000,
        disconnectCleanupTimeoutMillis: Long = 5_000,
        // BleConfig 는 재발견 상한이 link 상한을 넘지 못하게 강제한다 — 짧은 link 로 timeout 을 재현하는
        // 테스트가 그 계약에 걸리지 않도록 기본값을 link 안으로 맞춘다.
        rediscoveryTimeoutMillis: Long = minOf(3_000L, linkTimeoutMillis),
        gattDriver: GattDriver? = null,
        gattOperationTargetResolver: GattOperationTargetResolver? = null,
    ): BleRuntime = BleRuntime(
        config = BleConfig(
            dispatcher = StandardTestDispatcher(testScheduler),
            eventBufferCapacity = eventBufferCapacity,
            operationTimeoutMillis = operationTimeoutMillis,
            linkTimeoutMillis = linkTimeoutMillis,
            discoveryTimeoutMillis = discoveryTimeoutMillis,
            configurationTimeoutMillis = configurationTimeoutMillis,
            disconnectCleanupTimeoutMillis = disconnectCleanupTimeoutMillis,
            rediscoveryTimeoutMillis = rediscoveryTimeoutMillis,
        ),
        adapter = { null },
        reconnectPolicy = reconnectPolicy,
        gattDriver = gattDriver,
        gattOperationTargetResolver = gattOperationTargetResolver,
    )

    private fun testDeviceId(suffix: String = "FF") = DeviceId("AA:BB:CC:DD:EE:$suffix")

    private fun testDiagnostic(i: Int): BleEvent.Diagnostic = BleEvent.Diagnostic(
        severity = Severity.Info,
        failure = BleFailure(
            category = FailureCategory("test"),
            code = FailureCode("TEST_$i"),
            phase = FailurePhase.Operation,
            retryability = Retryability.Never,
        ),
    )

    private fun assertFailureCode(expected: String, actual: Throwable?) {
        assertEquals(expected, (actual as? BleException)?.failure?.code?.value)
    }

    // ── A. emit 순서(skeleton v3 §변경① — review #14, 지금까지 검증 테스트가 없었다) ──────

    @Test
    fun `A Configuring 상태에서 MtuChanged를 emit하면 MtuChanged 다음 Ready 순서로 나온다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Configuring) }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent() // 수집자가 구독자로 등록될 때까지 진행

        core.emit(BleEvent.MtuChanged(id, 247, 244))
        runCurrent()

        // BleCore.emit KDoc: "발행이 먼저다" — 순서가 뒤집히면(Connected 가 먼저 나오면) red.
        assertEquals(
            listOf(
                BleEvent.MtuChanged(id, 247, 244),
                BleEvent.ConnStateChanged(id, ConnState.Ready),
            ),
            acc,
        )
    }

    // ── B. advanceConnectSequence 분기 ────────────────────────────────────────

    @Test
    fun `B1 Connected 상태에서 MtuChanged가 도착해도 Connected를 재방출하지 않는다`() = runTest {
        // review #8 가드의 회귀 테스트 — Discovering 이 아니면 재전이하지 않는다.
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Ready) }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.MtuChanged(id, 247, 244))
        runCurrent()

        assertEquals(listOf(BleEvent.MtuChanged(id, 247, 244)), acc)
    }

    @Test
    fun `B2 Connected로 전이하면 retryCount가 0으로 리셋된다`() = runTest {
        // review #10 — 리셋이 없으면 끊김 5회 누적 후 영구 재연결 불가.
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { retryCount = 3 }

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Ready))

        assertEquals(0, core.registry.get(id)?.retryCount)
    }

    @Test
    fun `B3 Disconnected에서 suppressReconnect가 false면 재연결 백오프 Diagnostic이 events로 나온다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { suppressReconnect = false }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent()

        val attemptDiagnostic = acc.filterIsInstance<BleEvent.Diagnostic>()
            .firstOrNull { it.code == "RECONNECT_ATTEMPT" }
        assertTrue("scheduleReconnect 가 즉시 RECONNECT_ATTEMPT Diagnostic 을 낸다", attemptDiagnostic != null)

        // 가상시간 진행 — 백오프 delay 이후, 이 테스트에서는 session.gatt 를 채우지 않았으므로
        // 재연결 시도 자체가 실패(gatt.connect() 호출 불가)하는 것까지 관측한다(Reconnector.reconnect KDoc).
        //
        // 실측(구현 결함 아님, coreScope 와 backgroundScope 가 같은 testScheduler 를 공유하되
        // 서로 다른 StandardTestDispatcher 인스턴스라는 배선의 성질) — advanceUntilIdle() 한
        // 번만으로는 reconnect() 가 emit 한 값이 collector(backgroundScope 소속 dispatcher)로
        // 넘어가는 마지막 한 스텝이 안 도는 경우가 있어, 뒤이어 runCurrent() 를 한 번 더 불러
        // 그 스텝을 명시적으로 비운다.
        advanceUntilIdle()
        runCurrent()
        val connectFailedDiagnostic = acc.filterIsInstance<BleEvent.Diagnostic>()
            .firstOrNull { it.code == "RECONNECT_CONNECT_START_FAILED" }
        assertTrue("gatt==null 이므로 재연결 시도가 실패로 관측돼야 한다", connectFailedDiagnostic != null)
    }

    @Test
    fun `B4 Disconnected에서 suppressReconnect가 true면 재연결을 시도하지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { suppressReconnect = true }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent()

        assertEquals(listOf(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial))), acc)
    }

    @Test
    fun `adapter sweep reuses one session-correlated failure for diagnostic and terminal state`() = runTest {
        val core = newCore()
        val id = testDeviceId("A1")
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Ready) }
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()

        core.sweepSessions(AdapterState.PoweredOff)
        runCurrent()

        val diagnostic = events.filterIsInstance<BleEvent.Diagnostic>().single().failure
        val terminal = ((session.state as ConnState.Disconnected).reason as DisconnectReason.Failed).failure
        assertEquals(terminal, diagnostic)
        assertEquals(session.failureSessionId, diagnostic.context.sessionId)
        assertEquals(session.connectionCoordinator.generation, diagnostic.context.connectionGeneration)
    }

    // ── G. task3 배선 주입 테스트(plan blecore-app-tunables) ───────────────────────

    /**
     * G1(plan 결정②) — `BleConfig.reconnectPolicy` 가 실제로 `Reconnector.policy` 에 닿는지.
     * `maxAttempts = 0` 을 주입하면 `canRetry(0, policy)` 가 즉시 false 라 `scheduleReconnect`
     * 첫 호출에서 `RECONNECT_GIVE_UP` 을 내고 끝난다(Reconnector.scheduleReconnect KDoc) —
     * 기본 `ReconnectPolicy()`(maxAttempts=5)가 주입 대신 쓰였다면 `RECONNECT_ATTEMPT` 가
     * 먼저 나왔을 것이므로, 이 둘의 유무 자체가 주입 여부의 증거다.
     */
    @Test
    fun `G1 reconnectPolicy maxAttempts 0을 주입하면 재연결 즉시 포기한다`() = runTest {
        val core = newCore(reconnectPolicy = ReconnectPolicy(maxAttempts = 0))
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { suppressReconnect = false }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent()

        val diagnostics = acc.filterIsInstance<BleEvent.Diagnostic>()
        assertTrue(
            "maxAttempts=0 주입이 닿았으면 RECONNECT_GIVE_UP 이 즉시 나와야 한다: $diagnostics",
            diagnostics.any { it.code == "RECONNECT_GIVE_UP" },
        )
        assertTrue(
            "기본 정책(maxAttempts=5)이 쓰였다면 나왔을 RECONNECT_ATTEMPT 가 없어야 한다: $diagnostics",
            diagnostics.none { it.code == "RECONNECT_ATTEMPT" },
        )
        assertEquals(0, core.registry.get(id)?.retryCount)
    }

    /**
     * G2(plan 결정①) — `Session.requestedMtu` 가 `BleCore.advanceConnectSequence` 의
     * `ServicesDiscovered` 분기를 거쳐 `gattBridge.requestMtu` 에 그대로 전달되는지.
     * `session.gatt` 를 채우지 않아 `GattBridge.requestMtu` 호출 자체가 실패(started=false)
     * 하게 만든 뒤, 그 실패 Diagnostic 메시지에 실린 `requestMtu($mtu)` 문자열로 간접
     * 관측한다(GattBridge 는 실체라 인자를 직접 캡처할 수 없다 — plan task 3 §테스트 지시).
     * 세션 기본값(247)이 아니라 185 가 찍히면 Session 경유 배선이 실제로 닿았다는 증거다.
     */
    @Test
    fun `G2 session requestedMtu 185가 ServicesDiscovered 이후 requestMtu 호출에 그대로 전달된다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.DiscoveringServices)
            requestedMtu = 185
        }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ServicesDiscovered(id, emptyList()))
        runCurrent()

        val mtuRequestFailed = acc.filterIsInstance<BleEvent.Diagnostic>()
            .firstOrNull {
                it.failure.code == FailureCode("GATT_OPERATION_START_FAILED") &&
                    (it.failure.platformDetail as? PlatformDetail.Android)?.apiOperation == "requestMtu"
            }
        assertTrue("gatt==null 이므로 requestMtu 호출 실패 Diagnostic 이 나와야 한다: $acc", mtuRequestFailed != null)
    }

    // ── C. dropped 카운터(review #14 — "한 번도 실행 안 됨" 지적) ───────────────────

    @Test
    fun `C 느린 수집자가 buffer+1개를 받으면 droppedEventCount가 늘어난다`() = runTest {
        val bufferCapacity = 4
        val core = newCore(eventBufferCapacity = bufferCapacity)

        // 첫 이벤트를 받은 뒤 영원히 멈추는 수집자 — EventEmissionTest ②와 같은 패턴.
        backgroundScope.launch { core.events.collect { awaitCancellation() } }
        runCurrent() // 수집자가 구독자로 등록될 때까지 진행

        repeat(bufferCapacity + 1) { i ->
            core.emit(testDiagnostic(i))
        }

        assertTrue("buffer 초과분은 dropped 로 관측돼야 한다", core.droppedEventCount > 0)
    }

    @Test
    fun `C 수집자가 0이면 buffer+1개를 emit해도 droppedEventCount는 0이다`() = runTest {
        // SharedFlow 규약(EventEmissionTest ①과 동일 근거) — 수집자가 없으면 보관할 곳이
        // 없어 "버퍼가 찼다"는 상태 자체가 성립하지 않는다. KDoc 과대진술의 실측 고정.
        val bufferCapacity = 4
        val core = newCore(eventBufferCapacity = bufferCapacity)

        repeat(bufferCapacity + 1) { i ->
            core.emit(testDiagnostic(i))
        }

        assertEquals(0L, core.droppedEventCount)
    }

    // ── D. close 후 재호출(plan D5/review #7 — task 6 이 뺐던 close 테스트 부활) ────────

    /**
     * task 6 KDoc(구 이력, 아래 실측은 여전히 유효): `close()` 의 `runBlocking(coreDispatcherOnly)`
     * 는 이 테스트가 주입한 `StandardTestDispatcher` 위에서 데드락이다 — `runBlocking` 이
     * 그 안에서 예약한 정리 코루틴을 실제로 돌리려면 스케줄러가 pump 돼야 하는데,
     * `runBlocking` 은 자신을 호출한 바로 그 스레드를 정지시켜 기다릴 뿐이라 아무도
     * `runCurrent()`/`advanceUntilIdle()` 을 대신 불러주지 않는다 — 그래서 `close()` 를
     * 이 테스트 코루틴에서 직접(동기) 부르면 이 테스트 자체가 멈춘다.
     *
     * D5(review #7)가 `closed = true` 를 `runBlocking` **이전**의 첫 줄로 옮겨 둔 덕에
     * "close 후 진입 차단"은 그 `runBlocking` 정리부가 끝나는 것과 무관하게 관측 가능하다
     * — 그래서 `close()` 는 별도 JVM 스레드에서 부르고(정리부의 데드락 가능성을 이 테스트
     * 스레드에서 분리한다) **join 하지 않는다** — 정리부 실행 완료를 기다리지 않고
     * `closed` 플래그가 반영되는 순간만 짧게 폴링해 검증을 끝낸다(plan task 2 지시 —
     * "정리부 실행 전에 검증 종료"). 고정 `Thread.sleep` 대신 폴링을 쓰는 이유는 스레드
     * 시작 타이밍에 대한 임의 대기를 정당화할 근거가 없어서다.
     */
    @Test(timeout = 5_000)
    fun `D1 close 이후 진입은 CLIENT_CLOSED typed failure로 막힌다`() = runTest {
        val core = newCore()

        Thread { core.close() }.start()

        var thrown: Throwable? = null
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadlineNanos && (thrown as? BleException)?.failure?.code?.value != "CLIENT_CLOSED") {
            thrown = runCatching { core.startScan() }.exceptionOrNull()
        }

        assertTrue(
            "close 이후 진입은 CLIENT_CLOSED typed failure여야 한다: $thrown",
            (thrown as? BleException)?.failure?.code?.value == "CLIENT_CLOSED",
        )
    }

    // ── E. snapshot 경유 상태 확인(internal registry 와 공개 API 의 일관성) ────────────

    @Test
    fun `E 세션 2개를 심으면 snapshot에 devices 2개와 state mtu가 반영된다`() = runTest {
        val core = newCore()
        val idA = testDeviceId("A1")
        val idB = testDeviceId("B2")
        core.registry.getOrCreate(idA).apply {
            setStateForTest(ConnState.Ready)
            mtu = 247
            chunkSize = 244
        }
        core.registry.getOrCreate(idB).apply {
            setStateForTest(ConnState.DiscoveringServices)
        }

        val snapshot = core.snapshot()

        assertEquals(2, snapshot.devices.size)
        val snapA = snapshot.devices.first { it.deviceId == idA }
        val snapB = snapshot.devices.first { it.deviceId == idB }
        assertEquals(ConnState.Ready, snapA.connState)
        assertEquals(247, snapA.mtu)
        assertEquals(244, snapA.chunkSize)
        assertEquals(ConnState.DiscoveringServices, snapB.connState)
    }

    // ── review 지금-고침 그룹 회귀 (plan v3 task 8) ────────────────────────────────

    /**
     * H-1(#18): subscribe 가 실패하면 subscriptions 에 아무것도 남지 않아야 한다.
     * 탐색 결과가 빈 세션이라 GattBridge.subscribe 가 CharacteristicNotFound 를 던진다 —
     * 수정 전에는 던지기 전에 이미 += 가 실행돼 거짓 구독이 남았다.
     */
    @Test
    fun `H1 subscribe 실패 시 subscriptions에 기록이 남지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Ready) }

        val thrown = runCatching { core.subscribe(id, ref) }.exceptionOrNull()

        assertFailureCode("ATTRIBUTE_NOT_FOUND", thrown)
        assertTrue(
            "실패했는데 subscriptions 에 남았다(review H-1)",
            core.registry.get(id)!!.subscriptionState.desiredSnapshot.isEmpty(),
        )
    }

    /**
     * H-2: 백오프 대기 중 사용자가 disconnect 하면(suppressReconnect=true) 이미 예약된
     * 재연결 시도가 발화해도 연결을 시작하지 않아야 한다. 수정 전에는 예약 시점 가드만
     * 있어서 사용자가 끊은 기기를 코어가 되붙였다(Connecting 이벤트 발생).
     */
    @Test
    fun `H2 백오프 대기 중 disconnect하면 예약된 재연결이 Connecting을 만들지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val session = core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Disconnected(DisconnectReason.Initial))
            suppressReconnect = false
        }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        // 예기치 못한 끊김 → scheduleReconnect 예약
        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent()

        // 백오프 delay 소진 전에 사용자가 끊는다
        session.suppressReconnect = true

        advanceUntilIdle()
        runCurrent()

        assertTrue(
            "사용자가 끊은 기기를 코어가 되붙였다(review H-2): $acc",
            acc.none { it is BleEvent.ConnStateChanged && it.state is ConnState.Connecting },
        )
    }

    // ── Slice 1 common GATT scheduler migration ──────────────────────────────────

    /**
     * public [BleCore.read] 경계를 직접 통과해 GattBridge scheduler의 start rejection까지
     * 닿는다. JVM 테스트에서는 실제 [android.bluetooth.BluetoothGatt] handle을 만들 수
     * 없고 Android stub의 characteristic property 접근도 실행할 수 없으므로, 기본 production
     * resolution을 쓰는 이 테스트는 facade가 내부 resolve/start 실패를 바꾸거나 삼키지 않는
     * 배선을 고정한다. 아래 started-operation 테스트는 internal target resolver를 명시 주입한다.
     */
    @Test
    fun `Slice1 public read는 내부 start rejection을 그대로 전달하고 scheduler를 비운다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Ready) }

        val failure = runCatching { core.read(id, ref) }.exceptionOrNull()

        assertFailureCode("ATTRIBUTE_NOT_FOUND", failure)
        assertTrue("동기 start rejection은 generation을 poison하지 않는다", !session.gattOperationScheduler.isPoisoned)
        assertTrue("동기 start rejection 뒤 pending이 남지 않는다", session.gattOperationScheduler.pending == null)
        assertEquals(0, session.gattOperationScheduler.queuedCount)
    }

    @Test
    fun `Slice1 public read started timeout은 generation을 poison하고 late callback을 격리한다`() = runTest {
        val fixture = publicGattFixture(operationTimeoutMillis = 100)
        val read = async { runCatching { fixture.core.read(fixture.deviceId, fixture.ref) }.exceptionOrNull() }
        runCurrent()
        val queued = fixture.enqueueReadBehindPending(this)

        assertEquals(GattOperationKind.Read, fixture.session.gattOperationScheduler.pending?.operation?.kind)
        advanceTimeBy(100)
        runCurrent()

        assertFailureCode("OPERATION_TIMEOUT", read.await())
        assertPoisonedAndLateCallbackIsolated(
            fixture = fixture,
            queued = queued,
            expectedCommands = listOf("read", "disconnect", "close"),
            emitLateCallback = { fixture.driver.emitRead(fixture.deviceId, fixture.ref, byteArrayOf(9)) },
        )
    }

    @Test
    fun `Slice1 public read started caller cancellation은 generation을 poison한다`() = runTest {
        val fixture = publicGattFixture()
        val read = async { fixture.core.read(fixture.deviceId, fixture.ref) }
        runCurrent()
        val queued = fixture.enqueueReadBehindPending(this)

        read.cancelAndJoin()
        runCurrent()

        assertTrue(read.isCancelled)
        assertPoisonedAndLateCallbackIsolated(
            fixture = fixture,
            queued = queued,
            expectedCommands = listOf("read", "disconnect", "close"),
            emitLateCallback = { fixture.driver.emitRead(fixture.deviceId, fixture.ref, byteArrayOf(9)) },
        )
    }

    @Test
    fun `Slice1 public write started timeout은 generation을 poison하고 queue를 비운다`() = runTest {
        val fixture = publicGattFixture(operationTimeoutMillis = 100)
        val write = async {
            fixture.core.write(
                fixture.deviceId,
                fixture.ref,
                byteArrayOf(1),
                WriteType.WithResponse,
                Chunking.None,
            )
        }
        runCurrent()
        val queued = fixture.enqueueReadBehindPending(this)

        assertEquals(
            GattOperationKind.WriteWithResponse,
            fixture.session.gattOperationScheduler.pending?.operation?.kind,
        )
        advanceTimeBy(100)
        runCurrent()

        val result = write.await() as WriteResult.Failed
        assertEquals("OPERATION_TIMEOUT", result.failure.code.value)
        assertEquals(0, result.failure.writeProgress?.failedChunkIndex)
        assertTrue(result.failure.writeProgress?.currentChunkDeliveryUnknown == true)
        assertPoisonedAndLateCallbackIsolated(
            fixture = fixture,
            queued = queued,
            expectedCommands = listOf("write", "disconnect", "close"),
            emitLateCallback = { fixture.driver.emitWrite(fixture.deviceId, fixture.ref) },
        )
    }

    @Test
    fun `Slice1 public write started caller cancellation은 generation을 poison한다`() = runTest {
        val fixture = publicGattFixture()
        val write = async {
            fixture.core.write(
                fixture.deviceId,
                fixture.ref,
                byteArrayOf(1),
                WriteType.WithResponse,
                Chunking.None,
            )
        }
        runCurrent()
        val queued = fixture.enqueueReadBehindPending(this)

        write.cancelAndJoin()
        runCurrent()

        assertTrue(write.isCancelled)
        assertPoisonedAndLateCallbackIsolated(
            fixture = fixture,
            queued = queued,
            expectedCommands = listOf("write", "disconnect", "close"),
            emitLateCallback = { fixture.driver.emitWrite(fixture.deviceId, fixture.ref) },
        )
    }

    @Test
    fun `Slice1 public subscribe started timeout은 generation을 poison하고 구독 기록을 남기지 않는다`() = runTest {
        val fixture = publicGattFixture(operationTimeoutMillis = 100)
        val subscribe = async { runCatching { fixture.core.subscribe(fixture.deviceId, fixture.ref) }.exceptionOrNull() }
        runCurrent()
        val queued = fixture.enqueueReadBehindPending(this)

        assertEquals(GattOperationKind.Subscribe, fixture.session.gattOperationScheduler.pending?.operation?.kind)
        advanceTimeBy(100)
        runCurrent()

        assertFailureCode("OPERATION_TIMEOUT", subscribe.await())
        assertTrue(fixture.session.subscriptionState.desiredSnapshot.isEmpty())
        assertPoisonedAndLateCallbackIsolated(
            fixture = fixture,
            queued = queued,
            expectedCommands = listOf("notify", "descriptor", "disconnect", "close"),
            emitLateCallback = { fixture.driver.emitDescriptor(fixture.deviceId, fixture.ref) },
        )
    }

    @Test
    fun `Slice1 public subscribe started caller cancellation은 generation을 poison한다`() = runTest {
        val fixture = publicGattFixture()
        val subscribe = async { fixture.core.subscribe(fixture.deviceId, fixture.ref) }
        runCurrent()
        val queued = fixture.enqueueReadBehindPending(this)

        subscribe.cancelAndJoin()
        runCurrent()

        assertTrue(subscribe.isCancelled)
        assertTrue(fixture.session.subscriptionState.desiredSnapshot.isEmpty())
        assertPoisonedAndLateCallbackIsolated(
            fixture = fixture,
            queued = queued,
            expectedCommands = listOf("notify", "descriptor", "disconnect", "close"),
            emitLateCallback = { fixture.driver.emitDescriptor(fixture.deviceId, fixture.ref) },
        )
    }

    // ── D8 unsubscribe best-effort(plan blecore-design-decisions v1 task 3, review L-1) ──

    /**
     * D8-① — resolve 되지 않는 CharRef 로 unsubscribe 하면(수정 전이면 red:
     * `GattBridge.unsubscribe` 가 `CharacteristicNotFound` 를 throw) throw 없이 정상
     * 반환하고, `subscriptions` 에서는 항상 제거되며, `UNSUBSCRIBE_CHAR_NOT_FOUND`
     * Diagnostic 이 events 로 나온다(탐색이 갈렸으면 CCCD 를 쓸 대상이 없다 — 조용히
     * 끝내되 기록은 남긴다).
     */
    @Test
    fun `D8① resolve 안 되는 ref를 unsubscribe하면 throw 없이 제거되고 Diagnostic이 나온다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Ready)
            subscriptionState.addDesired(ref)
        }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        val thrown = runCatching { core.unsubscribe(id, ref) }.exceptionOrNull()
        runCurrent()

        assertTrue("unsubscribe 는 throw 없이 반환돼야 한다(D8): $thrown", thrown == null)
        assertTrue(
            "resolve 실패와 무관하게 subscriptions 에서 제거돼야 한다(D8)",
            core.registry.get(id)!!.subscriptionState.desiredSnapshot.isEmpty(),
        )
        val diagnostic = acc.filterIsInstance<BleEvent.Diagnostic>()
            .firstOrNull { it.code == "ATTRIBUTE_NOT_FOUND" }
        assertTrue("ATTRIBUTE_NOT_FOUND Diagnostic 이 나와야 한다: $acc", diagnostic != null)
    }

    /**
     * D8-② — 연결이 죽어 `session.gatt == null` 인 상태에서 unsubscribe 하면 throw 없이
     * `subscriptions` 에서 제거된다(연결이 죽었으면 기기 쪽 CCCD 도 죽었다 — 조용히 끝낸다).
     * resolve 자체는 성공해야(대상은 있음) gatt-null 분기까지 도달하므로 discovered 맵을
     * 채워 characteristic 을 심어둔다.
     */
    @Test
    fun `D8② gatt 없는 세션에서 unsubscribe하면 throw 없이 제거된다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Ready)
            subscriptionState.addDesired(ref)
            updateDiscovered(mapOf(ref to BluetoothGattCharacteristic(ref.charUuid, 0, 0)))
            // gatt 는 세팅하지 않는다 — 연결이 죽은 세션(session.gatt == null)을 재현한다.
        }

        val thrown = runCatching { core.unsubscribe(id, ref) }.exceptionOrNull()

        assertTrue("gatt==null 이어도 unsubscribe 는 throw 없이 반환돼야 한다(D8): $thrown", thrown == null)
        assertTrue(
            "gatt==null 이어도 subscriptions 에서 제거돼야 한다(D8)",
            core.registry.get(id)!!.subscriptionState.desiredSnapshot.isEmpty(),
        )
    }

    // ── D6 connect 실패 정리(plan blecore-design-decisions v1 task 4, review M-7) ──────

    /**
     * D6-①(review M-7) 회귀 테스트 — `GattBridge.connect` 에서 `adapter()` 가 null 이면
     * (권한 게이트 통과 직후 어댑터가 사라진 레이스) 예전에는 조용히 return 해 호출자
     * (`BleCore.connect`)가 이미 emit 한 `Connecting` 이 영구 잔류했다. 이제는
     * `ADAPTER_UNAVAILABLE` typed failure를 던진다.
     *
     * ⚠️ `BleCore` 레벨로는 재현 불가(실측) — `newCore(adapter = { null })` 를 쓰면
     * `ensureAdapterReady` 게이트의 `computeAdapterState()` 가 이 분기에 닿기도 전에
     * `AdapterState.Unsupported` 로 판정해 `AdapterUnavailable(Unsupported)` 를 먼저
     * 던진다(BleCore.computeAdapterState KDoc). 이 분기를 재현하려면 `ensureAdapterReady`
     * 호출 시점에는 어댑터가 살아있고(Ready) `GattBridge.connect` 호출 시점에는 사라진
     * 상태가 필요한데, 그런 `BluetoothAdapter`(공개 생성자 없는 final 클래스)를 이
     * 모듈의 테스트 의존성(junit + kotlinx-coroutines-test, 목킹 프레임워크 없음)으로는
     * 만들 수 없다 — 그래서 `GattBridge`(internal, 같은 모듈이라 직접 인스턴스화 가능)
     * 단위로 ① 만 검증한다. `context` 자리는 구체 클래스인 `ContextWrapper(null)`
     * 로 채운다 — 이 throw 경로는 `context` 필드의 실제 플랫폼 메서드를 하나도 호출하지
     * 않으므로(첫 줄의 null 체크만 통과하면 된다) non-null 이기만 하면 값 자체는
     * 상관없다.
     */
    @Test
    fun `D6① GattBridge connect에서 adapter가 사라지면 AdapterUnavailable PoweredOff를 던진다`() {
        val registry = kr.co.nucode.ble.internal.SessionRegistry()
        val id = testDeviceId()
        registry.getOrCreate(id)

        val bridge = kr.co.nucode.ble.internal.GattBridge(
            registry = registry,
            emit = {},
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            context = android.content.ContextWrapper(null),
            adapter = { null },
            ackTimeoutMillis = 5_000,
        )

        val thrown = runCatching { kotlinx.coroutines.runBlocking { bridge.connect(id, ConnectOptions()) } }.exceptionOrNull()

        assertTrue(
            "adapter() 가 null 이면 AdapterUnavailable(PoweredOff) 를 던져야 한다(D6): $thrown",
            (thrown as? BleException)?.failure?.code == FailureCode("ADAPTER_UNAVAILABLE"),
        )
    }

    // ── D3 구독 복원은 Configuring에서 종결한다 ─────────────────────────────────────

    /**
     * Ready/legacy Connected 공개 뒤에는 restore를 시작하지 않는다. 복원은 Configuring에서
     * 종결되어야 하므로 이 경로가 desired intent를 바꾸거나 CCCD 실패를 내보내면 안 된다.
     */
    @Test
    fun `D3① Connected 전이는 post-Ready restore를 시작하지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref1 = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        val ref2 = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.DiscoveringServices)
            subscriptionState.addDesired(ref1)
            subscriptionState.addDesired(ref2)
        }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.MtuChanged(id, 247, 244))
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            "Connected 공개 뒤에는 restore를 재시작하면 안 된다: $acc",
            0,
            acc.filterIsInstance<BleEvent.Diagnostic>().count { it.code.startsWith("RESUBSCRIBE") },
        )
        assertEquals(
            "desired intent는 pre-Ready restore만 변경할 수 있다",
            setOf(ref1, ref2),
            core.registry.get(id)!!.subscriptionState.desiredSnapshot,
        )
    }

    /**
     * D3-② — desired intent가 비어 있으면 Connected 전이는 no-op이다.
     */
    @Test
    fun `D3② 빈 desired intent로 Connected 전이하면 restore가 없다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnState.DiscoveringServices) }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.MtuChanged(id, 247, 244))
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            0,
            acc.filterIsInstance<BleEvent.Diagnostic>().count { it.code == "RESUBSCRIBE_FAILED" },
        )
    }

    /**
     * D3-③ stale Connected 이벤트는 desired intent를 바꾸지 않는다.
     */
    @Test
    fun `D3③ stale Connected 이벤트는 desired intent를 보존한다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref1 = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        val ref2 = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Disconnected(DisconnectReason.Initial)) // 재구독 진입 전 이미 재끊김을 재현
            subscriptionState.addDesired(ref1)
            subscriptionState.addDesired(ref2)
        }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Ready))
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            "stale Connected 이벤트가 restore를 시작하면 안 된다",
            0,
            acc.filterIsInstance<BleEvent.Diagnostic>().count { it.code == "RESUBSCRIBE_FAILED" },
        )
        assertEquals(
            "stale 이벤트는 desired intent를 바꾸면 안 된다",
            setOf(ref1, ref2),
            core.registry.get(id)!!.subscriptionState.desiredSnapshot,
        )
    }

    // ── D4 BT off 스윕(plan blecore-design-decisions v1 task 6, review M-6) ──────────

    /**
     * D4-① — Ready 세션 두 개(각각 scheduler pending/queued·subscriptions 보유)에
     * `sweepSessions(PoweredOff)` 를 돌리면 전부 `Disconnected(AdapterUnavailable)` 로 전이하고,
     * scheduler completion은 종결되며, `suppressReconnect`가 세워지고 `gatt`는 null이 되며,
     * `ConnStateChanged` 가 세션당 하나씩 emit 된다.
     *
     * `suppressReconnect` 를 emit 전에 세우는 순서(BleCore.sweepSessions KDoc)의 회귀
     * 고정이 "RECONNECT 계열 Diagnostic 0개"다 — 순서가 뒤집히면 emit 이
     * `advanceConnectSequence` 의 `Disconnected` 분기를 태워 `Reconnector.scheduleReconnect`
     * 가 돌고 `RECONNECT_ATTEMPT` Diagnostic 이 나온다(수정 전이면 이 부분이 red).
     */
    @Test
    fun `D4① Connected 세션들을 스윕하면 Disconnected AdapterUnavailable로 전이하고 pending이 취소되며 재연결 Diagnostic이 없다`() = runTest {
        val core = newCore()
        val idA = testDeviceId("A1")
        val idB = testDeviceId("B2")
        val ref = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())

        val sessionA = core.registry.getOrCreate(idA).apply {
            setStateForTest(ConnState.Ready)
            subscriptionState.addDesired(ref)
            suppressReconnect = false
        }
        val sessionB = core.registry.getOrCreate(idB).apply {
            setStateForTest(ConnState.Ready)
            suppressReconnect = false
        }
        val identity = GattCharacteristicIdentity.from(ref)
        val pendingA = sessionA.gattOperationScheduler.enqueue(
            kr.co.nucode.ble.internal.GattOperation.Read(0, identity),
        )
        val queuedA = sessionA.gattOperationScheduler.enqueue(
            kr.co.nucode.ble.internal.GattOperation.WriteWithResponse(0, identity, byteArrayOf(1)),
        )
        sessionA.gattOperationScheduler.startNext()
        val pendingB = sessionB.gattOperationScheduler.enqueue(
            kr.co.nucode.ble.internal.GattOperation.Read(0, identity),
        )
        sessionB.gattOperationScheduler.startNext()

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.sweepSessions(AdapterState.PoweredOff)
        advanceUntilIdle()
        runCurrent()

        for (session in listOf(sessionA, sessionB)) {
            val state = session.state
            assertTrue(
                "Disconnected(AdapterUnavailable) 여야 한다: $state",
                state is ConnState.Disconnected &&
                    ((state as ConnState.Disconnected).reason as? DisconnectReason.Failed)
                        ?.failure
                        ?.code == FailureCode("ADAPTER_UNAVAILABLE"),
            )
            assertTrue("suppressReconnect 가 세워져야 한다(D4)", session.suppressReconnect)
            assertTrue("gatt 는 null 이어야 한다(D4)", session.gatt == null)
        }
        assertTrue("pending A가 종결돼야 한다(D4)", pendingA.completion.isCompleted)
        assertTrue("queued A가 종결돼야 한다(D4)", queuedA.completion.isCompleted)
        assertTrue("pending B가 종결돼야 한다(D4)", pendingB.completion.isCompleted)

        val connStateChangedEvents = acc.filterIsInstance<BleEvent.ConnStateChanged>()
        assertEquals("세션 2개분 ConnStateChanged 가 나와야 한다(D4)", 2, connStateChangedEvents.size)

        assertEquals(
            "suppressReconnect 를 emit 전에 세워야 재연결이 예약되지 않는다(D4 구현 순서) — " +
                "RECONNECT 계열 Diagnostic 이 없어야 한다",
            0,
            acc.filterIsInstance<BleEvent.Diagnostic>().count { it.code.startsWith("RECONNECT") },
        )
    }

    /**
     * D4-② — 이미 `Disconnected` 인 세션은 스윕이 건드리지 않는다(가드: `state is
     * ConnState.Disconnected` 면 continue). 이벤트가 하나도 나오지 않고 `suppressReconnect`
     * 도 그대로여야 한다.
     */
    @Test
    fun `D4② 이미 Disconnected인 세션은 스윕이 건드리지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Disconnected(DisconnectReason.Initial))
            suppressReconnect = false
        }

        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.sweepSessions(AdapterState.PoweredOff)
        runCurrent()

        assertTrue("이미 Disconnected 인 세션은 이벤트가 나오지 않아야 한다(D4): $acc", acc.isEmpty())
        assertTrue(
            "이미 Disconnected 인 세션의 suppressReconnect 는 스윕이 바꾸면 안 된다(D4)",
            !core.registry.get(id)!!.suppressReconnect,
        )
    }

    /**
     * D4-③ — desired intent는 스윕이 지우지 않는다. 다음 연결 복원의 입력이
     * `SubscriptionState`이므로, 스윕이 이걸 지우면 다음 `connect()` 성공 시 재구독할
     * 대상 자체가 사라진다(D3/D4 접점) — 스윕은 gatt 핸들·pending ack 만 정리하고 "무엇을
     * 구독하고 있었는가"라는 사실은 건드리지 않는다.
     */
    @Test
    fun `D4③ desired intent는 스윕 후에도 보존돼 다음 연결 복원의 입력이 된다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref1 = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        val ref2 = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Ready)
            subscriptionState.addDesired(ref1)
            subscriptionState.addDesired(ref2)
        }

        core.sweepSessions(AdapterState.PoweredOff)

        assertEquals(
            "스윕이 desired intent를 지우면 다음 연결 복원이 재생할 게 없어진다(D4③)",
            setOf(ref1, ref2),
            core.registry.get(id)!!.subscriptionState.desiredSnapshot,
        )
    }

    // ── D7. 권한 판정 순수 함수(plan task 7 §테스트 — evaluatePermissionState) ──────────
    // sdkInt 를 파라미터로 받으므로 테스트 JVM 의 Build.VERSION.SDK_INT(항상 0)와 무관하게
    // 버전 매트릭스를 직접 구동한다. `BleCore` 인스턴스 없이 파일 최상위 함수를 바로 두드린다.

    @Test
    fun `D7① sdkInt 30에서 BLUETOOTH가 매니페스트에 선언되지 않으면 Unauthorized canRequest false`() {
        val result = evaluatePermissionState(
            sdkInt = 30,
            declared = setOf(Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION),
            isGranted = { true },
        )

        assertEquals(AdapterState.Unauthorized(canRequest = false), result)
    }

    @Test
    fun `D7② sdkInt 30에서 전부 선언됐고 FINE_LOCATION만 미허용이면 Unauthorized canRequest true`() {
        val result = evaluatePermissionState(
            sdkInt = 30,
            declared = setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            isGranted = { false },
        )

        assertEquals(AdapterState.Unauthorized(canRequest = true), result)
    }

    @Test
    fun `D7③ sdkInt 26에서 COARSE만 허용돼도 통과한다`() {
        val result = evaluatePermissionState(
            sdkInt = 26,
            declared = setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            isGranted = { it == Manifest.permission.ACCESS_COARSE_LOCATION },
        )

        assertEquals(null, result)
    }

    @Test
    fun `D7④ sdkInt 33에서 SCAN CONNECT 선언과 허용이 모두 되면 통과한다`() {
        val result = evaluatePermissionState(
            sdkInt = 33,
            declared = setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            isGranted = { true },
        )

        assertEquals(null, result)
    }

    /**
     * D7-⑤(review L): targetSdk < 31 앱은 Android 12+ 기기에서 legacy 권한 셋으로 합법
     * 동작한다 — SCAN/CONNECT 미선언을 "앱 빌드 결함(canRequest=false)" 으로 오판하면 안 된다.
     */
    @Test
    fun `D7-5 targetSdk 30 앱은 API 33 기기에서 legacy 권한 셋으로 통과한다`() {
        val declared = setOf(
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val result = evaluatePermissionState(
            sdkInt = 33,
            declared = declared,
            isGranted = { it == android.Manifest.permission.ACCESS_FINE_LOCATION },
            targetSdkInt = 30,
        )
        org.junit.Assert.assertNull("legacy 조합을 빌드 결함으로 오판했다: $result", result)
    }

    // ── autoConnect × Reconnector 배타 (plan app-tunables v3) ──────────────────

    /** 배타 ①: autoConnect 연결의 재연결 소유권은 플랫폼 — 코어는 예약조차 안 한다. */
    @Test
    fun `AC1 autoConnect 세션의 끊김에는 Reconnector가 개입하지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Ready)
            autoConnect = true
        }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        advanceUntilIdle(); runCurrent()

        assertTrue(
            "autoConnect 인데 코어 재연결이 돌았다: $acc",
            acc.none { it is BleEvent.Diagnostic && it.code.startsWith("RECONNECT") },
        )
    }

    /** 배타 ②: 예약은 기기당 하나 — 끊김 이벤트가 겹쳐 와도 백오프 job 은 1개다. */
    @Test
    fun `AC2 예약 대기 중 중복 끊김 이벤트는 두 번째 예약을 만들지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Disconnected(DisconnectReason.Initial)) }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent() // 첫 예약이 잡힌다 (백오프 대기 시작)
        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent() // isActive 가드 — 새 예약 없음

        val attempts = acc.count { it is BleEvent.Diagnostic && it.code == "RECONNECT_ATTEMPT" }
        assertEquals("예약이 중복됐다(기기당 1개여야 한다)", 1, attempts)
    }

    /** 배타 ③(실행 벨트): 예약 후 모드가 autoConnect 로 바뀌면 발화 시점에 멈춘다. */
    @Test
    fun `AC3 백오프 대기 중 autoConnect 모드가 되면 예약된 시도가 발화하지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Disconnected(DisconnectReason.Initial)) }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent()
        session.autoConnect = true // 대기 중 사용자가 autoConnect 로 재연결한 상황의 축약

        advanceUntilIdle(); runCurrent()
        assertTrue(
            "autoConnect 모드인데 예약된 시도가 발화했다: $acc",
            acc.none { it is BleEvent.ConnStateChanged && it.state is ConnState.Connecting },
        )
    }

    /** 배타 ④(취소 경로): 사용자 disconnect 는 대기 중 예약을 즉시 끝낸다 — 손잡이(Job) 검증. */
    @Test
    fun `AC4 disconnect는 대기 중인 재연결 예약을 취소한다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val session = core.registry.getOrCreate(id).apply { setStateForTest(ConnState.Disconnected(DisconnectReason.Initial)) }
        val acc = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(acc) }
        runCurrent()

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Disconnected(DisconnectReason.Initial)))
        runCurrent()
        assertTrue("예약이 잡혀 있어야 한다", session.reconnectJob?.isActive == true)

        core.disconnect(id)
        runCurrent()
        assertTrue("disconnect 후에도 예약이 살아 있다", session.reconnectJob == null)

        advanceUntilIdle(); runCurrent()
        assertTrue(
            "취소된 예약이 발화했다: $acc",
            acc.none { it is BleEvent.ConnStateChanged && it.state is ConnState.Connecting },
        )
    }

    // ── blecore-lifecycle v1 task 2 회귀 (매트릭스 감사 구멍 A~G) ──────────────────────
    // A(신원 가드)·D(GattBridge.connect 시작 리셋의 retryCount=0)·C(재연결 시도 타임아웃
    // 발화) 는 실제 BluetoothGatt/BluetoothAdapter 구성이 필요해 이 모듈의 테스트 의존성
    // (junit + kotlinx-coroutines-test, 목킹 프레임워크 없음)으로는 직접 재현할 수 없다
    // (D6① KDoc 과 같은 근거) — 코드체인 리뷰로만 확인했다. D 의 다른 리셋 지점(advanceConnectSequence
    // 의 Connected 분기)은 위 B2 테스트가 이미 고정한다.

    /**
     * post-Ready Connected 전이는 Configuring restore job을 대체하지 않는다.
     */
    @Test
    fun `PL-B2 Connected 전이는 restore job을 대체하지 않는다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        val ref = CharRef(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        val session = core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Ready)
            subscriptionState.addDesired(ref)
        }

        val firstJob = kotlinx.coroutines.Job()
        session.resubscribeJob = firstJob

        core.emit(BleEvent.ConnStateChanged(id, ConnState.Ready))
        runCurrent()
        assertTrue("Connected는 restore job을 대체하면 안 된다", session.resubscribeJob === firstJob)
        assertTrue("Connected는 restore job을 취소하면 안 된다", !firstJob.isCancelled)
    }

    /**
     * plan B — 취소 지점 3곳 중 [kr.co.nucode.ble.internal.GattBridge.disconnect]. `session.gatt`
     * 가 null 이어도(플랫폼 결합 없이) cancel 자체는 관측할 수 있다 — `session.gatt?.disconnect()`
     * 가 `?.` 라 null 이면 조용히 no-op 이기 때문이다(D6① 과 같은 방식으로 GattBridge 를
     * 직접 구성한다).
     */
    @Test
    fun `PL-B3 GattBridge disconnect가 세션의 resubscribeJob과 reconnectJob을 취소한다`() {
        val registry = kr.co.nucode.ble.internal.SessionRegistry()
        val id = testDeviceId()
        val session = registry.getOrCreate(id)
        val resubscribeJob = kotlinx.coroutines.Job()
        val reconnectJob = kotlinx.coroutines.Job()
        session.resubscribeJob = resubscribeJob
        session.reconnectJob = reconnectJob

        val bridge = kr.co.nucode.ble.internal.GattBridge(
            registry = registry,
            emit = {},
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            context = android.content.ContextWrapper(null),
            adapter = { null },
            ackTimeoutMillis = 5_000,
        )

        bridge.disconnect(id)

        assertTrue("resubscribeJob이 취소돼야 한다(plan B)", resubscribeJob.isCancelled)
        assertTrue("reconnectJob도 취소돼야 한다(기존 배타 규약)", reconnectJob.isCancelled)
        assertTrue("disconnect 후 session.resubscribeJob은 null이어야 한다", session.resubscribeJob == null)
    }

    /**
     * plan F — `close()`가 scheduler pending/queued와 resubscribeJob/reconnectJob/timeoutJob을
     * 전부 종결하는지 확인한다(D1 close 테스트 확장). `coreDispatcherOnly`
     * 가 `StandardTestDispatcher` 이면 close() 의 `runBlocking` 이 데드락한다(D1 KDoc) —
     * 그래서 여기서는 `BleRuntime(config = BleConfig(), adapter = { null })` 로 진짜
     * `Dispatchers.Default` 기반 인스턴스를 별도로 만들어 close() 를 동기 호출한다.
     */
    @Test
    fun `PL-F close 후 scheduler와 lifecycle job이 모두 종결된다`() {
        val core = BleRuntime(config = BleConfig(), adapter = { null })
        val id = testDeviceId()
        val resubscribeJob = kotlinx.coroutines.Job()
        val reconnectJob = kotlinx.coroutines.Job()
        val timeoutJob = kotlinx.coroutines.Job()
        val session = core.registry.getOrCreate(id).apply {
            this.resubscribeJob = resubscribeJob
            this.reconnectJob = reconnectJob
            this.timeoutJob = timeoutJob
        }
        val identity = GattCharacteristicIdentity.from(CharRef(UUID.randomUUID(), UUID.randomUUID()))
        val pending = session.gattOperationScheduler.enqueue(
            kr.co.nucode.ble.internal.GattOperation.Read(0, identity),
        )
        val queued = session.gattOperationScheduler.enqueue(
            kr.co.nucode.ble.internal.GattOperation.WriteWithResponse(0, identity, byteArrayOf(1)),
        )
        session.gattOperationScheduler.startNext()

        core.close()

        assertTrue("scheduler pending이 종결돼야 한다(plan F)", pending.completion.isCompleted)
        assertTrue("scheduler queued가 종결돼야 한다(plan F)", queued.completion.isCompleted)
        assertTrue("resubscribeJob이 취소돼야 한다(plan F)", resubscribeJob.isCancelled)
        assertTrue("reconnectJob이 취소돼야 한다(plan F)", reconnectJob.isCancelled)
        assertTrue("timeoutJob이 취소돼야 한다(plan F)", timeoutJob.isCancelled)
    }

    /**
     * plan G — [kr.co.nucode.ble.internal.GattBridge.handleConnectionStateChange] 의
     * STATE_DISCONNECTED 정리 블록은 `BluetoothGatt` 콜백 파라미터가 필요해 직접 호출이
     * 불가하다(플랫폼 결합) — 대신 같은 정리를 추가한 [BleCore.sweepSessions] 경유로
     * mtu/chunkSize 무효화를 검증한다(스윕도 세션을 `Disconnected` 로 보내므로 대칭이다).
     */
    @Test
    fun `PL-G sweepSessions가 Connected 세션의 mtu chunkSize를 null로 되돌린다`() = runTest {
        val core = newCore()
        val id = testDeviceId()
        core.registry.getOrCreate(id).apply {
            setStateForTest(ConnState.Ready)
            mtu = 247
            chunkSize = 244
        }

        core.sweepSessions(AdapterState.PoweredOff)

        assertEquals(null, core.registry.get(id)?.mtu)
        assertEquals(null, core.registry.get(id)?.chunkSize)
    }

    // ── Slice 2 Task 3 bounded lifecycle cleanup ────────────────────────────────

    @Test
    fun `Slice2 link start status callback 누락은 terminal cleanup으로 끝난다`() = runTest {
        val startFailure = lifecycleFixture()
        startFailure.driver.connectFailure = BleException(
            FailureMapper.fromAdapterState(AdapterState.PoweredOff),
        )
        val thrown = runCatching {
            startFailure.bridge.connect(startFailure.deviceId, ConnectOptions())
        }.exceptionOrNull()
        assertFailureCode("ADAPTER_UNAVAILABLE", thrown)
        assertEquals(ConnectionStage.Disconnected, startFailure.session.connectionCoordinator.stage)

        val statusFailure = lifecycleFixture()
        statusFailure.bridge.connect(statusFailure.deviceId, ConnectOptions())
        statusFailure.driver.emitConnection(
            statusFailure.deviceId,
            statusFailure.generation,
            status = 133,
            newState = BluetoothProfile.STATE_CONNECTED,
        )
        assertEquals(ConnState.Disconnecting, statusFailure.session.state)
        advanceTimeBy(25)
        runCurrent()
        assertTrue(statusFailure.session.state is ConnState.Disconnected)
        assertEquals(listOf("connect", "disconnect", "close"), statusFailure.driver.commands)

        val missing = lifecycleFixture()
        missing.bridge.connect(missing.deviceId, ConnectOptions())
        advanceTimeBy(65)
        runCurrent()
        assertTrue(missing.session.state is ConnState.Disconnected)
        assertEquals(listOf("connect", "disconnect", "close"), missing.driver.commands)
    }

    @Test
    fun `Slice2 discovery start status callback 누락은 bounded terminal cleanup으로 끝난다`() = runTest {
        suspend fun runScenario(
            configure: (LifecycleFakeGattDriver) -> Unit,
            fail: (LifecycleFixture) -> Unit,
        ) {
            val fixture = lifecycleFixture()
            configure(fixture.driver)
            fixture.bridge.connect(fixture.deviceId, ConnectOptions())
            fixture.driver.emitConnected(fixture.deviceId, fixture.generation)
            fail(fixture)

            assertEquals(ConnState.Disconnecting, fixture.session.state)
            assertEquals(ConnectionStage.Disconnecting, fixture.session.connectionCoordinator.stage)
            assertTrue(fixture.session.gattOperationScheduler.isPoisoned)
            advanceTimeBy(25)
            runCurrent()
            assertTrue(fixture.session.state is ConnState.Disconnected)
            assertEquals(ConnectionStage.Disconnected, fixture.session.connectionCoordinator.stage)
            assertEquals(listOf("connect", "discover", "disconnect", "close"), fixture.driver.commands)
        }

        runScenario(
            configure = { it.discoverStarted = false },
            fail = { it.bridge.discoverServices(it.deviceId) },
        )
        runScenario(
            configure = {},
            fail = { fixture ->
                fixture.bridge.discoverServices(fixture.deviceId)
                fixture.driver.emitServices(fixture.deviceId, fixture.generation, status = 133)
            },
        )
        runScenario(
            configure = {},
            fail = { fixture ->
                fixture.bridge.discoverServices(fixture.deviceId)
                advanceTimeBy(40)
                runCurrent()
            },
        )
    }

    @Test
    fun `Slice2 configuration fallback stays Configuring until Core terminalizes restore`() = runTest {
        suspend fun fallbackScenario(
            requestStarted: Boolean,
            callbackStatus: Int?,
        ) {
            val fixture = lifecycleFixture()
            fixture.driver.requestMtuStarted = requestStarted
            fixture.bridge.connect(fixture.deviceId, ConnectOptions(requestedMtu = 185))
            fixture.driver.emitConnected(fixture.deviceId, fixture.generation)
            fixture.bridge.discoverServices(fixture.deviceId)
            fixture.driver.emitServices(fixture.deviceId, fixture.generation)
            fixture.bridge.requestMtu(fixture.deviceId, 185)
            callbackStatus?.let {
                fixture.driver.emitMtu(fixture.deviceId, fixture.generation, 185, it)
            }

            assertEquals(ConnectionStage.Configuring, fixture.session.connectionCoordinator.stage)
            assertEquals(23, fixture.session.mtu)
            assertEquals(20, fixture.session.chunkSize)
            assertTrue("fallback은 disconnect를 시작하면 안 된다", "disconnect" !in fixture.driver.commands)
        }

        fallbackScenario(requestStarted = false, callbackStatus = null)
        fallbackScenario(requestStarted = true, callbackStatus = 133)

        val missing = lifecycleFixture()
        missing.bridge.connect(missing.deviceId, ConnectOptions(requestedMtu = 185))
        missing.driver.emitConnected(missing.deviceId, missing.generation)
        missing.bridge.discoverServices(missing.deviceId)
        missing.driver.emitServices(missing.deviceId, missing.generation)
        missing.bridge.requestMtu(missing.deviceId, 185)

        advanceTimeBy(65)
        runCurrent()

        assertTrue(missing.session.state is ConnState.Disconnected)
        assertEquals(ConnectionStage.Disconnected, missing.session.connectionCoordinator.stage)
        assertEquals(listOf("connect", "discover", "mtu:185", "disconnect", "close"), missing.driver.commands)
    }

    @Test
    fun `Slice2 public disconnect는 callback 누락에도 bounded하고 중복 호출은 no-op이다`() = runTest {
        val driver = LifecycleFakeGattDriver()
        val core = newCore(
            disconnectCleanupTimeoutMillis = 25,
            gattDriver = driver,
        )
        val id = testDeviceId("D1")
        val session = core.registry.getOrCreate(id)
        session.connectionCoordinator.beginConnection(this, 100) {}

        core.disconnect(id)
        core.disconnect(id)

        assertEquals(ConnState.Disconnecting, session.state)
        assertEquals(listOf("disconnect"), driver.commands)
        advanceTimeBy(25)
        runCurrent()

        assertTrue(session.state is ConnState.Disconnected)
        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertEquals(listOf("disconnect", "close"), driver.commands)

        core.disconnect(id)
        assertEquals(listOf("disconnect", "close"), driver.commands)
    }

    @Test
    fun `Slice2 terminal lifecycle은 stale callback으로 되살아나지 않는다`() = runTest {
        val fixture = lifecycleFixture()
        fixture.bridge.connect(fixture.deviceId, ConnectOptions())
        fixture.driver.emitConnected(fixture.deviceId, fixture.generation)
        fixture.bridge.discoverServices(fixture.deviceId)
        advanceTimeBy(65)
        runCurrent()
        val terminalEvents = fixture.events.toList()
        val terminalCommands = fixture.driver.commands.toList()

        fixture.driver.emitConnected(fixture.deviceId, fixture.generation)
        fixture.driver.emitServices(fixture.deviceId, fixture.generation)
        fixture.driver.emitMtu(fixture.deviceId, fixture.generation, 247, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue(fixture.session.state is ConnState.Disconnected)
        assertEquals(ConnectionStage.Disconnected, fixture.session.connectionCoordinator.stage)
        assertEquals(terminalEvents, fixture.events)
        assertEquals(terminalCommands, fixture.driver.commands)
    }

    @Test
    fun `Slice2 public read write subscribe는 Configuring에서 driver start 전에 거부된다`() = runTest {
        val driver = PublicFakeGattDriver()
        val core = newCore(gattDriver = driver)
        val id = testDeviceId("A1")
        val ref = CharRef(UUID.randomUUID(), UUID.randomUUID())
        val session = core.registry.getOrCreate(id)
        val generation = session.connectionCoordinator.beginConnection(this, 100) {}
        session.connectionCoordinator.transition(
            callbackGeneration = generation,
            expectedStage = ConnectionStage.Connecting,
            nextStage = ConnectionStage.DiscoveringServices,
            scope = this,
            timeoutMillis = 100,
        )
        session.connectionCoordinator.transition(
            callbackGeneration = generation,
            expectedStage = ConnectionStage.DiscoveringServices,
            nextStage = ConnectionStage.Configuring,
            scope = this,
            timeoutMillis = 100,
        )

        val readFailure = runCatching { core.read(id, ref) }.exceptionOrNull()
        val writeFailure = runCatching { core.write(id, ref, byteArrayOf(1)) }.exceptionOrNull()
        val subscribeFailure = runCatching { core.subscribe(id, ref) }.exceptionOrNull()

        assertFailureCode("NOT_READY", readFailure)
        assertFailureCode("NOT_READY", writeFailure)
        assertFailureCode("NOT_READY", subscribeFailure)
        assertTrue(driver.commands.isEmpty())
    }

    @Test
    fun `Slice5 permanent restore failure emits exact service instance and Ready observes cleared restore job`() = runTest {
        val failed = CharRef(UUID.randomUUID(), UUID.randomUUID(), instanceIndex = 2, serviceInstanceIndex = 3)
        val remaining = CharRef(UUID.randomUUID(), UUID.randomUUID(), instanceIndex = 1, serviceInstanceIndex = 4)
        val driver = PublicFakeGattDriver()
        val resolver = GattOperationTargetResolver { _, ref ->
            if (ref == failed) {
                null
            } else {
                GattOperationTarget(
                    characteristic = BluetoothGattCharacteristic(ref.charUuid, 0, 0),
                    identity = GattCharacteristicIdentity(
                        serviceUuid = ref.serviceUuid,
                        serviceInstanceIndex = ref.serviceInstanceIndex,
                        characteristicUuid = ref.charUuid,
                        characteristicInstanceIndex = ref.instanceIndex,
                    ),
                    propertyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    cccdDescriptor = BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID, 0),
                )
            }
        }
        val core = newCore(gattDriver = driver, gattOperationTargetResolver = resolver)
        val id = testDeviceId("R5")
        val session = core.registry.getOrCreate(id)
        val generation = session.connectionCoordinator.beginConnection(this, 100) {}
        session.connectionCoordinator.transition(
            callbackGeneration = generation,
            expectedStage = ConnectionStage.Connecting,
            nextStage = ConnectionStage.DiscoveringServices,
            scope = this,
            timeoutMillis = 100,
        )
        session.connectionCoordinator.transition(
            callbackGeneration = generation,
            expectedStage = ConnectionStage.DiscoveringServices,
            nextStage = ConnectionStage.Configuring,
            scope = this,
            timeoutMillis = 100,
        )
        session.subscriptionState.beginGeneration(generation)
        session.gattOperationScheduler.beginNextGeneration()
        session.subscriptionState.addDesired(failed)
        session.subscriptionState.addDesired(remaining)

        val officialEvents = mutableListOf<BleClientEvent>()
        var restoreJobAtReady: kotlinx.coroutines.Job? = null
        backgroundScope.launch { core.officialEvents.toList(officialEvents) }
        backgroundScope.launch {
            core.events.collect { event ->
                if (event == BleEvent.ConnStateChanged(id, ConnState.Ready)) {
                    restoreJobAtReady = session.resubscribeJob
                }
            }
        }
        runCurrent()

        core.emit(BleEvent.MtuChanged(id, 247, 244))
        runCurrent()

        assertEquals(listOf("notify", "descriptor"), driver.commands)
        assertEquals(setOf(remaining), session.subscriptionState.desiredSnapshot)
        val failureEvent = officialEvents.filterIsInstance<OfficialBleClientEvent.SubscriptionRestoreFailed>().single()
        assertEquals(
            ServiceRef(BleUuid(failed.serviceUuid), failed.serviceInstanceIndex),
            failureEvent.characteristic.service,
        )
        assertEquals(failed.instanceIndex, failureEvent.characteristic.instanceIndex)

        driver.emitDescriptor(id, remaining)
        runCurrent()

        assertEquals(ConnState.Ready, session.state)
        assertEquals(setOf(remaining), session.subscriptionState.activeSnapshot)
        assertNull("Ready observer must not see a completed restore handle", restoreJobAtReady)
        assertNull(session.resubscribeJob)
    }

    @Test
    fun `Slice2 duplicate connect는 같은 effective options에서 generation을 유지한다`() = runTest {
        val core = newCore(linkTimeoutMillis = 100)
        val id = testDeviceId("A3")
        val session = core.registry.getOrCreate(id).apply {
            autoConnect = false
            requestedMtu = 185
        }
        val generation = session.connectionCoordinator.beginConnection(this, 100) {}

        core.connect(id, ConnectOptions(requestedMtu = 185))

        assertEquals(generation, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(ConnState.Connecting, session.state)
    }

    @Test
    fun `Slice2 duplicate connect의 options mismatch와 disconnecting connect는 generation을 바꾸지 않는다`() = runTest {
        val core = newCore(linkTimeoutMillis = 100)
        val id = testDeviceId("A4")
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        val session = core.registry.getOrCreate(id).apply {
            autoConnect = false
            requestedMtu = 185
        }
        val generation = session.connectionCoordinator.beginConnection(this, 100) {}

        val optionsConflict = runCatching {
            core.connect(id, ConnectOptions(requestedMtu = 247))
        }.exceptionOrNull()

        assertEquals("CONNECTION_OPTIONS_CONFLICT", optionsConflict?.message)
        assertTrue(events.filterIsInstance<BleEvent.Diagnostic>().any { it.code == "CONNECTION_OPTIONS_CONFLICT" })
        assertEquals(generation, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Connecting, session.connectionCoordinator.stage)
        assertEquals(ConnState.Connecting, session.state)

        session.connectionCoordinator.beginCleanup(
            targetGeneration = generation,
            reason = TerminalCleanupReason.UserRequested,
            scope = this,
            disconnectCleanupTimeoutMillis = 100,
            onTimeout = {},
        )
        val disconnectConflict = runCatching {
            core.connect(id, ConnectOptions(requestedMtu = 185))
        }.exceptionOrNull()

        assertEquals("CONNECT_DURING_DISCONNECT", disconnectConflict?.message)
        assertTrue(events.filterIsInstance<BleEvent.Diagnostic>().any { it.code == "CONNECT_DURING_DISCONNECT" })
        assertEquals(generation, session.connectionCoordinator.generation)
        assertEquals(ConnectionStage.Disconnecting, session.connectionCoordinator.stage)
        assertEquals(ConnState.Disconnecting, session.state)
    }

    @Test
    fun `Slice2 Ready public write는 common scheduler를 거쳐 driver start에 도달한다`() = runTest {
        val fixture = publicGattFixture()

        val write = async {
            fixture.core.write(
                fixture.deviceId,
                fixture.ref,
                byteArrayOf(1),
                WriteType.WithResponse,
                Chunking.None,
            )
        }
        runCurrent()

        assertEquals(ConnState.Ready, fixture.session.state)
        assertEquals(ConnectionStage.Ready, fixture.session.connectionCoordinator.stage)
        assertEquals(GattOperationKind.WriteWithResponse, fixture.session.gattOperationScheduler.pending?.operation?.kind)
        assertEquals(listOf("write"), fixture.driver.commands)

        fixture.driver.emitWrite(fixture.deviceId, fixture.ref)
        runCurrent()

        assertEquals(WriteResult.Completed(1), write.await())
        assertNull(fixture.session.gattOperationScheduler.pending)
        assertEquals(0, fixture.session.gattOperationScheduler.queuedCount)
    }

    @Test
    fun `Slice2 close는 lifecycle timer와 scheduler work를 함께 drain한다`() {
        val driver = LifecycleFakeGattDriver()
        val core = BleRuntime(
            config = BleConfig(dispatcher = kotlinx.coroutines.Dispatchers.Unconfined),
            adapter = { null },
            gattDriver = driver,
        )
        val id = testDeviceId("A2")
        val session = core.registry.getOrCreate(id)
        session.connectionCoordinator.beginConnection(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            60_000,
        ) {}
        val identity = GattCharacteristicIdentity.from(CharRef(UUID.randomUUID(), UUID.randomUUID()))
        val pending = session.gattOperationScheduler.enqueue(
            kr.co.nucode.ble.internal.GattOperation.Read(
                session.gattOperationScheduler.currentGeneration,
                identity,
            ),
        )
        session.gattOperationScheduler.startNext()

        core.close()

        assertNull(session.connectionCoordinator.activeDeadline)
        assertEquals(ConnectionStage.Disconnected, session.connectionCoordinator.stage)
        assertTrue(session.gattOperationScheduler.isPoisoned)
        assertTrue(pending.completion.isCompleted)
        assertEquals(listOf("close"), driver.commands)
        assertNull(core.registry.get(id))
    }

    private fun TestScope.lifecycleFixture(): LifecycleFixture {
        val registry = SessionRegistry()
        val deviceId = testDeviceId("C1")
        val session = registry.getOrCreate(deviceId)
        val driver = LifecycleFakeGattDriver()
        val events = mutableListOf<BleEvent>()
        val bridge = kr.co.nucode.ble.internal.GattBridge(
            registry = registry,
            emit = events::add,
            scope = this,
            driver = driver,
            ackTimeoutMillis = 5_000,
            readinessTimeouts = ConnectionReadinessTimeouts(
                linkMillis = 40,
                discoveryMillis = 40,
                configurationMillis = 40,
                disconnectCleanupMillis = 25,
            ),
        )
        return LifecycleFixture(deviceId, session, driver, bridge, events)
    }

    private data class LifecycleFixture(
        val deviceId: DeviceId,
        val session: Session,
        val driver: LifecycleFakeGattDriver,
        val bridge: kr.co.nucode.ble.internal.GattBridge,
        val events: MutableList<BleEvent>,
    ) {
        val generation: Long
            get() = session.connectionCoordinator.generation
    }

    private fun TestScope.publicGattFixture(operationTimeoutMillis: Long = 5_000): PublicGattFixture {
        val driver = PublicFakeGattDriver()
        val targetResolver = GattOperationTargetResolver { _, ref ->
            GattOperationTarget(
                characteristic = BluetoothGattCharacteristic(ref.charUuid, 0, 0),
                identity = GattCharacteristicIdentity.from(ref),
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                cccdDescriptor = BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID, 0),
            )
        }
        val core = newCore(
            operationTimeoutMillis = operationTimeoutMillis,
            gattDriver = driver,
            gattOperationTargetResolver = targetResolver,
        )
        val deviceId = testDeviceId()
        val session = core.registry.getOrCreate(deviceId).apply {
            chunkSize = 20
        }
        val connectionGeneration = session.connectionCoordinator.beginConnection(this, 100) {}
        session.connectionCoordinator.transition(
            callbackGeneration = connectionGeneration,
            expectedStage = ConnectionStage.Connecting,
            nextStage = ConnectionStage.DiscoveringServices,
            scope = this,
            timeoutMillis = 100,
        )
        session.connectionCoordinator.transition(
            callbackGeneration = connectionGeneration,
            expectedStage = ConnectionStage.DiscoveringServices,
            nextStage = ConnectionStage.Configuring,
            scope = this,
            timeoutMillis = 100,
        )
        session.connectionCoordinator.transition(
            callbackGeneration = connectionGeneration,
            expectedStage = ConnectionStage.Configuring,
            nextStage = ConnectionStage.Ready,
            scope = this,
        )
        val events = mutableListOf<BleEvent>()
        backgroundScope.launch { core.events.toList(events) }
        runCurrent()
        return PublicGattFixture(
            core = core,
            deviceId = deviceId,
            ref = CharRef(UUID.randomUUID(), UUID.randomUUID()),
            queuedRef = CharRef(UUID.randomUUID(), UUID.randomUUID()),
            session = session,
            driver = driver,
            events = events,
            generation = session.gattOperationScheduler.currentGeneration,
        )
    }

    private fun PublicGattFixture.enqueueReadBehindPending(scope: TestScope): Deferred<Throwable?> {
        val queued = scope.async {
            runCatching { core.read(deviceId, queuedRef) }.exceptionOrNull()
        }
        scope.runCurrent()
        assertEquals(1, session.gattOperationScheduler.queuedCount)
        return queued
    }

    private suspend fun TestScope.assertPoisonedAndLateCallbackIsolated(
        fixture: PublicGattFixture,
        queued: Deferred<Throwable?>,
        expectedCommands: List<String>,
        emitLateCallback: () -> Unit,
    ) {
        assertFailureCode("OPERATION_INVALIDATED", queued.await())
        assertTrue(fixture.session.gattOperationScheduler.isPoisoned)
        assertEquals(fixture.generation, fixture.session.gattOperationScheduler.currentGeneration)
        assertNull(fixture.session.gattOperationScheduler.pending)
        assertEquals(0, fixture.session.gattOperationScheduler.queuedCount)
        assertTrue(fixture.session.state is ConnState.Disconnected)
        assertNull(fixture.session.gatt)
        assertEquals(expectedCommands, fixture.driver.commands)

        emitLateCallback()
        runCurrent()

        assertTrue(fixture.session.gattOperationScheduler.isPoisoned)
        assertNull(fixture.session.gattOperationScheduler.pending)
        assertEquals(0, fixture.session.gattOperationScheduler.queuedCount)
        assertFailureCode("OPERATION_INVALIDATED", queued.await())
        assertEquals(expectedCommands, fixture.driver.commands)
        assertTrue(
            fixture.events.filterIsInstance<BleEvent.Diagnostic>()
                .any { it.code == "GATT_OPERATION_DUPLICATE_CALLBACK" },
        )
    }

    private data class PublicGattFixture(
        val core: BleRuntime,
        val deviceId: DeviceId,
        val ref: CharRef,
        val queuedRef: CharRef,
        val session: Session,
        val driver: PublicFakeGattDriver,
        val events: List<BleEvent>,
        val generation: Long,
    )

    private class LifecycleFakeGattDriver : GattDriver {
        val commands = mutableListOf<String>()
        var connectFailure: Exception? = null
        var discoverStarted: Boolean = true
        var requestMtuStarted: Boolean = true
        private var sink: (GattDriverEvent) -> Unit = {}

        override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
            this.sink = sink
        }

        override fun connect(deviceId: DeviceId, autoConnect: Boolean): BluetoothGatt? {
            commands += "connect"
            connectFailure?.let { throw it }
            return null
        }

        override fun disconnect(deviceId: DeviceId, gatt: BluetoothGatt?) {
            commands += "disconnect"
        }

        override fun close(deviceId: DeviceId, gatt: BluetoothGatt?) {
            commands += "close"
        }

        override fun discoverServices(deviceId: DeviceId, gatt: BluetoothGatt?): Boolean {
            commands += "discover"
            return discoverStarted
        }

        override fun requestMtu(deviceId: DeviceId, gatt: BluetoothGatt?, mtu: Int): Boolean {
            commands += "mtu:$mtu"
            return requestMtuStarted
        }

        override fun setCharacteristicNotification(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            enabled: Boolean,
        ): Boolean = true

        override fun writeDescriptor(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
        ): Boolean = true

        override fun writeCharacteristic(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean = true

        fun emitConnected(deviceId: DeviceId, generation: Long) {
            emitConnection(
                deviceId = deviceId,
                generation = generation,
                status = BluetoothGatt.GATT_SUCCESS,
                newState = BluetoothProfile.STATE_CONNECTED,
            )
        }

        fun emitConnection(
            deviceId: DeviceId,
            generation: Long,
            status: Int,
            newState: Int,
        ) {
            sink(
                GattDriverEvent.ConnectionStateChanged(
                    deviceId = deviceId,
                    source = GattCallbackSource { true },
                    status = status,
                    newState = newState,
                    connectionGeneration = generation,
                ),
            )
        }

        fun emitServices(deviceId: DeviceId, generation: Long, status: Int = BluetoothGatt.GATT_SUCCESS) {
            sink(
                GattDriverEvent.ServicesDiscovered(
                    deviceId = deviceId,
                    source = GattCallbackSource { true },
                    status = status,
                    services = emptyList(),
                    connectionGeneration = generation,
                ),
            )
        }

        fun emitMtu(deviceId: DeviceId, generation: Long, mtu: Int, status: Int) {
            sink(
                GattDriverEvent.MtuChanged(
                    deviceId = deviceId,
                    source = GattCallbackSource { true },
                    mtu = mtu,
                    status = status,
                    connectionGeneration = generation,
                ),
            )
        }
    }

    private class PublicFakeGattDriver : GattDriver {
        val commands = mutableListOf<String>()
        private var sink: (GattDriverEvent) -> Unit = {}

        override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
            this.sink = sink
        }

        override fun connect(deviceId: DeviceId, autoConnect: Boolean): BluetoothGatt? = null

        override fun disconnect(deviceId: DeviceId, gatt: BluetoothGatt?) {
            commands += "disconnect"
        }

        override fun close(deviceId: DeviceId, gatt: BluetoothGatt?) {
            commands += "close"
        }

        override fun discoverServices(deviceId: DeviceId, gatt: BluetoothGatt?): Boolean = true

        override fun requestMtu(deviceId: DeviceId, gatt: BluetoothGatt?, mtu: Int): Boolean = true

        override fun setCharacteristicNotification(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            enabled: Boolean,
        ): Boolean {
            commands += "notify"
            return true
        }

        override fun writeDescriptor(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
        ): Boolean {
            commands += "descriptor"
            return true
        }

        override fun readCharacteristic(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
        ): Boolean {
            commands += "read"
            return true
        }

        override fun writeCharacteristic(
            deviceId: DeviceId,
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean {
            commands += "write"
            return true
        }

        fun emitRead(deviceId: DeviceId, ref: CharRef, value: ByteArray) {
            sink(
                GattDriverEvent.CharacteristicRead(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    characteristic = GattCharacteristicSnapshot(
                        serviceUuid = ref.serviceUuid,
                        charUuid = ref.charUuid,
                        instanceIndex = ref.instanceIndex,
                        propertyMask = BluetoothGattCharacteristic.PROPERTY_READ,
                    ),
                    value = value,
                    status = BluetoothGatt.GATT_SUCCESS,
                ),
            )
        }

        fun emitWrite(deviceId: DeviceId, ref: CharRef) {
            sink(
                GattDriverEvent.CharacteristicWrite(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    status = BluetoothGatt.GATT_SUCCESS,
                    attributeIdentity = GattCharacteristicIdentity.from(ref),
                ),
            )
        }

        fun emitDescriptor(deviceId: DeviceId, ref: CharRef) {
            sink(
                GattDriverEvent.DescriptorWrite(
                    deviceId = deviceId,
                    source = CURRENT_SOURCE,
                    status = BluetoothGatt.GATT_SUCCESS,
                    attributeIdentity = GattDescriptorIdentity(
                        characteristic = GattCharacteristicIdentity(
                            serviceUuid = ref.serviceUuid,
                            serviceInstanceIndex = ref.serviceInstanceIndex,
                            characteristicUuid = ref.charUuid,
                            characteristicInstanceIndex = ref.instanceIndex,
                        ),
                        descriptorUuid = CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    ),
                ),
            )
        }
    }

    private companion object {
        val CURRENT_SOURCE = GattCallbackSource { true }
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

private val BleEvent.Diagnostic.code: String
    get() = failure.code.value
