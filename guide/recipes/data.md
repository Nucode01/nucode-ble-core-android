# 레시피: 읽기 · 쓰기 · 구독

> 앞 레시피의 도우미(`connectAndAwaitReady`, `startReceiving` 등)를 뒤 레시피에서 다시 씁니다. 처음이면 [스캔과 연결](scan-connect.md)부터 보세요.

## 5.5 read

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
- `read()` 를 짧은 `withTimeout` 으로 감싸지 마세요. 호출자를 떼어 낼 뿐 read 는 계속됩니다([4.8](../concepts.md#48-스레드와-코루틴)). 코어가 `operationTimeoutMillis` 로 이미 제한합니다.
- 암호화가 필요한 캐릭터리스틱은 코드상 `GATT_STATUS_FAILED` + `AfterEnvironmentChange` 로 실패하도록 매핑돼 있습니다. 실기기에서는 확인하지 않았습니다(Android 가 스스로 페어링을 시작할 수도 있고, GATT status 8 은 검증 필요). 이 실패를 받으면 본딩([5.11](connection.md#511-본딩)) 뒤 다시 연결하세요.

## 5.6 write: 응답 있음/없음, 긴 데이터, 결과 확인

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
| | `Fixed(maxBytes)` | `maxBytes` 씩 자릅니다. 협상된 청크 크기보다 크면 `PAYLOAD_TOO_LARGE` |
| | `Disabled` | 자르지 않습니다. 한 청크보다 길면 `PAYLOAD_TOO_LARGE` |

지금 연결의 한도는 `session.writeLimits.value` 로 읽습니다. `WriteLimits(withResponse, withoutResponse)` 는 그 mode 의 write 한 번이 실을 수 있는
최대 바이트이고 `Automatic` 조각 크기·`Disabled`·`Fixed` 한도가 이 값을 씁니다. Ready 가 되기 직전에 정해지고 Ready 를 떠나면 `null` 입니다.
Android 는 두 값 모두 협상 MTU − 3 이고, MTU 숫자는 Android 전용 확장 `session.androidMtu` 로 봅니다. 요청 MTU 는 Android 전용
`BleConnectOptions(requestedMtu = …)` 로 고릅니다.

**주의점**

- 청크는 각각 별개의 GATT write 로 나갑니다. 기기 펌웨어가 조각을 이어 붙여야 합니다(코어는 프레이밍을 하지 않습니다).
- 실패했을 때 `writeProgress` 가 `null` 이면 한 바이트도 나가지 않은 것입니다. `null` 이 아니면 `bytesCompleted` 까지는 확실히 전달됐고, `failedChunkIndex` 번 청크는 **전달됐는지 알 수 없습니다**(`currentChunkDeliveryUnknown`). 이 경우 무작정 처음부터 다시 보내면 기기가 같은 데이터를 두 번 받을 수 있습니다. 기기 프로토콜(시퀀스 번호, 상태 조회 등)로 확인하세요.
- `write()` 를 짧은 `withTimeout` 으로 감싸지 마세요. 청크마다 `operationTimeoutMillis`(기본 5초)가 이미 적용됩니다.
- 빈 배열을 써도 청크 1개짜리 write 로 나갑니다.

## 5.7 notify / indicate 구독

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

### descriptor 읽기·쓰기

**언제 쓰나.** 캐릭터리스틱 설명(CUD 0x2901), 표시 형식(CPF 0x2904), 기기 전용 descriptor 처럼 캐릭터리스틱 아래 값을 읽고 쓸 때.

- 탐색이 끝나면 `services` 의 `GattCharacteristic.descriptors` 에 descriptor 가 탐색 순서대로 들어 있습니다. CCCD(0x2902)도 숨기지 않습니다.
  `DescriptorRef(characteristic, uuid, instanceIndex)` 로 가리키고, `instanceIndex` 는 같은 캐릭터리스틱 아래 같은 UUID descriptor 사이의 순번입니다.
- `session.readDescriptor(ref)` 는 원본 바이트를 돌려줍니다. 0x2901 문자열, 0x2904 구조 해석은 앱이 합니다.
- `session.writeDescriptor(ref, value)` 는 응답 있는 쓰기 한 번이고 나누지 않습니다. 512바이트를 넘으면 `PAYLOAD_TOO_LARGE` 로 아무것도 보내지 않습니다.
- read·write 와 같은 GATT 큐에서 같은 작업 기한·끊김 규칙으로 실행합니다. 탐색 결과에 없는 ref 는 `CHARACTERISTIC_NOT_FOUND` 입니다.
- 진단 기록(`GATT_PAYLOAD_OBSERVED`)의 원문 공개 여부는 그 descriptor 가 속한 캐릭터리스틱의 `safeCharacteristics`·`sensitiveCharacteristics` 를 따릅니다.

**CCCD(0x2902) 직접 쓰기 — Android 만.**

- 받은 값을 그대로 씁니다. `0x0003` 이나 비표준 값도 바꾸지 않고, properties 로 거르지 않습니다.
- 값이 0 이 아니면 로컬 알림 수신을 켜고 0 이면 끕니다. 기기가 성공으로 응답한 뒤에만 `activeSubscriptions` 와 구독 의도를 고칩니다.
- 구독 의도는 마지막으로 쓴 값을 기억하고 재연결 복원 때 그 값을 그대로 다시 씁니다. `subscribe()`·`unsubscribe()` 와 같은 큐에서 나중에 끝난 작업이 상태를 정합니다.
- 진단 logger 에는 이 쓰기로 구독이 켜지거나 꺼질 때 값 키 `subscription_change`(`enabled`·`disabled`)가 든 기록이 하나 남습니다.
- 구독만 필요하면 `subscribe()` 를 쓰세요. iOS 는 OS 제약으로 CCCD 쓰기를 할 수 없습니다(iOS 구현 때 거절 코드가 생깁니다). 읽기(`readDescriptor`)는 두 플랫폼이 같습니다.

## 5.8 요청-응답 (write 하고 notify 로 답 받기, 예: NUS)

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
- 시간 제한은 응답 대기(`reply.await()`)에만 겁니다. `session.write` 까지 감싸면 만료 때 호출자만 떨어지고 write 의 나머지 조각은 계속 나갑니다.
- 이 예시는 "첫 notify 하나 = 응답"이라고 가정합니다. 응답이 여러 notify 로 나뉘어 오거나 요청하지 않은 알림이 섞이는 기기라면 앱 프로토콜에 맞는 조립 · 매칭이 필요합니다.
- `Mutex` 로 요청을 한 번에 하나만 보냅니다. 동시에 보내면 응답이 뒤섞입니다.
