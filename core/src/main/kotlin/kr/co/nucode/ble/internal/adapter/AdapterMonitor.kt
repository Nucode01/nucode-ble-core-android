package kr.co.nucode.ble.internal.adapter

import kr.co.nucode.ble.internal.port.AdapterSource
import kr.co.nucode.ble.internal.port.ClockPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.internal.core.adapter.AdapterCore
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect
import kr.co.nucode.ble.internal.core.adapter.AdapterEvent
import kr.co.nucode.ble.internal.core.adapter.AdapterFacts

/**
 * 어댑터/권한 상태의 유일한 소유자(plan runtime-split) — 상태 계산·브로드캐스트 리시버·단일 쓰기 지점
 * ([updateAdapterState])과 "어느 전이가 무엇을 부르는가" 판정을 가진다. 전이가 부르는 다른 자원의 동작
 * (온기·스캔·세션 종결, 세션 재연결 재개)은 소유자인 런타임이 좁은 콜백([onAdapterLost]/[onAdapterReturned])
 * 으로 준다. 코어 스레드에서만 불린다(리시버는 [scope] 로 홉한다).
 *
 * @param adapterSource 어댑터 조회·on/off 통지 포트 — 리시버 소유는 platform 구현 몫.
 * @param clock Unknown 대기 기한을 재는 시계 포트.
 */
