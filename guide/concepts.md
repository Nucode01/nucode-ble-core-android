# 4. 핵심 개념

## 4.1 `BleClient` 와 `BleSession`

| | `BleClient` | `BleSession` |
| --- | --- | --- |
| 무엇 | 라이브러리 진입점. 프로세스당 하나 | 기기 하나를 보는 창 |
| 만드는 법 | `AndroidBleClientFactory.create(context, config)` | `client.connect(id)` 또는 `client.session(id)` |
| 하는 일 | 어댑터 상태, 스캔, 이벤트, 스냅샷, 전체 종료 | 연결 · 해제, read · write, 구독, 기기별 상태 |
| 수명 | `close()` 하면 끝(되살릴 수 없음) | 가벼운 뷰. 같은 `PeripheralId` 로 여러 번 얻어도 같은 상태를 봅니다 |

- `client.connect(id, options)` 는 `client.session(id).connect(options)` 와 같고, 세션을 돌려줍니다.
- `client.session(id)` 는 연결하지 않고 세션만 얻습니다(일시 중단 함수가 아닙니다). 연결 전 상태 관찰이나 이미 연결된 기기의 세션을 다시 얻을 때 씁니다.
- 한 번 `session()`/`connect()` 에 넘긴 `PeripheralId` 는 `close()` 전까지 클라이언트에 남습니다(`snapshot().sessions` 에 보입니다).

## 4.2 `PeripheralId`

기기를 가리키는 불투명한 ID 입니다. Android 에서는 Bluetooth 주소 문자열(`"AA:BB:CC:DD:EE:FF"` 형식, 대문자)입니다.

- 스캔 결과의 `ScanResult.peripheralId` 를 그대로 쓰세요. 저장할 때는 `peripheralId.value` 를 저장하고 `PeripheralId(saved)` 로 되살립니다.
- 라이브러리가 입구에서 id 를 대문자 MAC 으로 정규화합니다. 소문자로 만든 `PeripheralId` 와 대문자 것은 같은 세션이고, 세션 · 이벤트 · snapshot 의 id 는 대문자입니다. 저장은 `peripheralId.value` 를 그대로 하면 됩니다.
- 주소가 틀리면 `connect()` 가 결국 실패하지만 바로 실패하지는 않습니다. 처음 보는 주소라 먼저 아래의 주소 찾기 스캔을 거치기 때문입니다.
  MAC 형식이 아니면 스캔 없이 `Diagnostic`(`SCAN_START_FAILED`)을 남긴 뒤 `UNKNOWN_DEVICE` 로 실패합니다.
  `UNKNOWN_DEVICE` 의 category 는 validation 입니다.
- 이 클라이언트가 최근에 본 적 없는 주소(앱 재시작 뒤 저장된 ID 등)로 연결하면, 코어가 먼저 그 주소만 찾는 짧은 스캔(기본 3초, `BleConfig.rediscoveryTimeoutMillis`)을 돌립니다.
  못 찾으면 `autoReconnect = false` 는 `PERIPHERAL_NOT_FOUND` 를 던지고, `autoReconnect = true` 는 진단(`Diagnostic`)만 남기고 OS 대기로 넘어갑니다.

## 4.3 `ServiceRef` · `CharacteristicRef` · `instanceIndex`

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

## 4.4 `ConnectionState` 생애주기

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

## 4.5 `AdapterState`

| 값 | 뜻 | 앱이 할 일 |
| --- | --- | --- |
| `Ready` | 쓸 수 있음 | — |
| `PoweredOff` | 블루투스 꺼짐 | 켜 달라고 안내 |
| `Unauthorized(canRequest = true)` | 선언은 됐고 런타임 허용이 안 됨 | 권한 요청 |
| `Unauthorized(canRequest = false)` | 매니페스트 선언 누락 | 앱을 고쳐야 함 |
| `Unsupported` | 블루투스 어댑터 없음 | 기능 숨김 |
| `Resetting` | 어댑터 재시작 중 | 기다림. (현재 Android 구현은 이 값을 만들지 않습니다) |
| `Unknown` | 아직 판정 전 | 기다림. (현재 Android 구현은 이 값을 만들지 않습니다.) 명령은 최대 2초 판정을 기다린 뒤 `ADAPTER_UNAVAILABLE` 로 실패합니다. `when` 에 이 분기가 있어야 컴파일됩니다 |

