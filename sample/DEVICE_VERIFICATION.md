# 실기기 전제 검증 런북

Android 기기와 nRF52840 NUS 테스트 보드가 붙었을 때 미검증 전제(P1–P7)와 기존 하드웨어 테스트 9개를
한 번에 돌리고 결과를 남기는 절차. 이 저장소에서 기기 없이 확인되는 것은 **컴파일까지**다.

> 위치: `docs/*` 는 `.gitignore` 대상(추적 파일 제외)이라 이 문서는 `sample/` 에 둔다.

## 하드웨어

| 항목 | 요구 |
|---|---|
| Android 기기 | USB 디버깅 켠 기기 1대 (`adb devices` 에 정확히 1대). 기존 기준: Pixel 5a / Android 14 |
| Peripheral | nRF52840 + Zephyr Nordic UART Service 기반 통합 테스트 firmware (`IT:STATUS` / `IT:RESET` / `IT:DISCONNECT` / `IT:BOND_RESET` / `IT:BOND_STATUS`, 주기 `IT\|NTFY\|seq=` notify) |
| 주변 | 같은 NUS 광고 보드가 여럿이면 `BLE_PERIPHERAL_ID` 로 주소를 고정한다 |

Firmware 는 이 저장소에 없다(로컬 검증 firmware, revision 미고정). 구성·한계는
[`docs/v1/android-real-device-results.md`](../docs/v1/android-real-device-results.md) 의 환경 절을 본다.

## 실행

```bash
# 전체 (P1–P7 + 기존 9개) — logcat 을 build/device-verify/<timestamp>.log 로 떨구고 PREMISE 줄을 출력
BLE_PERIPHERAL_ID=F0:2F:24:A8:0F:9E scripts/device-verify.sh

# 한 클래스만
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true \
  -Pandroid.testInstrumentationRunnerArguments.class=kr.co.nucode.ble.sample.DevicePremiseVerificationTest

# 한 테스트만
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true \
  -Pandroid.testInstrumentationRunnerArguments.class=kr.co.nucode.ble.sample.DevicePremiseVerificationTest#p7BluetoothOffDuringScanStopsScanWithAdapterFailure
```

`AdapterStatePermissionBaselineTest` 는 권한 회수 상태가 필요해 전체 실행에서는 의미 있는 결과가 나오지 않는다.
수동 절차:

```bash
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest
adb install -r -t sample/build/outputs/apk/debug/sample-debug.apk
adb install -r -t sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
adb shell pm revoke kr.co.nucode.ble.sample android.permission.BLUETOOTH_SCAN
adb shell pm revoke kr.co.nucode.ble.sample android.permission.BLUETOOTH_CONNECT
adb shell am instrument -w -e hardwareBle true \
  -e class kr.co.nucode.ble.sample.AdapterStatePermissionBaselineTest \
  kr.co.nucode.ble.sample.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s BleBaseline
adb shell pm grant kr.co.nucode.ble.sample android.permission.BLUETOOTH_SCAN
adb shell pm grant kr.co.nucode.ble.sample android.permission.BLUETOOTH_CONNECT
```

P2/P3/P4/P7 은 `svc bluetooth disable/enable` 로 BT 를 끈다. 실패해도 `tearDown` 이 다시 켜지만,
실행 뒤 기기 BT 가 켜져 있는지 확인한다.

## 결과 추출

```bash
adb logcat -d -s BlePremise BleHardwareTest BleBaseline
adb logcat -d -s BlePremise | grep 'PREMISE|'
```

전제 줄 형식: `PREMISE|<id>|<key>=<value>|...` (값이 없으면 `none`). 합격/불합격은
`sample/build/reports/androidTests/connected/` 와 `sample/build/outputs/androidTest-results/connected/` 에서 본다.

### P6c — RF 손실 (수동)

