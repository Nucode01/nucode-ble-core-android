package kr.co.nucode.ble

/**
 * The largest number of bytes one write of each mode carries on the current connection. The core splits a
 * `BleChunking.Automatic` write into pieces of this size and checks `Disabled` and `Fixed` writes against it.
 *
 * On Android both values are the negotiated MTU minus 3 (a write with response is also split rather than sent as
 * a long write). Read the current value from [BleSession.writeLimits].
 */
public data class WriteLimits(
    val withResponse: Int,
    val withoutResponse: Int,
)
