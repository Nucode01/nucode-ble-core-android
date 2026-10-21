# Changelog

## Unreleased

공개 API 추가 하나(Android 전용 확장). 동작 변경: `AdvertisementData` 의 같음 판정이 원본 바이트를 반영한다(CANDIDATE-31).

### Added

- `kr.co.nucode.ble.android.androidScanRecordBytes`: `AdvertisementData` 의 Android 전용 확장 속성. 스캔 결과의 원본 광고 바이트
  (`ScanRecord.getBytes()`)를 복사본으로 준다. 플랫폼이 scan record 를 주지 않으면 `null` 이다.
  - legacy 광고의 active 스캔에서는 광고 데이터와 scan response 가 한 바이트열로 합쳐져 온다. 라이브러리는 자르거나(0 채움 포함)
    정규화하거나 디코딩하지 않는다.
  - 공통 API(`AdvertisementData` 공개 생성자·멤버)와 iOS 는 그대로다. iOS 는 원본 바이트를 주지 않는다.

### Behavior

- `AdvertisementData.equals`·`hashCode` 가 원본 바이트를 반영한다. 다른 필드가 같고 원본만 다른 스캔 결과는 이제 다른 값이다.
  `distinctUntilChanged` 같은 같음 기반 거르기를 쓰는 소비자는 결과를 더 자주 받는다. 공개 생성자로 만든 값은 원본이 `null` 이다.

### Contract

- 계약 러너에 `adTypes` 확장 레인(원본 바이트의 AD type 누적 집합, `await adTypes is`)을 더하고, sim 가짜 기기가 원본 바이트
  (Flags · Complete Local Name · scan response 의 128-bit 서비스 UUID)를 낸다. 시나리오 `scan-exposes-raw-advertisement` 가 sim 에서 통과한다.

### Internal

- `AdvertisementData` 를 `PublicModels.kt` 에서 `AdvertisementData.kt` 로 옮겼다(같은 패키지, 공개 시그니처 불변).
  공개 API 덤프 시험이 top-level 공개 선언(확장 속성)의 facade 클래스도 덤프에 넣는다.

## 0.3.1 (2026-10-20)

공개 API 변경 없음. 동작 변경: Android 첫 직접 연결의 빠른 GATT status 133 을 라이브러리가 다시 건다(contract/states.md §2, 2026-10-13).
배포처 변경: GitHub Packages 대신 JitPack 에서 받는다.

### Distribution

- JitPack 으로 배포한다. 좌표는 `com.github.Nucode01:nucode-ble-core-android:0.3.1` 이고 받을 때 인증이 필요 없다. 저장소 루트 `jitpack.yml` 이 JDK 17 로 `:core:publishToMavenLocal` 을 돈다.
- GitHub Packages 배포(publish.yml, core 빌드의 GitHubPackages 저장소)를 지웠다. 0.3.0 은 GitHub Packages(`kr.co.nucode:ble-core:0.3.0`)에 남아 있고, 이후 버전은 JitPack 으로 낸다. 태그와 `sdkVersion` 일치 검사는 ci.yml 태그 실행으로 옮겼다.

### Behavior

- `autoReconnect = false` 연결이 `Connecting`(링크 전)에서 연결 요청 뒤 5000ms 안에 status 133 을 받으면 handle 을 닫고 같은
  generation·같은 link deadline 안에서 다시 연결한다. 재시도는 최대 2회(시도 3번)이고 옵션이 아니다(기본 켜짐).
  - 공개 상태는 `Connecting` 하나다. 시도마다 그 133 을 `Diagnostic(Warning, LINK_FAILED)` 로 낸다.
  - 남은 link deadline 이 5000ms 미만이면 다시 걸지 않고 지금처럼 `LINK_FAILED` 로 끝난다. 시도가 모두 빠른 133 이면 세 번째 133 때
    `LINK_FAILED`, 재시도 중 deadline 이 다하면 `LINK_TIMEOUT` 이다.
  - 요청 뒤 5000ms 넘어 온 133, 133 이 아닌 status, `DiscoveringServices` 이후 실패, autoReconnect 연결은 지금과 같다.
    재시도 중 `disconnect()` 는 `Disconnected(Requested)`, close 는 `Disconnected(ClientClosed)` 로 끝낸다.
  - 근거: 실기기 첫 연결의 링크 수립 실패(HCI 0x3E)가 2026-10-12 lab 2회에서 48회 중 7회였다.

### Diagnostics

- `Connecting` 의 링크 실패 진단(재시도 Warning, 끝내는 Error)의 logger 기록(`BleConfig.logger`)에 이번 시도의 연결 요청 뒤
  경과 ms 를 `link_request_elapsed_ms` 로 싣는다. 공개 이벤트(`OfficialBleClientEvent.Diagnostic`)는 그대로다.

