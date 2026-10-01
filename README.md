# nucode_ble_core (Kotlin)

사내 Android · iOS · Flutter 앱이 공유하는 BLE 라이브러리의 **Kotlin 구현.** central 전용.

> **라이브러리를 쓰려면 [개발자 가이드](guide/README.md)부터 보세요.** 설치 · 스캔 · 연결 · 읽기/쓰기/구독 · 실패 처리 · 화면 연동을 예시 코드와 함께 다룹니다.
> 이 README 는 저장소 개발 기록입니다.

> **문서 상태:** 이 README와 기존 설계 문서는 2026-08 시점의 현재 구현 스냅샷과
> 역사적 결정 근거를 기록한다. 향후 동작과 API의 **목표 v1 설계 정본**은
> [docs/v1/README.md](https://github.com/pdev-jay/nucode-ble-core/blob/main/docs/v1/README.md)다. 목표 v1은 아직 구현 완료 상태를 뜻하지 않는다.

> **F26 진단 hardening:** JVM 기반 deterministic acceptance와 `:core`/`:sample` build는
> Slice 6 oracle로 검증된다. 실제 기기에서 장애 로그가 충분히 유용한지, PII/secret이
> 노출되지 않는지는 아직 실행하지 않았으며 Slice 8 수동 검토 대상으로 남는다.

> **Slice 7 API cleanup:** public 진입점은 `BleClient`/`BleSession`이며 Android 생성 경계는
> `AndroidBleClientFactory`다. deprecated `BleCore`는 0.2.0에서 제거됐다. migration은
> [0.2.0 changelog](CHANGELOG.md#020-2026-09-30), Kotlin-public 선언의 고정 JVM member signature는
> [public API dump](core/api/ble-core.api)를 본다. Slice 8 실기기 검증은 Pixel 5a/Android 14와
> nRF52840 통합 테스트 firmware 조합에서 부분 통과했으며 결과는
> [실기기 검증 기록](docs/v1/android-real-device-results.md)을 본다.

Swift 구현은 상위 폴더의 `../ios/`에 별도 git 저장소로 둔다 (아직 없음 — 이 구현 다음).

---

## 읽는 순서

```
1. 이 README                                   2026-08 현재 구현 스냅샷 · 당시 결정
2. docs/v1/README.md                           목표 v1 설계 정본 · 향후 구현 로드맵
3. docs/ble-core-design.md                     역사적 결정 근거 · 2026-08 구현 스냅샷
     §0 목적·목표   §1 진단   §3 규칙 9개 ← 여기까지가 필수
     §6 내부 구조    의존성 레이어 · 소유 관계 · 런타임 flow · 확장 지점
                    ← 구현 들어가기 직전에
     나머지는 구현하며 참조
4. docs/ble-core-diagrams.md                   2026-08 현재 구현 구조·흐름 스냅샷

참고 (다른 레포)
  ../nucode-ble-serial-terminal/docs/ble-terminal-design.md   검증 드라이버 앱
  ../nucode-ble-serial-terminal/docs/design.md                터미널 시각 스펙
```

> `docs/ble-core-design.md`는 터미널 레포에도 사본이 있다. **2026-08 당시 설계 근거와
> 구현 스냅샷의 정본은 이 프로젝트의 파일이다.** 사본이 갈라지면 이 파일이 이긴다.
> 향후 목표 v1 동작의 정본은 `docs/v1/README.md`와 그 하위 문서다.
> **2026-08-27 정본을 크게 고쳤으므로 터미널 쪽 사본은 지금 갈라져 있다.**
> 포인터로 바꿔야 한다 — 아직 안 함.

---

## 현재 상태

```
스캐폴딩   완료
코드      플랫폼 배선 완료 — TODO 0. Pixel 5a 핵심 실기기 시나리오 부분 통과
          main 10파일 · test 7파일 · 단위 테스트 89개 green (생애주기 매트릭스 감사 완료)
          :sample 검증 앱 — 실기기 전제 검증용 조작 UI + 이벤트 로그 뷰
배포      kr.co.nucode:ble-core:0.1.0 — publishToMavenLocal 동작 확인
설계결정   2026-08-27 이연 review 발견 해소 — 타임아웃=포기 · 자동 재구독 ·
          BT off 스윕 · GATT 락 통합 · 권한 미선언/미허용 구분 (설계 §12)
앱 조절    2026-08-27 개방 — ScanFilter(manufacturerData·serviceData) ·
          requestedMtu · reconnectPolicy · operationTimeoutMillis (설계 §5)
문서      설계 완료 (docs/ble-core-design.md)
          2026-08-27 백그라운드를 v0.1 범위에서 제외 — §2 근거 교체,
          §7 축소, §8 삭제(§9~13 → §8~12), 규칙 6·7 신설
          2026-08-27 §6 에 다이어그램 4종 추가 (레이어 · 소유 · flow · 경계)
          2026-08-27 §12-10 실측 정정 — 배포 파이프라인은 이미 현역이었다
git       아직 init 안 함
다음      플랫폼 호출 TODO 22개 채우기 → 실기기 검증
```

### 지금 되는 것 / 안 되는 것

**플랫폼 호출이 전부 배선됐고 핵심 정상 경로의 실기기 검증이 부분 완료됐다.**

```
되는 것    스캔 · 연결 · GATT 탐색 · MTU(실패 폴백 포함) · write 청킹+ack ·
          구독(CCCD ack) · 재연결(끊김 구분 · retryCount 리셋) — 코드 경로 완결
          단위 테스트 42개가 배선(emit 순서 · 가드 · dropped · ack 취소)을 회귀로 고정
          외부 앱 소비 검증 완료 (kr.co.nucode:ble-core:0.1.0, mavenLocal)

안 된 것   복수 OEM/API level, RF loss·GATT status·Bluetooth/권한 장애 주입,
          indication·late callback 등 전체 device matrix
          매니페스트가 권한을 선언하지 않는다 — 앱이 직접 선언 (파일 주석에 목록)
```

### 배포

```
좌표      kr.co.nucode:ble-core:0.2.2 (local build only)
          ★ 기존 kr.co.nucode:ble (1.0.0~1.2.5) 와 의도적으로 다른 artifactId
            설계 §0 비목표 "마이그레이션이 아니라 대체다" — 병존하려면 좌표가 달라야 한다
발행 대상  mavenLocal() 하나. 이 머신에서만 해석된다
          다른 개발자 / CI 는 아직 안 됨 — 원격 저장소 미정 (설계 §12-10)
api 스코프 kotlinx-coroutines 는 api 로 선언한다
          공개 API 가 Flow / StateFlow 를 노출하므로 implementation 이면
          소비자가 events 를 collect 조차 못 한다
```

### 프로젝트 구조

```
nucode_ble_core/
  core/                      라이브러리 모듈  (namespace kr.co.nucode.ble)
    api/ble-core.api         0.2.x Kotlin-public API의 JVM member signature 기준
    src/main/kotlin/kr/co/nucode/ble/
    src/test/kotlin/kr/co/nucode/ble/
    src/main/AndroidManifest.xml    권한 선언 없음 — uses-feature 만 (앱이 권한을 선언한다)
  sample/                    실기기 검증용 샘플 앱  (:core project 의존, 의존성 0 View UI)
  docs/v1/README.md          목표 v1 설계 정본 (아직 구현 완료 상태 아님)
  docs/ble-core-design.md    역사적 설계 근거 · 2026-08 구현 스냅샷
  docs/ble-core-diagrams.md  2026-08 현재 구현 구조·흐름 스냅샷
  README.md

AGP 8.13.2 · Kotlin 2.0.21 · Gradle 8.13 · compileSdk 36 · minSdk 24 · JVM 11
의존성: kotlinx-coroutines 만. androidx / compose 없음
```

**:sample 은 검증 도구다, 소비자가 아니다.** 실기기 전제 항목(.plans 미해결 절)을 조작으로 재현하는 판이며, API 설계의 입력으로 삼지 않는다 — 두 번째 소비자가 API 를 왜곡하는 문제(설계 §10)는 여전히 경계한다.
매니페스트에 **Foreground Service 선언이 없다.** 앱이 소유한다 (설계 §7).

### 실기기 자동 테스트

전용 peripheral 펌웨어가 올라간 보드와 ADB로 연결된 Android 기기가 필요하다. 테스트는 NUS service UUID로 보드를 찾고 다음 항목을 검증한다.

- 600 byte write with response / without response 및 peripheral 수신 결과
- 같은 기기에 대한 중복 connect 억제
- peripheral 강제 연결 해제 후 자동 재연결·구독 복원
- 중복 disconnect 호출이 코어의 disconnect 전이 1회로 처리되고 세션을 다시 사용할 수 있는지
- Android bond 생성·재연결 유지·firmware bond reset·재본딩

Pixel 5a/Android 14와 nRF52840 통합 테스트 firmware 조합에서는 위 시나리오가 통과했다.
이는 전체 OEM/API-level matrix 완료를 뜻하지 않는다. 상세 결과와 남은 범위는
[실기기 검증 기록](docs/v1/android-real-device-results.md)을 본다.

하드웨어 테스트는 명시적으로 활성화한 경우에만 실행된다.

```bash
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true
```

주변에 같은 NUS 보드가 여러 대면 대상 Bluetooth 주소를 고정한다.

```bash
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true \
  -Pandroid.testInstrumentationRunnerArguments.blePeripheralId=F0:2F:24:A8:0F:9E
```

보드가 없는 일반 빌드와 계측 테스트에서는 해당 테스트가 skip된다.

---

## 확정된 결정 — 재논의하려면 근거를 대라

이 목록은 결정을 얼리기 위한 게 아니라 **되짚는 비용을 물리기 위한 것**이다.
반대하려면 "무엇이 바뀌었는지"를 한 줄로 대야 한다.

| 결정 | 이유 |
|---|---|
| **네이티브 2개** (Kotlin + Swift). KMP 아님 | 기존 자산이 네이티브고 팀·툴체인이 플랫폼별로 갈려 있다 |
| **central 전용** | peripheral을 한 코어에 넣으면 상태 모델이 두 배가 된다 |
| **백지 구현.** 기존 4개 코드 재사용 안 함 | 새 전제가 기존과 정반대라 옮기면 주변 타입이 딸려온다 (설계 §11) |
| **기존 기록을 사전에 훑지 않음** | 축적이 얕고(15~40커밋), 앵커링 위험, 함정 일부가 옛 구조 산물 (설계 §11) |
| **Flutter 후순위** | 코어 없이 브리지를 만들면 자체 구현이 생긴다 — `ble-package`가 그랬다 |
| **FGS는 라이브러리 밖.** 앱이 소유 | 매니페스트 강제 · 앱당 FGS 하나 · iOS 대칭 (설계 §7의 6가지) |
| **공개 API는 ID 기반, 내부는 객체** | 수명이 불명확한 쪽을 향하는 면이면 ID. flutter_blue_plus도 같은 형태 |
| **단일 이벤트 스트림** | 타입별로 쪼개면 브리지에 채널 관리 로직이 생긴다 |
| **백그라운드는 v0.1 범위 밖** | 터미널이 요구 안 함. 코어의 값(재사용·1회해결)에 기여 안 함 (설계 §0) |
| **`snapshot()` 은 남긴다** | 백그라운드가 아니라 콜드 스타트 장치 — 회전 · 새 화면 · hot reload (설계 §5) |
| **코어가 자기 스코프 소유** | 구독이 구동하면 나중에 백그라운드가 추가가 아니라 재작성이 된다 (규칙 6) |
| **이벤트 방출 지점 한 곳** | 확장이 끼는 앵커. 흩어지면 끼울 자리가 세 곳이 된다 (규칙 7) |
| **상태 기계 없음** (Action/Reducer/Store 금지) | SDK가 가지면 소비자가 하나 더 만든다 — 앱 store 716줄이 그 결과 |
| **도메인은 코어 밖** | 프로토콜·판정·알림이 코어에 들어가면 다음 제품에 짐이 된다 |
| **iOS 복원은 통로만 3줄** | 생성 시점 옵션이라 코어만 넘길 수 있다. 안 뚫으면 나중에 못 뚫는다 (설계 §5) |
| **검증 드라이버 = 터미널 앱** | 도메인이 없어 도메인 침투를 구조적으로 막는다 |
| **배포 경로는 미룸** | Flutter 플러그인이 붙는 시점에 필요. 그전까지 로컬 소스 의존 |

---

## 규칙 — 유일한 게이트

리뷰에서 예/아니오로 묻는다. 자세한 근거는 설계 §1 · §3.

```
원인 4개에서 나온 것 — 깨지면 v0.1이 틀린다
  1. 상태 기계 없음            Action / Reducer / Store 금지
  2. 기기별 상태는 객체 하나에   sessions: Map[DeviceId, Session] — 맵은 이것 하나
  3. 도메인 없음               프로토콜 · 판정 · 알림은 코어 밖
  4. 진입점 한 겹              BleClient 하나 + peripheral view BleSession. Manager 금지
  5. 공개 API는 ID 기반         + 단일 이벤트 스트림 + snapshot(). 내부는 객체

확장 보존 — 깨져도 v0.1은 돌지만 백그라운드가 재작성이 된다
  6. 코어가 자기 스코프 소유     구독이 동작을 구동하지 않는다
                              ★ GATT를 callbackFlow로 감싸면 깨진다
  7. 이벤트 방출 지점 한 곳      내부 emit(e) 하나로. 각자 emit 금지

생애주기 — 상태 x 이벤트 교차의 구멍이 실제 결함 클래스였다 (감사 2026-08-27)
  8. delay/await 품은 launch 는 손잡이 필수   grep 게이트
  9. Session 필드 추가 = 생애주기 표 갱신     설계 §6 표에 없으면 미완성

이동·개명 — 테스트가 소스를 경로 문자열로 읽는다 (2026-09-22)
  10. 파일 이동·개명 전 grep 게이트        core/src/test 에서 경로·파일명·패키지명을 검색한다
                              이동 후에도 그 테스트가 의도한 대상을 검사하는지 확인한다
                              부재를 단언하는 검사는 대상을 잠깐 되살려 실패하는지 본다
```

```
참고치   1,100 ~ 1,600줄 / 10 ~ 15파일
         목표가 아니다. 넘으면 위 일곱 중 뭐가 깨졌는지 보는 온도계
```

---

## 다음 작업

```
0  Gradle 스캐폴딩            [완료]
1  코어 스켈레톤               [완료] 배선 실동작 · 플랫폼 호출 TODO
1b 플랫폼 호출 채우기          [완료] TODO 0 · review 결함 7건 해소
2  터미널 Android 로 검증      FGS 불필요 — 규칙 6은 구독 취소로 검증한다
3  Swift 구현                 ../ios/ (별도 저장소) + 복원 통로 3줄 (설계 §5)
4  터미널 iOS + 로그 diff
5  Flutter 채널층
```

---

## 착수 전 물어볼 것 (blocking)

```
없음. 백그라운드가 v0.1 범위를 벗어나면서 마지막 blocking 질문
("돌려야 하는 로직이 수동인가 능동인가")이 소멸했다 — 둘 다 코어 밖이다
```

미결정은 설계 §12. v0.1 안에서 정해야 할 것 5개와 나중 것 6개로 갈라뒀다.

---

## 구현하며 정해서 여기 채울 것

지금 추측으로 쓰면 구현이 다르게 나올 때 문서가 거짓말이 된다. **비어 있는 게 맞다.**
Kotlin에서 정하고 Swift가 그걸 따른다.

```
☑ events 백프레셔 정책     SUSPEND + tryEmit + drop 카운터로 확정
                          실측: 수집자 0 이면 블로킹 없음, 버퍼 초과 시 tryEmit=false
                          DROP_OLDEST 대조군은 전부 true — 유실이 조용해진다
                          (EventEmissionTest 가 이 선택을 고정한다)
□ 동시성 · 스레드 계약     coreScope 는 규칙 6이 정했다. 나머지 —
                          상태 고정 디스패처 / 콜백 스레드 /
                          구독 취소 시 CCCD 해제 / write 큐 단위
□ Session 생명주기        disconnect 후 남나 지워지나 / 재연결 중 / GC 시점
□ 청킹 실패 시맨틱         터미널 문서 §4-1 에 있는 것을 여기로 옮기기
□ 재시도 기본값            133 몇 번 · 백오프 곡선 · 타임아웃
```
