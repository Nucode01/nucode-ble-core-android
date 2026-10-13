# 레시피: 스캔과 연결

> 앞 레시피의 도우미(`connectAndAwaitReady`, `startReceiving` 등)를 뒤 레시피에서 다시 씁니다. 처음이면 [스캔과 연결](scan-connect.md)부터 보세요.

## 5.1 스캔: 필터로 찾고 가장 가까운 기기 고르기

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
- Android 는 30초에 스캔 시작 5회를 넘기면 조용히 결과를 끊습니다. 코어가 이를 세어 넘는 시작을 `SCAN_START_THROTTLED` 로 거절합니다([5.12](errors-diagnostics.md#512-실패-처리-패턴)의 `startScanPolitely`).
- 블루투스가 꺼지면 스캔은 `Idle(SCAN_STOPPED_BY_ADAPTER)` 로 멈추고, 다시 켜져도 **자동으로 다시 시작하지 않습니다.**
- RSSI 는 순간값이라 흔들립니다. "가장 가까운"은 그 순간 신호가 가장 센 기기라는 뜻입니다.
- `advertisement.serviceData` 에는 광고에 실린 서비스 데이터가 전부 들어옵니다. 서비스 UUID 목록에 없고 서비스 데이터로만 실린 UUID(비콘 등)도 포함되므로, 그런 UUID 로 `BleServiceDataFilter` 를 걸어도 됩니다. 이 동작은 코드로 확인했고 실기기 재확인은 아직입니다(아래 [7장](../troubleshooting.md#7-한계와-검증-범위)).

## 5.2 연결하고 `Ready` 까지 기다리기

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
| `BleConnectOptions(autoReconnect = true)` | `Recovering` 으로 OS 재연결을 무기한 기다림 | **없음** | 기기가 범위를 오가도 계속 붙어 있어야 할 때([5.9](connection.md#59-끊김과-블루투스-offon-을-넘어-연결-유지)) |

**주의점**

- 이미 연결 중이거나 `Ready` 인 세션에 **같은 옵션**으로 `connect()` 하면 아무 일도 하지 않고 바로 돌아옵니다. **다른 옵션**이면 `CONNECTION_OPTIONS_CONFLICT` 입니다. 옵션을 바꾸려면 `disconnect()` 뒤 다시 연결합니다.
- 해제 정리 중(`Disconnecting`)에 `connect()` 하면 `CONNECT_DURING_DISCONNECT`(`TransientMayHelp`)입니다. 정리가 끝난 뒤 다시 부르면 됩니다.
- 기본 `timeoutMillis` 40초는 기본 단계 제한(링크 15 + 탐색 10 + 설정 10초)보다 길게 잡은 값입니다. 더 짧으면 코어가 아직 진행 중인 시도를 앱이 먼저 끊게 됩니다.

## 5.3 앱이 다시 시도하는 연결

**언제 쓰나.** `autoReconnect = false` 로 연결하면서 일시적인 실패는 몇 번 더 시도하고 싶을 때. (0.2.0 에서 라이브러리 재연결 정책이 없어졌으므로 앱이 이렇게 합니다.)

**라이브러리가 먼저 다시 거는 실패.** 첫 연결이 링크를 맺지 못하면(HCI 0x3E) Android 는 연결 요청 직후 GATT status 133 을 줍니다.
`autoReconnect = false` 연결이 `Connecting` 에서 요청 뒤 5초 안에 133 을 받으면 라이브러리가 handle 을 닫고 최대 2번 다시 겁니다.
그동안 상태는 `Connecting` 그대로이고, 시도마다 `Diagnostic(Warning, LINK_FAILED)` 만 남습니다. 남은 link 시간 제한이 5초보다 짧으면 다시 걸지 않습니다.
세 번 모두 133 이면 `Disconnected(Failed(LINK_FAILED))` 로 끝나고, 그때 아래 코드가 다음 시도를 맡습니다. iOS 는 OS 가 연결 요청을 유지하므로 이 단계가 없습니다.

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

## 5.4 서비스 탐색과 캐릭터리스틱 찾기

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
