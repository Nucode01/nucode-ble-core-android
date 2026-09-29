package kr.co.nucode.ble.internal.core.adapter

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.AwaitDecision
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Lost
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Proceed
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Publish
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Reject
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Returned

/**
 * 어댑터 판정의 순수 상태 기계: `(상태, 사건) → (새 상태, 할 일 목록)`.
 *
 * 플랫폼 사실 분류·명령 진입 판정표·이탈/복귀 판정(`contract/states.md` §6)을 정한다.
 * 시간·입출력·스레드·코루틴을 쓰지 않는다. Unknown 대기는 [AwaitDecision] 으로 Runtime 에 맡긴다.
 */
internal object AdapterCore {
    /** Unknown 인 어댑터를 명령이 기다리는 최대 시간. 설정이 아니라 내부 상수다. */
    const val UNKNOWN_WAIT_MILLIS = 2_000L

    /** 생성 때 동기 분류. 할 일 없음. */
    fun initial(facts: AdapterFacts): AdapterCoreState = AdapterCoreState(classify(facts))

    fun step(state: AdapterCoreState, event: AdapterEvent): AdapterStep = when (event) {
        is AdapterEvent.Observed -> observed(state, classify(event.facts))
        is AdapterEvent.Admit -> admit(state, event)
    }

    /**
     * 어댑터 검사가 권한 검사보다 먼저다: 핸들이 없으면 [AdapterState.Unsupported], 그 뒤 권한 사실, 그 뒤 on/off.
     */
    fun classify(facts: AdapterFacts): AdapterState = when (facts) {
        is AdapterFacts.Probed -> facts.value
        AdapterFacts.Absent -> AdapterState.Unsupported
        is AdapterFacts.Present -> facts.unauthorized ?: if (facts.enabled) AdapterState.Ready else AdapterState.PoweredOff
    }

    fun isReady(value: AdapterState): Boolean = value is AdapterState.Ready

    fun isDecided(value: AdapterState): Boolean = value !is AdapterState.Unknown

    /** 게시 → (Ready 이탈이면 [Lost]) → (Ready 복귀면 [Returned]). 이탈은 Ready 에서 떨어질 때만 낸다. */
    private fun observed(state: AdapterCoreState, value: AdapterState): AdapterStep {
        val wasReady = isReady(state.current)
        val nowReady = isReady(value)
        val effects = buildList<AdapterEffect> {
            add(Publish(value))
            if (wasReady && !nowReady) add(Lost(value))
            if (!wasReady && nowReady) add(Returned)
        }
        return AdapterStep(state.copy(current = value), effects)
    }

    private fun admit(state: AdapterCoreState, event: AdapterEvent.Admit): AdapterStep {
        val effect: AdapterEffect = when (val value = state.current) {
            AdapterState.Ready -> Proceed
            is AdapterState.Unauthorized -> Reject(
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Adapter,
                    operation = event.operation,
                    canRequest = value.canRequest,
                ),
            )
            AdapterState.Unknown ->
                if (event.afterWait) Reject(FailureMapper.fromAdapterState(value)) else AwaitDecision(UNKNOWN_WAIT_MILLIS)
            AdapterState.Unsupported, AdapterState.PoweredOff, AdapterState.Resetting ->
                Reject(FailureMapper.fromAdapterState(value))
        }
        return AdapterStep(state, listOf(effect))
    }
}