### Internal

- Core 순수 시험(ConnectionCoreTest·GattQueueCoreTest)과 겹치는 셸 시험 23건을 지웠다(`TerminalCauseLatchTest`·`ConnectionCoordinatorTest`
  파일, `GattOperationSchedulerTest` 4건). 다른 곳에 없는 3건(BleConfig lifecycle 기본 timeout, Session 마다 coordinator 소유,
  종결 원인 → DisconnectReason 파생)은 옮겨 남겼다.
- main 과 남은 시험이 부르지 않는 internal 입구 8개를 지웠다: `GattOperationScheduler` 의 `removeQueued`·`takePendingIfMatches`·
  `poisonCurrentGeneration`, `ConnectionCoordinator` 의 `beginPlatformRelink`·`supersedeForClose`·`supersedePlatformWait`·
  `latchIfAbsent`·`setRelinkAfterAdapter`.

## 0.3.0 (2026-10-12)

공개 API 변경 없음. 계약 러너 `properties` 레인.

root·iOS 와 같은 계약 버전(0.3.0)으로 함께 태그한다. 공개 API 와 동작은 0.2.2 와 같다(`core/api/ble-core.api` 는 머리 주석의 버전만 바뀐다).

### Internal

- 계약 러너: 확장 값 레인 `properties`(CANDIDATE-30). `expect` 에 있을 때만 기록하고 `services` 다음, `peer` 앞에 낸다.
  `services` 와 같은 원천(session 서비스 목록)에서 기기 정의 characteristic 마다 properties 를 기기 정의 어휘(`WriteWithResponse` 는 `write`)로 남긴다.
  `await ... is` 대상이 아니다. 새 draft 시나리오 `services-expose-properties` 가 sim 에서 통과한다.
- `GuideSnippetsSyncTest`: 로컬 전용 문서 이름 `llms-android.md` 를 검사에서 뺀다(이전 이름 `llms.md`).

## 0.2.2 (2026-10-01)

공개 API 와 동작은 0.2.1 과 같다. GATT 명령 진입 검사 순서를 두 플랫폼 공통 계약으로 적었고(contract/states.md §5 명령 진입 검사 순서),
iOS 가 이 순서로 맞춰졌다.

### Documentation

- read·write·subscribe 는 닫힘 `CLIENT_CLOSED` → 어댑터(`ADAPTER_UNAVAILABLE`·`PERMISSION_DENIED`·`BLE_UNSUPPORTED`, `Unknown` 은 최대 2000ms 대기)
  → `UNKNOWN_DEVICE` → 세션이 `Ready` 아님 `NOT_READY`(단계 무관, 링크 유실 뒤 1000ms 창 포함) → `CHARACTERISTIC_NOT_FOUND`(큐가 작업을 시작할 때) 순서다.
  `NOT_CONNECTED` 는 받아들인 작업이 시작하려는 순간 연결이 없을 때만 난다. 가이드(`troubleshooting.md`, `recipes/errors-diagnostics.md`)를 이 순서로 고쳤다.
  iOS 는 0.2.2 에서 `NOT_CONNECTED` 대신 이 코드들을 낸다(iOS CHANGELOG 의 BREAKING) — 두 플랫폼을 함께 쓰는 앱은 같은 분기로 처리할 수 있다.
- 가이드 제목을 0.2.x 로 고치고 README 맨 위에 가이드 입구를 둔다.

### Internal

- `GattAdmissionOrderTest`: 진입 검사 순서 표 시험(세션 단계 × 어댑터 상태 × read·write·subscribe, unsubscribe 는 닫힘만).
- `sample` 실기기 점검표 항목 2 는 꺼진 채 read 도 `ADAPTER_UNAVAILABLE` 만 통과로 본다.

## 0.2.1 (2026-09-30)

공개 API 는 0.2.0 과 같다(`core/api/ble-core.api` 동일). 내부 구조를 순수 상태 기계 코어로 옮기고 실기기 수동 시험에서 찾은 결함을 고쳤다.
계약 시나리오 36개(sim), 실기기 두 대 기록 비교 23개 일치, iPhone XR 수동 시험 체크리스트 9개(블루투스 끄고 켜기·비행기 모드·재부팅 포함)를 자동 실행으로 확인했다.
Android 실기기의 블루투스 끄고 켜기 순서는 측정하지 않았다.

### Fixes

