# 레시피: 실패 처리 · 진단 · 스냅샷

> 앞 레시피의 도우미(`connectAndAwaitReady`, `startReceiving` 등)를 뒤 레시피에서 다시 씁니다. 처음이면 [스캔과 연결](scan-connect.md)부터 보세요.

## 5.12 실패 처리 패턴

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
| `NOT_READY` | 세션이 `Ready` 가 아닐 때 read/write/subscribe(단계 무관, 링크 유실 직후 1000ms 포함). 블루투스가 꺼져 있으면 이 코드가 아니라 `ADAPTER_UNAVAILABLE` | NewConnectionMayHelp | 던짐 |
| `NOT_CONNECTED` | 받아들인 작업이 시작하려는 순간 연결이 없음(명령 진입 검사에서는 나지 않음) | NewConnectionMayHelp | 던짐 / 상태 + 이벤트(구독 복원) |
| `CHARACTERISTIC_NOT_FOUND` | 탐색 결과에 없는 참조, 또는 CCCD 없는 캐릭터리스틱 구독 | Never | 던짐 / 이벤트(구독 해제) / 상태 + `SubscriptionRestoreFailed`(구독 복원, 구독 의도에서 빠짐) |
| `GATT_OPERATION_START_FAILED` | Android 가 GATT 작업 시작을 거절 | Unknown | 던짐 / 이벤트(구독 해제 · MTU 요청 시작) / 상태 + 이벤트(구독 복원) |
| `GATT_STATUS_FAILED` | 기기가 오류 status 로 응답. 인증 · 암호화 부족(status 5 · 8 · 15)이면 AfterEnvironmentChange | Unknown / AfterEnvironmentChange | 던짐 / 이벤트(구독 해제) / 상태 + 이벤트(구독 복원) |
| `OPERATION_TIMEOUT` | GATT 작업 응답 시간 초과 — 연결도 끝남 | NewConnectionMayHelp | 던짐 + 상태 + 이벤트(시작된 read · write · subscribe) / 던짐(아직 줄에서 기다리던 작업) / 상태 + 이벤트(구독 해제 · 구독 복원) |
| `OPERATION_INVALIDATED` | 연결이 끊기거나 `disconnect()` 로 작업이 무효화됨. 호출자 취소만으로는 나지 않음 | NewConnectionMayHelp | 던짐(read · write · subscribe, 주소 찾기 중 `disconnect()` 된 `connect`) / 이벤트(구독 해제) |
| `INTERNAL_TRANSPORT_ERROR` | 구독 복원 중 예상 밖 내부 오류 | Unknown | 상태 + 이벤트(구독 복원) |
| `DISCONNECT_START_FAILED` | 해제 요청 자체가 실패(정리는 계속됨) | NewConnectionMayHelp | 이벤트 |
| `DISCONNECT_CLEANUP_TIMEOUT` | 해제 콜백이 오지 않아 강제 정리 | NewConnectionMayHelp | 이벤트 |
| `DUPLICATE_CHARACTERISTIC_REFERENCE` | 탐색 결과에 같은 참조가 두 번 나옴(경고) | Never | 이벤트 |
| `GATT_OPERATION_CALLBACK_MISMATCH` | 대기 중인 작업과 다른 콜백이 옴(경고) | Unknown | 이벤트 |
| `GATT_OPERATION_DUPLICATE_CALLBACK` | 끝난 작업의 콜백이 또 옴(경고) | Unknown | 이벤트 |
| `CLEANUP_FAILURE` | `close()` 정리 중 오류 | Never | 이벤트, 로거 |
| `GATT_PAYLOAD_OBSERVED` | 실패가 아니라 페이로드 관측 기록 | Never | 로거만 |

## 5.13 진단 로그

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
- `Connecting` 의 링크 status 실패 기록(133 재시도 때마다 나가는 Warning, 링크 status 실패로 연결을 끝내는 Error)에는 `values` 에 `link_request_elapsed_ms`(이번 시도의 연결 요청 뒤 경과 ms, `DiagnosticValue.Number`)가 실립니다.
  "요청 직후 133" 인지 "한참 뒤 133" 인지 가르는 데 씁니다. logger 기록에만 있고 `events` 의 `Diagnostic` 에는 없습니다.
- 기록에는 기기 주소가 들어 있지 않습니다. 상관관계는 `failure.context` 의 세션 · 연결 세대 · 작업 ID 로 봅니다.
- 실제 기기 로그가 문제 추적에 충분한지, 개인정보가 새지 않는지는 아직 검토하지 않았습니다([7장](../troubleshooting.md#7-한계와-검증-범위)).

## 5.14 스냅샷

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
