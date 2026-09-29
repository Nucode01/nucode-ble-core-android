package kr.co.nucode.ble.internal.core.scan

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.internal.model.PlatformScanSpec
import kr.co.nucode.ble.internal.model.ScanObservation

/** Result of one bounded, address-filtered rediscovery scan. */
internal sealed interface RediscoveryOutcome {
    /** The peripheral advertised; the platform LE cache now knows its address type. */
    data object Found : RediscoveryOutcome
    /** No advertisement within the timeout. */
    data object NotFound : RediscoveryOutcome
    /** The scan could not run (permission, throttling, no scanner); caller falls back to a direct connect. */
    data class Unavailable(val failure: BleFailure) : RediscoveryOutcome
}

/** 재발견 소비자 하나. [address] 는 대문자 정규형이다. [id] 는 Core 가 매기고 Runtime 이 대기자 표의 열쇠로 쓴다. */
internal data class RediscoveryConsumer(val id: Long, val address: PeripheralId)

/** 플랫폼 시작 결과를 누가 기다리는가. 결과 사건의 소비자 규칙을 고른다. */
internal enum class StartOrigin {
    /** 동기 `startScan` 이 기다린다. */
    UserStart,

    /** 턴 끝 reconcile 이 기다린다. */
    Reconcile,
}

/** 스캔 Core 의 상태 값. 갱신은 copy 로만 한다. */
internal data class ScanCoreState(
    /** 사용자 요청. 시작 결과를 기다리는 동안에도 채워져 있고, 실패하면 되돌린다. */
    val userRequest: ScanRequest? = null,
    /** 진행 중 재발견 소비자 — 등록 순서. 끝나도 [ScanEvent.RediscoveryReleased] 전까지 남는다. */
    val rediscoveries: List<RediscoveryConsumer> = emptyList(),
    /** 지금 플랫폼에서 도는 스캔의 spec. null = 플랫폼 스캔 없음. */
    val platform: PlatformScanSpec? = null,
    /** 플랫폼 시작 시도마다 1 증가하는 스캔 식별. 실패한 시작도 값을 소비한다. */
    val generation: Long = 0L,
    /** 턴 끝 reconcile 이 예약되어 아직 돌지 않았는가. */
    val reconcileScheduled: Boolean = false,
    val nextConsumerId: Long = 1L,
    /** 플랫폼 시작 결과를 기다리는 중이면 누가 기다리는지. */
    val pendingStart: StartOrigin? = null,
    /** 공개 스캔 상태. */
    val published: ScanState = ScanState.Idle(null),
)

/** 플랫폼 시작 호출의 결과. */
internal sealed interface StartResult {
    data object Ok : StartResult

    /** 플랫폼이 시작을 거절했다(도는 스캔 없음). */
    data class Failed(val failure: BleFailure) : StartResult

    /** 시작 한도 초과 — 플랫폼을 부르지 않았고 도는 스캔은 그대로다. [waitMillis] 뒤에 다시 시작할 수 있다. */
    data class Throttled(val waitMillis: Long) : StartResult

    /** 포트가 예외를 던졌다. [afterStop] 은 정지 단계를 마치고 시작 호출에서 던졌는가. */
    data class Threw(val afterStop: Boolean) : StartResult

    /**
     * 취소(CancellationException)나 Error 로 시작이 끊겼다 — 호출자에게 다시 던져질 뿐이라 소비자는 끝내지 않는다.
     * 상태만 [Threw] 와 같이 맞춘다(도는 플랫폼 없음, 정지 단계를 마쳤으면 generation 소비).
     */
    data class Aborted(val afterStop: Boolean) : StartResult
}

internal sealed interface ScanEvent {
    data class StartRequested(val request: ScanRequest) : ScanEvent
    data object StopRequested : ScanEvent
    data class RediscoveryRequested(val deviceId: PeripheralId) : ScanEvent
    data class RediscoveryReleased(val id: Long) : ScanEvent
    data class RediscoveryEndRequested(val deviceId: PeripheralId, val outcome: RediscoveryOutcome) : ScanEvent
    data object ReconcileDue : ScanEvent
    data class PlatformStartFinished(val result: StartResult) : ScanEvent
    data class PlatformResult(val generation: Long, val observation: ScanObservation) : ScanEvent
    data class PlatformFailed(val generation: Long, val failure: BleFailure) : ScanEvent
    data class AdapterLost(val cause: AdapterState) : ScanEvent
    data class CloseRequested(val failure: BleFailure) : ScanEvent
}

internal sealed interface ScanEffect {
    /** [stopFirst] 면 도는 플랫폼 스캔을 먼저 멈추고 [spec] 으로 시작한다. 결과는 [ScanEvent.PlatformStartFinished] 로 돌려준다. */
    data class StartPlatform(val spec: PlatformScanSpec, val generation: Long, val stopFirst: Boolean) : ScanEffect
    data object StopPlatform : ScanEffect
    data class PublishState(val state: ScanState) : ScanEffect
    data class Deliver(val observation: ScanObservation) : ScanEffect
    data class Registered(val id: Long) : ScanEffect
    data class EndRediscovery(val id: Long, val outcome: RediscoveryOutcome) : ScanEffect

    /** 등록하지 못한 재발견(주소 형식 오류) — 플랫폼·사용자 소비자는 건드리지 않고 바로 [failure] 로 끝난다. */
    data class RediscoveryRefused(val failure: BleFailure) : ScanEffect

    /** 동기 `startScan` 호출자에게 답한다. null = 성공. */
    data class Reply(val failure: BleFailure?) : ScanEffect
    data class Diagnostic(val severity: Severity, val failure: BleFailure) : ScanEffect
    data object ScheduleReconcile : ScanEffect
    data object ClearStartLedger : ScanEffect
}

internal class ScanStep(val state: ScanCoreState, val effects: List<ScanEffect>)
