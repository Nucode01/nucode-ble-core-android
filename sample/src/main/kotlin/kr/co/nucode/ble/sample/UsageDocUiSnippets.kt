@file:Suppress("unused")

package kr.co.nucode.ble.sample

/*
 * 사용법 문서(docs/ble-core-usage.md) §14 — 라이브러리에서 UI까지 잇는 예시의 컴파일 검증본.
 * Nordic UART Service(NUS) 기기 하나를 찾아 연결하고 문자열을 주고받는 터미널 화면이다.
 * 샘플 앱의 매니페스트에는 등록하지 않는다 — 컴파일되는 것 자체가 목적이다.
 */

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.android.AndroidBleClientFactory

// ── §14.1 UUID ─────────────────────────────────────────────────────────
object Nus {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // 앱 → 기기 (write)
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // 기기 → 앱 (notify)
}

// ── §14.2 Application: 클라이언트와 Repository 를 한 번만 만든다 ────────────────────────
class NusApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: NusRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val client = AndroidBleClientFactory.create(this, BleConfig())
        repository = NusRepository(client, appScope)
    }
}

// ── §14.3 Repository: 라이브러리를 앱의 언어로 바꾼다 ──────────────────────────────
data class Message(val incoming: Boolean, val text: String)

@OptIn(ExperimentalCoroutinesApi::class)
class NusRepository(
    private val client: BleClient,
    private val scope: CoroutineScope,
) {
    private val session = MutableStateFlow<BleSession?>(null)
    private val _devices = MutableStateFlow<List<ScanResult>>(emptyList())
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var notificationJob: Job? = null

    val adapterState: StateFlow<AdapterState> = client.adapterState
    val devices: StateFlow<List<ScanResult>> = _devices.asStateFlow()
    val scanning: StateFlow<Boolean> = client.scanState
        .map { it is ScanState.Scanning }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    val errors: Flow<String> = _errors.asSharedFlow()

    /** 선택한 기기가 없으면 null. 기기가 바뀌면 그 기기의 상태로 갈아탄다. */
    val connectionState: StateFlow<ConnectionState?> = session
        .flatMapLatest { it?.connectionState ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 연결은 Ready 인데 수신(TX notify)이 꺼져 있으면 false — 재연결 뒤 복원 실패가 여기로 보인다. */
    val receiving: StateFlow<Boolean> = session
        .flatMapLatest { opened ->
            opened?.activeSubscriptions?.map { active -> active.any { it.uuid.value == Nus.TX } } ?: flowOf(false)
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // 명령이 던지는 실패는 호출한 쪽(ViewModel)이 받는다. 여기서 모으는 건 명령 밖에서 스캔이
        // 실패로 멈춘 경우뿐이다(§4) — scanState 에서 읽으므로 유실되지 않는다.
        scanStopFailures(client)
            .onEach { _errors.tryEmit(userMessage(it)) }
            .launchIn(scope)
    }

    suspend fun scan(durationMillis: Long = 5_000) {
        if (client.scanState.value is ScanState.Scanning) return
        _devices.value = emptyList()
        // scanFor 는 §4 — 시작 실패는 던지고, 끝나거나 취소되면 stopScan 까지 책임진다.
        _devices.value = scanFor(client, BleScanFilter(serviceUuids = listOf(BleUuid(Nus.SERVICE))), durationMillis)
    }

    suspend fun connect(id: PeripheralId) {
        disconnect()
        val opened = client.connect(id, BleConnectOptions(reconnectPolicy = reconnectPolicy))
        session.value = opened
        try {
            awaitReady(opened) // §5 — 실패하면 던진다
            val tx = findCharacteristic(opened, Nus.SERVICE, Nus.TX)
                ?: throw IllegalStateException("NUS TX characteristic 이 없다")
            // 구독 의도는 재연결을 넘어 유지되므로 수집·구독은 연결마다 한 번이면 된다(§8).
            notificationJob = streamNotifications(opened, tx, scope) { bytes ->
                append(Message(incoming = true, text = bytes.decodeToString()))
            }
        } catch (e: Throwable) {
            opened.disconnect() // 반쯤 열린 연결을 남기지 않는다 — disconnect 는 호출자가 취소돼도 끝까지 돈다
            throw e
        }
    }

    suspend fun send(text: String) {
        val opened = session.value ?: return
        val rx: CharacteristicRef = findCharacteristic(opened, Nus.SERVICE, Nus.RX) ?: return
        opened.write(rx, text.encodeToByteArray()) // 실패는 BleException 으로 호출자에게 간다
        append(Message(incoming = false, text = text))
    }

    suspend fun disconnect() {
        notificationJob?.cancel()
        notificationJob = null
        session.value?.disconnect()
        session.value = null
    }

    private companion object {
        val reconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
            maxAttempts = 3,
            initialDelayMillis = 1_000,
            maxDelayMillis = 10_000,
        )
    }

    private fun append(message: Message) {
        _messages.update { (it + message).takeLast(200) }
    }
}

