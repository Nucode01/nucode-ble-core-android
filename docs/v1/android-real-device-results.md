# Slice 8 Android 실기기 검증 결과

> **상태: 부분 진행**
> 이 문서는 Slice 8 전체 완료 기록이 아니다. 실행한 시나리오와 관찰 범위만 보존하며,
> 실행하지 않은 matrix 항목은 계속 **검증 필요**다.

## 실행 요약

| 날짜 | 실행 | 결과 | 보존된 근거 |
|---|---|---|---|
| 2026-09-21 | 장문 write와 disconnect 경합 수동 검증 | pass | 아래 관찰 기록 |
| 2026-09-21 | 자동 하네스: 장문 write 두 mode, duplicate connect, 강제 disconnect 뒤 reconnect·subscription restore | pass | `BleHardwareIntegrationTest.longWritesAndReconnectRestoreAreVerifiedByPeripheral` 실행 관찰 |
| 2026-09-21 | 자동 하네스: duplicate disconnect와 session 재사용 | pass | `BleHardwareIntegrationTest.duplicateDisconnectEmitsOneTransitionAndSessionRemainsReusable` 실행 관찰 |
| 2026-09-22 | 자동 하네스: bonding 유지, firmware bond reset, 재본딩 | **pass (1/1)** | `sample/build/outputs/androidTest-results/connected/debug/TEST-Pixel 5a - 14-_sample-.xml`, failures/errors/skipped 0 |

Gradle connected-test 결과 디렉터리는 실행마다 덮어써질 수 있다. 현재 저장된 XML은 마지막
bonding 단독 실행만 포함하며, 앞선 두 자동 시나리오는 이 문서의 실행 관찰 기록으로 남긴다.

## 2026-09-21 — 장문 write와 disconnect 경합

### 환경

| 항목 | 값 |
|---|---|
| Android 기기 | Google Pixel 5a |
| Android 버전 | 14 |
| Peripheral | Nordic nRF52840 DK |
| Firmware | Zephyr Nordic UART Service 기반 로컬 검증 firmware |
| Service | Nordic UART Service |
| 협상 MTU | 65 |
| ATT payload capability | 62 bytes |
| write mode | write with response |

Firmware revision은 별도 커밋으로 고정하지 않았다. 따라서 이 결과는 위 로컬 검증 firmware와
보드 조합에 한정하며 다른 firmware/OEM의 동작을 일반화하지 않는다.

### 결과

| scenario | 결과 | 관찰 증거 |
|---|---|---|
| 600-byte 정상 write | **pass** | Android `WriteReceipt(bytesCompleted=600, chunksCompleted=10, delivery=RemoteAcknowledged)` |
| peripheral 전체 payload 수신 | **pass** | firmware callback 10회 뒤 `Long write verified: 600 bytes` |
| 전송 중 user disconnect | **pass** | 250 ms 뒤 disconnect 요청, `OPERATION_INVALIDATED` 반환 |
| partial progress | **pass** | `totalBytes=600`, `totalChunks=10`, `bytesCompleted=124`, `chunksCompleted=2`, `failedChunkIndex=2`, `failedChunkBytes=62` |
| 현재 chunk 불확실성 | **pass** | `failedChunkWasStarted=true`, `delivery=RemoteAcknowledged`, `currentChunkDeliveryUnknown=true` |
| implicit replay 금지 | **pass** | disconnect 뒤 10초 관찰 동안 자동 재연결 및 남은 payload 재전송 없음 |
| JVM 회귀 oracle | **pass** | MTU 65 정상 10청크 및 3번째 청크 중단 진행률 테스트; `:core:testDebugUnitTest` green |

`RemoteAcknowledged`는 앞선 두 chunk의 GATT write response 완료를 뜻하며 firmware 제품 로직의
처리 완료를 뜻하지 않는다. 시작된 세 번째 chunk는 전달 여부를 확정할 수 없어 unknown으로
남긴다.

### 이 수동 실행만으로 증명하지 않은 범위

- RF link loss 또는 peripheral 전원 차단
- write without response 처리율과 backpressure
- reconnect와 subscription restore(후속 자동 하네스 결과는 아래 절 참조)
- Bluetooth off/on, permission revoke, GATT 8/19/133
- timeout 뒤 late callback과 wrong callback
- notify/indicate 차이, bonding(후속 자동 하네스 결과는 아래 절 참조), 중복 UUID instance
- Android API 24~32 및 다른 OEM 기기
- 로그 유용성 및 PII/secret 수동 검토

따라서 이 수동 실행 자체는 Slice 8 matrix의 `write with response`와
`long write cancel/disconnect` 일부 셀만 충족한다. 같은 환경의 후속 자동 하네스 결과는
아래 절에서 별도로 기록한다.

