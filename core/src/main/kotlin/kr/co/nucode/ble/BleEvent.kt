package kr.co.nucode.ble

/**
 * 코어 내부 신호. 연결 시퀀스 구동(`BleRuntime.advanceConnectSequence`)과 공식 이벤트 투영의
 * 입력이다. 공개 스트림이 아니다 — 공개 API 는 `scanResults`/`events`/세션별 `notifications`로
 * 쪼개져 있다. [CharValue] 는 공용 버스에 실리지 않고 `NotificationHub` 로만 간다(plan J).
 */
internal sealed interface BleEvent {
    public data class ConnStateChanged(
        val deviceId: PeripheralId,
        val state: ConnState,
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
 * producers use [kr.co.nucode.ble.internal.DiagnosticIngress] directly.
 */
internal class DiagnosticObservation(
    internal val severity: Severity,
    internal val failure: BleFailure,
    private val valuesFactory: (DiagnosticOptions) -> Map<String, DiagnosticValue> = { emptyMap() },
) {
    internal fun values(options: DiagnosticOptions): Map<String, DiagnosticValue> = valuesFactory(options)
}

/**
 * Structured official projection. Scan events are fed from ScanObservation at
 * ingress; no raw advertisement projection exists.
 */
public sealed interface OfficialBleClientEvent : BleClientEvent {
    public data class Scan(val result: ScanResult) : OfficialBleClientEvent
    public data class Connection(val peripheralId: PeripheralId, val state: ConnectionState) : OfficialBleClientEvent
    public data class Services(val peripheralId: PeripheralId, val services: List<GattService>) : OfficialBleClientEvent
    /** A desired subscription could not be restored permanently and was removed from intent. */
    public data class SubscriptionRestoreFailed(
        val peripheralId: PeripheralId,
        val characteristic: CharacteristicRef,
        val failure: BleFailure,
    ) : OfficialBleClientEvent
    public data class Diagnostic(val severity: Severity, val failure: BleFailure) : OfficialBleClientEvent
}

/** Diagnostic 심각도. 작게 유지한다. */
public enum class Severity {
    Info,
    Warning,
    Error,
}
