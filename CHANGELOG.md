# Changelog

## 0.2.0 (unreleased)

Slice 7 public API cleanup. Build와 unit test만 완료했으며 publish, tag, release, Android 실기기
검증은 수행하지 않았다.

### Breaking changes

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

### Fixed

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

### Verification boundary

- checked-in Kotlin-public surface with JVM member signatures:
  [`core/api/ble-core.api`](core/api/ble-core.api)
- automated: core unit/build와 official API만 쓰는 sample assemble
- 미실행: scan→connect→Ready→read/write→subscribe→disconnect 실기기 smoke, OEM callback,
  reconnect/CCCD restore, 로그 유용성·PII 검토. 이 항목은 Slice 8 범위다.