## 2026-09-21~22 — 자동 실기기 하네스

### 환경

| 항목 | 값 |
|---|---|
| Android 기기 | Google Pixel 5a |
| Android 버전 | 14 |
| Peripheral | Nordic nRF52840 DK |
| Firmware | NUS 기반 통합 테스트 firmware (`IT:*` 명령 지원) |
| 테스트 | `sample/src/androidTest/.../BleHardwareIntegrationTest.kt` |

### 결과

| matrix 항목 | 결과 | 자동 oracle |
|---|---|---|
| 600-byte write with response | **pass** | 10 chunk, `RemoteAcknowledged`, peripheral 수신 결과 확인 |
| 600-byte write without response | **pass** | 10 chunk, `SubmittedToPlatform`, peripheral 수신 결과 확인 |
| duplicate connect | **pass** | 동일 options 연결 2회가 추가 상태 전이·link connect count를 만들지 않음 |
| 강제 disconnect 뒤 SDK reconnect | **pass** | firmware `IT:DISCONNECT` 뒤 같은 session이 `Ready`로 복귀 |
| subscription restore | **pass** | reconnect 뒤 subscribe count 증가 및 notification sequence 재개 |
| duplicate disconnect | **pass** | `Disconnecting` 전이 1회, 최종 disconnected |
| disconnected session 재사용 | **pass** | 같은 peripheral에 다시 연결해 status와 notification 수신 |
| Android bond 생성 | **pass** | `AndroidBondController.createBond`, 시스템 승인 자동화 후 bonded |
| bond 유지와 암호화 reconnect | **pass** | reconnect 전후 firmware bond count 1, security level 2 이상 |
| peripheral bond reset | **pass** | `IT:BOND_RESET` accepted 뒤 disconnect 및 peripheral 저장 bond 삭제 |
| 재본딩 | **pass** | 로컬 bond 정리 후 createBond 재실행, firmware `reset=PASS`, bond count 1 |

본딩 검증에서 Android의 bond 삭제는 공개 라이브러리 API가 아니라 androidTest 전용
`AndroidBondTestController`가 숨은 `removeBond`를 호출한다. 제품 API는 bond 생성과 상태 조회만
제공하고, peripheral의 bond 삭제는 통합 테스트 firmware 명령으로만 수행한다.

### 아직 증명하지 않은 범위

- write without response의 장시간 처리율·backpressure와 다른 OEM 동작
- RF range out/in, peripheral 전원 차단, GATT 8/19/133
- Bluetooth off/on, runtime permission revoke
- timeout 뒤 late callback과 wrong callback
- indication, 동일 UUID 다중 instance, service topology 변경 중 restore
- Android API 24~32 및 Pixel 외 OEM 기기
- 로그 유용성 및 PII/secret 수동 검토

따라서 Pixel 5a/Android 14/nRF52840 조합의 정상 경로와 명시적 firmware disconnect·bond reset은
통과했지만, OEM/API-level callback matrix와 장애 주입 matrix는 계속 **검증 필요**다.

## 2026-09-22 — 어댑터 상태 baseline (plan B `AndroidCentral` 배선 선행 조건)

> **목적**: `BleRuntime` 의 어댑터·권한 판정 코드를 `AndroidCentral` 로 위임하기 **전**의 동작을
> 고정한다. 아래 두 시나리오는 이전까지 "아직 증명하지 않은 범위"였다. plan B 뒤 같은 테스트를
> 다시 돌려 이 표와 diff 한다.

### 환경

| 항목 | 값 |
|---|---|
| 코드 | `5716ea2` (plan A 완료 직후, plan B 이전) |
| Android 기기 | Google Pixel 5a / Android 14 |
| Peripheral | nRF52840 DK, Zephyr NUS firmware, `F0:2F:24:A8:0F:9E` |
| 테스트 | `sample/src/androidTest/.../AdapterStateBaselineTest.kt`, `AdapterStatePermissionBaselineTest.kt` (logcat tag `BleBaseline`) |

### ① Bluetooth off → on (세션 Ready 상태에서)

`UiDevice.executeShellCommand("svc bluetooth disable" / "enable")` 로 토글.

| 단계 | 관찰 | 결과 |
|---|---|---|
| off 직후 | `adapterState` `Ready → PoweredOff` (335 ms) | **pass** |
| off 직후 세션 | `Ready → Disconnected(Failed: ADAPTER_UNAVAILABLE / phase Adapter)` — sweep 동작 | **pass** |
| on 뒤 | `adapterState` `PoweredOff → Ready` (3.3 s) | **pass** |
| on 뒤 **직접** `connect(peripheralId)` (재스캔 없이) | `Connecting → (30 s) → Disconnecting → Disconnected(Failed: LINK_TIMEOUT / Link)` | **fail** |
| on 뒤 **재스캔 후** `connect(peripheralId)` | 재스캔 0.2 s에 발견, `Connecting → DiscoveringServices → Configuring → Ready` 1.2 s | **pass** |