internal class AdapterMonitor(
    private val scope: CoroutineScope,
    private val adapterSource: AdapterSource,
    private val clock: ClockPort,
    private val adapterStateProbe: (() -> AdapterState)?,
    private val onAdapterLost: (AdapterState) -> Unit,
    private val onAdapterReturned: () -> Unit,
) {

    private var state = AdapterCore.initial(observeFacts())

    /** 게시용 거울 — 쓰기는 [AdapterEffect.Publish] 실행기([execute])뿐이다. */
    private val _adapterState = MutableStateFlow(state.current)

    /** 어댑터/권한 상태. `Unauthorized(canRequest)`로 요청 가능/미허용을 구분한다(설계 §5, D7). */
    val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    /**
     * 플랫폼 사실 수집만 한다(분류는 [AdapterCore.classify]). 어댑터 검사가 권한 검사보다 먼저다 —
     * 내부 테스트 생성자(`adapter = { null }`)는 권한 조회도 빈 선언 집합이라 권한 판정 경로를 타면 항상
     * `Unauthorized(false)` 가 나오는데, 핸들이 없으면 권한을 묻지 않고 [AdapterFacts.Absent] 로 끝난다
     * (`BleCoreWiringTest` 56개가 이 순서에 기대고 있다). 권한이 없으면 `isEnabled` 도 부르지 않는다.
     *
     * 권한 판정 자체(D7/M-8+#16, BLE 스캔 결과 배달 조건 — review #2)는
     * [AdapterSource.permissionState] 포트가 맡는다 — 구현(platform)이 드라이버 유무에 따라 고른다.
     */
    private fun observeFacts(): AdapterFacts {
        adapterStateProbe?.let { return AdapterFacts.Probed(it()) }
        val adapterInstance = adapterSource.current() ?: return AdapterFacts.Absent
        val unauthorized = adapterSource.permissionState()
        return AdapterFacts.Present(unauthorized, unauthorized == null && adapterInstance.isEnabled())
    }

    /**
     * 어댑터 on/off 통지(review #3 해동) — 잡는 즉시 [_adapterState] 를 다시 계산해 반영한다.
     * `scope.launch` 로 홉하는 이유는 다른 워커 콜백과 동일하다(GattBridge/Scanner KDoc) —
     * 통지는 브로드캐스트를 발송한 시스템 스레드에서 오고, `_adapterState` 를 coreScope 단일
     * 스레드 밖에서 직접 건드리면 그 확정이 깨진다.
     */
    private fun onAdapterStateChanged() {
        scope.launch { refreshAdapterState() }
    }

    /**
     * [onAdapterStateChanged] 본문 추출(plan G task 1) — 리시버는 등록이 안 되는 테스트
     * 생성자 경로에서 검증할 수 없어(브로드캐스트 자체가 없다), 이 함수를 직접 호출해
     * [adapterStateProbe] 플립 → [updateAdapterState] 전이(스윕 1회 규칙 포함)를 검증한다.
     * 프로덕션에서는 항상 [scope] 위(코어 단일 스레드)에서만 불린다 — 이 함수 자체는
     * 스레드 확정을 강제하지 않는다. 런타임의 `sweepSessions`(plan task 6 지시)와 같은
     * 테스트 seam 규약: 테스트는 이 함수를 코어 디스패처 밖(테스트 스레드)에서
     * 직접 부른다 — `BleCoreWiringTest` 가 `core.sweepSessions(...)`를 같은 방식으로
     * 직접 부르는 것과 동일 전례.
     */
    fun refreshAdapterState() {
        updateAdapterState(observeFacts())
    }

    /** 리시버 등록 — 런타임이 워커 조립 뒤 init 에서 부른다(생성 시점 타이밍 유지). */
    fun start() {
        adapterSource.startListening(::onAdapterStateChanged)
    }

    /** 리시버 해제 — 런타임 close 정리의 첫 단계. */
    fun close() {
        adapterSource.stopListening()
    }

    /**
     * 사실 관측의 유일한 입구(review High). Ready → non-Ready 전이 감지는 쓰는 쪽(리시버·명령 재계산)이
     * 아니라 [AdapterCore] 가 한다 — 어느 경로가 먼저 전이를 관측하든 스윕이 정확히 1회 돈다.
     * 감지가 리시버에만 있으면, BT off 직후 명령이 먼저 재계산해 값을 써버린 경우 리시버가
     * "이미 non-Ready 였다"고 읽어 스윕을 건너뛴다(콜백 안 오는 기기에서 세션 Connected 동결 — D4 가 막으려던 바로 그 상태).
     *
     * D4/M-6 — Ready 로 들어오거나 non-Ready 사이를 오가는 전이는 스윕 대상이 아니다.
     */
    private fun updateAdapterState(facts: AdapterFacts) {
        execute(step(AdapterEvent.Observed(facts)))
    }

    private fun step(event: AdapterEvent): List<AdapterEffect> {
        val result = AdapterCore.step(state, event)
        state = result.state
        return result.effects
    }

    private fun execute(effects: List<AdapterEffect>) {
        for (effect in effects) when (effect) {
            is AdapterEffect.Publish -> _adapterState.value = effect.value
            is AdapterEffect.Lost -> onAdapterLost(effect.cause)
            AdapterEffect.Returned -> onAdapterReturned()
            else -> Unit
        }
    }

    /**
     * 즉시 알 수 있는 실패는 throw 한다(plan §명령 실패의 두 갈래) — 권한 없음 · 어댑터 off.
     * 끊김·재시도 같은 비동기 실패는 여기서 다루지 않고 events 로만 나간다.
     *
     * 매번 [observeFacts] 로 재계산한다(review #3 "냉동 해제") — [adapterSource] 통지
     * 는 `ACTION_STATE_CHANGED` 브로드캐스트만 잡는다. 사용자가 설정 앱에서 위치/BLUETOOTH
     * 권한을 새로 허용하는 것처럼 그 리시버가 못 잡는 변화는, 늦어도 다음 명령 진입에서
     * 이 재계산으로 반영된다.
     */
    suspend fun ensureAdapterReady(
        operation: AndroidApiOperation,
    ) {
        updateAdapterState(observeFacts())
        var effect = step(AdapterEvent.Admit(operation, afterWait = false)).single()
        if (effect is AdapterEffect.AwaitDecision) {
            // 판정 전(Unknown)이면 세션 전이 없이 값이 바뀌기를 기다린다. 호출자 취소는 대기를 끝낸다.
            clock.awaitWithinOrNull(effect.maxMillis) { _adapterState.first { AdapterCore.isDecided(it) } }
            updateAdapterState(observeFacts())
            effect = step(AdapterEvent.Admit(operation, afterWait = true)).single()
        }
        if (effect is AdapterEffect.Reject) throw BleException(effect.failure)
    }
}