자동화할 수 없다. 연결 Ready 상태에서 보드 전원을 끊고(또는 차폐), `BlePremise`/`BleHardwareTest` 로그에서
Recovering/Disconnected 의 `platformDetail` statusCode 가 8(`GATT_CONN_TIMEOUT`) 인지 기록한다.

## 전제별 관측과 뒤집을 수 있는 결정

| id | 테스트 | 단언 (라이브러리 계약) | 기록만 (플랫폼 전제) | 뒤집을 수 있는 결정 |
|---|---|---|---|---|
| P1 | `p1AutoConnectRelinkRestoresSubscriptions` | Recovering → 60 s 내 Ready, 구독 복원, seq 진행 | elapsedMs, lastFailure code/status | autoReconnect 방향 · 결정 A |
| P2 | `p2BluetoothToggleWithAutoReconnectRelinksOnce` | Disconnected(Failed ADAPTER_UNAVAILABLE), bt-on 뒤 Ready | PERIPHERAL_NOT_FOUND 여부, elapsedMs, 상태열 | autoReconnect 방향 · 결정 A |
| P3 | `p3ColdCacheAutoReconnectOutcomeIsRecorded` | 실패 종료면 Failed 사유 | outcome=ready/waiting, 재발견 진단 | autoReconnect 방향 · 결정 A |
| P4 | `p4ScanFilterUnionKeepsUserResultsFiltered` | scanResults 에 사용자 필터 밖 결과 없음 | 주소 결과로 연결 진행 여부 | scan union 정책 A |
| P5 | `p5ScanStartThrottlingIsRecorded` | 처음 5회 시작 예외 없음, 6번째 시작이 BleException(SCAN_START_THROTTLED), 끝나면 Idle, 끝에서 한도 창(31 s) 대기 | 처음 5회 각 결과 수(`firstFiveResults` — 0 이면 OEM 한도 < 5 의심), 6번째 시작 결과 수, Idle lastFailure, 진단 | scan start budget 메모 |
| P6 | `p6DisconnectStatusValuesAreRecorded` | (a) Requested, (b) failure 존재 | (b) statusCode/symbolicName (기대 19 / GATT_CONN_TERMINATE_PEER_USER), (c) 수동 | link status 매핑 |
| P7 | `p7BluetoothOffDuringScanStopsScanWithAdapterFailure` | Idle(SCAN_STOPPED_BY_ADAPTER) | SCAN_RUNTIME_FAILED(onScanFailed) 도착 여부 | adapter sweep 의 scan 처리 |

## 결과 기록 양식

실행마다 아래 표를 복사해 채운다. 확정된 결과는 `docs/v1/android-real-device-results.md` 로 옮긴다.

| 항목 | date | device | Android | outcome | notes (PREMISE 줄 요약) |
|---|---|---|---|---|---|
| P1 autoConnect relink | | | | | |
| P2 BT off/on + autoReconnect | | | | | |
| P3 cold cache autoReconnect | | | | | |
| P4 scan filter OR | | | | | |
| P5 scan start throttling | | | | | |
| P6a user disconnect | | | | | |
| P6b peer disconnect status | | | | | |
| P6c RF loss (수동) | | | | | |
| P7 BT off during scan | | | | | |
| longWritesAndReconnectRestoreAreVerifiedByPeripheral | | | | | |
| autoReconnectWaitsInRecoveringAndRestoresAfterPlatformRelink | | | | | |
| duplicateDisconnectEmitsOneTransitionAndSessionRemainsReusable | | | | | |
| readReturnsGapDeviceNameConfiguredInFirmware | | | | | |
| startScanWithDifferentRequestWhileScanningThrowsScanAlreadyRunning | | | | | |
| activeSubscriptionsTracksNotifySubscriptionAcrossDisconnect | | | | | |
| bondingPersistsAcrossReconnectAndCanBeResetByTestFirmware | | | | | |
| bluetoothOffThenOnSweepsSessionAndAllowsReconnect (BleBaseline) | | | | | |
| revokedRuntimePermissionYieldsUnauthorizedAndScanIsDenied (수동) | | | | | |