관찰 시퀀스(logcat):
```
adapter    = Ready > PoweredOff > Ready
connection = Connecting > DiscoveringServices > Configuring > Ready
           > Disconnected(Failed:ADAPTER_UNAVAILABLE/Adapter)
           > Connecting > Disconnecting > Disconnected(Failed:LINK_TIMEOUT/Link)      ← 직접 connect
           > Connecting > DiscoveringServices > Configuring > Ready                    ← 재스캔 후
           > Disconnecting > Disconnected(Initial)
```

**기존 결함(plan B 무관)**: `ble-core-diagrams.md` §7 의 "BT off→on 이후에도 앱이 들고 있던
`PeripheralId` 로 재발견 없이 다시 `connect()` 할 수 있다" 는 이 조합에서 성립하지 않는다.
원인 후보(확인 필요): BT 재시작으로 LE 주소 캐시가 비면 `BluetoothAdapter.getRemoteDevice(address)`
가 주소 타입을 알 수 없어 random static 주소의 peripheral 에 직접 연결이 되지 않는 Android 스택
동작 — API 33+ `getRemoteLeDevice(address, ADDRESS_TYPE_RANDOM)` 또는 "재스캔 후 연결" 정책이
후보. 별도 plan 사안.

### ② 런타임 권한 회수 (프로세스 재시작 후 초기 상태)

Android 는 런타임 권한 회수 시 앱 프로세스를 종료하므로 라이브 전이가 아니라 **재시작 후 초기
상태**로 관측한다. `adb shell pm revoke` 두 권한 → `am instrument` → `pm grant` 복구.

| 단계 | 관찰 | 결과 |
|---|---|---|
| 클라이언트 생성 직후 `adapterState.value` | `Unauthorized(canRequest=true)` | **pass** |
| `startScan()` | `BleException PERMISSION_DENIED / phase Adapter / AfterEnvironmentChange` | **pass** |

매니페스트 미선언(`Unauthorized(canRequest=false)`)은 sample 이 권한을 선언하므로 이 방법으로
재현 불가 — 계속 **검증 필요**.

### 재현 명령