- Ready 이후 링크가 끊긴 직후 블루투스가 꺼지면 `Disconnected(Failed(LINK_FAILED))` 로 끝나던 문제를 고쳤다. 링크 유실 뒤 1000ms 안에 어댑터가 꺼지면 `ADAPTER_UNAVAILABLE` 로 끝나고 autoReconnect 세션은 어댑터가 돌아올 때 한 번 다시 연결한다. 이 1000ms 동안 공개 상태는 바뀌지 않고, 새 GATT 작업은 `NOT_READY` 로 거절되며, 진짜 링크 실패는 최대 1000ms 늦게 알려진다.

### Internal

- 연결 상태 전이, GATT 큐 규칙, 스캔 수명, 어댑터 판정을 순수 Core(`internal/core`)로 옮기고 런타임은 사건을 넣고 할 일을 실행하는 껍데기로 줄였다. 플랫폼 인터페이스를 `internal/port` 로 분리하고 구체 드라이버 생성은 `android/` 조립 계층에서만 한다. 시간은 `ClockPort` 로만 쓴다. 이 구조는 `ArchitectureRulesTest` 가 지킨다.

## 0.2.0 (2026-09-30)

Slice 7 public API cleanup과 이후 내부 정리·결함 수정, 두 플랫폼 공통 계약(nucode-ble-core contract/)에 맞춘 동작 정렬.
Build·unit test, 계약 시나리오 33개(sim), 실기기 두 대(Pixel 5a Android 14, iPhone XR iOS 18.7 + nRF52840 펌웨어) 기록 비교 23개 일치를 확인했다.
블루투스 켜고 끄기·재부팅 뒤 재연결 수동 시험(전달 계획 §6)은 하지 않고 태그했다. publish 는 하지 않았다.

### Breaking changes

