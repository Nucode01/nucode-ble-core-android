package kr.co.nucode.ble.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.internal.DefaultBleSession

/**
 * Android only: the ATT MTU negotiated while `Configuring` on the current connection. iOS has no equivalent; the
 * common API offers `BleSession.writeLimits` instead.
 *
 * - Set and cleared at the same moments as `writeLimits`: just before `Ready`, and null outside `Ready`.
 * - 23 when the negotiation failed or the request was refused (the core then logs `CONFIGURATION_FAILED`).
 * - The value can differ from `BleConnectOptions.requestedMtu`: the peripheral and the platform decide it.
 * - Always null for a `BleSession` that this library did not create.
 */
public val BleSession.androidMtu: StateFlow<Int?>
    get() = (this as? DefaultBleSession)?.androidMtu ?: NO_MTU

private val NO_MTU: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