```
# ① — gradle 이 APK 설치·실행·삭제
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true \
  -Pandroid.testInstrumentationRunnerArguments.class=kr.co.nucode.ble.sample.AdapterStateBaselineTest
adb logcat -d -s BleBaseline

# ② — APK 를 직접 설치한 뒤 권한 회수 상태로 실행 (GrantPermissionRule 없음)
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

### 사후 검증 — plan B 완료 후 (2026-09-22, HEAD `52e4c0d` → squash 전)

같은 두 테스트를 `AndroidCentral` 배선 뒤 재실행. peripheral 은 `blePeripheralId=F0:2F:24:A8:0F:9E` 로 고정
(미지정 시 주변의 다른 NUS 광고 기기 `D1:0B:C5:49:D9:AD` 가 먼저 잡혔다 — 어댑터 경로 결과는 동일했으나
재연결 행 비교를 같은 기기로 맞추기 위해 고정 재실행).

| 항목 | baseline (`5716ea2`) | plan B 후 | diff |
|---|---|---|---|
| off → `PoweredOff` | 335 ms | 385 ms | 상태 동일 |
| off → 세션 | `Disconnected(Failed: ADAPTER_UNAVAILABLE/Adapter)` | 동일 | 0 |
| on → `Ready` | 3.3 s | 3.25 s | 상태 동일 |
| on → 직접 `connect` | `LINK_TIMEOUT` (fail) | `LINK_TIMEOUT` (fail) | 0 — 기존 결함 유지 |
| on → 재스캔 후 `connect` | 발견 0.2 s · Ready 1.2 s | 발견 0.33 s · Ready 1.3 s | 상태 동일 |
| 권한 회수 → 초기 상태 | `Unauthorized(canRequest=true)` | 동일 | 0 |
| 권한 회수 → `startScan()` | `PERMISSION_DENIED/Adapter/AfterEnvironmentChange` | 동일 | 0 |

연결 시퀀스 15단계(logcat `SEQUENCE connection=`)가 baseline 2차 실행과 문자열 단위로 동일.
**판정: 동작 보존.** 직접 재연결 실패는 plan B 이전부터 있던 결함으로 별도 사안.

### 진단 — BT 토글 뒤 직접 재연결 실패 (2026-09-22, raw Android API 프로브)

라이브러리를 거치지 않는 프로브로 같은 주소(`F0:2F:24:A8:0F:9E`, random static)에 세 방식을 비교.
BT 스택 로그(`bt_btif_gattc: btif_gattc_open_impl`)의 `device type` / `address type` 이 결정 증거다.

| 시점 | 방식 | 스택이 본 주소 타입 | 결과 |
|---|---|---|---|
| 토글 전 | `getRemoteDevice(addr).connectGatt` | device type=2(LE), address type=1(RANDOM) | 연결 547 ms |
| 토글 후 | **A** `getRemoteDevice(addr).connectGatt` | **device type=0(UNKNOWN), address type=0(PUBLIC)** | **12 s 무응답** |
| 토글 후 | **B** `getRemoteLeDevice(addr, ADDRESS_TYPE_RANDOM).connectGatt` (API 33+) | address type=1 | 연결 261 ms |
| 토글 후 | **C** 주소 필터 스캔 1회 → `getRemoteDevice(addr).connectGatt` | address type=1 | 스캔 즉시 발견, 연결 123 ms |

**원인**: BT 재시작으로 스택의 LE 기기 캐시(주소별 device/address type)가 비면
`BluetoothAdapter.getRemoteDevice(address)` 는 타입을 모른 채 PUBLIC 으로 연결을 시도한다.
random static 주소의 peripheral 은 PUBLIC 으로는 존재하지 않으므로 콜백이 오지 않고 라이브러리의
`linkTimeoutMillis` 까지 매달린다. 스캔은 캐시를 다시 채워 타입을 복원하고(C), API 33+ 에서는
타입을 명시해 우회할 수 있다(B). 스택 코드 위치: `packages/modules/Bluetooth/system/btif/src/btif_gatt_client.cc:339`.

라이브러리는 `PeripheralId` 문자열만 들고 있어 주소 타입을 모른다. `BluetoothDevice.getAddressType()` 는
API 35 부터라 Pixel 5a(34)에서는 스캔 결과에서도 읽을 수 없다. 주소 상위 2비트(11 = random static)
휴리스틱은 첫 옥텟이 0xC0 이상인 **public** OUI 와 구분되지 않아 안전하지 않다.

### 수정 결과 — cold-cache 재발견 (2026-09-22, plan F, HEAD `b79594a` → squash 전)

`connect()` 앞에 이 클라이언트가 마지막 Ready 이후 본 적 없는 주소(cold)면 주소 필터 스캔 1회
(`AndroidPeripheralRediscovery`, 상한 `BleConfig.rediscoveryTimeoutMillis` = 3 s)로 스택 LE 캐시를
채운 뒤 `connectGatt` 한다. warm 주소(스캔 관측·링크 성립)는 그대로 직접 연결.

| 항목 | 수정 전 (`5716ea2`) | 수정 후 | 근거 |
|---|---|---|---|
| BT on → 직접 `connect(peripheralId)` | 30 s `LINK_TIMEOUT` | **Ready 1,280 ms** | `AdapterStateBaselineTest` pass, `btif_gattc_open_impl … address type =1` |
| 어댑터 시퀀스 | `Ready > PoweredOff > Ready` | 동일 | logcat `SEQUENCE` |
| 연결 시퀀스 | (재스캔 폴백 경로) | `… Disconnected(ADAPTER_UNAVAILABLE) > Connecting > DiscoveringServices > Configuring > Ready` — 재스캔 없이 | logcat `SEQUENCE` |
| 하네스 `longWritesAndReconnectRestore…` (warm 재연결·CCCD 복원) | pass | pass | XML |
| 하네스 `duplicateDisconnect…` | pass | pass | XML |
| 하네스 `bondingPersists…` | pass(아침) | **fail — 환경** | 아래 |

**본딩 하네스 실패는 plan F 와 무관한 환경 문제다.** 폰 로그에 BONDING(11) 진입이 없고
`btm_sec_pairing_timeout … state: IDLE`; 프로브로 보낸 `IT:BOND_RESET` 에 펌웨어가
`status=DENIED_SECURITY`(암호화 링크 필요 = 펌웨어에 본딩이 남아 있음). 아침 실행이 재본딩 단계로
끝나 펌웨어 본딩을 남겼고, 테스트 시작의 정리는 폰 쪽 본딩만 지운다 → 펌웨어는 이미 본딩된
상대의 재페어링을 거부해 다이얼로그가 뜨지 않는다. 그 테스트는 스캔→connect(warm) 라 재발견
경로를 밟지 않는다. **복구: 보드에서 본딩 삭제(Zephyr 셸 `bt clear all` 또는 리플래시) 후 재실행.**
후속: 본딩 하네스가 실행 간 멱등이 되도록 마지막 단계에서 `IT:BOND_RESET` 을 한 번 더 보내야 한다.

JVM: `GattBridgeRediscoveryTest` 6(cold 선행 · warm 미호출 · 미주입 경로 보존 · NotFound fail-fast ·
Unavailable 폴백+Diagnostic · 재발견 중 disconnect 중단) + `BleConfig` require 1. `:core:testDebugUnitTest` 288 green.

### 사후 검증 — plan D(GattBridge lifecycle/operation 분리) 완료 후 (2026-09-22, 체크포인트 `9a15364`, squash 전)

`GattBridge` 를 `GattConnectionLifecycle`(789줄) + `GattOperationEngine`(918줄) + façade(200줄)로 가른 뒤,
`OperationQueue` 실패 helper 를 `GattFailureFactory` 로 흡수한 상태. peripheral `F0:2F:24:A8:0F:9E` 고정.

| 항목 | plan F 뒤 | plan D 뒤 | 근거 |
|---|---|---|---|
| BT on → 직접 `connect` | Ready 1,102~1,280 ms | **Ready 1,074 ms** | `AdapterStateBaselineTest` pass |
| 어댑터 시퀀스 | `Ready > PoweredOff > Ready` | 동일 | logcat `SEQUENCE` |
| 연결 시퀀스 | `… Ready > Disconnected(Failed:ADAPTER_UNAVAILABLE) > Connecting > DiscoveringServices > Configuring > Ready > Disconnecting > Disconnected(Initial)` | 동일 | logcat `SEQUENCE` |
| `longWritesAndReconnectRestore…` (600B 두 모드·warm 재연결·CCCD 복원) | pass | pass 8.9 s | XML |
| `duplicateDisconnect…` | pass | pass 4.8 s | XML |
| 권한 revoke → `Unauthorized(canRequest=true)`, `startScan` → `PERMISSION_DENIED/Adapter/AfterEnvironmentChange` | pass | pass | `AdapterStatePermissionBaselineTest` 수동 경로 |
| `bondingPersists…` | fail — 환경(보드 본딩 잔존) | 미실행(보드 본딩 미삭제) | — |

write 경로 실패의 `FailureContext` 에 `sessionId`/`connectionGeneration` 이 붙는 것(plan D 승인 변경)은 실기기
로그의 `GATT_PAYLOAD_OBSERVED` context 에서 확인(`sessionId=21f4eec1…, connectionGeneration=1`).
JVM: `:core:testDebugUnitTest` 296 green.

### 사후 검증 — plan E(모델 수렴) 완료 후 (2026-09-22, 체크포인트 `b7e3fb4`, squash 전)

내부 legacy 모델(`DeviceId`·`CharRef`·`Chunking`·`CharProperty`·`Discovered*`·`Scan*`·`Snapshot`)을 지우고 공개 타입을
내부에서 직접 쓰는 상태. characteristic 미발견 실패는 `attribute`/`CHARACTERISTIC_NOT_FOUND`로 통일.

| 항목 | plan D 뒤 | plan E 뒤 | 근거 |
|---|---|---|---|
| BT on → 직접 `connect` | Ready 1,074 ms | **Ready 1,309 ms** | `AdapterStateBaselineTest` pass |
| 어댑터/연결 시퀀스 | 동일 | 동일 | logcat `SEQUENCE` |
| `longWritesAndReconnectRestore…` | pass | pass 9.0 s | XML |
| `duplicateDisconnect…` | pass | pass 4.0 s | XML |
| NUS 속성 투영(`CharProperty`→`CharacteristicProperty` 매핑) | — | TX `6e400003` `[Notify]`, RX `6e400002` `[WriteWithResponse, WriteWithoutResponse]` | `Services` 이벤트 로그 |
| 권한 revoke | pass | pass | `AdapterStatePermissionBaselineTest` 수동 경로 |

JVM: `:core:testDebugUnitTest` 296 green. 동일 UUID 다중 instance는 실기기(NUS 단일 instance)로 검증 불가 — JVM 3건만.

### 본딩 하네스 정상 경로·반복 실행 — plan G 완료 후, 보드 리플래시(chiperase) 뒤 (2026-09-22, HEAD `bcd5e29`)

보드에 남아 있던 stale 본딩을 리플래시로 지운 뒤(`storage` 파티션 소거, identity 주소 `F0:2F:24:A8:0F:9E` 유지 확인)
자가 복구 하네스를 두 번 연속 실행했다.

| 실행 | 결과 | 시간 |
|---|---|---|
| `bondingPersistsAcrossReconnectAndCanBeResetByTestFirmware` 1회차 | pass | 24.0 s |
| 같은 테스트 2회차(연속, 수동 정리 없음) | **pass** | 25.1 s |
| `AdapterStateBaselineTest` | pass — 직접 재연결 Ready 852 ms, 시퀀스 `Ready > PoweredOff > Ready` | 5.7 s |
| `longWritesAndReconnectRestore…` | pass | 8.6 s |
| `duplicateDisconnect…` | pass | 3.6 s |

2회차 pass가 하네스 멱등성의 증거다: 1회차의 cleanup(`IT:BOND_RESET` ACCEPTED 확인 → 폰 키 제거)이 다음 실행이
요구하는 "양쪽 본딩 없음" 상태를 남겼다. 이전 하네스는 폰 키를 먼저 지워 보드 잔존을 만들었고, 그 상태는
`BOARD_BOND_RESIDUE` fail-fast(32 s)로 진단됐다(앞 절). 프로세스 강제 종료 시 cleanup 미보장은 여전하다.

### 사후 검증 — plan H(runBlocking 제거) 완료 후 (2026-09-22, 체크포인트 `cc04350`, squash 전)

`session()`/`connect()`가 registry 락으로 즉시 뷰를 만들고, `close()`가 정리를 코어 디스패처에 예약하고 즉시 반환하는 상태.

| 항목 | 결과 |
|---|---|
| `AdapterStateBaselineTest` | pass — 직접 재연결 Ready 1,079 ms, `Ready > PoweredOff > Ready` |
| `longWritesAndReconnectRestore…` / `duplicateDisconnect…` | pass 8.3 s / 2.8 s |
| `bondingPersists…`(연속 3회차) | pass 25.2 s |
| 권한 revoke | pass — `Unauthorized(canRequest=true)`, `PERMISSION_DENIED/Adapter/AfterEnvironmentChange` |

JVM 309 green(red→green 4: session view 2, close 2). 하네스는 `close()` 뒤 `snapshot()`/`connectionState` 로 정리 완료를 관측하므로
지연 정리와 무관하게 통과했다.

### 사후 검증 — plan I(read/write/subscribe 어댑터 게이트) (2026-09-23, 체크포인트 `9d98804`)

| 항목 | 결과 |
|---|---|
| `AdapterStateBaselineTest` | pass — 직접 재연결 Ready 720 ms, `Ready > PoweredOff > Ready` |
| `longWritesAndReconnectRestore…` / `duplicateDisconnect…` | pass 8.6 s / 4.1 s |
| `bondingPersists…` | 1회차 **fail(오진)**, 화면 깨운 뒤 2회차 pass 22.6 s |

BT off 중 read/write/subscribe 가 `ADAPTER_UNAVAILABLE` 을 던지는 경로는 JVM 으로만 검증했다(하네스에 op-while-off 시나리오 없음).

**본딩 1회차 실패는 보드 잔존이 아니다 — 하네스 오진.** 하네스는 `BOARD_BOND_RESIDUE` 로 보고했지만 logcat 은 폰이
`BOND_NONE => BOND_BONDING` 에 들어가 SMP 가 돌았고 페어링 요청(`pairingVariant=3`, consent)이 도착했음을 보여준다(보드 잔존이면
BONDING 진입 자체가 없다 — 앞 절의 서명). Android 는 요청을 다이얼로그가 아니라 알림으로 띄웠고
(`BluetoothPairingService: Show pairing notification for (null)`, `BluetoothEventManager: … we have no record of that device`),
기기 이름이 null 이라 UiAutomator 가 승인 UI 를 찾지 못해 30 s 뒤 SMP 타임아웃. 2회차에는 같은 알림 경로가
`(Nordic_UART_Service)` 로 떠 통과했다. 원인은 이름 캐시 전 알림 + 하네스가 "DENIED_SECURITY·폰 본딩 없음 → 본딩 실패"를
전부 보드 잔존으로 판정하는 것(펌웨어는 비암호화 링크면 보드 본딩 유무와 무관하게 `DENIED_SECURITY` 를 준다).

### 본딩 하네스 오진·알림 경로 수정 후 (2026-09-23)

| 실행 | 결과 |
|---|---|
| 전체 스위트(수정 전, fallback `By.pkg("com.android.settings")`) | 본딩 fail — 이제 "BONDING 도달했으나 승인 안 됨"으로 **정확히 진단**(잔존 오진 해소) |
| 기기 UI 덤프 | 알림 셰이드 노드는 전부 `com.android.systemui` 소유 — Settings package 로는 매치 불가(fallback 이 원천적으로 죽어 있었음). 알림 제목 `페어링 요청`, 본문 `<이름>에 페어링하려면 탭하세요.` |
| 전체 스위트(수정 후, `com.android.systemui` + 제목 정규식) | **4/4 pass** — 이름 없는 알림 `(null)` 2회 발생에도 본딩 pass 24.2 s |

간헐 1건: 수정 직후 한 번의 전체 스위트에서 BT 토글 테스트 직후 `longWrites…`·`bonding…` 이 `NUS service was not discovered`
로 실패했다. 단독 2회·전체 스위트 재실행에서 재발 없음. 해당 실행 로그가 남지 않아 **원인 미상**(BT 재시작 직후 서비스 발견이
GAP/GATT 만 돌려준 것으로 추정되나 검증 안 됨). 재발 시 `adb logcat -G 16M` 로 보존해 진단한다.

### notification 경로 분리 후 (plan J, 2026-09-23)

`BleSession.notifications()` 가 전역 이벤트 버퍼 대신 수집자별 버퍼(`NotificationHub`)를 쓰도록 바뀐 뒤의 회귀 확인.
실기기 스위트에서 notification 을 수집하는 경로는 `longWrites…`(NUS TX 응답), `duplicateDisconnect…`(두 세션 순차),
`bonding…`(재연결 후 응답) 셋이다.

| 실행 | 결과 |
|---|---|
| `BleHardwareIntegrationTest` (3) | **3/3 pass** — longWrites 8.3 s, bonding 24.7 s, duplicateDisconnect 2.2 s |
| `AdapterStateBaselineTest` (1) | **1/1 pass** — 5.8 s |

유실 자체(느린 수집자 + 무관 이벤트 폭주)는 실기기로 재현하지 않았다 — JVM `NotificationRoutingTest` 가 결정적으로 고정한다.
간헐 `NUS service was not discovered` 는 이번 실행에서 재발하지 않았다(logcat `-G 16M` 설정 상태).

### 버퍼 용량 검증·`events` 에서 Scan 제거 후 (plan K·L, 2026-09-23)

`BleConfig.eventBufferCapacity >= 1` 검증, `scanResults` 유실 집계, 공개 `OfficialBleClientEvent.Scan` 제거 뒤의 회귀 확인.
두 클래스를 한 번에(BT 토글 테스트 직후 나머지 3개가 이어지는 순서) 실행했다.

| 테스트 | 결과 |
|---|---|
| `AdapterStateBaselineTest.bluetoothOffThenOnSweepsSessionAndAllowsReconnect` | pass 5.4 s |
| `longWritesAndReconnectRestoreAreVerifiedByPeripheral` | pass 8.5 s |
| `bondingPersistsAcrossReconnectAndCanBeResetByTestFirmware` | pass 24.2 s |
| `duplicateDisconnectEmitsOneTransitionAndSessionRemainsReusable` | pass 2.3 s |

**4/4 pass.** 하네스는 `events` 에서 `Connection` 만 걸러 쓰므로 Scan 제거의 영향을 받지 않는다. 스캔 폭주 시 연결 이벤트
유실 자체는 JVM `OfficialEventsScanIsolationTest` 가 결정적으로 고정한다(수정 전 `[Connecting]`, 수정 후 `[Connecting, Disconnecting]`).
간헐 `NUS service was not discovered` 는 BT 토글 직후 순서에서도 재발하지 않았다.

### 간헐 `NUS service was not discovered` 재현 시도 (2026-09-23)

조건: APK 1회 설치 후 `am instrument` 로 `AdapterStateBaselineTest#bluetoothOffThenOnSweepsSessionAndAllowsReconnect`
→ `BleHardwareIntegrationTest#longWritesAndReconnectRestoreAreVerifiedByPeripheral` 를 한 instrumentation 안에서 연속 실행,
20회 반복. `blePeripheralId=F0:2F:24:A8:0F:9E`, logcat `-G 16M`, 실패 시 instrument 출력·logcat 을 보존하도록 구성.

