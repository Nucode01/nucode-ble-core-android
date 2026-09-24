# Changelog

## 0.2.0 (unreleased)

Slice 7 public API cleanup과 이후 내부 정리·결함 수정. Build·unit test와 단일 Android 기기(Pixel 5a,
Android 14 + nRF52840 NUS 펌웨어) 실기기 검증을 수행했다. publish, tag, release 는 수행하지 않았다.

### Breaking changes

- `BleClient.startScan()` 이 호출 중 확정된 시작 실패를 던진다: 다른 요청으로 이미 스캔 중(`SCAN_ALREADY_RUNNING`), 스캐너 획득 실패(`SCANNER_UNAVAILABLE`), 플랫폼 호출 단계의 권한 거절(`PERMISSION_DENIED`). 이전에는 `events` 의 `Diagnostic` 으로만 알려 버퍼가 차면 실패가 사라지고 앱은 스캔 중이라고 믿었다. `Diagnostic` 기록은 유지된다. 같은 요청 재호출은 여전히 no-op 이다.
- 재연결 예산을 다 쓰면 `connectionState` 가 `Disconnected(Failed(RECONNECT_EXHAUSTED))`(legacy 정책은 `RECONNECT_GIVE_UP`)가 된다. 이전에는 직전 원인의 `Disconnected(Failed)` 에 머물러 "포기했다"를 상태로 알 수 없었다. `failure.code` 로 분기하던 앱은 이 코드를 추가로 처리한다.
- 재연결 시도 전용 시간 제한과 `RECONNECT_ATTEMPT_TIMEOUT` 코드는 0.2.0 에서 제거됐다. 재연결 시도의 상한은 연결 링크 제한(`linkTimeoutMillis`) 하나다(복구 중 시도마다 적용 — 이 값을 늘린 앱은 재연결 시도 하나도 그만큼 길어진다) — 시도가 이를 넘기면 복구 중 그 시도가 `Disconnected(Failed(LINK_TIMEOUT))` 로 끝나고 다음 백오프로 넘어가며, 소진 시 `RECONNECT_EXHAUSTED` 가 이 원인을 감싼다. `RECONNECT_ATTEMPT_TIMEOUT` 으로 분기하던 앱은 복구 중 `LINK_TIMEOUT` 으로 옮긴다.
- 재연결 시작이 권한 회수로 예외를 던지면 크래시 대신 `Disconnected(Failed(PERMISSION_DENIED))` 로 끝나고 더 재시도하지 않는다. 끝난 연결에 늦게 온 실패 status 콜백은 상태를 바꾸지 않고 `Diagnostic(Warning, LINK_FAILED)` 로만 알린다.
- 사용자 `disconnect()` 는 이제 `Disconnected(Requested)` 로 끝난다. 이전에는 실기기에서 플랫폼이 성공 해제 콜백을 주면 `Disconnected(Initial)` 로 덮였다.
- 라이브러리 쪽 원인이 없는 플랫폼 해제(성공 status 콜백)는 `Disconnected(Failed(LINK_FAILED))` 로 끝나고 `platformDetail.statusCode` 에 콜백 status 가 실린다. `Diagnostic(Warning, LINK_FAILED)` 도 함께 나온다 — 재연결 예산이 소진돼 상태가 `RECONNECT_EXHAUSTED`/`RECONNECT_GIVE_UP` 이 되는 경우에도 이 `LINK_FAILED` Diagnostic 이 소진 Diagnostic 보다 먼저 나온다. 이전에는 `Disconnected(Initial)` 이었다.
- `Disconnected(Initial)` 은 이제 첫 연결 시도 전에만 나온다. `Initial` 을 "해제됨" 으로 분기하던 앱은 `Requested`·`Failed(LINK_FAILED)` 를 처리해야 한다.
- 재연결 소진(`RECONNECT_EXHAUSTED`/`RECONNECT_GIVE_UP`)은 게시 전에 판정돼 `Disconnected` 가 한 번만 게시된다(이전: 직전 원인으로 게시한 뒤 소진으로 한 번 더). 소진 실패의 `retryability` 는 `Never` 이고, 원 원인의 context·`platformDetail` 을 담는다. 이전에는 원 원인의 retryability 를 물려받았다.
- 사용자 해제가 진행 중일 때 뒤이어 온 링크 실패 콜백은 더는 `Requested` 를 대체하지 않는다 — `Diagnostic` 으로만 남는다. 일반적으로 한 연결의 종료 이유는 먼저 확정된 원인이 유지된다.
- 연결 중·정리 중이거나 재연결 백오프를 기다리는(`Recovering`, legacy 정책은 재연결 예약됨) 세션에 `close()` 를 부르면 그 세션은 `Disconnected(ClientClosed)` 로 끝나고 닫힌 뒤 `snapshot()` 에도 그 상태가 남는다. 재시도 대기 없이 이미 끝난 세션은 원래 이유를 유지한다. 사용자 해제·`close()` 로 끝나는 세션은 재연결 소진으로 바뀌지 않는다.
- 재연결 백오프를 기다리는(`Recovering`, legacy 정책은 재연결 예약됨) 세션에서 어댑터가 꺼지거나 권한이 사라지면 회복이 끝나 `Disconnected(Failed(ADAPTER_UNAVAILABLE))`(권한은 `PERMISSION_DENIED`)가 되고 예약된 재연결은 취소된다. `Diagnostic` 도 함께 나온다. 이전에는 `Recovering` 에 남았다.
- operation 무효화(예: 작업 timeout)로 연결이 끝날 때 무효화 원인 Diagnostic 이 재연결 소진 Diagnostic 보다 먼저 나온다.
- unsubscribe 중 CCCD ack timeout(`OPERATION_TIMEOUT`)·권한 거절(`PERMISSION_DENIED`)은 연결을 종결하므로 연결 상태 실패로 분류된다(상태 + Diagnostic, 동작 변경 없음).
- `OfficialBleClientEvent.Scan` 제거: 스캔 결과는 `BleClient.scanResults` 로만 나온다. `events` 는 연결·서비스·구독 복구 실패·진단만 싣는다 — 스캔이 `events` 버퍼를 채워 느린 소비자가 연결 이벤트를 잃던 문제를 없앤다. `when … else` 로 `events` 에서 스캔을 받던 코드는 컴파일은 되지만 더는 스캔을 받지 못한다.
- deprecated `BleCore` adapter를 제거했다. Android 앱은
  `AndroidBleClientFactory.create(context, config)`로 `BleClient`를 만든다.
