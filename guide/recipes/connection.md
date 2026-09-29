# 레시피: 연결 유지 · 여러 기기 · 본딩

> 앞 레시피의 도우미(`connectAndAwaitReady`, `startReceiving` 등)를 뒤 레시피에서 다시 씁니다. 처음이면 [스캔과 연결](scan-connect.md)부터 보세요.

## 5.9 끊김과 블루투스 off/on 을 넘어 연결 유지

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

## 5.10 여러 기기에 동시에 연결

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

## 5.11 본딩

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
  `bondState()` 는 주소 형식이 틀리면 `IllegalArgumentException`, 블루투스 어댑터가 없는 기기면 `NullPointerException` 입니다. `createBond` 는 던지지 않고 `StartRejected`/`Failed` 로 돌려줍니다.
- 기기 쪽에서 본딩 정보를 지웠는데 폰에 남아 있으면 연결이나 암호화가 실패할 수 있습니다. 이때는 Android 설정에서 기기를 지우고 다시 본딩해야 합니다.