- `AdapterState.Unknown` 을 추가했다(아직 판정 전). `else` 없이 `AdapterState` 를 전수 분기하던 `when` 은 컴파일되지 않으므로 `Unknown` 을 판정 대기로 다루는 분기를 더한다. Android 는 이 값을 내지 않는다. 명령(`connect`·`startScan`·`read`·`write`·`subscribe`)은 `Unknown` 이면 최대 2000ms 판정을 기다린 뒤 `ADAPTER_UNAVAILABLE` 로 실패하고, `Ready` 에서 `Unknown` 이 되면 어댑터 이탈과 같이 세션·스캔을 정리한다.
- 청크 한도를 넘는 `write`(`BleChunking.Disabled` 로 한 청크보다 긴 값, `Fixed(n)` 의 n 이 한도 초과)가 `IllegalArgumentException` 대신 `BleException(PAYLOAD_TOO_LARGE)`(category operation, `Never`, `writeProgress == null`)를 던진다.
- 호출자 취소가 세션을 끝내지 않는다. 세션 줄에 접수된 GATT 작업(read·write·subscribe·unsubscribe)은 호출자가 취소돼도 끝까지 진행한다: 나머지 write 조각이 모두 전송되고, 취소한 `subscribe` 는 완료되어 `activeSubscriptions` 와 구독 의도에 남아 재연결마다 복원된다(되돌리려면 `unsubscribe`). 작업 기한(`operationTimeoutMillis`)은 큐가 갖고, 그 만료(`OPERATION_TIMEOUT`)만 세션을 끝낸다. 이전에는 시작된 작업을 취소하면 `Disconnected(Failed(OPERATION_INVALIDATED))` 였다. `OPERATION_INVALIDATED` 는 해제·링크 유실·세션 종결에서만 난다. 앱의 `withTimeout` 만료는 이제 모든 GATT 작업에서 호출자에게 `TimeoutCancellationException` 이다(이전에는 `read`·`write`·`subscribe` 가 `BleException(OPERATION_TIMEOUT)` 으로 바꿔 던졌다 — `OPERATION_TIMEOUT` 을 잡던 앱은 바꿔야 한다). 작업은 계속된다.
- `WriteProgress` 의 `failedChunkWasStarted`·`currentChunkDeliveryUnknown` 을 원인별로 채운다: 시작 전 거절·줄에서 무효화 → 시작 안 함, 불명 아님; 기기 확정 거절(실패 status) → 시작함, 불명 아님; 응답 없이 timeout·링크 유실 → 시작함, 불명. 이전에는 원인과 무관하게 시작함·불명이었다. `bytesCompleted`·`chunksCompleted` 는 확정된 앞 조각까지다.
- `UNKNOWN_DEVICE` 의 category 는 validation 이다(이전 connection 경로 포함). 코드 하나에 category 하나이며 시험이 `failure-codes.json` 과 대조한다. category 로 분기하던 앱은 확인한다.
- `AndroidBondController.createBond` 가 플랫폼 예외(권한 없음, 형식이 틀린 id, 어댑터 없음)를 던지지 않고 `AndroidBondResult` 로 돌려준다: 시작 전 예외 → `StartRejected`, 관찰 중 예외 → `Failed`. 호출자 취소는 그대로 전파한다. `bondState()` 는 조회라 그대로 던질 수 있다.
- `PeripheralId` 를 라이브러리 입구(`session`·`connect`·세션 작업·`AndroidBondController`)에서 대문자로 정규화한다. 소문자·대문자 MAC 은 같은 세션이고 이벤트·snapshot 의 id 는 대문자다. 이전에는 소문자 id 가 `UNKNOWN_DEVICE` 였다.
- 코어 스코프에서 잡히지 않은 내부 예외는 그 세션만 `Disconnected(Failed(INTERNAL_TRANSPORT_ERROR))` 로 끝내고 handle 을 닫는다(다른 세션은 계속 쓸 수 있다). 세션을 특정할 수 없으면 `Diagnostic(Error, INTERNAL_TRANSPORT_ERROR)` 로만 알린다. 이전에는 처리되지 않았다.
- `CLEANUP_FAILURE` 진단의 `cleanup_exception` 값이 예외 메시지 대신 예외 클래스 이름만 싣는다(메시지에 주소 등이 실릴 수 있다).
- 사용자 `disconnect()` 는 그 세션의 구독 의도를 비운다(v1 계약: 명시적으로 끊은 구독은 복원하지 않는다). 이전에는 다시 `connect()` 하면 이전 구독이 CCCD 까지 복원됐다. 재연결 후 통지가 필요하면 `subscribe()` 를 다시 부른다. 예기치 않은 끊김 뒤 자동 재연결의 복원은 그대로다.
- `BleClient.startScan()` 이 호출 중 확정된 시작 실패를 던진다: 다른 요청으로 이미 스캔 중(`SCAN_ALREADY_RUNNING`), 스캐너 획득 실패(`SCANNER_UNAVAILABLE`), 플랫폼 호출 단계의 권한 거절(`PERMISSION_DENIED`), 스캔 시작 한도 초과(`SCAN_START_THROTTLED`). 이전에는 `events` 의 `Diagnostic` 으로만 알려 버퍼가 차면 실패가 사라지고 앱은 스캔 중이라고 믿었다. `Diagnostic` 기록은 유지된다. 같은 요청 재호출은 여전히 no-op 이다.
- 라이브러리 재연결 시간표(백오프·예산·소진)를 제거했다. `BleReconnectPolicy`(`Disabled`, `ExponentialBackoff`)와 `BleConnectOptions.reconnectPolicy` 가 없어지고 연결 옵션은 `BleConnectOptions(autoReconnect: Boolean = false)` 하나다. 끊긴 뒤 다시 붙이는 것은 Android autoConnect(`autoReconnect = true`) 또는 앱이 한다.
- `ConnectionState.Recovering` 은 `Recovering(lastFailure)` 로 줄었다 — `attempt`·`retryAfterMillis` 는 라이브러리 시간표가 없어 제거됐다. `Recovering` 은 이제 `autoReconnect = true` 세션이 OS 재연결을 기다리는 동안만 나온다.
- 실패 코드 `RECONNECT_ATTEMPT`·`RECONNECT_CONNECT_START_FAILED`·`RECONNECT_EXHAUSTED`·`RECONNECT_GIVE_UP` 과 `FailurePhase.Reconnect` 를 제거했다. 이 코드·phase 로 분기하던 앱은 `Disconnected(Failed)` 의 원 원인 코드(예: `LINK_FAILED`, `LINK_TIMEOUT`)로 옮긴다.
- `autoReconnect = false`(기본) 연결의 링크가 끊기면 재연결 없이 `Disconnected(Failed(...))` 로 끝나고 handle 을 닫는다. 이전 `ExponentialBackoff` 처럼 다시 시도하려면 앱이 `connect()` 를 다시 부른다 — `sample` 의 `reconnectWithRetry` 가 예시다.
- 재연결 시도 전용 시간 제한과 `RECONNECT_ATTEMPT_TIMEOUT` 코드는 0.2.0 에서 제거됐다. 연결 링크 제한(`linkTimeoutMillis`)은 `autoReconnect = false` 연결의 링크 단계에만 적용되고, 넘기면 `Disconnected(Failed(LINK_TIMEOUT))` 로 끝난다. `autoReconnect = true` 연결에는 link deadline 이 없다.
- 끝난 연결에 늦게 온 실패 status 콜백은 상태를 바꾸지 않고 `Diagnostic(Warning, LINK_FAILED)` 로만 알린다.
- 사용자 `disconnect()` 는 이제 `Disconnected(Requested)` 로 끝난다. 이전에는 실기기에서 플랫폼이 성공 해제 콜백을 주면 `Disconnected(Initial)` 로 덮였다.
- 라이브러리 쪽 원인이 없는 플랫폼 해제(성공 status 콜백)는 `Disconnected(Failed(LINK_FAILED))` 로 끝나고 `platformDetail.statusCode` 에 콜백 status 가 실린다. `Diagnostic(Warning, LINK_FAILED)` 도 함께 나온다. 이전에는 `Disconnected(Initial)` 이었다.
- `Disconnected(Initial)` 은 이제 첫 연결 시도 전에만 나온다. `Initial` 을 "해제됨" 으로 분기하던 앱은 `Requested`·`Failed(LINK_FAILED)` 를 처리해야 한다.
- 사용자 해제가 진행 중일 때 뒤이어 온 링크 실패 콜백은 더는 `Requested` 를 대체하지 않는다 — `Diagnostic` 으로만 남는다. 일반적으로 한 연결의 종료 이유는 먼저 확정된 원인이 유지된다.
- 연결 중·정리 중이거나 OS 재연결을 기다리는(`Recovering`) 세션에 `close()` 를 부르면 그 세션은 `Disconnected(ClientClosed)` 로 끝나고 닫힌 뒤 `snapshot()` 에도 그 상태가 남는다. 대기 없이 이미 끝난 세션은 원래 이유를 유지한다.
- OS 재연결을 기다리는(`Recovering`) 세션에서 어댑터가 꺼지거나 권한이 사라지면 대기가 끝나 `Disconnected(Failed(ADAPTER_UNAVAILABLE))`(권한은 `PERMISSION_DENIED`)가 되고 열어 둔 handle 은 닫힌다. `Diagnostic` 도 함께 나온다. 이전에는 `Recovering` 에 남았다.
- unsubscribe 중 CCCD ack timeout(`OPERATION_TIMEOUT`)·권한 거절(`PERMISSION_DENIED`)은 연결을 종결하므로 연결 상태 실패로 분류된다(상태 + Diagnostic, 동작 변경 없음).
- `OfficialBleClientEvent.Scan` 제거: 스캔 결과는 `BleClient.scanResults` 로만 나온다. `events` 는 연결·서비스·구독 복구 실패·진단만 싣는다 — 스캔이 `events` 버퍼를 채워 느린 소비자가 연결 이벤트를 잃던 문제를 없앤다. `when … else` 로 `events` 에서 스캔을 받던 코드는 컴파일은 되지만 더는 스캔을 받지 못한다.
- deprecated `BleCore` adapter를 제거했다. Android 앱은
  `AndroidBleClientFactory.create(context, config)`로 `BleClient`를 만든다.
