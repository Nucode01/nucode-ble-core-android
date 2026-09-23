package kr.co.nucode.ble.internal.event

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DiagnosticOptions
import kr.co.nucode.ble.DiagnosticValue
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.Severity

/**
 * 코어 내부 신호. 내부 관측 탭(`_events`)과 공식 이벤트 투영의
 * 입력이다. 연결 절차는 이 이벤트로 구동되지 않는다(`ConnectSequence` 직접 호출). 공개 스트림이 아니다 — 공개 API 는 `scanResults`/`events`/세션별 `notifications`로
 * 쪼개져 있다. [CharValue] 는 공용 버스에 실리지 않고 `NotificationHub` 로만 간다(plan J).
 */
internal sealed interface BleEvent {
    public data class ConnStateChanged(
        val deviceId: PeripheralId,
        val state: ConnectionState,
    ) : BleEvent

    public data class ServicesDiscovered(
        val deviceId: PeripheralId,
        val services: List<GattService>,
    ) : BleEvent

    /** notify/indicate 로 수신한 값. */
    public data class CharValue(
        val deviceId: PeripheralId,
        val charRef: CharacteristicRef,
        val bytes: ByteArray,
    ) : BleEvent {
        // ByteArray 를 data class 필드로 두면 equals/hashCode 가 참조 비교가 되므로 내용 비교로 직접 구현한다.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CharValue) return false
            return deviceId == other.deviceId &&
                charRef == other.charRef &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = deviceId.hashCode()
            result = 31 * result + charRef.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    public data class MtuChanged(
        val deviceId: PeripheralId,
        val mtu: Int,
        val chunkSize: Int,
    ) : BleEvent

    /**
     * A typed diagnostic. Device correlation belongs to [BleFailure.context]; raw exception
     * messages, payloads, and platform identifiers never cross this public boundary.
     */
    public data class Diagnostic(
        val severity: Severity,
        val failure: BleFailure,
    ) : BleEvent

}

/**
 * Internal diagnostic input retained for same-module test seams. It is not a [BleEvent]; runtime
 * producers use [kr.co.nucode.ble.internal.diagnostics.DiagnosticIngress] directly.
 *
 * Despite the name, `BleRuntime.emit(DiagnosticObservation)` goes through `report` and IS projected to
 * the public event stream. Do not route per-payload observations through it — those must use
 * `DiagnosticIngress.observe` (logger-only) or they flood `events` again.
 */
internal class DiagnosticObservation(
    internal val severity: Severity,
    internal val failure: BleFailure,
    private val valuesFactory: (DiagnosticOptions) -> Map<String, DiagnosticValue> = { emptyMap() },
) {
    internal fun values(options: DiagnosticOptions): Map<String, DiagnosticValue> = valuesFactory(options)
}