// ── §14.4 ViewModel: 화면 상태 하나와 사용자 동작 ────────────────────────────────
data class NusUiState(
    val adapter: AdapterState = AdapterState.Resetting,
    val devices: List<ScanResult> = emptyList(),
    val scanning: Boolean = false,
    val connection: ConnectionState? = null,
    val messages: List<Message> = emptyList(),
    val receiving: Boolean = false,
) {
    val canSend: Boolean get() = connection == ConnectionState.Ready
    val receiveLost: Boolean get() = connection == ConnectionState.Ready && !receiving
}

class NusViewModel(private val repository: NusRepository) : ViewModel() {
    val uiState: StateFlow<NusUiState> = combine(
        repository.adapterState,
        repository.devices,
        repository.scanning,
        repository.connectionState,
        repository.messages,
    ) { adapter, devices, scanning, connection, messages ->
        NusUiState(adapter, devices, scanning, connection, messages)
    }.combine(repository.receiving) { state, receiving ->
        state.copy(receiving = receiving)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NusUiState(adapter = repository.adapterState.value))

    private val localErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: Flow<String> = merge(repository.errors, localErrors)

    fun scan() = launchReporting { repository.scan() }
    fun connect(id: PeripheralId) = launchReporting { repository.connect(id) }
    fun disconnect() = launchReporting { repository.disconnect() }
    fun send(text: String) = launchReporting { repository.send(text) }

    private fun launchReporting(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: BleException) {
                localErrors.tryEmit(userMessage(e.failure)) // §9
            } catch (e: IllegalStateException) {
                localErrors.tryEmit(e.message ?: "연결 실패")
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { NusViewModel((this[APPLICATION_KEY] as NusApp).repository) }
        }
    }
}

// ── §14.5 Compose 화면 ────────────────────────────────────────────────────
class NusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NusScreen() } }
    }
}

@Composable
fun NusScreen(viewModel: NusViewModel = viewModel(factory = NusViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 결과는 adapterState 가 반영한다 — 여기서 따로 판정하지 않는다 */ }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        NusContent(
            state = state,
            modifier = Modifier.padding(padding),
            onRequestPermission = { permissionLauncher.launch(requiredBlePermissions()) }, // §3
            onScan = viewModel::scan,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onSend = viewModel::send,
        )
    }
}

/** 상태만 받고 이벤트만 내보낸다 — 미리보기·UI 테스트에서 ViewModel 없이 그릴 수 있다. */
@Composable
fun NusContent(
    state: NusUiState,
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit,
    onScan: () -> Unit,
    onConnect: (PeripheralId) -> Unit,
    onDisconnect: () -> Unit,
    onSend: (String) -> Unit,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val adapter = state.adapter
        if (adapter != AdapterState.Ready) {
            Text(describeAdapter(adapter)) // §3
            if (adapter is AdapterState.Unauthorized && adapter.canRequest) {
                Button(onClick = onRequestPermission) { Text("권한 허용") }
            }
            return@Column
        }

        val connection = state.connection
        if (connection == null || connection is ConnectionState.Disconnected) {
            if (connection != null) Text(describeConnection(connection)) // §5
            Button(onClick = onScan, enabled = !state.scanning) {
                Text(if (state.scanning) "찾는 중…" else "NUS 기기 찾기")
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.devices, key = { it.peripheralId.value }) { device ->
                    Text(
                        text = "${device.advertisement.localName ?: "(이름 없음)"}  ${device.rssi} dBm",
                        modifier = Modifier.fillMaxWidth().clickable { onConnect(device.peripheralId) }.padding(12.dp),
                    )
                    HorizontalDivider()
                }
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(describeConnection(connection), Modifier.weight(1f))
            Button(onClick = onDisconnect) { Text("해제") }
        }
        if (state.receiveLost) {
            Text("수신이 꺼져 있습니다 — 해제 후 다시 연결하세요", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.messages) { message ->
                Text((if (message.incoming) "← " else "→ ") + message.text)
            }
        }
        var input by rememberSaveable { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f))
            Button(
                onClick = { onSend(input); input = "" },
                enabled = state.canSend && input.isNotEmpty(),
            ) { Text("보내기") }
        }
    }
}