판정 순서는 `Unsupported` → `Unauthorized` → `PoweredOff`/`Ready` 입니다. 권한이 없으면 블루투스가 꺼져 있어도 `Unauthorized` 입니다.

## 4.6 상태와 흐름: 어디서 무엇을 읽나

| 이름 | 타입 | 성질 |
| --- | --- | --- |
| `client.adapterState` | `StateFlow<AdapterState>` | 항상 최신값. 갱신 시점은 [2.3](install.md#23-권한)의 주의 참고 |
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

## 4.7 실패: `BleException` · `BleFailure` · `FailureCode` · `Retryability`

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

- `IllegalArgumentException`: 모델 생성 시 잘못된 값(예: `BleChunking.Fixed(0)`, 음수 `instanceIndex`). `BleChunking.Disabled` 로 한 청크보다 긴 데이터를 쓰거나 `BleChunking.Fixed(n)` 의 n 이 협상된 청크 크기보다 크면 `write` 는 `BleException(PAYLOAD_TOO_LARGE)` 를 던집니다(아무것도 보내지 않음).
- `SecurityException`: 권한 없이 `AndroidBondController.bondState()` 를 부를 때. `createBond` 는 플랫폼 예외를 던지지 않고 `StartRejected`/`Failed` 로 돌려줍니다.
- `CancellationException`: 호출한 코루틴의 Job 이 취소될 때. 앱이 건 `withTimeout` 이 만료되면 모든 명령에서 `TimeoutCancellationException` 을 그대로 받습니다(`BleException` 으로 바꾸지 않습니다). `OPERATION_TIMEOUT` 은 큐의 작업 기한에서만 납니다.

코드별 의미와 재시도 가능성은 [5.12](recipes/errors-diagnostics.md#512-실패-처리-패턴)의 표에 있습니다.

## 4.8 스레드와 코루틴

- 모든 공개 함수는 **어느 스레드에서 불러도 됩니다**(메인 포함). 내부 상태는 코어 전용 단일 스레드 디스패처에서만 바뀝니다. 호출 스레드를 막지 않습니다.
- Flow 값은 **수집하는 쪽의 디스패처**에서 받습니다. 화면에 그릴 값이면 Main 에서 수집하세요.
- 한 세션의 GATT 작업(read · write 의 모든 청크 · 구독 · 구독 해제)은 한 줄로 서서 하나씩 실행됩니다. 긴 write 의 청크 사이에 다른 작업이 끼어들지 않습니다. 서로 다른 세션은 따로 진행됩니다.
- 각 GATT 작업은 시작된 뒤 `operationTimeoutMillis`(기본 5초) 안에 응답이 와야 합니다. 넘기면 `OPERATION_TIMEOUT` 을 던지고 **그 연결을 끝냅니다.**
- **호출자를 끊어도 접수된 GATT 작업은 끝까지 진행됩니다.** 작업이 세션 줄에 들어간 뒤 호출자가 취소되면 호출자만 떨어지고 세션은 끝나지 않습니다. 대기 중이던 작업도 차례가 오면 시작되고, 긴 write 의 나머지 조각도 모두 나갑니다. 취소한 `subscribe` 는 완료되어 `activeSubscriptions` 와 구독 의도에 남고 재연결마다 복원됩니다. 되돌리려면 `unsubscribe` 를 부릅니다.
  - Job 취소: 호출자는 `CancellationException`, 세션은 그대로입니다.
  - 앱의 `withTimeout` 만료(모든 GATT 작업): 호출자는 `TimeoutCancellationException`, 작업은 계속됩니다.
  - 떠난 작업이 응답을 못 받으면 큐의 기한(`operationTimeoutMillis`)이 끝냅니다. 그때만 `OPERATION_TIMEOUT` 으로 연결이 끝납니다.
- GATT 작업이 아닌 `connect` 는 주소 찾기 스캔 중에 끊으면 그 연결 시도를 정리하고 `CancellationException`(`withTimeout` 이면 `TimeoutCancellationException`)을 그대로 던집니다. `startScan` 도 변환하지 않습니다.
- `stopScan()` 과 `disconnect()` 는 호출 코루틴이 이미 취소됐어도 정리를 끝까지 한 뒤 취소를 전파합니다. `finally` 에서 그냥 불러도 됩니다.
- `BleConfig.dispatcher` 는 테스트용 주입 지점입니다. 앱에서는 넣지 마세요. 넣는다면 `Job` 없이 단일 스레드 디스패처만 넣어야 합니다.
