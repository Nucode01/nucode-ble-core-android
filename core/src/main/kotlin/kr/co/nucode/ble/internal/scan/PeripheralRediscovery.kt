package kr.co.nucode.ble.internal.scan

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.PeripheralId

/** Result of one bounded, address-filtered rediscovery scan. */
internal sealed interface RediscoveryOutcome {
    /** The peripheral advertised; the platform LE cache now knows its address type. */
    data object Found : RediscoveryOutcome
    /** No advertisement within the timeout. */
    data object NotFound : RediscoveryOutcome
    /** The scan could not run (permission, throttling, no scanner); caller falls back to a direct connect. */
    data class Unavailable(val failure: BleFailure) : RediscoveryOutcome
}

/**
 * Repopulates the platform LE device cache for one address before connectGatt.
 * Motivation: after a Bluetooth restart the stack forgets address types and
 * `getRemoteDevice(address)` connects as PUBLIC, which never reaches a random-static peripheral.
 */
internal fun interface PeripheralRediscovery {
    suspend fun rediscover(deviceId: PeripheralId, timeoutMillis: Long): RediscoveryOutcome

    /** Ends any pending [rediscover] for [deviceId] now with [outcome] (e.g. the connect was cancelled by disconnect). */
    fun endRediscovery(deviceId: PeripheralId, outcome: RediscoveryOutcome) = Unit
}