결과: **0/20 재현** (20회 모두 pass). 재현율 상한은 약 15%(95%, 3/n 규칙)로만 말할 수 있다 — 원인 미상 유지.
원 실패 조건과의 차이: 원 실패는 gradle `connectedDebugAndroidTest`(매 실행 재설치) 전체 스위트에서, 하네스 수정 직후
첫 실행이었다. 재설치 직후 첫 실행·본딩 테스트가 뒤따르는 순서는 이번 루프에 포함되지 않았다.

### 재설치 전체 스위트 반복 — 본딩 정리 단계 ACK 유실 (2026-09-23)

조건: gradle `connectedDebugAndroidTest`(매 회 재설치) 로 `AdapterStateBaselineTest` + `BleHardwareIntegrationTest` 반복,
첫 실패에서 정지. 보드 RTT 로그(채널 0)를 pylink 로 호스트 시각과 함께 기록.

| 루프 | 결과 |
|---|---|
| 2 (하네스 수정 전) | 1회차 본딩 fail, 2–20회차는 하네스 안전장치로 연쇄 fail(무효) |
| 3 (하네스 수정 전, RTT 로그) | 1 pass, 2회차 본딩 fail |
| 4 (하네스 수정 후) | 1–4 pass(1회차는 폰-only 잔존 상태에서 자가 복구), 5회차 본딩 fail |

