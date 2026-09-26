# nucode BLE Core 개발자 가이드 (0.2.0)

이 문서는 nucode BLE Core 를 처음 보는 Android 개발자가 이 문서 하나만 따라 해서 앱에 붙일 수 있도록 쓴 안내서입니다.
설치부터 스캔 · 연결 · 읽기 · 쓰기 · 알림 수신 · 실패 처리 · 화면 연동까지 순서대로 다룹니다.

> **예시 코드에 대해.** 이 문서의 Kotlin 예시는
> [`sample/.../GuideSnippets.kt`](../sample/src/main/kotlin/kr/co/nucode/ble/sample/GuideSnippets.kt)와
> [`GuideUiSnippets.kt`](../sample/src/main/kotlin/kr/co/nucode/ble/sample/GuideUiSnippets.kt)의
> 같은 이름 `// region guide:…` 블록과 글자 그대로 같고, `./gradlew :sample:assembleDebug` 로 컴파일이 확인됩니다.
> 지면을 아끼려고 `import` 문은 뺐습니다. 필요한 import 는 두 파일 상단에 있습니다.
> 예시 UUID(Nordic UART Service, Battery, Heart Rate)는 **예시**입니다. 실제 기기의 UUID 로 바꿔 쓰세요.

## 목차

1. [소개](#1-소개)
2. [설치](#2-설치)
3. [5분 빠른 시작](#3-5분-빠른-시작)
4. [핵심 개념](#4-핵심-개념)
5. [유즈케이스 레시피](#5-유즈케이스-레시피)
6. [자주 틀리는 것 / FAQ](#6-자주-틀리는-것--faq)
7. [한계와 검증 범위](#7-한계와-검증-범위)

---

## 1. 소개

### 무엇인가

nucode BLE Core 는 Android 앱이 BLE 기기(peripheral)에 **central** 로 붙을 때 쓰는 Kotlin 라이브러리입니다.
Android 의 `BluetoothGatt` 를 직접 쓰면 한 번에 GATT 작업 하나만 보낼 수 있다는 제약, 콜백으로 흩어지는 흐름,
숫자뿐인 status 코드, 끊긴 뒤 복구 같은 문제를 앱이 모두 떠안게 됩니다. 이 라이브러리는 그 일을 코루틴 API 뒤에서 대신 처리합니다.

### 주요 기능

| 기능 | 내용 |
| --- | --- |
| 코루틴 API | 명령은 `suspend` 함수, 상태는 `StateFlow`, 수신 값은 `Flow` 로 다룹니다. |
| GATT 작업 줄 세우기 | 기기(세션)마다 read · write · 구독을 먼저 온 순서대로 하나씩 실행합니다. 여러 코루틴에서 동시에 불러도 됩니다. |
| 긴 write 자동 분할 | 협상된 MTU 에 맞춰 잘라 보내고, 성공하면 `WriteReceipt`, 도중에 실패하면 어디까지 갔는지(`WriteProgress`)를 알려 줍니다. |
| 타입이 있는 실패 | 모든 실패는 `BleException(failure: BleFailure)` 입니다. 바뀌지 않는 문자열 `code` 와 재시도 가능성(`Retryability`)으로 분기합니다. |
| 연결 단계 관리 | 서비스 탐색과 MTU 협상을 코어가 하고, 진행 상황을 `ConnectionState`(`Connecting → DiscoveringServices → Configuring → Ready`)로 보여 줍니다. |
| 재연결과 구독 복원 | `autoReconnect = true` 면 링크가 끊겨도 OS 가 다시 붙일 때까지 기다리고, 블루투스가 다시 켜지면 한 번 다시 연결합니다. 켜 둔 구독(notify)은 새 연결마다 `Ready` 전에 자동으로 다시 켭니다. |
| 권한 · 어댑터 상태 | `AdapterState` 하나로 "지금 BLE 를 쓸 수 있는가"를 표현합니다. 매니페스트 선언 누락과 런타임 권한 미허용을 구분합니다. |
| 진단 로그 | 선택형 `BleLogger` 로 구조화된 진단 기록을 받습니다. 기본값은 페이로드 원문을 남기지 않습니다. |
| 본딩 | Android 전용 `AndroidBondController` 로 본딩(페어링)을 요청하고 결과를 기다립니다. |

### 하지 않는 것

- **peripheral(서버) 역할과 광고 송출.** central 전용입니다.
- **앱 수준 프로토콜 · 암호화 · 파싱.** 바이트를 주고받을 뿐입니다. 링크 암호화는 본딩으로 Android 가 처리합니다.
- **Foreground Service 와 백그라운드 정책.** 앱이 소유합니다.
- **권한 선언과 권한 요청 UI.** 앱이 선언하고 요청합니다.
- **라이브러리 자체 재연결 시간표(백오프 · 재시도 횟수).** 0.2.0 에서 없앴습니다. OS autoConnect(`autoReconnect = true`)나 앱이 맡습니다.
- **MTU 값 지정, 광고 원본 바이트, Android `autoConnect` 플래그 직접 노출.** 공통 API 에 없습니다.
- **원격 저장소 배포.** 아직 로컬 Maven 뿐입니다.

### 지원 환경

| 항목 | 값 |
| --- | --- |
| minSdk | 24 (Android 7.0) |
| compileSdk (라이브러리 빌드) | 36 |
| 의존성 | `kotlinx-coroutines-android` 1.9.0 (`api` 로 전달되므로 앱에서 따로 선언하지 않아도 됩니다) |
| 라이브러리 빌드 도구 | AGP 8.13.2 · Kotlin 2.0.21 · JVM 11 |
| 좌표 | `kr.co.nucode:ble-core:0.2.0` — 기존 `kr.co.nucode:ble`(1.x)와 **다른** artifactId 입니다 |

---

## 2. 설치

### 2.1 로컬 Maven 에 배포하기

라이브러리 저장소에서 한 번 실행합니다.

```bash
./gradlew :core:publishReleasePublicationToMavenLocal
```

`~/.m2/repository/kr/co/nucode/ble-core/0.2.0/` 에 aar · sources.jar · pom · module 이 생깁니다.
원격 저장소가 아직 없어서 **이 명령을 실행한 컴퓨터에서만** 받을 수 있습니다. 다른 개발자나 CI 는 각자 배포해야 합니다.

### 2.2 앱에 추가하기

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal() // 0.2.0 은 로컬 Maven 에만 있습니다
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("kr.co.nucode:ble-core:0.2.0")
}
```

### 2.3 권한

**라이브러리 매니페스트는 권한을 하나도 선언하지 않습니다.** 선언하는 것은 아래 한 줄뿐입니다.

```xml
<!-- core/src/main/AndroidManifest.xml (라이브러리) -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

`required="false"` 라서 BLE 가 없는 기기에도 앱을 설치할 수 있습니다. BLE 가 필수인 앱이면 앱 매니페스트에서 `required="true"` 로 올립니다.
권한은 앱 매니페스트에 직접 선언합니다. 앱 `targetSdk` 가 31 이상이면 샘플 앱의 선언을 그대로 쓰면 됩니다.

```xml
<!-- app/src/main/AndroidManifest.xml (앱) — <manifest> 에 xmlns:tools 가 필요합니다 -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

**위 선언은 앱 `targetSdk` 31 이상이 전제입니다.** targetSdk 가 30 이하면 코어는 Android 12 이상 기기에서도 옛 방식(아래 표 두 번째 줄)으로 판정합니다.
그런데 `maxSdkVersion="30"` 이 붙은 권한은 API 31 이상 기기에서 선언되지 않은 것으로 취급되므로, 이 선언 그대로면 `Unauthorized(canRequest = false)` 가 됩니다.
targetSdk 30 이하 앱은 `BLUETOOTH` · `BLUETOOTH_ADMIN` · `ACCESS_FINE_LOCATION` 을 `maxSdkVersion` **없이** 선언하고, 런타임에는 `ACCESS_FINE_LOCATION` 을 요청하세요.

코어는 실행 중인 기기의 API 레벨에 따라 아래처럼 판정합니다.

| 기기 API 레벨 | 매니페스트에 있어야 하는 것 | 런타임에 요청할 것 | 코어가 `Ready` 로 보는 조건 |
| --- | --- | --- | --- |
| 31 이상 (앱 targetSdk 31 이상) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | 둘 다 | 둘 다 허용 |
| 29 ~ 30 (또는 31 이상인데 targetSdk 30 이하) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (targetSdk 30 이하 앱이 31 이상 기기에서 쓰려면 `maxSdkVersion` 없이) | `ACCESS_FINE_LOCATION` | FINE 위치 허용 |
| 24 ~ 28 | `BLUETOOTH`, `BLUETOOTH_ADMIN`, 그리고 FINE 또는 COARSE 위치 | 선언한 위치 권한 | 선언한 위치 권한 중 하나 허용 |

- `BLUETOOTH` · `BLUETOOTH_ADMIN` 은 설치할 때 주어지는 권한이라 선언 여부만 봅니다.
- 필요한 선언이 하나라도 빠지면 `AdapterState.Unauthorized(canRequest = false)` 입니다. 권한을 요청해도 소용없는 **앱 빌드 문제**입니다.
- 선언은 됐는데 허용이 안 됐으면 `Unauthorized(canRequest = true)` 입니다. 이때 권한을 요청합니다.
- "다시 묻지 않음"으로 영구 거부됐는지는 코어가 판단하지 않습니다. `Activity` 가 필요해서 앱이 `shouldShowRequestPermissionRationale` 로 판단합니다.
- 스캔 결과로 위치를 추정하지 않는다면 `neverForLocation` 을 붙이세요. 빼면 Android 12 이상에서도 스캔 결과에 위치 권한이 필요한데,
  코어는 이 플래그를 읽지 않으므로 이 경우를 `Ready` 로 잘못 판정할 수 있습니다(코어 주석에 적힌 알려진 한계).
- Android 11 이하에서는 기기의 위치(GPS) 설정이 꺼져 있으면 스캔 결과가 오지 않는 것으로 알려져 있습니다. 코어는 이 설정을 검사하지 않습니다(이 동작은 이 저장소에서 검증하지 않았습니다).
- `AndroidBondController`(본딩)는 Android 12 이상에서 `BLUETOOTH_CONNECT` 가 허용돼 있어야 합니다. 허용이 없으면 `SecurityException` 이 그대로 올라옵니다.

런타임 요청에 쓸 권한 목록과 상태별 안내 문구입니다.

```kotlin
/** 런타임에 요청할 권한(앱 targetSdk 31 이상 기준). */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** 사용자에게 보여 줄 안내 문구. 쓸 수 있는 상태(Ready)면 null. */
fun adapterMessage(state: AdapterState): String? = when (state) {
    AdapterState.Ready -> null
    AdapterState.PoweredOff -> "블루투스를 켜 주세요"
    AdapterState.Resetting -> "블루투스가 다시 시작되는 중입니다"
    AdapterState.Unsupported -> "이 기기는 BLE 를 지원하지 않습니다"
    is AdapterState.Unauthorized ->
        if (state.canRequest) "블루투스 권한을 허용해 주세요" else "매니페스트에 권한 선언이 빠졌습니다(앱 빌드 문제)"
}
```

> **중요 — `adapterState` 는 권한 변경을 스스로 알지 못합니다.**
> `adapterState` 가 다시 계산되는 때는 두 경우뿐입니다. (1) 블루투스 켜짐/꺼짐 방송을 받았을 때,
> (2) `startScan` · `connect` · `read` · `write` · `subscribe` 를 호출한 첫머리.
> 그래서 권한을 받은 직후 `adapterState.first { it == AdapterState.Ready }` 로 기다리면 영원히 기다릴 수 있습니다.
> 권한 결과를 받으면 곧바로 `startScan` 같은 명령을 부르세요. 그 호출이 상태를 다시 판정하고, 여전히 안 되면 `PERMISSION_DENIED` 를 던집니다.

---

## 3. 5분 빠른 시작

가장 작은 전체 흐름입니다. 스캔 → 연결 → 알림 구독 → 쓰기 → 해제까지 한 번에 봅니다.

**① 앱 전체에서 `BleClient` 하나를 만듭니다.** `Application` 에 두고, 매니페스트의 `android:name` 에 등록합니다.

```kotlin
class MyApplication : Application() {
    lateinit var bleClient: BleClient
        private set

    // 화면보다 오래 살아야 하는 정리 작업(예: ViewModel 이 사라질 때 연결 해제)용
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // 프로세스당 하나만 만듭니다. Activity·ViewModel 마다 만들지 않습니다.
        bleClient = AndroidBleClientFactory.create(this)
    }
}
```

**② 대상 기기의 UUID 를 정합니다.** 여기서는 Nordic UART Service(NUS)를 예로 듭니다.

```kotlin
// 예시 UUID: Nordic UART Service(NUS). 실제 기기의 UUID 로 바꿉니다.
val NUS_SERVICE = ServiceRef(BleUuid(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")))
val NUS_RX = CharacteristicRef(NUS_SERVICE, BleUuid(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"))) // 앱 → 기기(write)
val NUS_TX = CharacteristicRef(NUS_SERVICE, BleUuid(UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"))) // 기기 → 앱(notify)
```

**③ 흐름 전체.** 권한이 허용되고 블루투스가 켜진 상태에서 `quickStart((application as MyApplication).bleClient)` 처럼 부릅니다.

```kotlin
suspend fun quickStart(client: BleClient): Unit = coroutineScope {
    // 1) 스캔: NUS 서비스를 광고하는 첫 기기를 10초 안에 찾습니다.
    client.startScan(ScanRequest(BleScanFilter(serviceUuids = listOf(NUS_SERVICE.uuid))))
    val found = try {
        withTimeout(10_000) { client.scanResults.first() }
    } finally {
        client.stopScan()
    }

    // 2) 연결: connect() 는 연결을 "시작"하면 돌아옵니다. Ready 는 따로 기다립니다.
    val session = client.connect(found.peripheralId)
    val settled = withTimeout(40_000) {
        session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
    }
    check(settled == ConnectionState.Ready) { "연결 실패: $settled" }

    // 3) notify: 수집을 먼저 걸고 나서 구독합니다.
    val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
        session.notifications(NUS_TX).collect { bytes -> Log.i("ble", "수신: ${bytes.decodeToString()}") }
    }
    session.subscribe(NUS_TX)

    // 4) write
    val receipt = session.write(NUS_RX, "hello".encodeToByteArray())
    Log.i("ble", "보냄: ${receipt.bytesCompleted} bytes / ${receipt.chunksCompleted} chunks")

    // 5) 정리: 수집 중지 → 연결 해제. client 는 닫지 않습니다(앱 수명 동안 유지).
    delay(2_000)
    receiver.cancel()
    session.disconnect()
}
```

꼭 기억할 세 가지입니다.

1. **`connect()` 는 연결을 "시작"하면 돌아옵니다.** 서비스 탐색 · MTU 협상 · 구독 복원이 끝난 상태가 `Ready` 이고,
   그 전에 read/write/subscribe 를 부르면 `NOT_READY` 로 실패합니다. 반드시 `connectionState` 로 `Ready` 를 기다리세요.
   `Ready` 만 기다리면 실패했을 때 영원히 기다리므로 `Disconnected` 도 함께 기다립니다.
2. **수집을 먼저 걸고 `subscribe()` 합니다.** `notifications()` 는 수집하는 동안 들어온 값만 받습니다.
3. **`client` 는 닫지 않습니다.** 연결 하나를 끝낼 때는 `session.disconnect()` 입니다. `client.close()` 는 되돌릴 수 없습니다.

---

## 4. 핵심 개념

### 4.1 `BleClient` 와 `BleSession`

| | `BleClient` | `BleSession` |
| --- | --- | --- |
| 무엇 | 라이브러리 진입점. 프로세스당 하나 | 기기 하나를 보는 창 |
| 만드는 법 | `AndroidBleClientFactory.create(context, config)` | `client.connect(id)` 또는 `client.session(id)` |
| 하는 일 | 어댑터 상태, 스캔, 이벤트, 스냅샷, 전체 종료 | 연결 · 해제, read · write, 구독, 기기별 상태 |
| 수명 | `close()` 하면 끝(되살릴 수 없음) | 가벼운 뷰. 같은 `PeripheralId` 로 여러 번 얻어도 같은 상태를 봅니다 |

- `client.connect(id, options)` 는 `client.session(id).connect(options)` 와 같고, 세션을 돌려줍니다.
- `client.session(id)` 는 연결하지 않고 세션만 얻습니다(일시 중단 함수가 아닙니다). 연결 전 상태 관찰이나 이미 연결된 기기의 세션을 다시 얻을 때 씁니다.
- 한 번 `session()`/`connect()` 에 넘긴 `PeripheralId` 는 `close()` 전까지 클라이언트에 남습니다(`snapshot().sessions` 에 보입니다).

### 4.2 `PeripheralId`

기기를 가리키는 불투명한 ID 입니다. Android 에서는 Bluetooth 주소 문자열(`"AA:BB:CC:DD:EE:FF"` 형식, 대문자)입니다.

- 스캔 결과의 `ScanResult.peripheralId` 를 그대로 쓰세요. 저장할 때는 `peripheralId.value` 를 저장하고 `PeripheralId(saved)` 로 되살립니다.
- **저장 · 복원은 대문자 MAC 그대로 합니다.** `PeripheralId` 는 값을 바꾸지 않고, Android 의 `getRemoteDevice` 는 대문자 주소만 받습니다. 다른 곳에서 받은 주소면 `PeripheralId(address.uppercase())` 로 만드세요.
- 주소가 틀리면 `connect()` 가 결국 실패하지만 바로 실패하지는 않습니다. 처음 보는 주소라 먼저 아래의 주소 찾기 스캔을 거치기 때문입니다.
  MAC 형식이 아니면 스캔 없이 `Diagnostic`(`SCAN_START_FAILED`)을 남긴 뒤 `UNKNOWN_DEVICE` 로 실패합니다.
  소문자 MAC 이면 스캔(최대 `rediscoveryTimeoutMillis`)은 그 기기를 찾지만 연결 단계에서 `UNKNOWN_DEVICE` 로 실패합니다. 기기가 광고하지 않으면 그 시간을 다 기다린 뒤 `PERIPHERAL_NOT_FOUND` 입니다(`autoReconnect = true` 면 진단만 남기고 `UNKNOWN_DEVICE`).
- 이 클라이언트가 최근에 본 적 없는 주소(앱 재시작 뒤 저장된 ID 등)로 연결하면, 코어가 먼저 그 주소만 찾는 짧은 스캔(기본 3초, `BleConfig.rediscoveryTimeoutMillis`)을 돌립니다.
  못 찾으면 `autoReconnect = false` 는 `PERIPHERAL_NOT_FOUND` 를 던지고, `autoReconnect = true` 는 진단(`Diagnostic`)만 남기고 OS 대기로 넘어갑니다.

### 4.3 `ServiceRef` · `CharacteristicRef` · `instanceIndex`

```text
CharacteristicRef(
    service = ServiceRef(uuid, instanceIndex = 0),   // 같은 UUID 서비스 중 몇 번째인가(탐색 순서, 0부터)
    uuid = 캐릭터리스틱 UUID,
    instanceIndex = 0,                               // 그 서비스 안에서 같은 UUID 캐릭터리스틱 중 몇 번째인가
)
```

- 대부분의 기기는 UUID 가 겹치지 않으므로 `instanceIndex` 는 기본값 0 그대로 씁니다.
- 탐색 결과(`session.services`)에 없는 참조로 read/write/subscribe 하면 `CHARACTERISTIC_NOT_FOUND` 입니다.
- UUID 는 `BleUuid(java.util.UUID)` 로 감쌉니다. 16비트 SIG UUID 는 `0000xxxx-0000-1000-8000-00805F9B34FB` 형태로 씁니다.

### 4.4 `ConnectionState` 생애주기

```text
Disconnected(Initial)
     │ connect()
     ▼
Connecting ──► DiscoveringServices ──► Configuring ──► Ready
     │                 │                    │ (MTU 협상 → 구독 복원)
     └──── 실패·시간 초과 ──────────────────┴──────────► Disconnected(Failed(원인))

Ready ── disconnect() ──► Disconnecting ──► Disconnected(Requested)
Ready ── 링크 끊김 ──► autoReconnect = false : Disconnected(Failed(LINK_FAILED 등))
                  └► autoReconnect = true  : Recovering(lastFailure) ── OS 가 다시 붙이면 ──► … ──► Ready
블루투스 꺼짐 ──► Disconnected(Failed(ADAPTER_UNAVAILABLE))   (autoReconnect = true 면 다시 켜질 때 한 번 재연결)
client.close() ──► 아직 끝나지 않은 세션은 Disconnected(ClientClosed)
```

| 상태 | 의미 |
| --- | --- |
| `Disconnected(reason)` | 연결 없음. `reason` 은 `Initial`(아직 연결 시도 전), `Requested`(앱이 `disconnect()`), `ClientClosed`(`close()`), `Failed(failure)`(실패로 끝남) |
| `Connecting` | 링크를 맺는 중 |
| `DiscoveringServices` | 서비스 탐색 중 |
| `Configuring` | MTU 협상과 구독 복원 중 |
| `Ready` | read · write · subscribe 가능 |
| `Disconnecting` | 해제 정리 중 |
| `Recovering(lastFailure)` | `autoReconnect = true` 세션의 링크가 끊겨 OS 재연결을 기다리는 중. **기한이 없습니다** — 포기하려면 `disconnect()` |

단계별 기본 시간 제한(`BleConfig`)은 링크 15초, 서비스 탐색 10초, 설정 10초입니다. 넘기면 `LINK_TIMEOUT` · `SERVICE_DISCOVERY_TIMEOUT` · `CONFIGURATION_TIMEOUT` 으로 끝납니다.
**`autoReconnect = true` 연결에는 링크 시간 제한이 없습니다**(첫 연결 포함). 앱이 시간을 정해 `disconnect()` 해야 합니다.

### 4.5 `AdapterState`

| 값 | 뜻 | 앱이 할 일 |
| --- | --- | --- |
| `Ready` | 쓸 수 있음 | — |
| `PoweredOff` | 블루투스 꺼짐 | 켜 달라고 안내 |
| `Unauthorized(canRequest = true)` | 선언은 됐고 런타임 허용이 안 됨 | 권한 요청 |
| `Unauthorized(canRequest = false)` | 매니페스트 선언 누락 | 앱을 고쳐야 함 |
| `Unsupported` | 블루투스 어댑터 없음 | 기능 숨김 |
| `Resetting` | 어댑터 재시작 중 | 기다림. (현재 Android 구현은 이 값을 만들지 않습니다) |

판정 순서는 `Unsupported` → `Unauthorized` → `PoweredOff`/`Ready` 입니다. 권한이 없으면 블루투스가 꺼져 있어도 `Unauthorized` 입니다.

### 4.6 상태와 흐름: 어디서 무엇을 읽나

| 이름 | 타입 | 성질 |
| --- | --- | --- |
| `client.adapterState` | `StateFlow<AdapterState>` | 항상 최신값. 갱신 시점은 [2.3](#23-권한)의 주의 참고 |
| `client.scanState` | `StateFlow<ScanState>` | `Idle(lastFailure)` / `Scanning(request)`. 스캔이 도중에 멈춘 이유가 남습니다. 최신 값은 잃지 않지만 느린 수집자는 중간 상태를 건너뛸 수 있고, 새 수집자는 지금 값(지난 `Idle(실패)` 포함)을 바로 받습니다 |
| `client.scanResults` | `Flow<ScanResult>` | 스캔 중 **수집하고 있을 때만** 받습니다. 같은 기기가 광고할 때마다 반복해서 옵니다. 버퍼(기본 64)를 모든 수집자가 공유하고, 느린 수집자가 있으면 새 값이 버려집니다 |
| `client.events` | `Flow<BleClientEvent>` | 연결 변화 · 서비스 탐색 · 구독 복원 실패 · 진단 **알림**. 버퍼가 차면 버려질 수 있으니 상태 재구성에 쓰지 않습니다 |
| `session.connectionState` | `StateFlow<ConnectionState>` | 연결 상태의 기준값. 최신 값은 잃지 않지만 느린 수집자는 중간 상태(예: `DiscoveringServices`)를 건너뛸 수 있습니다 |
| `session.services` | `StateFlow<List<GattService>>` | 마지막 서비스 탐색 결과. 끊겨도 비워지지 않으므로 `Ready` 일 때만 믿습니다 |
| `session.activeSubscriptions` | `StateFlow<Set<CharacteristicRef>>` | **지금 연결에서** 알림이 실제로 켜진 캐릭터리스틱. 끊기면 비고, 복원에 실패한 것은 `Ready` 여도 빠집니다 |
| `session.notifications(ref)` | `Flow<ByteArray>` | 그 캐릭터리스틱의 notify/indicate 값. 수집하는 동안만 받고, 수집자마다 버퍼(기본 64)가 따로 있습니다. 연결이 끊겨도 끝나지 않으므로 수집 Job 은 앱이 취소합니다 |

이 Flow 들은 끝나지 않습니다(`close()` 뒤에도 완료되지 않고 값만 멈춥니다). `collect` 는 `withTimeout`, `first()`, Job 취소로 끝냅니다.

`events` 는 로그용으로 이렇게 씁니다.

```kotlin
/** events 는 "무슨 일이 있었는지" 로그용입니다. 현재 상태는 StateFlow 에서 읽습니다. */
fun logEvents(client: BleClient, scope: CoroutineScope): Job = scope.launch {
    client.events.collect { event ->
        when (event) {
            is OfficialBleClientEvent.Connection -> Log.i("ble", "${event.peripheralId.value} → ${event.state}")
            is OfficialBleClientEvent.Services -> Log.i("ble", "${event.peripheralId.value} 서비스 ${event.services.size}개")
            is OfficialBleClientEvent.SubscriptionRestoreFailed ->
                Log.w("ble", "구독 복원 실패 ${event.characteristic.uuid.value}: ${event.failure.code.value}")
            is OfficialBleClientEvent.Diagnostic -> Log.d("ble", "[${event.severity}] ${event.failure.code.value}")
        }
    }
}
```

### 4.7 실패: `BleException` · `BleFailure` · `FailureCode` · `Retryability`

명령이 실패하면 `BleException` 을 던집니다. 분기는 예외 클래스가 아니라 `e.failure` 로 합니다.

| `BleFailure` 필드 | 쓰임 |
| --- | --- |
| `code: FailureCode` | **분기 기준.** `failure.code.value` 는 `"LINK_TIMEOUT"` 같은 안정적인 문자열 |
| `retryability: Retryability` | 다시 해 볼 가치가 있는가 (아래 표) |
| `phase: FailurePhase` | 어느 단계에서 났나: `Validation`, `Adapter`, `Scan`, `Connect`, `Link`, `DiscoverServices`, `Configure`, `Operation`, `Close` |
| `category: FailureCategory` | 큰 분류(`environment`, `scan`, `connection`, `operation` 등). 로그용 |
| `writeProgress: WriteProgress?` | write 가 도중에 실패했을 때 어디까지 갔나 |
| `platformDetail: PlatformDetail?` | Android status 코드 등(로그용) |
| `context: FailureContext` | 세션 · 연결 세대 · 작업 ID(로그 상관관계용). 기기 주소는 들어 있지 않습니다 |
| `message: String?` | 개발자용 설명. **분기에 쓰지 않습니다** |

| `Retryability` | 뜻 |
| --- | --- |
| `TransientMayHelp` | 잠시 뒤 같은 호출을 다시 해 볼 만함 |
| `NewConnectionMayHelp` | 연결을 새로 맺은 뒤 다시 해 볼 만함 |
| `AfterEnvironmentChange` | 블루투스 켜기 · 권한 허용 · 본딩 같은 환경 변화 뒤에 다시 |
| `Never` | 다시 해도 같음. 입력이나 앱 설정을 고칠 문제 |
| `Unknown` | 분류 불가. 로그를 봅니다 |

`Retryability` 는 전송 계층에 대한 판단일 뿐입니다. **부분 전달된 write 를 자동으로 다시 보내도 된다는 뜻이 아닙니다.**

실패가 전달되는 길은 세 가지입니다.

1. **명령이 던짐** — 명령 호출 중에 확정된 실패(예: `NOT_READY`, `SCAN_ALREADY_RUNNING`). 스캔 시작 실패처럼 `Diagnostic` 이 함께 나가는 것도 있습니다.
2. **상태에 남음** — 명령 밖에서 생긴 실패. `Disconnected(Failed(failure))`, `Recovering(lastFailure)`, `ScanState.Idle(lastFailure)`. 대부분 같은 실패가 `Diagnostic` 으로도 나갑니다.
3. **`events` 의 `Diagnostic` 만** — 자원 상태를 바꾸지 않는 정보성 실패(예: MTU 협상 실패 뒤 기본값으로 진행, 구독 해제 실패).

`BleException` 이 아닌 예외도 있습니다.

- `IllegalArgumentException`: 모델 생성 시 잘못된 값(예: `BleChunking.Fixed(0)`, 음수 `instanceIndex`), 그리고 `BleChunking.Disabled` 로 한 청크보다 긴 데이터를 쓰거나 `BleChunking.Fixed(n)` 의 n 이 협상된 청크 크기보다 클 때의 `write`.
- `SecurityException`: 권한 없이 `AndroidBondController` 를 부를 때.
- `CancellationException`: 호출한 코루틴의 Job 이 취소될 때. 앱이 건 `withTimeout` 이 만료된 경우는 `read` · `write` · `subscribe` 에서만 예외입니다 — 코어가 이를 잡아 `BleException(OPERATION_TIMEOUT)` 으로 던집니다.
  `unsubscribe` 는 그 실패를 던지지 않고 `events` 의 `Diagnostic` 으로만 남기므로 호출자는 `withTimeout` 의 `TimeoutCancellationException` 을 받습니다. `connect`(주소 찾기 스캔 중) · `startScan` 등도 `TimeoutCancellationException` 을 그대로 받습니다.

코드별 의미와 재시도 가능성은 [5.12](#512-실패-처리-패턴)의 표에 있습니다.

### 4.8 스레드와 코루틴

- 모든 공개 함수는 **어느 스레드에서 불러도 됩니다**(메인 포함). 내부 상태는 코어 전용 단일 스레드 디스패처에서만 바뀝니다. 호출 스레드를 막지 않습니다.
- Flow 값은 **수집하는 쪽의 디스패처**에서 받습니다. 화면에 그릴 값이면 Main 에서 수집하세요.
- 한 세션의 GATT 작업(read · write 의 모든 청크 · 구독 · 구독 해제)은 한 줄로 서서 하나씩 실행됩니다. 긴 write 의 청크 사이에 다른 작업이 끼어들지 않습니다. 서로 다른 세션은 따로 진행됩니다.
- 각 GATT 작업은 시작된 뒤 `operationTimeoutMillis`(기본 5초) 안에 응답이 와야 합니다. 넘기면 `OPERATION_TIMEOUT` 을 던지고 **그 연결을 끝냅니다.**
- **이미 시작된 GATT 작업을 호출자가 끊으면 그 연결이 끝나고, `autoReconnect` 여도 재연결하지 않습니다.** 응답을 받을 방법이 없어져서 코어가 연결을 정리하기 때문입니다. 끊는 방법에 따라 결과가 다릅니다.
  - Job 취소: 호출자는 `CancellationException`, 연결은 `Disconnected(Failed(OPERATION_INVALIDATED))`.
  - 앱의 `withTimeout` 만료(`read` · `write` · `subscribe`): 호출자는 `BleException(OPERATION_TIMEOUT)`, 연결은 `Disconnected(Failed(OPERATION_TIMEOUT))`.
  - 앱의 `withTimeout` 만료(`unsubscribe`): 연결은 똑같이 끝나지만 `OPERATION_TIMEOUT` 은 `Diagnostic` 으로만 나가고, 호출자는 `TimeoutCancellationException` 을 받습니다.
  - 아직 줄에서 기다리던 작업은 끊어도 연결에 영향이 없습니다(`read` · `write` · `subscribe` 를 `withTimeout` 으로 끊었으면 `OPERATION_TIMEOUT` 을 받습니다).
- GATT 작업이 아닌 `connect` 는 주소 찾기 스캔 중에 끊으면 그 연결 시도를 정리하고 `CancellationException`(`withTimeout` 이면 `TimeoutCancellationException`)을 그대로 던집니다. `startScan` 도 변환하지 않습니다.
- `stopScan()` 과 `disconnect()` 는 호출 코루틴이 이미 취소됐어도 정리를 끝까지 한 뒤 취소를 전파합니다. `finally` 에서 그냥 불러도 됩니다.
- `BleConfig.dispatcher` 는 테스트용 주입 지점입니다. 앱에서는 넣지 마세요. 넣는다면 `Job` 없이 단일 스레드 디스패처만 넣어야 합니다.

---

## 5. 유즈케이스 레시피

앞 레시피의 도우미(`connectAndAwaitReady`, `startReceiving` 등)를 뒤 레시피에서 다시 씁니다.

### 5.1 스캔: 필터로 찾고 가장 가까운 기기 고르기

**언제 쓰나.** 주변 기기 중 조건에 맞는 것을 찾을 때. 특히 "가장 가까운 기기 하나"를 자동으로 고를 때.

```kotlin
/** [durationMillis] 동안 스캔해 신호가 가장 센(RSSI 최대) 기기를 고릅니다. 못 찾으면 null. */
suspend fun scanNearest(
    client: BleClient,
    filter: BleScanFilter,
    durationMillis: Long = 5_000,
): ScanResult? = coroutineScope {
    val latest = HashMap<PeripheralId, ScanResult>()
    // 수집을 먼저 등록해야 startScan 직후의 결과도 받습니다.
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        client.scanResults.collect { latest[it.peripheralId] = it } // 같은 기기는 최신 값으로 덮어씁니다
    }
    client.startScan(ScanRequest(filter, BleScanOptions(BleScanMode.LowLatency))) // 실패하면 여기서 던집니다
    try {
        delay(durationMillis)
    } finally {
        client.stopScan() // 호출자가 취소돼도 스캔은 끝까지 멈춥니다
    }
    collector.cancelAndJoin()
    latest.values.maxByOrNull { it.rssi }
}

suspend fun scanExamples(client: BleClient) {
    // 이름이 "NUS-" 로 시작하는 기기(광고 패킷의 local name 기준, 대소문자 구분)
    val byName = BleScanFilter(namePrefix = "NUS-")
    // NUS 서비스 UUID 를 광고하는 기기
    val byService = BleScanFilter(serviceUuids = listOf(NUS_SERVICE.uuid))
    // 제조사 데이터: 0x0059(Nordic Semiconductor 의 company ID) 이고 첫 바이트가 0x01 인 기기(예시 값)
    val byManufacturer = BleScanFilter(
        manufacturerData = listOf(
            BleManufacturerDataFilter(companyId = 0x0059, data = byteArrayOf(0x01), mask = byteArrayOf(0xFF.toByte())),
        ),
    )
    for (filter in listOf(byName, byService, byManufacturer)) {
        val nearest = scanNearest(client, filter) ?: continue
        Log.i("ble", "${nearest.peripheralId.value} ${nearest.advertisement.localName} ${nearest.rssi} dBm")
    }
}

/**
 * 스캔이 명령 밖에서 실패로 멈춘 이유만 흘려보냅니다(블루투스 꺼짐 등).
 * scanState 는 StateFlow 라 최신 값은 잃지 않지만, 느린 수집자는 중간 상태를 건너뛸 수 있습니다.
 * 새로 수집을 시작하면 지금 남아 있는 Idle(실패)가 바로 한 번 옵니다. 이미 지난 실패일 수 있습니다.
 */
fun scanStopReasons(client: BleClient): Flow<BleFailure> =
    client.scanState.filterIsInstance<ScanState.Idle>().mapNotNull { it.lastFailure }
```

**주의점**

- 필터 의미: `serviceUuids` · `manufacturerData` · `serviceData` 항목은 **하나라도 맞으면 통과(OR)** 이고, `namePrefix` 는 그 결과에 **추가 조건(AND)** 입니다. 항목이 모두 비면 전부 통과합니다.
- `namePrefix` 는 광고 패킷의 local name 기준이고 대소문자를 구분합니다. 이름을 광고하지 않는 기기는 걸러집니다.
- 제조사 · 서비스 데이터 필터는 `data` 로 **앞부분 일치**를 보고, `mask` 를 주면 비트 단위로 비교합니다. `mask` 는 `data` 와 길이가 같아야 합니다.
- 스캔은 스스로 멈추지 않습니다. 반드시 `stopScan()` 하세요.
- 스캔 중에 다른 조건으로 `startScan` 하면 `SCAN_ALREADY_RUNNING` 입니다. 같은 조건이면 아무 일도 하지 않습니다.
- Android 는 30초에 스캔 시작 5회를 넘기면 조용히 결과를 끊습니다. 코어가 이를 세어 넘는 시작을 `SCAN_START_THROTTLED` 로 거절합니다([5.12](#512-실패-처리-패턴)의 `startScanPolitely`).
- 블루투스가 꺼지면 스캔은 `Idle(SCAN_STOPPED_BY_ADAPTER)` 로 멈추고, 다시 켜져도 **자동으로 다시 시작하지 않습니다.**
- RSSI 는 순간값이라 흔들립니다. "가장 가까운"은 그 순간 신호가 가장 센 기기라는 뜻입니다.
- **알려진 결함 — 서비스 데이터.** 코어는 광고의 서비스 UUID 목록에 있는 UUID 에 대해서만 서비스 데이터를 읽습니다. 그래서 서비스 데이터로만 광고되고 목록에는 없는 UUID 는 `advertisement.serviceData` 에서 빠지고,
  그 UUID 로 만든 `BleServiceDataFilter` 는 Android 스캔 필터는 통과해도 코어의 필터 검사에서 버려져 결과가 오지 않습니다. 코드로 확인한 결함이고, 실기기에서 재현해 보지는 않았습니다.
  그런 기기를 찾아야 하면 서비스 데이터 필터 대신 다른 조건(이름 · 제조사 데이터 등)으로 찾으세요.

### 5.2 연결하고 `Ready` 까지 기다리기

**언제 쓰나.** 모든 연결의 기본형입니다. 연결 옵션(`BleConnectOptions`)을 고르고, 실패 · 시간 초과를 한곳에서 처리합니다.

```kotlin
/** Ready 또는 Disconnected 가 될 때까지 기다립니다. [timeoutMillis] 안에 둘 다 아니면 null. */
suspend fun awaitSettled(session: BleSession, timeoutMillis: Long): ConnectionState? =
    withTimeoutOrNull(timeoutMillis) {
        session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
    }

/** Ready 가 되지 못한 연결 시도. [state] 가 null 이면 시간 초과입니다. */
class ConnectFailedException(val state: ConnectionState?) : Exception("Ready 가 되지 못했습니다: $state")

/**
 * 연결하고 Ready 까지 기다립니다.
 * - Ready 전에 실패로 끊기면 그 원인을 BleException 으로 던집니다.
 * - 시간 안에 결론이 안 나면 시도를 disconnect() 로 정리하고 ConnectFailedException(null) 을 던집니다.
 *   disconnect() 는 이 세션의 구독 의도도 모두 지우므로, 그 뒤 다시 연결하면 subscribe 를 다시 해야 합니다.
 */
suspend fun connectAndAwaitReady(
    client: BleClient,
    peripheralId: PeripheralId,
    options: BleConnectOptions = BleConnectOptions(),
    timeoutMillis: Long = 40_000,
): BleSession {
    val session = client.connect(peripheralId, options) // 즉시 실패(권한·어댑터·옵션 충돌 등)는 여기서 던집니다
    when (val settled = awaitSettled(session, timeoutMillis)) {
        ConnectionState.Ready -> return session
        is ConnectionState.Disconnected -> {
            val reason = settled.reason
            if (reason is BleDisconnectReason.Failed) throw BleException(reason.failure)
            throw ConnectFailedException(settled) // 누군가 disconnect() 했거나 client 가 닫혔습니다
        }
        else -> {
            session.disconnect() // autoReconnect 연결은 기한이 없으므로 앱이 끊어야 합니다
            throw ConnectFailedException(settled)
        }
    }
}
```

**연결 옵션 고르기**

| 옵션 | 링크가 끊기면 | 첫 연결 시간 제한 | 이런 때 |
| --- | --- | --- | --- |
| `BleConnectOptions()` (`autoReconnect = false`, 기본) | `Disconnected(Failed(…))` 로 끝남. 다시 붙이는 건 앱 몫([5.3](#53-앱이-다시-시도하는-연결)) | 링크 15초 | 사용자가 버튼을 눌러 연결하는 대부분의 경우 |
| `BleConnectOptions(autoReconnect = true)` | `Recovering` 으로 OS 재연결을 무기한 기다림 | **없음** | 기기가 범위를 오가도 계속 붙어 있어야 할 때([5.9](#59-끊김과-블루투스-offon-을-넘어-연결-유지)) |

**주의점**

- 이미 연결 중이거나 `Ready` 인 세션에 **같은 옵션**으로 `connect()` 하면 아무 일도 하지 않고 바로 돌아옵니다. **다른 옵션**이면 `CONNECTION_OPTIONS_CONFLICT` 입니다. 옵션을 바꾸려면 `disconnect()` 뒤 다시 연결합니다.
- 해제 정리 중(`Disconnecting`)에 `connect()` 하면 `CONNECT_DURING_DISCONNECT`(`TransientMayHelp`)입니다. 정리가 끝난 뒤 다시 부르면 됩니다.
- 기본 `timeoutMillis` 40초는 기본 단계 제한(링크 15 + 탐색 10 + 설정 10초)보다 길게 잡은 값입니다. 더 짧으면 코어가 아직 진행 중인 시도를 앱이 먼저 끊게 됩니다.

### 5.3 앱이 다시 시도하는 연결

**언제 쓰나.** `autoReconnect = false` 로 연결하면서 일시적인 실패는 몇 번 더 시도하고 싶을 때. (0.2.0 에서 라이브러리 재연결 정책이 없어졌으므로 앱이 이렇게 합니다.)

```kotlin
/**
 * autoReconnect = false 로 연결하고, 실패하면 간격을 두고 최대 [attempts] 번까지 다시 시도합니다.
 * 다시 해도 소용없는 실패(Never·AfterEnvironmentChange)는 바로 던집니다.
 */
suspend fun connectWithRetry(
    client: BleClient,
    peripheralId: PeripheralId,
    attempts: Int = 3,
    retryDelayMillis: Long = 2_000,
): BleSession {
    var last: Exception? = null
    repeat(attempts) { attempt ->
        if (attempt > 0) delay(retryDelayMillis)
        try {
            return connectAndAwaitReady(client, peripheralId, BleConnectOptions(autoReconnect = false))
        } catch (e: BleException) {
            val retryability = e.failure.retryability
            if (retryability == Retryability.Never || retryability == Retryability.AfterEnvironmentChange) throw e
            last = e
        } catch (e: ConnectFailedException) {
            if (e.state != null) throw e // 사용자 해제·close 로 끝났으면 다시 걸지 않습니다
            last = e
        }
    }
    throw checkNotNull(last)
}
```

**주의점**

- `AfterEnvironmentChange`(블루투스 꺼짐 · 권한 없음 등)는 기다려도 저절로 바뀌지 않으므로 바로 던집니다. 사용자에게 안내한 뒤 다시 시도하세요.
- 예전 끊김 이후의 재연결에서도 앱이 켜 둔 구독은 코어가 `Ready` 전에 복원합니다. 다시 `subscribe()` 할 필요가 없습니다(단, 앱이 `disconnect()` 로 끊었다면 다시 해야 합니다. `connectAndAwaitReady` 가 시간 초과로 `disconnect()` 한 뒤에 이어서 연결한 경우도 마찬가지입니다).

### 5.4 서비스 탐색과 캐릭터리스틱 찾기

**언제 쓰나.** 기기의 GATT 구조를 확인하거나, 캐릭터리스틱이 어떤 동작(read · write · notify)을 지원하는지 볼 때.

```kotlin
/** Ready 인 세션에서 UUID 로 캐릭터리스틱을 찾습니다. 없으면 null. */
fun lookupCharacteristic(session: BleSession, serviceUuid: UUID, characteristicUuid: UUID): GattCharacteristic? {
    check(session.connectionState.value == ConnectionState.Ready) { "services 는 Ready 일 때만 믿을 수 있습니다" }
    return session.services.value
        .firstOrNull { it.ref.uuid.value == serviceUuid }
        ?.characteristics
        ?.firstOrNull { it.ref.uuid.value == characteristicUuid }
}

/** 탐색된 GATT 구조를 로그로 남깁니다. */
fun logGattTable(session: BleSession) {
    for (service in session.services.value) {
        Log.i("ble", "service ${service.ref.uuid.value} #${service.ref.instanceIndex}")
        for (characteristic in service.characteristics) {
            Log.i("ble", "  └ ${characteristic.ref.uuid.value} #${characteristic.ref.instanceIndex} ${characteristic.properties}")
        }
    }
}

/** 같은 UUID 의 서비스가 두 개 있을 때, 두 번째 서비스 안의 캐릭터리스틱을 가리키는 법. */
fun secondServiceInstance(serviceUuid: UUID, characteristicUuid: UUID): CharacteristicRef =
    CharacteristicRef(
        service = ServiceRef(BleUuid(serviceUuid), instanceIndex = 1),
        uuid = BleUuid(characteristicUuid),
    )
```

**주의점**

- 서비스 탐색은 코어가 연결 과정에서 자동으로 합니다. 앱이 따로 요청하는 API 는 없습니다.
- `services` 는 끊겨도 마지막 결과를 유지합니다. `Ready` 가 아닐 때의 값은 믿지 마세요.
- UUID 를 이미 알면 탐색 결과를 뒤질 필요 없이 `CharacteristicRef` 를 직접 만들어도 됩니다(퀵 스타트의 `NUS_RX` 처럼).
- 코어는 read · write 전에 `properties` 를 검사하지 않습니다. 지원하지 않는 동작은 Android 나 기기가 거절해 `GATT_OPERATION_START_FAILED` 또는 `GATT_STATUS_FAILED` 가 됩니다. 미리 `properties` 로 확인하는 편이 좋습니다.

### 5.5 read

**언제 쓰나.** 값을 한 번 읽을 때(배터리 잔량, 펌웨어 버전 등).

```kotlin
// 예시 UUID: Battery Service(0x180F) 의 Battery Level(0x2A19). 값은 0~100 한 바이트입니다.
val BATTERY_LEVEL = CharacteristicRef(
    ServiceRef(BleUuid(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))),
    BleUuid(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")),
)

suspend fun readBatteryPercent(session: BleSession): Int? =
    try {
        session.read(BATTERY_LEVEL).firstOrNull()?.toInt()?.and(0xFF)
    } catch (e: BleException) {
        Log.w("ble", "read 실패: ${e.failure.code.value} retry=${e.failure.retryability}")
        null
    }
```

**주의점**

- read 결과는 반환값으로만 옵니다. `notifications()` 로는 오지 않습니다.
- `read()` 를 짧은 `withTimeout` 으로 감싸지 마세요. 시작된 read 를 취소하면 연결이 끊깁니다([4.8](#48-스레드와-코루틴)). 코어가 `operationTimeoutMillis` 로 이미 제한합니다.
- 암호화가 필요한 캐릭터리스틱은 코드상 `GATT_STATUS_FAILED` + `AfterEnvironmentChange` 로 실패하도록 매핑돼 있습니다. 실기기에서는 확인하지 않았습니다(Android 가 스스로 페어링을 시작할 수도 있고, GATT status 8 은 검증 필요). 이 실패를 받으면 본딩([5.11](#511-본딩)) 뒤 다시 연결하세요.

### 5.6 write: 응답 있음/없음, 긴 데이터, 결과 확인

**언제 쓰나.** 기기에 명령이나 데이터를 보낼 때. 길이에 상관없이 같은 함수를 씁니다.

```kotlin
/** write 결과를 앱이 다루기 쉬운 모양으로 바꿉니다. */
sealed interface SendResult {
    /** 전부 보냈습니다. confirmed = 기기가 받았다고 응답했는가(WithResponse 일 때만 true). */
    data class Sent(val bytes: Int, val chunks: Int, val confirmed: Boolean) : SendResult

    /** 한 바이트도 보내기 전에 실패했습니다. 같은 데이터를 다시 보내도 중복되지 않습니다. */
    data class NotSent(val failure: BleFailure) : SendResult

    /** 보내는 도중 실패했습니다. progress.bytesCompleted 까지만 확실하고, 실패한 청크는 전달 여부를 모릅니다. */
    data class Interrupted(val progress: WriteProgress, val failure: BleFailure) : SendResult
}

suspend fun sendData(
    session: BleSession,
    target: CharacteristicRef,
    data: ByteArray,
    mode: BleWriteMode = BleWriteMode.WithResponse,
): SendResult =
    try {
        // Automatic: 협상된 MTU 에 맞춰 코어가 자릅니다(요청 MTU 247 이 받아들여지면 청크 244 바이트).
        val receipt = session.write(target, data, BleWriteOptions(mode = mode, chunking = BleChunking.Automatic))
        SendResult.Sent(
            bytes = receipt.bytesCompleted,
            chunks = receipt.chunksCompleted,
            confirmed = receipt.delivery == DeliverySemantics.RemoteAcknowledged,
        )
    } catch (e: BleException) {
        val progress = e.failure.writeProgress
        if (progress == null) SendResult.NotSent(e.failure) else SendResult.Interrupted(progress, e.failure)
    }
```

펌웨어가 작은 패킷만 받는다면 청크 크기를 고정합니다.

```kotlin
/** 펌웨어가 한 번에 20 바이트까지만 처리할 때: 청크 크기를 고정하고 응답 없이 보냅니다. */
suspend fun sendIn20ByteChunks(session: BleSession, data: ByteArray): WriteReceipt =
    session.write(
        NUS_RX,
        data,
        BleWriteOptions(mode = BleWriteMode.WithoutResponse, chunking = BleChunking.Fixed(20)),
    )
```

| `BleWriteOptions` | 값 | 의미 |
| --- | --- | --- |
| `mode` | `WithResponse` (기본) | 청크마다 기기의 응답을 기다립니다. `receipt.delivery == RemoteAcknowledged` |
| | `WithoutResponse` | 응답을 기다리지 않습니다. `SubmittedToPlatform` = Android 에 넘겼다는 뜻이지 기기가 받았다는 증거가 아닙니다 |
| `chunking` | `Automatic` (기본) | 협상된 MTU − 3 바이트씩 자릅니다(요청 MTU 247 → 244 바이트, 협상 실패 시 23 → 20 바이트) |
| | `Fixed(maxBytes)` | `maxBytes` 씩 자릅니다. 협상된 청크 크기보다 크면 `IllegalArgumentException` |
| | `Disabled` | 자르지 않습니다. 한 청크보다 길면 `IllegalArgumentException` |

**주의점**

- 청크는 각각 별개의 GATT write 로 나갑니다. 기기 펌웨어가 조각을 이어 붙여야 합니다(코어는 프레이밍을 하지 않습니다).
- 실패했을 때 `writeProgress` 가 `null` 이면 한 바이트도 나가지 않은 것입니다. `null` 이 아니면 `bytesCompleted` 까지는 확실히 전달됐고, `failedChunkIndex` 번 청크는 **전달됐는지 알 수 없습니다**(`currentChunkDeliveryUnknown`). 이 경우 무작정 처음부터 다시 보내면 기기가 같은 데이터를 두 번 받을 수 있습니다. 기기 프로토콜(시퀀스 번호, 상태 조회 등)로 확인하세요.
- `write()` 를 짧은 `withTimeout` 으로 감싸지 마세요. 청크마다 `operationTimeoutMillis`(기본 5초)가 이미 적용됩니다.
- 빈 배열을 써도 청크 1개짜리 write 로 나갑니다.

### 5.7 notify / indicate 구독

**언제 쓰나.** 기기가 보내는 값을 계속 받을 때(센서 값, UART 수신 등).

```kotlin
/**
 * notify/indicate 수신을 시작합니다. 돌려준 Job 을 취소하면 수집이 멈춥니다.
 * 수집을 먼저 등록하고(UNDISPATCHED) 그다음 구독해야 구독 직후 값도 놓치지 않습니다.
 */
suspend fun startReceiving(
    scope: CoroutineScope,
    session: BleSession,
    characteristic: CharacteristicRef,
    onValue: (ByteArray) -> Unit,
): Job {
    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.notifications(characteristic).collect { onValue(it) }
    }
    try {
        session.subscribe(characteristic) // CCCD 쓰기 응답까지 기다립니다
    } catch (e: Throwable) {
        job.cancel()
        throw e
    }
    return job
}

suspend fun stopReceiving(session: BleSession, characteristic: CharacteristicRef, job: Job) {
    job.cancel()
    session.unsubscribe(characteristic) // 기기 쪽 해제 실패는 던지지 않고 events 의 Diagnostic 으로만 알립니다
}
```

**주의점**

- 순서가 중요합니다. **수집 먼저(`UNDISPATCHED`), `subscribe()` 나중.** 반대로 하면 구독 직후 값을 놓칩니다.
- notify 와 indicate 는 같은 API 입니다. 캐릭터리스틱이 notify 를 지원하면 notify 로, 아니면 indicate 로 켭니다(indicate 는 실기기 검증 전).
- 수집자마다 버퍼가 따로 있어서 다른 수집자나 다른 기기 때문에 값을 잃지 않습니다. 단, 한 수집자가 버퍼(`eventBufferCapacity`, 기본 64)보다 많이 밀리면 그 수집자에게 갈 **최신 값**이 버려집니다. 수집 블록에서 오래 걸리는 일을 하지 마세요.
- 구독 의도는 링크가 끊겨도 유지되고, 새 연결의 `Ready` 전에 코어가 다시 켭니다. 그동안 수집 Job 은 그대로 두면 됩니다.
- 복원이 영구적으로 실패하면 의도에서 빠지고 `events` 로 `SubscriptionRestoreFailed` 가 나갑니다. 이벤트는 유실될 수 있으니 "지금 받고 있나"는 `activeSubscriptions` 로 확인합니다.
- `disconnect()` 는 그 세션의 구독 의도를 모두 지웁니다. 다시 연결한 뒤 `subscribe()` 를 다시 불러야 합니다.
- `unsubscribe()` 는 기기 쪽 해제가 실패해도 던지지 않습니다(`events` 에 진단만 남김). 로컬에서는 즉시 꺼집니다.

### 5.8 요청-응답 (write 하고 notify 로 답 받기, 예: NUS)

**언제 쓰나.** 명령을 보내고 그 응답을 notify 로 받는 프로토콜(UART 형 명령어 등).

```kotlin
/** NUS 위의 요청-응답. 명령을 RX 에 쓰고 TX 로 오는 첫 notify 를 응답으로 받습니다. */
class NusClient(private val session: BleSession) {
    private val lock = Mutex() // 응답이 섞이지 않도록 한 번에 요청 하나만

    /** 전제: 연결 직후 session.subscribe(NUS_TX) 를 한 번 해 두었습니다. */
    suspend fun request(command: ByteArray, timeoutMillis: Long = 3_000): ByteArray = lock.withLock {
        coroutineScope {
            // 응답 수집을 write 보다 먼저 등록합니다.
            val reply = async(start = CoroutineStart.UNDISPATCHED) { session.notifications(NUS_TX).first() }
            try {
                session.write(NUS_RX, command)
                withTimeout(timeoutMillis) { reply.await() } // 시간 제한은 응답 대기에만 겁니다
            } finally {
                reply.cancel()
            }
        }
    }
}
```

**주의점**

- 응답 수집을 **write 보다 먼저** 등록합니다. 기기가 빨리 답하면 write 가 끝나기 전에 응답이 올 수 있습니다.
- 시간 제한은 응답 대기(`reply.await()`)에만 겁니다. `session.write` 까지 감싸면 시작된 write 가 취소돼 연결이 끊길 수 있습니다.
- 이 예시는 "첫 notify 하나 = 응답"이라고 가정합니다. 응답이 여러 notify 로 나뉘어 오거나 요청하지 않은 알림이 섞이는 기기라면 앱 프로토콜에 맞는 조립 · 매칭이 필요합니다.
- `Mutex` 로 요청을 한 번에 하나만 보냅니다. 동시에 보내면 응답이 뒤섞입니다.

### 5.9 끊김과 블루투스 off/on 을 넘어 연결 유지

**언제 쓰나.** 기기가 잠깐 범위를 벗어나거나 사용자가 블루투스를 껐다 켜도 연결과 수신을 이어 가야 할 때.

**방법 A — `autoReconnect = true` (권장).** 코어와 OS 가 다시 붙입니다.

```kotlin
/**
 * 링크 끊김과 블루투스 off/on 을 넘어 유지되는 연결.
 * autoReconnect = true 면 링크가 끊겨도 Recovering 상태로 OS 재연결을 기다리고, 블루투스가 다시 켜지면
 * 코어가 한 번 다시 연결을 겁니다. 구독은 Ready 전에 자동 복원되므로 수집·구독은 한 번만 합니다.
 */
suspend fun openPersistentNus(
    client: BleClient,
    peripheralId: PeripheralId,
    scope: CoroutineScope,
    onValue: (ByteArray) -> Unit,
): Pair<BleSession, Job> {
    val session = connectAndAwaitReady(client, peripheralId, BleConnectOptions(autoReconnect = true))
    val receiver = try {
        startReceiving(scope, session, NUS_TX, onValue)
    } catch (e: Exception) {
        session.disconnect()
        throw e
    }
    return session to receiver
}

/** 연결과 수신(notify) 상태를 한 줄 문구로 봅니다. */
fun linkStatus(session: BleSession): Flow<String> =
    combine(session.connectionState, session.activeSubscriptions) { state, active ->
        when (state) {
            ConnectionState.Ready -> if (NUS_TX in active) "연결됨" else "연결됨(수신 복원 실패)"
            is ConnectionState.Recovering -> "재연결 대기 중(${state.lastFailure.code.value})"
            is ConnectionState.Disconnected -> "끊김: ${state.reason}"
            else -> "연결 중…"
        }
    }
```

- 링크가 끊기면 `Recovering(lastFailure)` 가 되고, OS 가 다시 붙이면 첫 연결과 같은 경로(서비스 탐색 → MTU → 구독 복원 → `Ready`)를 탑니다.
- 블루투스가 꺼지면 `Disconnected(Failed(ADAPTER_UNAVAILABLE))` 가 되고, 다시 켜지면 코어가 **한 번** 다시 연결을 겁니다. 그 사이 `disconnect()` 하면 걸지 않습니다.
- 구독과 수집은 한 번만 합니다. 재연결 뒤에도 같은 수집 Job 이 계속 값을 받습니다.
- `Recovering` 에는 기한이 없습니다. 그만두려면 `disconnect()` 합니다(`Disconnected(Requested)`).
- OS 재연결 뒤 `Ready` 전에 실패하면(서비스 탐색 실패 등) 연결은 `Disconnected(Failed(원인))` 로 끝나고 더 기다리지 않습니다. 이때는 앱이 다시 연결해야 합니다.
- 연결은 의도적으로 느립니다(OS 의 저전력 대기 방식).

**방법 B — 앱이 직접 다시 연결.** 재시도 간격과 조건을 앱이 정하고 싶을 때입니다.

```kotlin
/**
 * autoReconnect 없이 앱이 직접 다시 연결하는 방식. 블루투스가 꺼지면 기다렸다가 켜지면 다시 겁니다.
 * 사용자가 disconnect() 했거나, client 가 닫혔거나, 다시 해도 소용없는 실패(Never)면 반환한 Job 이 끝납니다(취소로 끝남).
 * 링크가 끊겨도 구독 의도는 남아 코어가 다시 연결할 때 복원합니다. 단 connectAndAwaitReady 가 시간 초과로
 * disconnect() 한 뒤에는 의도가 지워졌으므로 연결되면 다시 subscribe 해야 합니다.
 */
fun keepConnectedByApp(client: BleClient, peripheralId: PeripheralId, scope: CoroutineScope): Job = scope.launch {
    client.adapterState.collectLatest { adapter ->
        if (adapter != AdapterState.Ready) return@collectLatest // 꺼지면 collectLatest 가 아래 루프를 취소합니다
        while (true) {
            val session = try {
                connectAndAwaitReady(client, peripheralId)
            } catch (e: BleException) {
                if (e.failure.retryability == Retryability.Never) break
                delay(5_000)
                continue
            } catch (e: ConnectFailedException) {
                if (e.state != null) break // 사용자 해제·close
                delay(5_000)
                continue
            }
            val ended = session.connectionState.filterIsInstance<ConnectionState.Disconnected>().first()
            if (ended.reason !is BleDisconnectReason.Failed) break // 사용자 해제·close
            delay(2_000)
        }
        // 여기 오면 멈출 때입니다. return@collectLatest 는 이 블록만 끝내고 adapterState 수집은 계속되므로,
        // 블루투스가 다시 켜질 때 사용자가 끊은 기기에 또 연결합니다. 그래서 Job 전체를 취소합니다.
        this@launch.cancel()
    }
}
```

- 링크가 끊겨도 구독 의도는 남으므로, 다시 연결하면 코어가 `Ready` 전에 복원합니다. 단 `connectAndAwaitReady` 가 시간 초과로 `disconnect()` 했다면 의도가 모두 지워졌으므로, 연결된 뒤 다시 `subscribe()` 해야 합니다.
- `adapterState` 가 `Ready` 가 아니게 되면 `collectLatest` 가 재시도 루프를 취소하고, 다시 `Ready` 가 되면 새로 시작합니다.
- 멈출 이유(사용자 해제 · `close()` · `Never` 실패)가 생기면 `this@launch.cancel()` 로 Job 전체를 끝냅니다. `return@collectLatest` 만 쓰면 그 블록만 끝나고 `adapterState` 수집은 계속되어, 블루투스가 다시 켜질 때 사용자가 끊은 기기에 또 연결합니다. 반환한 Job 은 취소 상태로 끝납니다(`join()` 은 그대로 돌아옵니다).
- 앱이 연결을 그만둘 때는 `disconnect()` 전에 이 Job 을 먼저 취소하세요. 재시도 대기(`delay`) 중에는 세션이 이미 `Disconnected` 라서 `disconnect()` 를 불러도 상태가 바뀌지 않고, 루프는 대기가 끝나면 다시 연결합니다.

### 5.10 여러 기기에 동시에 연결

**언제 쓰나.** 센서 여러 개처럼 기기 여러 대를 함께 쓸 때.

```kotlin
/** 여러 기기에 동시에 연결합니다. 기기별 실패는 null 로 모읍니다. */
suspend fun connectAllAtOnce(client: BleClient, ids: List<PeripheralId>): Map<PeripheralId, BleSession?> =
    coroutineScope {
        ids.map { id ->
            async {
                id to try {
                    connectAndAwaitReady(client, id)
                } catch (e: BleException) {
                    Log.w("ble", "${id.value}: ${e.failure.code.value}")
                    null
                } catch (e: ConnectFailedException) {
                    Log.w("ble", "${id.value}: ${e.message}")
                    null
                }
            }
        }.awaitAll().toMap()
    }
```

**주의점**

- 세션마다 작업 줄이 따로라서 한 기기의 느린 작업이 다른 기기를 막지 않습니다.
- Android 가 동시에 유지할 수 있는 연결 수는 기기 · OS 마다 다릅니다. 여러 기기 동시 연결은 이 저장소에서 실기기 검증하지 않았습니다(확인 필요).
- 처음 보는 주소 여러 개로 한꺼번에 연결하면 기기 찾기 스캔이 스캔 시작 한도에 걸릴 수 있습니다. 이때 코어는 진단을 남기고 바로 연결을 시도합니다. 먼저 한 번 스캔해서 기기를 "본" 상태로 만들면 이 과정을 건너뜁니다.

### 5.11 본딩

**언제 쓰나.** 기기가 암호화된 링크를 요구할 때. 코어는 인증 · 암호화 부족 status(5 · 8 · 15)를 `GATT_STATUS_FAILED` + `AfterEnvironmentChange` 로 옮기지만, 이는 코드상 매핑일 뿐 실기기 동작은 확인하지 않았습니다. Android 가 오류 대신 스스로 페어링을 시작할 수도 있고, GATT status 8 은 릴리스 노트에서도 검증 필요로 남아 있습니다.

```kotlin
/** 본딩(페어링)이 필요한 기기: 연결 전에 본딩을 끝내고 연결합니다(실기기 검증과 같은 순서). */
suspend fun bondThenConnect(context: Context, client: BleClient, peripheralId: PeripheralId): BleSession {
    val bonds = AndroidBleClientFactory.createBondController(context)
    if (bonds.bondState(peripheralId) != AndroidBondState.Bonded) {
        when (bonds.createBond(peripheralId, timeoutMillis = 30_000)) {
            AndroidBondResult.Bonded, AndroidBondResult.AlreadyBonded -> Unit
            AndroidBondResult.StartRejected -> error("시스템이 본딩 시작을 거절했습니다")
            AndroidBondResult.Failed -> error("본딩 실패(사용자 거절·키 불일치 등)")
            AndroidBondResult.TimedOut -> error("30초 안에 본딩이 끝나지 않았습니다")
        }
    }
    return connectAndAwaitReady(client, peripheralId)
}
```

| `AndroidBondResult` | 뜻 |
| --- | --- |
| `Bonded` | 이번 요청으로 본딩됨 |
| `AlreadyBonded` | 이미 본딩돼 있었음 |
| `StartRejected` | Android 가 본딩 시작을 거절함 |
| `Failed` | 본딩을 요청한 뒤 본딩 상태가 `None` 으로 알려짐(사용자 거절, 키 불일치 등). `Bonding` 알림이 먼저 오지 않았어도 이 결과입니다 |
| `TimedOut` | `timeoutMillis`(기본 30초) 안에 끝나지 않음 |

**주의점**

- 실기기 검증은 "본딩 → 연결" 순서로 했습니다(Pixel 5a + nRF52840). 연결된 상태에서 본딩하는 순서는 검증하지 않았습니다.
- 시스템 페어링 대화상자가 뜰 수 있습니다. 사용자가 응답할 시간을 고려해 `timeoutMillis` 를 정하세요.
- 본딩 해제(remove bond) API 는 없습니다.
- `bondState()` 와 `createBond()` 는 `BleException` 으로 감싸지 않은 예외를 그대로 던집니다. Android 12 이상에서 `BLUETOOTH_CONNECT` 가 허용되지 않았으면 `SecurityException`(Android 의 본딩 상태 조회 · 본딩 시작 호출에서 발생),
  주소 형식이 틀리면(소문자 포함) `IllegalArgumentException`, 블루투스 어댑터가 없는 기기면 `NullPointerException` 입니다.
- 기기 쪽에서 본딩 정보를 지웠는데 폰에 남아 있으면 연결이나 암호화가 실패할 수 있습니다. 이때는 Android 설정에서 기기를 지우고 다시 본딩해야 합니다.

### 5.12 실패 처리 패턴

**언제 쓰나.** 모든 명령에서. 재시도할지, 사용자에게 안내할지, 포기할지를 정할 때.

```kotlin
/** 실패를 보고 다음 행동을 고릅니다. 분기는 code 와 retryability 로만 합니다(message 는 로그용). */
enum class NextAction { RetrySoon, Reconnect, FixEnvironment, GiveUp, Investigate }

fun nextActionFor(failure: BleFailure): NextAction = when (failure.retryability) {
    Retryability.TransientMayHelp -> NextAction.RetrySoon // 잠시 뒤 같은 호출
    Retryability.NewConnectionMayHelp -> NextAction.Reconnect // 연결을 새로 맺은 뒤 다시
    Retryability.AfterEnvironmentChange -> NextAction.FixEnvironment // 블루투스 켜기·권한·본딩 뒤 다시
    Retryability.Never -> NextAction.GiveUp // 입력이나 앱 설정을 고쳐야 하는 문제
    Retryability.Unknown -> NextAction.Investigate // 로그를 보고, 필요하면 연결부터 다시
}

fun userMessageFor(failure: BleFailure): String = when (failure.code.value) {
    "ADAPTER_UNAVAILABLE" -> "블루투스를 켜 주세요"
    "PERMISSION_DENIED" -> "블루투스 권한이 필요합니다"
    "BLE_UNSUPPORTED" -> "이 기기는 BLE 를 지원하지 않습니다"
    "PERIPHERAL_NOT_FOUND" -> "기기를 찾지 못했습니다. 가까이 두고 다시 시도하세요"
    "LINK_TIMEOUT", "LINK_FAILED", "LINK_START_FAILED" -> "기기와 연결하지 못했습니다"
    "NOT_READY", "NOT_CONNECTED" -> "기기가 아직 준비되지 않았습니다"
    // 인증·암호화 부족은 별도 코드가 아니라 GATT_STATUS_FAILED + AfterEnvironmentChange 로 옵니다.
    "GATT_STATUS_FAILED" ->
        if (failure.retryability == Retryability.AfterEnvironmentChange) "기기와 페어링이 필요합니다" else "기기가 요청을 거절했습니다"
    "SCAN_START_THROTTLED" -> "잠시 후 다시 검색해 주세요"
    else -> "블루투스 오류 (${failure.code.value})"
}

/** 스캔 시작이 Android 한도(30초에 5회)에 걸리면 한 번 기다렸다가 다시 시작합니다. */
suspend fun startScanPolitely(client: BleClient, request: ScanRequest) {
    try {
        client.startScan(request)
    } catch (e: BleException) {
        if (e.failure.code.value != "SCAN_START_THROTTLED") throw e
        delay(31_000)
        client.startScan(request)
    }
}
```

**원칙**

- `catch (e: BleException)` 으로 받고 `e.failure.code` / `e.failure.retryability` 로 분기합니다. `message` 로 분기하지 않습니다.
- 재시도는 **명령 단위로, 앱이 판단해서** 합니다. 특히 `writeProgress` 가 있는 write 는 그대로 다시 보내지 마세요.
- 예상하지 못한 새 코드가 올 수 있습니다(`FailureCode` 는 enum 이 아닙니다). `when` 에는 항상 `else` 를 둡니다.

**FailureCode 표** (코드에서 확인한 값. "전달"은 코어의 `FailureCatalog.kt` 에 적힌 경로를 따릅니다)

- 던짐: 명령이 `BleException` 으로 던짐.
- 상태: `Disconnected(Failed)` · `Recovering` · `ScanState.Idle` 에 남음. 구독 복원 경로에서는 그 캐릭터리스틱이 `activeSubscriptions` 에서 빠지는 것.
- 이벤트: `events` 의 `Diagnostic`. 구독 복원 실패가 `Never` 면 대신 `SubscriptionRestoreFailed` 가 나갑니다.
- "연결 시작": `connect()` 가 `Connecting` 을 알린 뒤 던진 실패. 이때 세션도 `Disconnected(Failed(그 코드))` 로 끝납니다.
- 연결 상태가 바뀔 때마다 나가는 `Connection` 이벤트는 표에 적지 않았습니다.

| 코드 | 뜻 | Retryability | 전달 |
| --- | --- | --- | --- |
| `ADAPTER_UNAVAILABLE` | 블루투스 꺼짐 | AfterEnvironmentChange | 던짐(명령 첫머리 검사) / 던짐 + 상태(연결 시작) / 상태 + 이벤트(블루투스 꺼짐으로 연결 종료) |
| `BLE_UNSUPPORTED` | BLE 어댑터 없음 | Never | 던짐(명령 첫머리 검사) / 던짐 + 상태(연결 시작) |
| `PERMISSION_DENIED` | 권한 없음(선언 누락이면 Never) | AfterEnvironmentChange / Never | 던짐(명령 첫머리 검사) / 던짐 + 이벤트(`startScan`) / 던짐 + 상태(연결 시작) / 던짐 + 상태 + 이벤트(read · write · subscribe 시작 — 연결 종료) / 상태 + 이벤트(구독 해제 · 구독 복원 · 탐색/MTU 시작 · 권한 회수) / 이벤트(주소 찾기 스캔) |
| `CLIENT_CLOSED` | `close()` 뒤 명령 | Never | 던짐 |
| `UNKNOWN_DEVICE` | 주소 형식이 잘못된 `PeripheralId`, 등록되지 않은 세션 | Never | 던짐(등록 안 된 세션) / 던짐 + 상태(`connect` 에 잘못된 주소) |
| `SCAN_ALREADY_RUNNING` | 다른 조건으로 이미 스캔 중 | Never | 던짐 + 이벤트 |
| `SCANNER_UNAVAILABLE` | Android 스캐너를 얻지 못함 | Unknown | 던짐 + 이벤트(`startScan`) / 이벤트(주소 찾기 스캔) |
| `SCAN_START_FAILED` | 스캔 시작 거절(필터 오류 등) | Unknown | 던짐 + 이벤트(`startScan`) / 이벤트(주소 찾기 스캔, MAC 형식이 아닌 주소 포함) |
| `SCAN_START_THROTTLED` | 30초에 5회 스캔 시작 한도 초과. `message` 에 다음 시작 가능까지 ms | TransientMayHelp | 던짐 + 이벤트(`startScan`) / 이벤트(주소 찾기 스캔) |
| `SCAN_RUNTIME_FAILED` | 스캔 도중 Android 가 실패 통보 | Unknown | 상태(`scanState`) + 이벤트 / 이벤트(이미 끝난 스캔 · 주소 찾기 스캔) |
| `SCAN_STOPPED_BY_ADAPTER` | 블루투스가 꺼져 스캔이 멈춤 | 어댑터 상태에 따름 | 상태(`scanState`) + 이벤트 |
| `PERIPHERAL_NOT_FOUND` | 연결 전 기기 찾기 스캔에서 못 찾음 | TransientMayHelp | 던짐 + 상태(`autoReconnect = false`) / 이벤트(`true`) |
| `CONNECTION_OPTIONS_CONFLICT` | 진행 중인 연결과 다른 옵션으로 `connect()` | Never | 던짐 + 이벤트 |
| `CONNECT_DURING_DISCONNECT` | 해제 정리 중 `connect()` | TransientMayHelp | 던짐 + 이벤트 |
| `LINK_START_FAILED` | Android 가 연결 시작을 거절 | Unknown | 던짐 + 상태(연결 시작) / 상태 + 이벤트(블루투스가 다시 켜진 뒤 코어의 재연결) |
| `LINK_TIMEOUT` | 링크 시간 제한 초과 | NewConnectionMayHelp | 상태 + 이벤트 |
| `LINK_FAILED` | 링크가 끊김/실패. `platformDetail.symbolicName` 에 `GATT_CONN_TIMEOUT` 등 | Unknown (인증 실패면 AfterEnvironmentChange) | 상태 + 이벤트 / 이벤트만(이미 끝난 연결의 늦은 콜백) |
| `SERVICE_DISCOVERY_START_FAILED` | 서비스 탐색 시작 실패 | Unknown | 상태 + 이벤트 |
| `SERVICE_DISCOVERY_FAILED` | 서비스 탐색 실패(서비스 0개 포함) | Unknown (GATT status 5 · 8 · 15 면 AfterEnvironmentChange, 서비스 0개는 Unknown) | 상태 + 이벤트 |
| `SERVICE_DISCOVERY_TIMEOUT` | 서비스 탐색 시간 초과 | NewConnectionMayHelp | 상태 + 이벤트 |
| `CONFIGURATION_FAILED` | MTU 협상 실패 — 기본 MTU 로 계속 진행 | Unknown (GATT status 5 · 8 · 15 면 AfterEnvironmentChange) | 이벤트 |
| `CONFIGURATION_TIMEOUT` | 설정 단계 시간 초과 | NewConnectionMayHelp | 상태 + 이벤트 |
| `NOT_READY` | 세션이 `Ready` 가 아닐 때 read/write/subscribe | NewConnectionMayHelp | 던짐 |
| `NOT_CONNECTED` | 작업 시작 시점에 연결이 없음 | NewConnectionMayHelp | 던짐 / 상태 + 이벤트(구독 복원) |
| `CHARACTERISTIC_NOT_FOUND` | 탐색 결과에 없는 참조, 또는 CCCD 없는 캐릭터리스틱 구독 | Never | 던짐 / 이벤트(구독 해제) / 상태 + `SubscriptionRestoreFailed`(구독 복원, 구독 의도에서 빠짐) |
| `GATT_OPERATION_START_FAILED` | Android 가 GATT 작업 시작을 거절 | Unknown | 던짐 / 이벤트(구독 해제 · MTU 요청 시작) / 상태 + 이벤트(구독 복원) |
| `GATT_STATUS_FAILED` | 기기가 오류 status 로 응답. 인증 · 암호화 부족(status 5 · 8 · 15)이면 AfterEnvironmentChange | Unknown / AfterEnvironmentChange | 던짐 / 이벤트(구독 해제) / 상태 + 이벤트(구독 복원) |
| `OPERATION_TIMEOUT` | GATT 작업 응답 시간 초과 — 연결도 끝남 | NewConnectionMayHelp | 던짐 + 상태 + 이벤트(시작된 read · write · subscribe) / 던짐(아직 줄에서 기다리던 작업) / 상태 + 이벤트(구독 해제 · 구독 복원) |
| `OPERATION_INVALIDATED` | 연결이 끊기거나 `disconnect()` 로 작업이 무효화됨. 시작된 작업을 호출자가 취소해도 이 코드로 연결이 끝남 | NewConnectionMayHelp | 던짐(read · write · subscribe, 주소 찾기 중 `disconnect()` 된 `connect`) / 상태 + 이벤트(시작된 작업을 호출자가 취소) / 이벤트(구독 해제) |
| `INTERNAL_TRANSPORT_ERROR` | 구독 복원 중 예상 밖 내부 오류 | Unknown | 상태 + 이벤트(구독 복원) |
| `DISCONNECT_START_FAILED` | 해제 요청 자체가 실패(정리는 계속됨) | NewConnectionMayHelp | 이벤트 |
| `DISCONNECT_CLEANUP_TIMEOUT` | 해제 콜백이 오지 않아 강제 정리 | NewConnectionMayHelp | 이벤트 |
| `DUPLICATE_CHARACTERISTIC_REFERENCE` | 탐색 결과에 같은 참조가 두 번 나옴(경고) | Never | 이벤트 |
| `GATT_OPERATION_CALLBACK_MISMATCH` | 대기 중인 작업과 다른 콜백이 옴(경고) | Unknown | 이벤트 |
| `GATT_OPERATION_DUPLICATE_CALLBACK` | 끝난 작업의 콜백이 또 옴(경고) | Unknown | 이벤트 |
| `CLEANUP_FAILURE` | `close()` 정리 중 오류 | Never | 이벤트, 로거 |
| `GATT_PAYLOAD_OBSERVED` | 실패가 아니라 페이로드 관측 기록 | Never | 로거만 |

### 5.13 진단 로그

**언제 쓰나.** 개발 중이나 현장 문제를 추적할 때, 코어 내부에서 무슨 일이 있었는지 기록으로 남길 때.

```kotlin
/** 디버그 빌드에서만 진단 로그를 켭니다. 원문 바이트는 safe 로 지정한 캐릭터리스틱만 남깁니다. */
fun bleConfigFor(debug: Boolean): BleConfig = BleConfig(
    logger = if (debug) BleLogger { record -> Log.d("ble-diag", formatDiagnostic(record)) } else null,
    diagnosticOptions = DiagnosticOptions(
        rawPayloadsEnabled = debug,
        maxRawPayloadBytes = 64,
        safeCharacteristics = setOf(BATTERY_LEVEL), // 원문을 남겨도 되는 것
        sensitiveCharacteristics = setOf(NUS_RX, NUS_TX), // 항상 가림(Redacted). safe 와 겹치면 이쪽이 이깁니다
    ),
)

fun formatDiagnostic(record: DiagnosticRecord): String {
    val failure = record.failure
    val values = record.values.entries.joinToString { (key, value) ->
        when (value) {
            is DiagnosticValue.Payload -> "$key=${value.bytes.joinToString("") { "%02x".format(it) }}(${value.originalLength}B)"
            else -> "$key=$value"
        }
    }
    return "[${record.severity}] ${failure.code.value} phase=${failure.phase} retry=${failure.retryability} $values"
}
```

**주의점**

- `logger` 를 주지 않으면(기본 `null`) 진단 기록을 만들지 않습니다.
- 로거는 코어 전용 백그라운드 스레드(`nucode-ble-diagnostics`)에서 불립니다. 로거가 예외를 던져도 BLE 동작에는 영향이 없습니다.
- 대기열(`queueCapacity`, 기본 64)이 차면 기록을 버리고, 같은 기록이 1초 안에 반복되면 하나만 남깁니다. 버리거나 줄인 개수는 나중에 `diagnostic_summary` · `diagnostic_count` 값이 담긴 요약 기록으로 알려 줍니다.
- "같은 기록"은 심각도 · 코드 · 단계 · 재시도 가능성 · `failure.context` · `platformDetail` · `writeProgress` 가 같은 기록입니다. `values`(페이로드 등)는 비교하지 않습니다.
  그래서 notify 수신 기록(`GATT_PAYLOAD_OBSERVED`, `rx`)은 한 세션(한 연결)에서 **캐릭터리스틱과 상관없이 1초에 하나 정도만** 남습니다. 수신 기록에는 작업 ID · 작업 종류 · 캐릭터리스틱 같은 구분 정보가 `context` 에 없기 때문입니다.
  나머지는 `diagnostic_summary = "suppressed"` 요약 기록의 개수로만 보입니다. notify 값을 빠짐없이 기록해야 하면 진단 로그 대신 `notifications()` 수집 쪽에서 기록하세요.
- **개인정보.** 기본값은 페이로드 원문을 남기지 않습니다. 페이로드 기록은 길이와 SHA-256 요약(`payload_digest`)만 담습니다.
  원문(`DiagnosticValue.Payload`)은 `rawPayloadsEnabled = true` **이고** 그 캐릭터리스틱이 `safeCharacteristics` 에 있을 때만, 최대 `maxRawPayloadBytes`(기본 256, 최대 1024)까지 남습니다.
  `sensitiveCharacteristics` 에 넣은 것은 항상 `Redacted` 이고, safe 와 겹치면 sensitive 가 이깁니다.
- 기록에는 기기 주소가 들어 있지 않습니다. 상관관계는 `failure.context` 의 세션 · 연결 세대 · 작업 ID 로 봅니다.
- 실제 기기 로그가 문제 추적에 충분한지, 개인정보가 새지 않는지는 아직 검토하지 않았습니다([7장](#7-한계와-검증-범위)).

### 5.14 스냅샷

**언제 쓰나.** 화면이 새로 만들어질 때(회전, 새 화면) 현재 상태를 한 번에 읽거나, 버그 리포트에 상태를 붙일 때.

```kotlin
suspend fun logSnapshot(client: BleClient) {
    val snapshot = client.snapshot()
    Log.i("ble", "adapter=${snapshot.adapterState} scanning=${snapshot.isScanning}")
    for (session in snapshot.sessions) {
        Log.i(
            "ble",
            "${session.peripheralId.value} ${session.connectionState} " +
                "services=${session.services.size} 구독의도=${session.subscriptions.size}",
        )
    }
}
```

**주의점**

- `SessionSnapshot.subscriptions` 는 **앱이 켜 둔 구독 의도**입니다. 지금 실제로 켜져 있는지는 `session.activeSubscriptions` 로 봅니다.
- `sessions` 에는 이 클라이언트에 한 번이라도 넘긴 `PeripheralId` 가 모두 들어 있습니다(끊긴 세션 포함).
- MTU · 청크 크기는 의도적으로 노출하지 않습니다.

### 5.15 ViewModel + Compose 연동

**언제 쓰나.** 화면에 스캔 목록 · 연결 상태 · 수신 값을 보여 줄 때. 여기서는 표준 Heart Rate 서비스(심박계)를 예로 듭니다.
Compose 와 ViewModel 은 라이브러리가 요구하는 의존성이 아닙니다. 이 예시는 앱에 `activity-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `material3` 가 있다고 가정합니다.

**모델과 UI 상태**

```kotlin
// 예시 UUID: Bluetooth SIG Heart Rate 서비스(0x180D)의 Heart Rate Measurement(0x2A37, notify)
val HEART_RATE_MEASUREMENT = CharacteristicRef(
    ServiceRef(BleUuid(UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB"))),
    BleUuid(UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")),
)

/** Heart Rate Measurement 값에서 bpm 을 꺼냅니다(flags bit0: 0 = 8비트 값, 1 = 16비트 값). */
fun parseHeartRate(bytes: ByteArray): Int? {
    if (bytes.size < 2) return null
    val sixteenBit = (bytes[0].toInt() and 0x01) != 0
    if (!sixteenBit) return bytes[1].toInt() and 0xFF
    if (bytes.size < 3) return null
    return (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
}

data class HeartRateUiState(
    val adapter: AdapterState = AdapterState.PoweredOff,
    val scanning: Boolean = false,
    val devices: List<ScanResult> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected(BleDisconnectReason.Initial),
    val receiving: Boolean = false,
    val bpm: Int? = null,
    val error: String? = null,
)
```

**ViewModel** — 라이브러리의 `StateFlow` 들을 화면 상태 하나(`StateFlow<HeartRateUiState>`)로 합칩니다.

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateViewModel(
    private val client: BleClient,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val devices = MutableStateFlow<List<ScanResult>>(emptyList())
    private val session = MutableStateFlow<BleSession?>(null)
    private val bpm = MutableStateFlow<Int?>(null)
    private val error = MutableStateFlow<String?>(null)
    private var receiver: Job? = null

    private val scanning: Flow<Boolean> = client.scanState.map { it is ScanState.Scanning }

    // 세션이 바뀌면 그 세션의 StateFlow 로 갈아탑니다.
    private val connection: Flow<ConnectionState> = session.flatMapLatest { opened ->
        opened?.connectionState ?: flowOf(ConnectionState.Disconnected(BleDisconnectReason.Initial))
    }
    private val receiving: Flow<Boolean> = session.flatMapLatest { opened ->
        opened?.activeSubscriptions?.map { HEART_RATE_MEASUREMENT in it } ?: flowOf(false)
    }

    val uiState: StateFlow<HeartRateUiState> =
        combine(client.adapterState, scanning, devices, connection, receiving) { adapter, isScanning, found, link, rx ->
            HeartRateUiState(adapter = adapter, scanning = isScanning, devices = found, connection = link, receiving = rx)
        }
            .combine(bpm) { state, value -> state.copy(bpm = value) }
            .combine(error) { state, message -> state.copy(error = message) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeartRateUiState(adapter = client.adapterState.value))

    /** 5초 동안 심박계를 찾습니다. 권한을 받은 직후에도 이것을 부릅니다(startScan 이 권한을 다시 판정). */
    fun scan() = launchReporting {
        devices.value = emptyList()
        client.startScan(ScanRequest(BleScanFilter(serviceUuids = listOf(HEART_RATE_MEASUREMENT.service.uuid))))
        try {
            withTimeoutOrNull(5_000) {
                client.scanResults.collect { result ->
                    devices.update { list ->
                        (list.filterNot { it.peripheralId == result.peripheralId } + result).sortedByDescending { it.rssi }
                    }
                }
            }
        } finally {
            client.stopScan()
        }
    }

    fun connect(peripheralId: PeripheralId) = launchReporting {
        disconnectCurrent()
        val opened = connectAndAwaitReady(client, peripheralId, BleConnectOptions(autoReconnect = true))
        session.value = opened
        receiver = startReceiving(viewModelScope, opened, HEART_RATE_MEASUREMENT) { bytes ->
            parseHeartRate(bytes)?.let { bpm.value = it }
        }
    }

    fun disconnect() = launchReporting { disconnectCurrent() }

    private suspend fun disconnectCurrent() {
        receiver?.cancel()
        receiver = null
        session.value?.disconnect()
        session.value = null
        bpm.value = null
    }

    private fun launchReporting(block: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = null
            try {
                block()
            } catch (e: BleException) {
                error.value = userMessageFor(e.failure)
            } catch (e: ConnectFailedException) {
                error.value = "연결하지 못했습니다"
            }
        }
    }

    override fun onCleared() {
        // viewModelScope 는 이미 취소됐으므로 연결 해제는 앱 수명 scope 에서 합니다.
        val opened = session.value ?: return
        appScope.launch { opened.disconnect() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MyApplication
                HeartRateViewModel(app.bleClient, app.appScope)
            }
        }
    }
}
```

**화면**

```kotlin
@Composable
fun HeartRateScreen(viewModel: HeartRateViewModel = viewModel(factory = HeartRateViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // adapterState 는 권한 변경을 스스로 알지 못합니다. 명령(startScan)이 다시 판정하게 합니다.
        viewModel.scan()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        adapterMessage(state.adapter)?.let { Text(it) }
        val adapter = state.adapter
        if (adapter is AdapterState.Unauthorized && adapter.canRequest) {
            Button(onClick = { permissionLauncher.launch(blePermissions()) }) { Text("권한 허용") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (val connection = state.connection) {
            ConnectionState.Ready -> {
                Text(state.bpm?.let { "$it bpm" } ?: "측정 대기 중", style = MaterialTheme.typography.displayMedium)
                if (!state.receiving) Text("수신이 꺼져 있습니다 — 다시 연결해 주세요")
                Button(onClick = viewModel::disconnect) { Text("연결 해제") }
            }
            is ConnectionState.Disconnected -> {
                Button(onClick = viewModel::scan, enabled = !state.scanning) {
                    Text(if (state.scanning) "찾는 중…" else "심박계 찾기")
                }
                LazyColumn {
                    items(state.devices, key = { it.peripheralId.value }) { device ->
                        Text(
                            text = "${device.advertisement.localName ?: "(이름 없음)"}  ${device.rssi} dBm",
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.connect(device.peripheralId) }.padding(12.dp),
                        )
                    }
                }
            }
            else -> {
                Text("연결 중… ($connection)")
                Button(onClick = viewModel::disconnect) { Text("취소") }
            }
        }
    }
}
```

**주의점**

- 화면 상태는 이벤트(`events`)가 아니라 `StateFlow`(`adapterState`, `scanState`, `connectionState`, `activeSubscriptions`)에서 만듭니다. 이벤트는 유실될 수 있습니다.
- 권한 결과 콜백에서 `scan()` 을 부르는 이유는 [2.3](#23-권한)의 주의 때문입니다. `adapterState` 만 보고 기다리지 마세요.
- ViewModel 이 사라질 때 `viewModelScope` 는 이미 취소됐으므로, 연결 해제는 앱 수명 scope(`MyApplication.appScope`)에서 합니다.
- `BleClient` 는 ViewModel 이 아니라 `Application` 이 소유합니다. ViewModel 에서 `close()` 하지 마세요.
- `Activity` 에서는 `setContent { MaterialTheme { HeartRateScreen() } }` 로 띄웁니다.

### 5.16 수명과 `close()`

**언제 쓰나.** 클라이언트를 완전히 끝낼 때. 대부분의 앱은 **부르지 않습니다.**

```kotlin
/** client 를 영구히 닫습니다. 닫은 client 는 다시 쓸 수 없습니다 — 필요하면 새로 만듭니다. */
suspend fun shutdownBle(client: BleClient): ClientSnapshot {
    client.close() // 즉시 돌아옵니다. 정리는 뒤에서 진행됩니다
    return client.snapshot() // 정리가 끝날 때까지 기다린 뒤 닫힌 시점의 스냅샷을 돌려줍니다
}
```

**언제 닫나**

- `BleClient` 는 `Application` 에서 하나 만들어 프로세스가 끝날 때까지 씁니다. 앱이 종료되면 Android 가 연결 자원을 회수합니다.
- 닫는 경우: 테스트 정리, 또는 사용자가 BLE 기능을 영구히 끈 경우처럼 다시 쓰지 않을 때.
- 닫은 뒤의 `session()` · `connect()` · `startScan()` · `read()` · `write()` · `subscribe()` · `unsubscribe()` 는 `CLIENT_CLOSED` 를 던집니다. `stopScan()` · `disconnect()` · `close()` 는 아무 일도 하지 않습니다. 다시 쓰려면 새 클라이언트를 만듭니다.

**동작**

- `close()` 는 여러 번 불러도 안전하고, 정리를 예약한 뒤 **바로 돌아옵니다.** 정리가 끝났다는 보장이 필요하면 `snapshot()` 을 부르세요. `snapshot()` 은 정리가 끝날 때까지 기다린 뒤 닫힌 시점의 스냅샷을 돌려줍니다.
- 스캔을 멈추고, 아직 끝나지 않은 세션(연결 중 · `Ready` · `Recovering` 등)은 `Disconnected(ClientClosed)` 로 끝냅니다. 이미 끝난 세션은 원래 이유를 유지합니다.
- 닫힌 뒤에는 `events` 로 (정리 실패 진단 `CLEANUP_FAILURE` 말고는) 아무것도 나오지 않습니다. 최종 상태는 `connectionState` 와 `snapshot()` 으로 확인합니다.
- `close()` 가 돌아온 순간에는 Android 연결 핸들 정리가 아직 끝나지 않았을 수 있습니다. 그 직후 프로세스가 바로 끝나면 남은 핸들은 Android 가 회수합니다.

---

## 6. 자주 틀리는 것 / FAQ

| 하지 말 것 | 대신 |
| --- | --- |
| Activity · ViewModel 마다 `BleClient` 생성 | `Application` 에서 하나 |
| `connect()` 가 돌아오자마자 read/write | `connectionState` 로 `Ready` 를 기다림([5.2](#52-연결하고-ready-까지-기다리기)) |
| `first { it is ConnectionState.Ready }` 만으로 대기 | 실패하면 영원히 기다립니다. `Disconnected` 도 함께 기다리고 시간 제한을 둡니다 |
| 권한을 받은 뒤 `adapterState` 가 `Ready` 가 되길 기다림 | 권한 결과에서 바로 `startScan` 등 명령을 부름([2.3](#23-권한)) |
| `subscribe()` 먼저, 수집 나중 | 수집을 `UNDISPATCHED` 로 먼저 걸고 `subscribe()` |
| write 한 뒤에 응답 수집 시작 | 응답 수집을 먼저 등록하고 write |
| `read`/`write` 를 짧은 `withTimeout` 으로 감쌈 | 시작된 작업을 취소하면 연결이 끊깁니다. 코어의 `operationTimeoutMillis` 를 믿거나 `BleConfig` 에서 조정 |
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

**Q. 블루투스를 껐다 켰더니 스캔 결과가 안 옵니다.**
블루투스가 꺼지면 스캔은 `Idle(SCAN_STOPPED_BY_ADAPTER)` 로 멈추고 자동으로 다시 시작하지 않습니다. `startScan()` 을 다시 부르세요.

**Q. 앱 재시작 뒤 저장해 둔 기기로 바로 연결하면 느리거나 `PERIPHERAL_NOT_FOUND` 가 납니다.**
처음 보는 주소라서 코어가 먼저 짧은 스캔으로 기기를 찾기 때문입니다. 기기가 광고 중인지 확인하고, 필요하면 `BleConfig(rediscoveryTimeoutMillis = …)` 를 늘립니다(`linkTimeoutMillis` 이하).

**Q. 타임아웃 값을 바꾸고 싶습니다.**
`AndroidBleClientFactory.create(context, BleConfig(operationTimeoutMillis = 10_000))` 처럼 `BleConfig` 로 바꿉니다. 모든 값은 0보다 커야 하고 `rediscoveryTimeoutMillis ≤ linkTimeoutMillis` 여야 합니다(아니면 `IllegalArgumentException`).

**Q. MTU 를 직접 정할 수 있나요?**
없습니다. 코어가 247 을 요청하고, 협상에 실패하면 23 으로 진행합니다(`CONFIGURATION_FAILED` 진단). 쓰기 청크는 코어가 맞춰 자릅니다.

**Q. 스캔 결과가 여러 번 옵니다.**
정상입니다. 광고를 받을 때마다 옵니다. `peripheralId` 로 묶어서 최신 값만 쓰세요([5.1](#51-스캔-필터로-찾고-가장-가까운-기기-고르기)).

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

### 알려진 결함

- **서비스 데이터가 서비스 UUID 목록에 없으면 보이지 않습니다.** `AdvertisementData.serviceData` 는 광고의 서비스 UUID 목록에 있는 UUID 만 채웁니다. 서비스 데이터로만 광고된 UUID 는 빠지고, 그 UUID 의 `BleServiceDataFilter` 는 결과를 내지 않습니다([5.1](#51-스캔-필터로-찾고-가장-가까운-기기-고르기)). 코드로 확인했고 실기기에서는 아직 재현하지 않았습니다.

### 배포 · 난독화

- **배포:** `./gradlew :core:publishReleasePublicationToMavenLocal` → `~/.m2/repository/kr/co/nucode/ble-core/0.2.0/`. 원격 저장소 · 태그는 아직 없습니다.
- **라이브러리는 난독화하지 않습니다.** `isMinifyEnabled = false`, `consumer-rules.pro` 는 비어 있고 sources.jar 를 함께 배포합니다. 난독화는 앱의 R8 에 맡깁니다.
- **앱 R8 호환은 빌드 단계만 확인했습니다.** sample release 를 `isMinifyEnabled = true`(`proguard-android-optimize.txt`)로 빌드해 R8 경고 · 누락 클래스 없이 통과했고, core 클래스가 난독화됐습니다. core 에 리플렉션 · 직렬화 · ServiceLoader 가 없어 keep 규칙이 필요 없었습니다.
- **검증 필요:** 난독화된 release APK 의 **런타임** 동작(스캔 · 연결 · read/write · 구독)은 아직 확인하지 않았습니다. R8 을 켠 앱은 release 빌드로 실기기에서 한 번 확인하세요.

### 관련 문서

- [CHANGELOG](../CHANGELOG.md) — 0.2.0 변경 사항과 마이그레이션
- [공개 API 시그니처](../core/api/ble-core.api) — 테스트가 소스와 대조하는 공개 API 기준
- `guide/llms.md` — 다른 LLM 세션에 줄 영문 API 요약(로컬 전용 문서라 git 에는 없습니다)
