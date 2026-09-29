package kr.co.nucode.ble.android

import android.content.Context
import android.os.SystemClock
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.platform.android.AndroidAdapterSource
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import kr.co.nucode.ble.internal.platform.android.AndroidClock
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import kr.co.nucode.ble.internal.scan.ScanStartLedger

/**
 * 프로세스 공유 스캔 시작 기록 — 운영 조립만 쓴다. 시계는 `SystemClock.elapsedRealtime()`:
 * deep sleep 에도 진행한다(nanoTime 은 멈춰 창이 늘어나 false throttle).
 */
private val processScanStartLedger = ScanStartLedger { SystemClock.elapsedRealtime() }

/**
 * 운영 조립 — 구체 Driver 를 만드는 유일한 곳이다. [AndroidCentral] 은 applicationContext 로 하나만 만든다
 * (Activity 를 잡으면 코어가 소유하는 스코프가 Activity 보다 오래 살아 누수를 만든다).
 */
internal fun androidRuntime(context: Context, config: BleConfig): BleRuntime {
    val central = AndroidCentral(context.applicationContext)
    return BleRuntime(
        config = config,
        gattDriverFactory = { mailbox -> AndroidGattDriver(central, mailbox) },
        scanPort = AndroidScanner(central),
        adapterSource = AndroidAdapterSource(central, true),
        clock = AndroidClock(),
        scanStartLedger = processScanStartLedger,
        scannerRediscovery = true,
    )
}
