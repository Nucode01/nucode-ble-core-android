# nucode BLE Core 개발자 가이드 (0.2.x)

이 문서는 nucode BLE Core 를 처음 보는 Android 개발자가 이 가이드만 따라 해서 앱에 붙일 수 있도록 쓴 안내서입니다.
설치부터 스캔 · 연결 · 읽기 · 쓰기 · 알림 수신 · 실패 처리 · 화면 연동까지 순서대로 다룹니다.

> **예시 코드에 대해.** 이 문서의 Kotlin 예시는
> [`sample/.../GuideSnippets.kt`](../sample/src/main/kotlin/kr/co/nucode/ble/sample/GuideSnippets.kt)와
> [`GuideUiSnippets.kt`](../sample/src/main/kotlin/kr/co/nucode/ble/sample/GuideUiSnippets.kt)의
> 같은 이름 `// region guide:…` 블록과 글자 그대로 같고, `./gradlew :sample:assembleDebug` 로 컴파일이 확인됩니다.
> 지면을 아끼려고 `import` 문은 뺐습니다. 필요한 import 는 두 파일 상단에 있습니다.
> 예시 UUID(Nordic UART Service, Battery, Heart Rate)는 **예시**입니다. 실제 기기의 UUID 로 바꿔 쓰세요.

## 목차

| 문서 | 내용 |
|---|---|
| [설치](install.md) | JitPack 에서 받기, 앱에 추가, 권한 |
| [핵심 개념](concepts.md) | `BleClient`·`BleSession`, 주소, 캐릭터리스틱 참조, 상태, 실패, 스레드 |
| [레시피: 스캔과 연결](recipes/scan-connect.md) | 5.1 스캔 필터 · 5.2 연결하고 Ready 기다리기 · 5.3 재시도 연결 · 5.4 서비스 찾기 |
| [레시피: 읽기 · 쓰기 · 구독](recipes/data.md) | 5.5 read · 5.6 write · 5.7 notify/indicate · 5.8 요청-응답 |
| [레시피: 연결 유지 · 여러 기기 · 본딩](recipes/connection.md) | 5.9 끊김·블루투스 off/on 넘기기 · 5.10 여러 기기 · 5.11 본딩 |
| [레시피: 실패 처리 · 진단 · 스냅샷](recipes/errors-diagnostics.md) | 5.12 실패 처리 패턴 · 5.13 진단 로그 · 5.14 스냅샷 |
| [레시피: 앱 구조에 넣기](recipes/app-integration.md) | 5.15 ViewModel + Compose · 5.16 수명과 `close()` |
| [문제 해결과 한계](troubleshooting.md) | 6 자주 틀리는 것 / FAQ · 7 한계와 검증 범위 |

처음이면 이 페이지의 소개와 빠른 시작 → 설치 → 핵심 개념 → 필요한 레시피 순서로 읽으세요.


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
  예외 하나: `autoReconnect = false` 첫 연결이 요청 직후 GATT status 133 으로 실패하면 라이브러리가 같은 link 시간 제한 안에서 최대 2번 다시 겁니다([5.3](recipes/scan-connect.md#53-앱이-다시-시도하는-연결)).
- **MTU 값 지정, 광고 원본 바이트, Android `autoConnect` 플래그 직접 노출.** 공통 API 에 없습니다.
- **Maven Central 배포.** JitPack 으로만 냅니다([설치](install.md)).

### 지원 환경

| 항목 | 값 |
| --- | --- |
| minSdk | 24 (Android 7.0) |
| compileSdk (라이브러리 빌드) | 36 |
| 의존성 | `kotlinx-coroutines-android` 1.9.0 (`api` 로 전달되므로 앱에서 따로 선언하지 않아도 됩니다) |
| 라이브러리 빌드 도구 | AGP 8.13.2 · Kotlin 2.0.21 · JVM 11 |
| 좌표 | JitPack `com.github.Nucode01:nucode-ble-core-android:0.3.1`. 로컬 Maven 은 `kr.co.nucode:ble-core:<버전>` — 기존 `kr.co.nucode:ble`(1.x)와 **다른** artifactId 입니다 |

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