- `DeviceId` → `PeripheralId`, `CharRef` → `CharacteristicRef`, 전역 `BleEvent` →
  `BleClient.events`/`scanResults`와 `BleSession.notifications()`로 전환한다.
- ID 기반 `read/write/subscribe/unsubscribe/disconnect`는 `BleClient.session(peripheralId)`가
  반환하는 `BleSession`에서 호출한다.
- legacy `ConnectOptions`, `ReconnectPolicy`, `WriteResult`, `Chunking`, snapshot 모델은 public
  API에서 제거됐다. replacement는 각각 `BleConnectOptions`, `BleConnectOptions.autoReconnect`,
  `WriteReceipt`, `BleChunking`, `ClientSnapshot`/`SessionSnapshot`이다.
- `SessionSnapshot`은 legacy `DeviceSnapshot`의 `mtu`/`chunkSize`를 노출하지 않는다 — 의도적 제외다.
  코어가 청킹하고 `WriteReceipt`가 전송 결과(bytes/chunks)를 돌려주므로 앱이 MTU를 알 필요가 없다.
  전송 한도가 필요해지면 Android MTU 노출이 아니라 플랫폼 공통 "쓰기 가능 크기" 계약으로 별도 설계한다.
- `BleConfig`는 data-class semantics를 유지하지만 legacy `reconnectPolicy` constructor/copy
  parameter는 제거됐다. 재연결 방식은 연결별 `BleConnectOptions(autoReconnect)`로 정한다.
- Android `autoConnect` 플래그, requested MTU, raw advertisement bytes는 공통 API에서 제거됐다.
  iOS와 동등한 의미가 아니므로 필요 시 별도 Android 전용 계약으로 설계한다. OS 재연결 대기는 플랫폼
  중립 이름 `BleConnectOptions.autoReconnect` 로만 노출한다.

### Added

