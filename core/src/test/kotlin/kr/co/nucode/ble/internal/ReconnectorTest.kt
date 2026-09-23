package kr.co.nucode.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.PlatformDetail
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.connection.ConnectSequenceHooks
import kr.co.nucode.ble.internal.connection.Reconnector
import kr.co.nucode.ble.internal.connection.canRetry
import kr.co.nucode.ble.internal.connection.computeBackoffDelayMillis
import kr.co.nucode.ble.internal.event.BleEvent
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.SessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectorTest {

    private val defaultPolicy = ReconnectPolicy()

    // ---- 백오프 곡선 : base * 2^attempt, maxDelay 로 clamp ----

    @Test
    fun `백오프 곡선 attempt 0에서 3까지 지수적으로 증가한다`() {
        assertEquals(1_000L, computeBackoffDelayMillis(0, defaultPolicy))
        assertEquals(2_000L, computeBackoffDelayMillis(1, defaultPolicy))
        assertEquals(4_000L, computeBackoffDelayMillis(2, defaultPolicy))
        assertEquals(8_000L, computeBackoffDelayMillis(3, defaultPolicy))
    }

    @Test
    fun `백오프는 attempt가 커져도 maxDelay를 넘지 않는다`() {
        // attempt=5 -> 1000 * 2^5 = 32000 > maxDelay(30000) → clamp
        assertEquals(30_000L, computeBackoffDelayMillis(5, defaultPolicy))
        // attempt가 매우 커도(오버플로 방지 경로) maxDelay로 clamp 된 값을 벗어나지 않는다
        assertEquals(30_000L, computeBackoffDelayMillis(100, defaultPolicy))
    }

    // ---- 재시도 판정 ----

    @Test
    fun `최대 시도 횟수 미만이면 재시도 가능`() {
        val policy = ReconnectPolicy(maxAttempts = 3)
        assertTrue(canRetry(0, policy))
        assertTrue(canRetry(1, policy))
        assertTrue(canRetry(2, policy))
    }

    @Test
    fun `최대 시도 횟수 도달 시 재시도를 중단한다`() {
        val policy = ReconnectPolicy(maxAttempts = 3)
        assertFalse(canRetry(3, policy))
        assertFalse(canRetry(10, policy))
    }

    // ---- Diagnostic 방출 : 삼키지 않는다는 계약 ----

    @Test
    fun `재연결 시도마다 Diagnostic이 emit된다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:FF")
        registry.getOrCreate(deviceId)

        val emitted = mutableListOf<BleEvent>()
        val reconnector = Reconnector(registry, { emitted.add(it) }, this, ConnectSequenceHooks.None)
        reconnector.policy = ReconnectPolicy(maxAttempts = 3, baseDelayMillis = 100L, maxDelayMillis = 1_000L)

        // 1회차 시도
        reconnector.scheduleReconnect(deviceId, linkFailure(deviceId, 133))
        val firstAttemptDiag = emitted.filterIsInstance<BleEvent.Diagnostic>().last()
        assertEquals("RECONNECT_ATTEMPT", firstAttemptDiag.failure.code.value)
        assertEquals(133, (firstAttemptDiag.failure.platformDetail as PlatformDetail.Android).statusCode)
        assertEquals(Severity.Info, firstAttemptDiag.severity)

        advanceUntilIdle()

        // 시도 중 Connecting 이 방출된다 (Reconnecting 상태는 존재하지 않는다)
        assertTrue(emitted.filterIsInstance<BleEvent.ConnStateChanged>().any { it.state == ConnectionState.Connecting })
        // gatt 가 null(=connect() 시작 실패)이므로 시도는 typed failure Disconnected로
        // **종결**된다 — plan lifecycle v2 ①. 이전에는 Connecting 에 동결돼 H-3 가드가
        // 이후 사용자 connect() 를 영구 no-op 으로 만들었다(그 동결을 정상으로 박제했던
        // 이 자리의 구 assertion 이 review 에서 반박된 것).
        val stateAfter = registry.get(deviceId)?.connectionState?.value
        assertTrue(
            "시작 실패한 시도가 종결되지 않았다: $stateAfter",
            stateAfter is ConnectionState.Disconnected &&
                ((stateAfter as ConnectionState.Disconnected).reason as? BleDisconnectReason.Failed)
                    ?.failure
                    ?.code
                    ?.value == "RECONNECT_CONNECT_START_FAILED",
        )

        // retryCount 가 늘어 다음 시도가 이어진다
        assertEquals(1, registry.get(deviceId)?.retryCount)

        // 2회차, 3회차 시도
        reconnector.scheduleReconnect(deviceId, linkFailure(deviceId, 8))
        advanceUntilIdle()
        reconnector.scheduleReconnect(deviceId, linkFailure(deviceId, 19))
        advanceUntilIdle()

        val attemptDiagnostics = emitted.filterIsInstance<BleEvent.Diagnostic>()
            .filter { it.failure.code.value == "RECONNECT_ATTEMPT" }
        assertEquals(3, attemptDiagnostics.size)
        // GATT 원본 코드가 그대로 보존된다 — 133/8/19는 원인이 다르다(설계 §5)
        assertEquals(
            listOf(133, 8, 19),
            attemptDiagnostics.map { (it.failure.platformDetail as PlatformDetail.Android).statusCode },
        )

        // 4회차 시도 : maxAttempts(3) 도달로 포기 Diagnostic 방출, 더 이상 attempt 하지 않는다
        reconnector.scheduleReconnect(deviceId, linkFailure(deviceId, 133))
        advanceUntilIdle()

        val giveUpDiagnostics = emitted.filterIsInstance<BleEvent.Diagnostic>()
            .filter { it.failure.code.value == "RECONNECT_GIVE_UP" }
        assertEquals(1, giveUpDiagnostics.size)
        assertEquals(Severity.Warning, giveUpDiagnostics.first().severity)

        // 포기 이후에는 attempt 횟수가 더 늘지 않는다
        assertEquals(3, attemptDiagnostics.size)
        assertEquals(3, registry.get(deviceId)?.retryCount)
    }

    @Test(timeout = 10_000)
    fun `retained connect 동기 시작 실패는 active job guard를 풀어 다음 bounded retry로 이어진다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:01")
        val session = registry.getOrCreate(deviceId)
        val emitted = mutableListOf<BleEvent>()
        lateinit var reconnector: Reconnector
        reconnector = Reconnector(
            registry = registry,
            emit = { emitted += it },
            scope = this,
            sequence = reconnectOnDisconnectedStage(registry) { reconnector },
        ).also {
            it.policy = ReconnectPolicy(
                maxAttempts = 2,
                baseDelayMillis = 100,
                maxDelayMillis = 100,
            )
        }

        reconnector.scheduleReconnect(deviceId)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            2,
            emitted.filterIsInstance<BleEvent.Diagnostic>()
                .count { it.failure.code.value == "RECONNECT_ATTEMPT" },
        )
        assertEquals(2, session.retryCount)
        assertTrue("다음 bounded retry가 예약돼야 한다", session.reconnectJob?.isActive == true)

        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            2,
            emitted.filterIsInstance<BleEvent.Diagnostic>()
                .count { it.failure.code.value == "RECONNECT_ATTEMPT" },
        )
        assertEquals(
            1,
            emitted.filterIsInstance<BleEvent.Diagnostic>()
                .count { it.failure.code.value == "RECONNECT_GIVE_UP" },
        )
        assertEquals(2, session.retryCount)
        assertTrue(session.connectionState.value is ConnectionState.Disconnected)
        assertTrue(session.reconnectJob == null)
    }

    @Test(timeout = 10_000)
    fun `예약 상태를 알리는 동기 이벤트가 같은 재연결을 중복 예약하지 않는다`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:04")
        val session = registry.getOrCreate(deviceId).apply {
            effectiveReconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                maxAttempts = 4,
                initialDelayMillis = 500,
                maxDelayMillis = 2_000,
            )
        }
        val emitted = mutableListOf<BleEvent>()
        lateinit var reconnector: Reconnector
        reconnector = Reconnector(
            registry = registry,
            emit = { emitted += it },
            scope = this,
            sequence = reconnectOnDisconnectedStage(registry) { reconnector },
        )

        reconnector.scheduleReconnect(deviceId)

        assertEquals(1, session.retryCount)
        assertEquals(
            1,
            emitted.filterIsInstance<BleEvent.Diagnostic>()
                .count { it.failure.code.value == "RECONNECT_ATTEMPT" },
        )
        assertTrue(session.reconnectJob != null)
    }

    @Test
    fun `reconnect start rejection shares the owner failure with terminal state`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:03")
        val session = registry.getOrCreate(deviceId)
        val emitted = mutableListOf<BleEvent>()
        val reconnector = Reconnector(registry, emitted::add, this, ConnectSequenceHooks.None).also {
            it.policy = ReconnectPolicy(maxAttempts = 1, baseDelayMillis = 10, maxDelayMillis = 10)
        }

        reconnector.scheduleReconnect(deviceId)
        advanceTimeBy(10)
        runCurrent()

        val diagnostic = emitted.filterIsInstance<BleEvent.Diagnostic>()
            .first { it.failure.code.value == "RECONNECT_CONNECT_START_FAILED" }
        val stateFailure = ((session.connectionState.value as ConnectionState.Disconnected).reason as BleDisconnectReason.Failed).failure
        assertEquals(stateFailure, diagnostic.failure)
        assertEquals(session.failureSessionId, diagnostic.failure.context.sessionId)
        assertEquals(session.connectionCoordinator.generation, diagnostic.failure.context.connectionGeneration)
    }

    @Test
    fun `official policy publishes recovering and exhausts its own finite budget`() = runTest {
        val registry = SessionRegistry()
        val deviceId = PeripheralId("AA:BB:CC:DD:EE:02")
        val session = registry.getOrCreate(deviceId).apply {
            effectiveReconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                maxAttempts = 1,
                initialDelayMillis = 25,
                maxDelayMillis = 25,
            )
        }
        val emitted = mutableListOf<BleEvent>()
        val reconnector = Reconnector(registry, emitted::add, this, ConnectSequenceHooks.None)

        reconnector.scheduleReconnect(deviceId, linkFailure(deviceId, 133))

        assertEquals(
            kr.co.nucode.ble.ConnectionState.Recovering(
                attempt = 1,
                retryAfterMillis = 25,
                lastFailure = (session.connectionState.value as kr.co.nucode.ble.ConnectionState.Recovering).lastFailure,
            ),
            session.connectionState.value,
        )
        assertTrue(session.reconnectJob?.isActive == true)

        advanceTimeBy(25)
        runCurrent()
        reconnector.scheduleReconnect(deviceId, linkFailure(deviceId, 8))

        assertEquals(
            1,
            emitted.filterIsInstance<BleEvent.Diagnostic>()
                .count { it.failure.code.value == "RECONNECT_EXHAUSTED" },
        )
        assertEquals(1, session.retryCount)
    }

    private fun linkFailure(deviceId: PeripheralId, status: Int): BleFailure = FailureMapper.gattStatusFailed(
        statusCode = status,
        phase = FailurePhase.Link,
        operation = AndroidApiOperation.OnConnectionStateChange,
        context = kr.co.nucode.ble.FailureContext(),
    )

    /**
     * 프로덕션 재진입 경로를 그대로 흉내 낸다 — `ConnectSequence.onStatePublished` 처럼 발행 직후
     * 세션 stage 가 Disconnected 이고 `suppressReconnect` 가 아니면 재연결을 예약한다.
     */
    private fun reconnectOnDisconnectedStage(
        registry: SessionRegistry,
        reconnector: () -> Reconnector,
    ): ConnectSequenceHooks = object : ConnectSequenceHooks {
        override fun onStatePublished(deviceId: PeripheralId) {
            val session = registry.get(deviceId) ?: return
            if (session.stage == ConnectionStage.Disconnected && !session.suppressReconnect) {
                reconnector().scheduleReconnect(deviceId)
            }
        }

        override fun onServicesDiscovered(deviceId: PeripheralId) = Unit
        override fun onMtuSettled(deviceId: PeripheralId) = Unit
    }
}
