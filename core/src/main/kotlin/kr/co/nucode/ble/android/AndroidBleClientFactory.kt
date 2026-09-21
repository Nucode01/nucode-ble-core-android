package kr.co.nucode.ble.android

import android.content.Context
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.BleRuntime

/** Android-only construction boundary; the common BleClient interface contains no Android types. */
public object AndroidBleClientFactory {
    @JvmStatic
    public fun create(context: Context, config: BleConfig = BleConfig()): BleClient =
        DefaultBleClient(BleRuntime(context, config))

    /** Creates the Android-only bonding boundary for devices discovered by [BleClient]. */
    @JvmStatic
    public fun createBondController(context: Context): AndroidBondController =
        DefaultAndroidBondController(ContextAndroidBondPlatform(context.applicationContext))
}