**증상.** 시나리오는 끝까지 통과한다. 실패는 정리 단계다: `IT:BOND_RESET` 쓰기는 성공(`RemoteAcknowledged`)하고
보드는 ACK(`IT|RESULT|test=BOND_RESET|status=ACCEPTED`) 전송 → `bt_unpair` → 끊김(보드 0x16)까지 진행하지만,
폰 쪽 하네스는 ACK 를 받지 못해 10 s 타임아웃. 같은 실행 안의 시나리오 `BOND_RESET` ACK(동일 명령·동일 링크 재사용 조건)는
매번 수신됐다 — 간헐이다. 재현율: 재설치 스위트 8회 중 3회.

**유실 위치.** 5회차 로그에서 쓰기(Tx 진단 11:26:39.670) 와 끊김(11:26:39.823) 사이에 라이브러리 엔진의 Rx 진단
(`GATT_PAYLOAD_OBSERVED`, notification 수신 시 CharValue 발행 직전에 나옴)이 없다. Android 콜백 → 코어 스레드 전달은
단일 스레드 FIFO(`AndroidGattCallbackRouter.forward`)라 끊김보다 먼저 처리된다. 따라서 notification 은 **앱 콜백에
도달하지 않았다**(추론 — btsnoop 은 비루팅 기기라 켜지 못해 폰 호스트 스택 도달 여부는 미확인). 펌웨어는 ACK 전송 완료
콜백(`nus_sent_cb`) 직후 곧바로 `bt_unpair`/끊김을 하므로, 끊김 직전 데이터가 폰 호스트에서 버려지는 경합으로 보인다(가설).

