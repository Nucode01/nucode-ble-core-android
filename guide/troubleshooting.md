# 문제 해결과 한계

## 6. 자주 틀리는 것 / FAQ

| 하지 말 것 | 대신 |
| --- | --- |
| Activity · ViewModel 마다 `BleClient` 생성 | `Application` 에서 하나 |
| `connect()` 가 돌아오자마자 read/write | `connectionState` 로 `Ready` 를 기다림([5.2](recipes/scan-connect.md#52-연결하고-ready-까지-기다리기)) |
| `first { it is ConnectionState.Ready }` 만으로 대기 | 실패하면 영원히 기다립니다. `Disconnected` 도 함께 기다리고 시간 제한을 둡니다 |
| 권한을 받은 뒤 `adapterState` 가 `Ready` 가 되길 기다림 | 권한 결과에서 바로 `startScan` 등 명령을 부름([2.3](install.md#23-권한)) |
| `subscribe()` 먼저, 수집 나중 | 수집을 `UNDISPATCHED` 로 먼저 걸고 `subscribe()` |
| write 한 뒤에 응답 수집 시작 | 응답 수집을 먼저 등록하고 write |
| `read`/`write` 를 짧은 `withTimeout` 으로 감쌈 | 호출자 시간 제한은 호출자만 떼어 내고 작업은 계속됩니다(호출자는 `TimeoutCancellationException`). 코어의 `operationTimeoutMillis` 를 믿거나 `BleConfig` 에서 조정 |
| 재연결될 때마다 앱이 다시 `subscribe()` | 코어가 `Ready` 전에 복원합니다. 확인은 `activeSubscriptions` |
| `disconnect()` 뒤 다시 연결하면 구독이 살아 있다고 가정 | 명시적 해제는 구독 의도를 지웁니다. 다시 `subscribe()` |
| `events` 로 연결 · 스캔 · 구독 상태를 재구성 | `connectionState` · `scanState` · `activeSubscriptions` 를 봅니다 |
| `events` 의 `Diagnostic` 을 사용자에게 표시 | 로그용입니다. 사용자 오류는 명령의 예외와 `StateFlow` 에서 |
| `catch (e: Exception)` 으로 뭉뚱그림 | `catch (e: BleException)` 후 `failure.code` / `retryability` |
| `failure.message` 로 분기 | `failure.code` 로 분기 |
| 스캔을 켜 두고 잊음 | 반드시 `stopScan()`. 스스로 멈추지 않습니다 |
| `WithoutResponse` write 를 전달 완료로 간주 | `SubmittedToPlatform` 은 기기 수신의 증거가 아닙니다 |
| 실패한 write 를 처음부터 무조건 재전송 | `writeProgress` 로 어디까지 갔는지 먼저 봅니다 |
| `autoReconnect = true` 연결을 시간 제한 없이 기다림 | 기한이 없습니다. 앱이 시간을 정하고 `disconnect()` |
| 끊긴 뒤에도 `session.services` 를 믿음 | `Ready` 일 때만 믿습니다 |
| 앱 종료마다 `close()` | 필요 없습니다. 닫으면 다시 열 수 없습니다 |

**Q. `client.session(id)` 와 `client.connect(id)` 는 무엇이 다른가요?**
`session(id)` 는 연결하지 않고 세션 뷰만 돌려줍니다. `connect(id)` 는 세션을 얻고 연결을 시작합니다. 같은 id 면 둘 다 같은 상태를 봅니다.

**Q. 연결은 됐는데 `NOT_READY` 가 납니다.**
`connectionState` 가 `Ready` 가 되기 전에 호출했습니다. `Configuring`(MTU 협상 · 구독 복원) 중에도 `NOT_READY` 입니다.
세션이 `Ready` 가 아니면 단계와 무관하게 `NOT_READY` 입니다: `Recovering`, `Disconnected`, 링크가 끊긴 직후 원인을 확정하는 1000ms
(이때 `connectionState` 는 아직 `Ready` 로 보입니다) 모두 같습니다. 블루투스가 꺼져 있으면 세션보다 어댑터를 먼저 보므로 `NOT_READY` 가 아니라
`ADAPTER_UNAVAILABLE` 입니다(검사 순서: `CLIENT_CLOSED` → 어댑터 → `NOT_READY` → `CHARACTERISTIC_NOT_FOUND`).

**Q. 블루투스를 껐다 켰더니 스캔 결과가 안 옵니다.**
블루투스가 꺼지면 스캔은 `Idle(SCAN_STOPPED_BY_ADAPTER)` 로 멈추고 자동으로 다시 시작하지 않습니다. `startScan()` 을 다시 부르세요.

**Q. 앱 재시작 뒤 저장해 둔 기기로 바로 연결하면 느리거나 `PERIPHERAL_NOT_FOUND` 가 납니다.**
처음 보는 주소라서 코어가 먼저 짧은 스캔으로 기기를 찾기 때문입니다. 기기가 광고 중인지 확인하고, 필요하면 `BleConfig(rediscoveryTimeoutMillis = …)` 를 늘립니다(`linkTimeoutMillis` 이하).

**Q. 타임아웃 값을 바꾸고 싶습니다.**
`AndroidBleClientFactory.create(context, BleConfig(operationTimeoutMillis = 10_000))` 처럼 `BleConfig` 로 바꿉니다. 모든 값은 0보다 커야 하고 `rediscoveryTimeoutMillis ≤ linkTimeoutMillis` 여야 합니다(아니면 `IllegalArgumentException`).

**Q. MTU 를 직접 정할 수 있나요?**
Android 에서만 요청 값을 고릅니다: `session.connect(BleConnectOptions(requestedMtu = 185))`(23..517, 생략하면 247). iOS 에는 없습니다.
요청은 요청일 뿐이고 기기와 플랫폼이 실제 값을 정합니다. 협상에 실패하면 23 으로 진행합니다(`CONFIGURATION_FAILED` 진단).
Android 14 는 요청 값을 무시할 수 있습니다. Pixel 5a(Android 14)에서 `requestedMtu = 23` 으로 연결하자 코어는 23 을 요청했지만 스택이 517 을 요청했고, 협상값은 기기 최대인 65 였습니다(2026-10-22). 다른 기기는 확인하지 않았습니다.
실제 값은 Ready 뒤 `session.writeLimits`(mode 별 한 번에 쓸 수 있는 바이트, Android 는 MTU − 3)와 Android 전용 `session.androidMtu` 로 읽습니다.
같은 기기에 다른 `requestedMtu` 로 다시 `connect()` 하면 `CONNECTION_OPTIONS_CONFLICT` 입니다. 바꾸려면 먼저 `disconnect()` 합니다.

**Q. 스캔 결과가 여러 번 옵니다.**
정상입니다. 광고를 받을 때마다 옵니다. `peripheralId` 로 묶어서 최신 값만 쓰세요([5.1](recipes/scan-connect.md#51-스캔-필터로-찾고-가장-가까운-기기-고르기)).

**Q. `Recovering` 에서 벗어나지 않습니다.**
OS 가 기기를 다시 찾을 때까지 기한 없이 기다립니다. 기기가 광고 중인지 확인하고, 포기하려면 `disconnect()` 합니다.

---

## 7. 한계와 검증 범위

### 검증한 것

- **자동:** core 단위 테스트와 빌드, 공개 API 만 쓰는 sample 빌드(이 문서의 예시 포함).
- **실기기(부분 통과):** Pixel 5a(Android 14) + nRF52840(통합 테스트 펌웨어) **한 조합에서만** 확인했습니다.
  응답 있음/없음 두 방식의 600 바이트 write, 사용자 해제 중 부분 진행과 자동 재전송 금지, 같은 옵션 중복 연결,
  중복 해제와 세션 재사용, 펌웨어가 끊은 뒤 재연결 · notify 구독 복원, 본딩 생성 · 유지 · 펌웨어 초기화 · 재본딩,
  GAP Device Name read. 상세 결과는 [실기기 검증 기록](../docs/v1/android-real-device-results.md)에 있습니다.
- 0.2.0 의 실패 전달 규칙 정비(`startScan` 예외, `scanState`, `activeSubscriptions`, 종료 이유 확정 등)는 **가짜 드라이버 기반 단위 테스트로만** 검증했습니다. 위 실기기 항목은 대부분 이 변경 이전 결과입니다.

### 검증 필요

릴리스 노트(`docs/v1/release-notes.md`, 저장소에 올라가지 않는 로컬 문서)의 "Verification boundary" 기준으로 아직 확인하지 않은 것입니다.

- RF 손실, peripheral 전원 차단
- GATT status 8 / 19 / 133 상황
- Bluetooth off/on (CHANGELOG 에는 BT off/on 스윕 뒤 재연결의 단일 기기 확인이 적혀 있으나, 릴리스 노트는 검증 필요로 둡니다)
- 실행 중 권한 회수
- write-without-response 장시간 처리율과 backpressure
- indication
- 늦게 오거나 엉뚱한 GATT 콜백
- 같은 UUID 다중 인스턴스(`instanceIndex` > 0)
- 다른 Android API 레벨 · 다른 제조사(OEM) 기기
- 진단 로그의 유용성과 개인정보(PII) 노출 검토

이 가이드를 쓰며 추가로 확인하지 못한 것:

- 여러 기기 동시 연결의 실기기 동작과 동시 연결 수 한계
- 연결된 상태에서 본딩하는 순서
- Android 11 이하에서 위치 설정이 꺼졌을 때의 스캔 동작

### 수정한 결함

- **서비스 데이터 누락(2026-09-26 수정).** 이전에는 서비스 UUID 목록에 있는 UUID 의 서비스 데이터만 읽어서, 서비스 데이터로만 광고된 UUID 가 `AdvertisementData.serviceData` 에서 빠지고 그 UUID 의 `BleServiceDataFilter` 가 결과를 내지 않았습니다. 지금은 광고의 서비스 데이터를 전부 읽습니다. 코드와 빌드로 확인했고, 그런 광고를 내는 기기로 한 실기기 재확인은 **검증 필요**입니다.

### 배포 · 난독화

- **배포:** JitPack(`https://jitpack.io`)에서 `com.github.Nucode01:nucode-ble-core-android:0.4.0` 로 받습니다. 인증은 필요 없습니다([설치](install.md)). JitPack 빌드를 확인한 버전은 0.3.0 과 0.3.1 입니다. `0.2.0` · `0.2.1` · `0.2.2` 는 JitPack 에서 빌드해 보지 않았습니다.
- **라이브러리는 난독화하지 않습니다.** `isMinifyEnabled = false`, `consumer-rules.pro` 는 비어 있고 sources.jar 를 함께 배포합니다. 난독화는 앱의 R8 에 맡깁니다.
- **앱 R8 호환은 빌드 단계만 확인했습니다.** sample release 를 `isMinifyEnabled = true`(`proguard-android-optimize.txt`)로 빌드해 R8 경고 · 누락 클래스 없이 통과했고, core 클래스가 난독화됐습니다. core 에 리플렉션 · 직렬화 · ServiceLoader 가 없어 keep 규칙이 필요 없었습니다.
- **검증 필요:** 난독화된 release APK 의 **런타임** 동작(스캔 · 연결 · read/write · 구독)은 아직 확인하지 않았습니다. R8 을 켠 앱은 release 빌드로 실기기에서 한 번 확인하세요.

### 관련 문서

- [CHANGELOG](../CHANGELOG.md) — 0.2.0 변경 사항과 마이그레이션
- [공개 API 시그니처](../core/api/ble-core.api) — 테스트가 소스와 대조하는 공개 API 기준
- `guide/llms-android.md` — 다른 LLM 세션에 줄 영문 API 요약(로컬 전용 문서라 git 에는 없습니다)
