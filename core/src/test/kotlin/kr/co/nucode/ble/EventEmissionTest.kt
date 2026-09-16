package kr.co.nucode.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 배선 테스트(plan task 8, §잠재 에러) — `SUSPEND` + `tryEmit` 조합이 의도대로
 * 동작하는지 검증한다. `BleCore.emit` 은 private 이고 `BleCore` 는 Context 를 요구해
 * 직접 인스턴스화할 수 없으므로, `BleCore.kt` 의 `_events` 와 동일한 설정
 * (`replay = 0, extraBufferCapacity = 64, onBufferOverflow = SUSPEND`)을 그대로
 * 재현해 코루틴 라이브러리 자체의 동작을 검증한다(설계 §6 배선②, plan §미해결·전제).
 *
 * ★ 이 테스트가 틀리면 유실이 조용해진다 — 배선 전체가 이 가정 위에 서 있다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEmissionTest {

    @Test
    fun `official restore failure event preserves only structured public values`() {
        val uuid = java.util.UUID.randomUUID()
        val characteristic = CharacteristicRef(ServiceRef(BleUuid(uuid)), BleUuid(uuid))
        val failure = BleFailure(
            category = FailureCategory("gatt"),
            code = FailureCode("CCCD_REJECTED"),
            phase = FailurePhase.Configure,
            retryability = Retryability.Never,
        )

        val event = OfficialBleClientEvent.SubscriptionRestoreFailed(
            peripheralId = PeripheralId("peripheral"),
            characteristic = characteristic,
            failure = failure,
        )

        assertEquals(PeripheralId("peripheral"), event.peripheralId)
        assertEquals(characteristic, event.characteristic)
        assertEquals(failure, event.failure)
    }

    private val bufferCapacity = 64 // BleConfig.eventBufferCapacity 기본값과 동일

    private fun newEvent(i: Int): BleEvent =
        BleEvent.Diagnostic(
            Severity.Info,
            BleFailure(
                category = FailureCategory("test"),
                code = FailureCode("TEST_$i"),
                phase = FailurePhase.Operation,
                retryability = Retryability.Never,
            ),
        )

    // ── ① 수집자 0 : tryEmit 은 블로킹하지 않는다 ──────────────────────────────

    @Test
    fun `수집자가 0일 때 tryEmit은 블로킹하지 않고 true를 반환한다`() = runTest {
        // 규칙 6 의 전제: emit 이 GATT 콜백 스레드를 막으면 안 된다.
        // replay=0 이라 수집자가 없으면 값을 보관할 곳이 없어 그냥 버려지고, tryEmit 은 즉시 반환한다.
        val flow = MutableSharedFlow<BleEvent>(
            replay = 0,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        // 버퍼 용량을 훨씬 넘는 횟수를 호출해도 전부 즉시 반환되어야 한다(블로킹 없음의 증거).
        val results = (1..(bufferCapacity * 2)).map { i -> flow.tryEmit(newEvent(i)) }

        // 관측: 수집자 0 에서는 tryEmit 이 전부 true 를 반환한다 — 보관할 구독자가 없어
        // "버퍼가 찼다"는 상태 자체가 성립하지 않기 때문이다.
        assertTrue(results.all { it })
    }

    // ── ② 느린 수집자 + 버퍼 초과 : tryEmit 이 false 를 반환한다 ────────────────

    @Test
    fun `느린 수집자가 버퍼를 채우면 tryEmit이 false를 반환한다`() = runTest {
        // 이 설계의 핵심 주장: 유실이 나면 반드시 tryEmit == false 로 관측 가능해야 한다.
        val flow = MutableSharedFlow<BleEvent>(
            replay = 0,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        // 첫 이벤트를 받은 뒤 영원히 멈추는 수집자 — 이후 값은 전부 버퍼에 쌓인다.
        val job = launch {
            flow.collect { awaitCancellation() }
        }
        runCurrent() // 수집자가 collect 를 시작해 구독자로 등록될 때까지 진행시킨다

        // 버퍼 용량 + 1 개를 tryEmit 한다.
        val results = (1..(bufferCapacity + 1)).map { i -> flow.tryEmit(newEvent(i)) }

        // 관측: 마지막(버퍼 용량 + 1번째)은 false 다 — 유실이 조용하지 않고 관측된다.
        assertFalse(results.last())

        job.cancel()
    }

    // ── ③ 대조군 : DROP_OLDEST 였다면 tryEmit 은 항상 true ──────────────────────

    @Test
    fun `DROP_OLDEST였다면 같은 시나리오에서 tryEmit은 항상 true다`() = runTest {
        // 왜 SUSPEND 를 골랐는지 이 테스트가 증명한다 — DROP_OLDEST 는 오래된 값을 조용히
        // 버리고 자리를 내주므로 tryEmit 이 실패할 일이 없다. 유실이 있어도 호출부는 모른다.
        val flow = MutableSharedFlow<BleEvent>(
            replay = 0,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val job = launch {
            flow.collect { awaitCancellation() }
        }
        runCurrent()

        val results = (1..(bufferCapacity + 1)).map { i -> flow.tryEmit(newEvent(i)) }

        // 관측: DROP_OLDEST 에서는 전부 true 다 — SUSPEND 를 고른 이유가 이 대조로 드러난다.
        assertTrue(results.all { it })

        job.cancel()
    }
}
