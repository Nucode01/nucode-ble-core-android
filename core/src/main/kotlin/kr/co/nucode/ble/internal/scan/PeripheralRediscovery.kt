package kr.co.nucode.ble.internal.scan

import kr.co.nucode.ble.internal.core.scan.RediscoveryOutcome
import kr.co.nucode.ble.PeripheralId

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
