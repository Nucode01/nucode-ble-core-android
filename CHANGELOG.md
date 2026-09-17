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
- `BleConfig`는 data-class semantics를 유지하지만 legacy `reconnectPolicy` constructor/copy
  parameter는 제거됐다. 재연결 정책은 연결별 `BleConnectOptions`로 전달한다.
- Android `autoConnect`, requested MTU, raw advertisement bytes는 공통 API에서 제거됐다.
  iOS와 동등한 의미가 아니므로 필요 시 별도 Android 전용 계약으로 설계한다.

### Verification boundary

- checked-in Kotlin-public surface with JVM member signatures:
  [`core/api/ble-core.api`](core/api/ble-core.api)
- automated: core unit/build와 official API만 쓰는 sample assemble
- 미실행: scan→connect→Ready→read/write→subscribe→disconnect 실기기 smoke, OEM callback,
  reconnect/CCCD restore, 로그 유용성·PII 검토. 이 항목은 Slice 8 범위다.
