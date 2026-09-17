package kr.co.nucode.ble

/**
 * 코어가 내보내는 단일 이벤트 스트림의 원소.
 * 타입별로 스트림을 쪼개지 않는다 — 쪼개면 브리지에 EventChannel 관리 로직이 생긴다(설계 §5).
 */
internal sealed interface BleEvent {
    public data class ScanResult(
        val deviceId: DeviceId,
        val name: String?,
        val rssi: Int,
        val advData: ByteArray,
    ) : BleEvent {
        // ByteArray 를 data class 필드로 두면 equals/hashCode 가 참조 비교가 되므로 내용 비교로 직접 구현한다.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ScanResult) return false
            return deviceId == other.deviceId &&
                name == other.name &&
                rssi == other.rssi &&
                advData.contentEquals(other.advData)
        }

        override fun hashCode(): Int {
            var result = deviceId.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + rssi
            result = 31 * result + advData.contentHashCode()
            return result
        }
    }

    public data class ConnStateChanged(
        val deviceId: DeviceId,
        val state: ConnState,
    ) : BleEvent

    public data class ServicesDiscovered(
        val deviceId: DeviceId,
        val services: List<DiscoveredService>,
    ) : BleEvent

    /** notify/indicate 로 수신한 값. */
    public data class CharValue(
        val deviceId: DeviceId,
        val charRef: CharRef,
        val bytes: ByteArray,
    ) : BleEvent {
        // ByteArray 참조 비교 회피 — ScanResult 와 동일한 이유.
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
        val deviceId: DeviceId,
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
 * ingress, not reconstructed from the deprecated raw [BleEvent.ScanResult].
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
