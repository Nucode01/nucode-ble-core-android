package kr.co.nucode.ble

/**
 * Structured official projection: connection, services, subscription restore failures and
 * diagnostics. Scan results are not projected here; they flow only through `BleClient.scanResults`.
 */
public sealed interface OfficialBleClientEvent : BleClientEvent {
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
