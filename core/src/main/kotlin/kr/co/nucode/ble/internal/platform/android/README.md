# internal/platform/android — 패키지 규칙

이 패키지는 **Android framework adapter**를 모으는 곳이다. 크로스플랫폼 경계가 아니다 —
`core` 모듈은 Android 전용이고 iOS는 별도 Swift Package다.

## 무엇이 여기 오나

**adapter** — Android API 호출, Android callback → 내부 이벤트(`GattDriverEvent`,
`ScanObservation`) 변환, 플랫폼 상수(`Manifest.permission.*`, `Build.VERSION_CODES.*`)에 따른
판정. `BluetoothGattCallback`·`ScanCallback` 을 상속하는 코드는 전부 여기다.

**orchestration** — 연결 단계, 작업 순서, 취소·정리 정책. `internal/` 에 남는다.
`GattBridge`, `BleRuntime`, `Session`, `Reconnector`, `OperationQueue`, `Scanner`.

## 이 규칙이 뜻하지 않는 것

- **orchestration의 Android 의존은 남아 있다.** `BleRuntime` 은 어댑터 on/off 브로드캐스트를 받는
  `BroadcastReceiver`·`IntentFilter` 를(어댑터 조회·권한 판정 입력은 `AndroidCentral` 로 위임했다 — 단
  Context 없는 테스트 생성자 경로의 `Build.VERSION.SDK_INT` 읽기는 `BleRuntime` 에 남는다),
  `GattBridge` 는 `BluetoothGatt*`·`BluetoothProfile` 을, `Session` 은 `BluetoothGatt` 핸들을 직접 쓴다. `GattDriver` 인터페이스 시그니처에도
  `BluetoothGatt` 가 있다. 이 패키지 정리는 그 의존을 제거하지 않는다 — 현재 예외로 명시한다.
- **다른 패키지의 Android 상수 참조를 금지하지 않는다.** `GattBridge` 의 `GATT_SUCCESS`·
  `STATE_CONNECTED` 가 그 예다.
- 공개 Android 진입점(`AndroidBleClientFactory`, `AndroidBondController`)은 `kr.co.nucode.ble.android`
  에 둔다. 이 패키지는 internal 전용이다.

## 파일을 옮기거나 개명하기 전에

루트 README 규칙 10번(grep 게이트)을 따른다. 이 저장소의 테스트는 소스를 **경로 문자열로
읽는다** — `GattDriverSeamTest` 가 `AndroidGattCallbackRouter.kt` 를, `PublicApiSurfaceTest` 와
`LegacyBleCoreCompatibilityTest` 가 `PublicModels.kt`·`BleClient.kt`·`BleSession.kt` 를,
`BleClientContractTest` 가 `kr/co/nucode/ble/` 아래 상대 경로로 받은 파일을 텍스트로 검사한다.