**여파와 조치.** 정리 단계가 보드 삭제를 확인하지 못하면 하네스는 폰 키를 보존한다 → 보드 0 / 폰 1 → 이후 모든 실행이
`DENIED_SECURITY` + 폰 본딩으로 차단(폰 쪽은 BT 재시작 뒤 `No key available to encrypt the link`). 하네스를 고쳤다:
- 시작: `DENIED_SECURITY` 이면 암호화 없이도 응답하는 `IT:BOND_STATUS` 로 보드 본딩 수를 확인, 0 이고 폰에만 본딩이 있으면
  폰 키를 지우고 진행.
- 정리: ACK 대기가 실패하면 재연결해 `IT:BOND_STATUS` 로 확인, 0 이면 폰 키도 정리. ACK 유실 자체는 테스트 실패로 남긴다.
- 본딩 테스트에 `events`/수신(`rx=`) 로그 추가.
검증: 루프 4 의 1회차(폰-only 잔존에서 시작) pass, 5회차 실패 뒤 폰 0 / 보드 0(RTT `LOCAL|BOND|count=0`) 확인.

**간헐 `NUS service was not discovered`.** 이번 전체 구간(am instrument 20회 + 재설치 스위트 8회)에서 0회. 원인 미상 유지.

### 실기기 `read` 검증 (2026-09-23)

