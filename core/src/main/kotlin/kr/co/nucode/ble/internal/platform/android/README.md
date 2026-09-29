# internal/platform/android — 패키지 규칙

이 패키지는 **Android framework adapter**를 모으는 곳이다. 크로스플랫폼 경계가 아니다 —
`core` 모듈은 Android 전용이고 iOS는 별도 Swift Package다.

## 무엇이 여기 오나

**adapter** — Android API 호출, Android callback → 내부 이벤트(`GattDriverEvent`,
`ScanObservation`) 변환, 플랫폼 상수(`Manifest.permission.*`, `Build.VERSION_CODES.*`)에 따른
판정. `BluetoothGattCallback`·`ScanCallback` 을 상속하는 코드는 전부 여기다.

**orchestration** — 연결 단계, 작업 순서, 취소·정리 정책. `internal/` 루트와 기능별 하위 패키지(`session/`·`connection/`·`gatt/`·`scan/`)에 남는다.
`GattBridge`(+ `GattConnectionLifecycle` → `ConnectStarter`·`LinkCallbacks`·`LinkEffects`, `GattOperationEngine` → `OperationExecutor`·`OperationCallbacks`·`SubscriptionCommands`), `BleRuntime`, `Session`, `OperationQueue`, `Scanner`.

## 이 규칙이 뜻하지 않는 것

- **orchestration 의 Android 의존은 `android.bluetooth` 밖의 것만 남아 있다.** `AdapterMonitor`(`internal/adapter/`)는
  Context 없는 테스트 생성자 경로에서 `Build.VERSION.SDK_INT` 를 읽고, `BleRuntime` 은 `Context`·`AndroidCentral`·Driver 를 알지 못하고 포트(`GattDriver`·`PlatformScanPort`·`AdapterSource`)만 받는다.
  구체 Driver 는 Factory 조립(`kr.co.nucode.ble.android.androidRuntime`)이 `Context` 로 `AndroidCentral` 하나를 만들어 나눠 주며 만드는 유일한 곳이다.
  어댑터 on/off 브로드캐스트 리시버는 `AndroidAdapterSource` 가 소유한다.
- **`android.bluetooth` 의존은 플랫폼 어댑터 패키지에만 있다.** 이 패키지와 공개 `kr.co.nucode.ble.android` 밖에는 `android.bluetooth` import 가 없다(`PackageStructureFitnessTest` 가 강제). GATT 포트(`GattDriver`)는
  불투명 핸들 `GattHandle`·`PlatformCharacteristic`·`PlatformDescriptor`(`internal/model/`)만 주고받고, 상태·쓰기 상수는
  `gatt/GattPlatformTypes.kt`(`GattStatus`·`LinkState`·`GattWriteTypes`)에 둔다. 어댑터 조회는 `AdapterSource` 포트(`AndroidAdapterSource` 구현)로 받는다.
- 공개 Android 진입점(`AndroidBleClientFactory`, `AndroidBondController`)은 `kr.co.nucode.ble.android`
  에 둔다. 이 패키지는 internal 전용이다.

## 파일을 옮기거나 개명하기 전에

루트 README 규칙 10번(grep 게이트)을 따른다. 이 저장소의 테스트는 소스를 **경로 문자열로
읽는다** — `GattDriverSeamTest` 가 `AndroidGattCallbackRouter.kt` 를, `PublicApiSurfaceTest` 와
`LegacyBleCoreCompatibilityTest` 가 `PublicModels.kt`·`BleClient.kt`·`BleSession.kt`·`ClientEvents.kt`·`internal/event/BleEvent.kt` 를,
`BleClientContractTest` 가 `kr/co/nucode/ble/` 아래 상대 경로로 받은 파일을 텍스트로 검사한다.
