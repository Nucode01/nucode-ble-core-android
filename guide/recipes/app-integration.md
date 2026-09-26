# 레시피: 앱 구조에 넣기

> 앞 레시피의 도우미(`connectAndAwaitReady`, `startReceiving` 등)를 뒤 레시피에서 다시 씁니다. 처음이면 [스캔과 연결](scan-connect.md)부터 보세요.

## 5.15 ViewModel + Compose 연동

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
- 권한 결과 콜백에서 `scan()` 을 부르는 이유는 [2.3](../install.md#23-권한)의 주의 때문입니다. `adapterState` 만 보고 기다리지 마세요.
- ViewModel 이 사라질 때 `viewModelScope` 는 이미 취소됐으므로, 연결 해제는 앱 수명 scope(`MyApplication.appScope`)에서 합니다.
- `BleClient` 는 ViewModel 이 아니라 `Application` 이 소유합니다. ViewModel 에서 `close()` 하지 마세요.
- `Activity` 에서는 `setContent { MaterialTheme { HeartRateScreen() } }` 로 띄웁니다.

## 5.16 수명과 `close()`

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