- `FailureCode` 공개 상수 — `contract/failure-codes.json` 의 코드 전부(값 = 이름). Android 전용 코드는 KDoc 에 표시한다. 코드 문자열 대신 상수로 분기할 수 있다(비 BREAKING).
- `BleClient.scanState: StateFlow<ScanState>` — `Idle(lastFailure)` / `Scanning(request)`. 스캔 도중 중단(`SCAN_RUNTIME_FAILED`, `SCAN_STOPPED_BY_ADAPTER`)이 유실 없이 이유와 함께 남는다.
- `BleSession.activeSubscriptions: StateFlow<Set<CharacteristicRef>>` — 현재 연결에서 통지가 실제로 켜진 캐릭터리스틱. 끊기면 비고, 재연결 뒤 복원에 실패한 것은 `Ready` 여도 빠진다.
- `BleConnectOptions.autoReconnect: Boolean`(기본 `false`) — `true` 면 Android `connectGatt(autoConnect = true)` 로 연결하고, 링크가 끊기면 handle 을 열어 둔 채 `Recovering(lastFailure)` 으로 OS 재연결을 기다린다. OS 가 다시 붙이면 첫 연결과 같은 경로(서비스 탐색 → MTU → 구독 복원 → `Ready`)를 탄다. 첫 연결에도 link deadline 이 없다 — 포기는 `disconnect()` 로 하며 `Disconnected(Requested)` 로 끝난다(`close()` 는 `ClientClosed`, 어댑터 꺼짐은 `Failed(ADAPTER_UNAVAILABLE)` — 어댑터가 다시 켜지면 한 번 다시 건다).

### Fixed

- 스캔 결과의 `AdvertisementData.serviceData` 가 광고의 서비스 UUID 목록에 있는 UUID 만 채우던 결함을 고쳤다. 서비스 데이터로만 광고된 UUID(비콘 등)가 빠져서 그 UUID 의 `BleServiceDataFilter` 가 결과를 내지 않았다. 이제 `ScanRecord.getServiceData()` 로 전부 읽는다. 실기기 재확인(서비스 데이터만 광고하는 기기)은 검증 필요.