- `DeviceId` → `PeripheralId`, `CharRef` → `CharacteristicRef`, 전역 `BleEvent` →
  `BleClient.events`/`scanResults`와 `BleSession.notifications()`로 전환한다.
- ID 기반 `read/write/subscribe/unsubscribe/disconnect`는 `BleClient.session(peripheralId)`가
  반환하는 `BleSession`에서 호출한다.
- legacy `ConnectOptions`, `ReconnectPolicy`, `WriteResult`, `Chunking`, snapshot 모델은 public
  API에서 제거됐다. replacement는 각각 `BleConnectOptions`, `BleReconnectPolicy`,
  `WriteReceipt`, `BleChunking`, `ClientSnapshot`/`SessionSnapshot`이다.
- `SessionSnapshot`은 legacy `DeviceSnapshot`의 `mtu`/`chunkSize`를 노출하지 않는다 — 의도적 제외다.
  코어가 청킹하고 `WriteReceipt`가 전송 결과(bytes/chunks)를 돌려주므로 앱이 MTU를 알 필요가 없다.
  전송 한도가 필요해지면 Android MTU 노출이 아니라 플랫폼 공통 "쓰기 가능 크기" 계약으로 별도 설계한다.
- `BleConfig`는 data-class semantics를 유지하지만 legacy `reconnectPolicy` constructor/copy
  parameter는 제거됐다. 재연결 정책은 연결별 `BleConnectOptions`로 전달한다.
- Android `autoConnect`, requested MTU, raw advertisement bytes는 공통 API에서 제거됐다.
  iOS와 동등한 의미가 아니므로 필요 시 별도 Android 전용 계약으로 설계한다.

### Added

- `BleClient.scanState: StateFlow<ScanState>` — `Idle(lastFailure)` / `Scanning(request)`. 스캔 도중 중단(`SCAN_RUNTIME_FAILED`, `SCAN_STOPPED_BY_ADAPTER`)이 유실 없이 이유와 함께 남는다.
- `BleSession.activeSubscriptions: StateFlow<Set<CharacteristicRef>>` — 현재 연결에서 통지가 실제로 켜진 캐릭터리스틱. 끊기면 비고, 재연결 뒤 복원에 실패한 것은 `Ready` 여도 빠진다.

### Fixed

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
- 실패 전달 규칙 정비(startScan 예외, `scanState`, `activeSubscriptions`, 재연결 소진·시도 timeout 상태, 정리 명령 취소 무관 실행, 종료 이유 확정 — `Requested`·원인 없는 해제의 `LINK_FAILED`·소진 단일 게시와 `Never`·먼저 확정된 원인 유지·`close()` 의 `ClientClosed`)는 **fake 드라이버 기준 unit test 로만 검증**했다. 특히 실기기가 사용자 해제·원인 없는 해제에 status 0 콜백을 준다는 전제는 기기에서 확인되지 않았다 — 기기 미연결 상태에서 작업했다. 위 실기기 항목은 이 변경 이전 결과이며, 기존 실기기 테스트 재실행과 신규 시나리오 실행이 남아 있다.