NUS 에는 Read 속성 characteristic 이 없어 표준 GAP Device Name(0x2A00)을 읽는다 — 펌웨어 변경 불필요.
`readReturnsGapDeviceNameConfiguredInFirmware`: 두 번 읽어 펌웨어 `CONFIG_BT_DEVICE_NAME`(`Nordic_UART_Service`)과 같은지,
두 값이 같은지 확인.

| 실행 | 결과 |
|---|---|
| 단독 | pass 1.4 s |
| 전체 스위트(5개) | read pass 2.1 s. 본딩은 알려진 정리 단계 ACK 유실로 fail — 하네스가 보드 0 확인 후 폰 키 정리(자가 복구). 나머지 3개 pass |

### 펌웨어 BOND_RESET 해제 지연 후 (2026-09-23)

펌웨어 `peripheral_uart/src/main.c`: ACK 전송 완료(`nus_sent_cb`) 뒤 `bt_unpair`(끊김 동반) 재예약을 `K_NO_WAIT` →
`INTEGRATION_TEST_BOND_RESET_UNPAIR_DELAY`(300 ms)로 변경. NCS v3.3.0 로 빌드, `nrfjprog --sectorerase` 로 앱 영역만 기록
(본딩 저장 영역 유지). 기록 후 RTT `LOCAL|BOND|count=0` 확인.

| 조건 | 재설치 전체 스위트(5개) 반복 | 본딩 정리 ACK 유실 |
|---|---|---|
| 수정 전 | 9회 | 4회 |
| 수정 후 | 10회 | **0회** (10/10 전체 pass) |

수정 전 유실률(4/9)이 유지됐다면 10회 연속 무유실 확률은 약 0.3% — 끊김 직전 notification 유실 경합이라는 가설과 부합한다.
폰 호스트 스택에서의 유실 지점 자체는 btsnoop 없이 확인하지 못했다(가설 유지).