- 30 초 안 6번째 스캔 시작이 `Scanning` 인데 결과가 오지 않던 문제를 고쳤다(Android 는 스캔 시작 한도 초과 거절을 앱에 알리지 않는다). 이제 라이브러리가 성공한 시작을 세어 30 s(+여유 1 s) 안 5회를 넘는 시작을 `SCAN_START_THROTTLED`(`TransientMayHelp`, message 에 다음 시작 가능까지 ms)로 거부한다 — `startScan()` 은 던지고, 재발견 스캔은 `Diagnostic` 과 함께 직접 연결로 진행한다. 도는 스캔은 멈추지 않는다. 시작 기록은 프로세스 단위(여러 `BleClient` 합산, 시계 `elapsedRealtime`)이고 Bluetooth 가 꺼지면 비운다. 한계: 같은 앱이 라이브러리 밖에서 한 스캔 시작과 같은 앱(같은 UID)의 다른 프로세스가 한 스캔 시작은 셀 수 없고, 한도 값은 Android 버전·OEM 마다 다를 수 있다.
- 서비스 탐색이 `GATT_SUCCESS` 인데 서비스 0개면 `Diagnostic(Error)` 와 함께 `Disconnected(Failed(SERVICE_DISCOVERY_FAILED))` 로 끝난다(`platformDetail.statusCode` 0, 첫 연결·플랫폼 재연결 모두). 규격상 GAP 은 필수라 빈 목록은 플랫폼 캐시·L2CAP 실패다. 이전에는 빈 토폴로지로 `Ready` 가 됐다.
- `autoReconnect = true` 연결에서 캐시에 없는 주소를 찾는 짧은 스캔이 기기를 못 찾으면(또는 스캔을 쓸 수 없으면) `Diagnostic(Warning, PERIPHERAL_NOT_FOUND)`(스캔 실패는 그 실패)를 남기고 OS 대기(`Connecting`, 기한 없음)로 들어간다. 이전에는 앱 재시작 뒤 첫 연결이나 어댑터 복귀 재연결이 약 3초 뒤 `Disconnected(Failed(PERIPHERAL_NOT_FOUND))` 로 끝났다. `autoReconnect = false` 는 그대로 `PERIPHERAL_NOT_FOUND` 로 던진다.
- 어댑터 복귀 재연결의 시작 실패를 조용히 삼키던 것을 고쳤다 — `Disconnected(Failed(원인))` 과 함께 `Diagnostic(Warning, 원인)` 이 나온다.
- 기기 탐색 스캔을 기다리는 중 세션이 끝나면(어댑터 꺼짐·`close()`·`disconnect()`) `connect()` 가 정상 반환하던 것을 고쳤다. 이제 그 종결 실패(`ADAPTER_UNAVAILABLE`·`CLIENT_CLOSED` 등, 사용자 `disconnect()` 면 `OPERATION_INVALIDATED`)로 던진다. 그 중 `disconnect()` 는 탐색 스캔을 즉시 끝내 바로 `Disconnected(Requested)` 가 된다(이전에는 스캔 시간 초과까지 `Disconnecting` 에 남았다).
- 정리 중 `connect()` 의 `CONNECT_DURING_DISCONNECT` retryability 가 `Never` 에서 `TransientMayHelp` 로 바뀌었다 — 정리가 끝난 뒤 다시 걸면 된다.
- `autoReconnect = true` 세션에서 OS 재연결 뒤 Ready 전에 실패(서비스 탐색·구성 실패·단계 시간 초과·구독 복원 시간 초과)하면 handle 을 닫고 `Disconnected(Failed(원인))` 로 끝난다(첫 연결 실패와 같은 규칙). 이전에는 handle 이 닫혔는데도 `Recovering` 에 남아 `disconnect()` 로도 벗어나지 못했다. 사용자 해제 중 어댑터가 꺼졌다 켜지면 그 세션을 다시 연결하던 것도 고쳤다.
- 연결 상태 콜백의 status 를 GATT 작업 status 표로 해석해 링크 끊김(8, 연결 타임아웃)이 `GATT_INSUFFICIENT_AUTHORIZATION`·`AfterEnvironmentChange` 로 보이던 것을 고쳤다. 이제 `LINK_FAILED` 의 `symbolicName` 은 `GATT_CONN_*` 이고 retryability 는 `Unknown`(0x05 인증 실패만 `AfterEnvironmentChange`).
- 링크가 끊긴 뒤 플랫폼이 스스로 다시 연결(autoConnect 등)하면 구독은 복원되는데 `activeSubscriptions` 가 비어 있던 것을 고쳤다.
- 재연결 대기 중(끝난 연결)에 도착한 notify 가 `notifications()` 로 전달되던 문제를 고쳤다.
- 재연결 대기 중 `unsubscribe()` 가 `IllegalStateException` 을 던지고 구독 의도를 지우지 못해 재연결 뒤 구독이 복원되던 문제를 고쳤다. 이제 링크가 없으면 로컬 해제만 한다.
- 이전 스캔 요청의 늦은 `onScanFailed` 가 새 스캔을 멈추고 `scanState` 를 그 실패로 덮던 것을 고쳤다. 스캔마다 generation 을 붙여 다른 스캔·이미 멈춘 스캔의 실패는 `Diagnostic(Warning)` 으로만 남긴다.
- 스캔 종료 이유는 먼저 확정된 것이 유지된다 — 블루투스가 꺼져 `Idle(SCAN_STOPPED_BY_ADAPTER)` 가 된 뒤 도착한 `onScanFailed` 가 이유를 `SCAN_RUNTIME_FAILED` 로 바꾸지 않는다.
- `stopScan()`·`disconnect()` 가 이미 취소된 코루틴에서 불리면 정리를 실행하지 않고 취소 예외만 던지던 것을 고쳤다. 이제 정리를 끝까지 한 뒤 취소를 전파한다 — `finally { stopScan() }` 이 화면 이탈 시에도 스캔을 멈춘다.
- 실패 전달 규칙을 내부 `FailureCatalog` 로 확정하고 `FailureCatalogTest` 가 강제한다: 공개 명령 중 확정된 실패는 던지고, 명령 밖 실패는 그 자원의 `StateFlow` 에 남는다. 모든 실패 코드가 등록돼야 하며 위반은 0 이어야 한다.
- 도달 불가 실패 코드 `LIFECYCLE_TIMEOUT` 를 제거했다.
- GATT 작업(재구독 포함) 중 권한이 회수돼 연결을 정리할 때 `PERMISSION_DENIED` Diagnostic 이 나가지 않던 것을 고쳤다(상태는 이전에도 `Disconnected(Failed(PERMISSION_DENIED))` 였다).
- `BleClient.events` 에 GATT 페이로드마다 `Diagnostic(Info, GATT_PAYLOAD_OBSERVED)` 가 실리던 것을 없앴다 — 관측 진단은 이제 `BleConfig.logger` 로만 간다. 고빈도 notify·장문 write 중 이 진단이 `events` 버퍼를 채워 느린 소비자에게서 연결 이벤트가 버려질 수 있었다. `events` 로 이 코드를 받던 코드는 logger 로 옮겨야 한다.
- `BleConfig(eventBufferCapacity)` 가 1 미만이면 이제 생성 시 `IllegalArgumentException` 을 던진다. 0 은 이전에
  받아들여졌지만 모든 스트림이 사실상 전량 유실되는 값이었다.
