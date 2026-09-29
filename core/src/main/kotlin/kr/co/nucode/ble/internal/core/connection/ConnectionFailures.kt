package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureCategories
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability

/** 연결 정리·기한 정책이 만드는 실패 값. 세션 상관 문맥은 호출자가 [FailureContext] 로 준다. */
internal object ConnectionFailures {
    private fun failure(code: String, phase: FailurePhase, context: FailureContext): BleFailure = BleFailure(
        category = FailureCategories.of(code),
        code = FailureCode(code),
        phase = phase,
        retryability = Retryability.NewConnectionMayHelp,
        context = context,
        platformDetail = null,
    )

    /** `Disconnecting` 이외 단계의 기한 초과 — 만료된 단계의 원인이 코드·단계를 정한다. */
    fun lifecycleTimeout(reason: TerminalCleanupReason, context: FailureContext): BleFailure {
        // timeoutReasonFor 는 이 세 reason 과 DisconnectCleanupTimeout 만 만들고,
        // DisconnectCleanupTimeout 은 정리 기한 정책이 disconnectCleanupTimeout 으로 먼저 가른다.
        val (code, phase) = when (reason) {
            TerminalCleanupReason.LinkTimeout -> "LINK_TIMEOUT" to FailurePhase.Link
            TerminalCleanupReason.ServiceDiscoveryTimeout -> "SERVICE_DISCOVERY_TIMEOUT" to FailurePhase.DiscoverServices
            TerminalCleanupReason.ConfigurationTimeout -> "CONFIGURATION_TIMEOUT" to FailurePhase.Configure
            else -> error("unreachable: lifecycleTimeout($reason)")
        }
        return failure(code, phase, context)
    }

    /** `Disconnecting` 단계 기한 자체가 지났을 때. */
    fun disconnectCleanupTimeout(context: FailureContext): BleFailure =
        failure("DISCONNECT_CLEANUP_TIMEOUT", FailurePhase.Close, context)

    /** `driver.disconnect()` 호출 자체가 던질 때(연결 정리 시작 실패). */
    fun disconnectStartFailed(context: FailureContext): BleFailure =
        failure("DISCONNECT_START_FAILED", FailurePhase.Close, context)
}
