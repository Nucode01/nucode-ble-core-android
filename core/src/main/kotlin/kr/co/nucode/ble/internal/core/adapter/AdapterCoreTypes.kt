package kr.co.nucode.ble.internal.core.adapter

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure

/** 어댑터 Core 의 상태 값. 현재 어댑터 값 하나다. 갱신은 copy 로만 한다. */
internal data class AdapterCoreState(val current: AdapterState)

/** 플랫폼 사실. 수집은 Runtime(포트 호출)이 하고 분류는 Core 가 한다. */
internal sealed interface AdapterFacts {
    /** 어댑터 핸들이 없다. */
    data object Absent : AdapterFacts

    /** 어댑터 핸들이 있다. [unauthorized] 가 null 이 아니면 권한 사실이 우선이고 [enabled] 는 보지 않는다. */
    data class Present(val unauthorized: AdapterState.Unauthorized?, val enabled: Boolean) : AdapterFacts

    /** 시험 seam(adapterStateProbe) 이 준 값 그대로. */
    data class Probed(val value: AdapterState) : AdapterFacts
}

internal sealed interface AdapterEvent {
    /** 플랫폼 사실을 새로 관측했다(리시버 통지·명령 진입 재계산). */
    data class Observed(val facts: AdapterFacts) : AdapterEvent

    /** 명령 진입 판정. 상태를 바꾸지 않는다. [afterWait] 는 Unknown 대기를 한 번 마친 뒤의 판정이다. */
    data class Admit(val operation: AndroidApiOperation, val afterWait: Boolean) : AdapterEvent
}

internal sealed interface AdapterEffect {
    /** 게시 값을 새 값으로 쓴다. */
    data class Publish(val value: AdapterState) : AdapterEffect

    /** Ready 에서 비Ready(Unknown 포함)로 떨어졌다. 전이당 1회. */
    data class Lost(val cause: AdapterState) : AdapterEffect

    /** 비Ready 에서 Ready 로 돌아왔다. */
    data object Returned : AdapterEffect

    /** 명령을 진행해도 된다. */
    data object Proceed : AdapterEffect

    /** Unknown 이다. 값이 정해지기를 최대 [maxMillis] 기다린 뒤 다시 관측·판정한다. */
    data class AwaitDecision(val maxMillis: Long) : AdapterEffect

    /** 명령을 거절한다. */
    data class Reject(val failure: BleFailure) : AdapterEffect
}

internal class AdapterStep(val state: AdapterCoreState, val effects: List<AdapterEffect>)
