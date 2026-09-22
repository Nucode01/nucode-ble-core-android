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