- `BleSession.notifications()` 값이 무관한 이벤트 때문에 유실되지 않는다. 이전에는 모든 notification 수집자가
  스캔 결과·연결 상태·진단과 버퍼(`eventBufferCapacity`) 하나를 공유해, 한 수집자가 바쁜 동안 스캔이나 다른
  세션의 이벤트가 버퍼를 채우면 notification 이 조용히 버려졌다. 이제 수집자마다 독립 버퍼를 갖고, 유실은
  그 수집자 자신이 `eventBufferCapacity` 이상 밀렸을 때만 난다(최신 값을 버린다).
- `read`/`write`/`subscribe`는 이제 세션/디바이스 확인보다 **먼저** 어댑터 상태를 확인한다(`startScan` 과 같다;
  `connect` 는 기존 세션 확인이 먼저다). 어댑터를 쓸 수 없으면 `NOT_READY` 대신
  `ADAPTER_UNAVAILABLE`/`PERMISSION_DENIED`/`BLE_UNSUPPORTED`(phase `Adapter`, 세션 컨텍스트 없음)로 실패한다 —
  모르는 id 도 마찬가지이며, 이전에는 `UNKNOWN_DEVICE` 였다. op 가 BT off 를 처음 관측하면 세션을 스윕하므로
  호출이 throw 하기 전에 스트림에 `Diagnostic` + `ConnStateChanged(Disconnected)` 가 먼저 나온다.
  `NOT_READY` 는 이제 어댑터는 Ready 인데 세션이 Ready 가 아닐 때만 뜻한다. `unsubscribe` 는 변화 없다.
- `BleClient.close()`는 이제 코어 디스패처 위에 정리를 예약하고 즉시 반환한다(이전에는
  정리가 끝날 때까지 블록했다). `snapshot()`은 `close()` 뒤에도 여전히 정리 완료를 기다린
  뒤 동결된 스냅샷을 돌려준다. `session()`/`connect()`도 더는 호출자의 디스패처를 블록하지
  않는다 — `Dispatchers.Main`이나 `runTest` 디스패처 위에서 부르면 나던 데드락이 없어졌다.
  `close()`가 반환하는 시점에 `BluetoothGatt.close()`가 아직 안 끝났을 수 있다 — 그 직후
  프로세스가 바로 종료되면 남은 GATT 핸들 회수는 플랫폼이 프로세스 죽음 시점에 담당한다.

### Documented contracts

- `BleClient.events` 는 **유실될 수 있는** 변경 알림 스트림이다. 버퍼(`eventBufferCapacity`)를 모든 수집자가 공유하므로
  수집자 하나라도 그만큼 밀려 있으면 새 이벤트는 종류와 무관하게 모든 수집자에게서 버려진다. `scanResults` 도 같다.
  연결 상태·서비스의 기준 값은 유실 없는 `BleSession.connectionState` / `services`(`StateFlow`) 다.
  `notifications()` 는 수집자별 버퍼라 자기 수집자의 지연으로만 유실된다. KDoc 에 명시했다(동작 변경 없음).
- 사용자 필터 스캔 중 cold-cache 연결의 재발견은 합집합 재시작 1회를 쓴다 — Android 스캔 시작 한도(기기 확인 필요)에 사용자 스캔과 같이 계산된다.

### Verification boundary

- checked-in Kotlin-public surface with JVM member signatures:
  [`core/api/ble-core.api`](core/api/ble-core.api)
- automated: core unit/build와 official API만 쓰는 sample assemble
- 실기기(단일 기기, `sample` 의 `BleHardwareIntegrationTest`·`AdapterStateBaselineTest`): scan →
  connect → Ready → read(GAP Device Name) → subscribe/notifications → 장문 write(응답 있음/없음) → disconnect,
  재연결 뒤 CCCD 복구,
  본딩 유지·펌웨어 본딩 초기화·재본딩, BT off/on 스윕 뒤 재연결, 중복 disconnect. 결과와 알려진 간헐 이슈는
  [`docs/v1/android-real-device-results.md`](docs/v1/android-real-device-results.md).
- 미실행: 여러 기기·OEM callback 차이, 로그 유용성·PII 검토.
- 실패 전달 규칙 정비(startScan 예외, `scanState`, `activeSubscriptions`, 정리 명령 취소 무관 실행, 종료 이유 확정 — `Requested`·원인 없는 해제의 `LINK_FAILED`·먼저 확정된 원인 유지·`close()` 의 `ClientClosed`)는 **fake 드라이버 기준 unit test 로만 검증**했다. 특히 실기기가 사용자 해제·원인 없는 해제에 status 0 콜백을 준다는 전제는 기기에서 확인되지 않았다 — 기기 미연결 상태에서 작업했다. 위 실기기 항목은 이 변경 이전 결과이며, 기존 실기기 테스트 재실행과 신규 시나리오 실행이 남아 있다.
