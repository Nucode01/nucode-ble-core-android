package kr.co.nucode.ble.internal

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.platform.android.AndroidAdapterSource
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import kr.co.nucode.ble.internal.platform.android.AndroidGattDriver
import kr.co.nucode.ble.internal.platform.android.AndroidScanner
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.internal.port.CoreMailbox
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.port.PlatformScanPort
import kr.co.nucode.ble.internal.scan.PeripheralRediscovery

/**
 * 시험 전용 조립 — 플랫폼 `Context` 없이 배선 로직만 검증한다. 운영 조립은
 * `kr.co.nucode.ble.android.androidRuntime` 이고, 여기는 central 없는 Driver 를 기본으로 채운다.
 *
 * central 이 없으면 AdapterMonitor 는 어댑터 null 을 먼저 걸러 `Unsupported` 로 판정하고 브로드캐스트 리시버는
 * 등록하지 않는다. `AndroidGattDriver.connect()` 는 null context 를 만나면 typed adapter failure 로 즉시 막는다 —
 * [gattDriver] 와 [gattOperationTargetResolver] 를 함께 주입하면 Android GATT 객체 없이도 공개 read/write/subscribe
 * 의 driver start 경계를 검증할 수 있다.
 */
internal fun BleRuntime(
    config: BleConfig,
    gattDriver: GattDriver? = null,
    gattOperationTargetResolver: GattOperationTargetResolver? = null,
    rediscovery: PeripheralRediscovery? = null,
    adapterState: (() -> AdapterState)? = null,
    scanPort: PlatformScanPort? = null,
    scannerRediscovery: Boolean = false,
    clock: ClockPort = TestClock,
): BleRuntime = BleRuntime(
    config = config,
    gattDriverFactory = gattDriver?.let { driver -> { _: CoreMailbox -> driver } }
        ?: { mailbox: CoreMailbox -> AndroidGattDriver(null as AndroidCentral?, mailbox) },
    scanPort = scanPort ?: AndroidScanner(central = null),
    adapterSource = AndroidAdapterSource(null, registerReceiver = false),
    clock = clock,
    scanStartLedger = null,
    scannerRediscovery = scannerRediscovery,
    gattOperationTargetResolver = gattOperationTargetResolver,
    rediscoveryOverride = rediscovery,
    adapterStateProbe = adapterState,
)
