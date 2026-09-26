@file:Suppress("unused")

package kr.co.nucode.ble.sample

/*
 * guide/README.md 의 ViewModel·Compose 예시 컴파일 검증본.
 * `// region guide:<이름>` 과 `// endregion` 사이가 문서의 코드 블록과 글자 그대로 같다.
 * 공통 도우미(connectAndAwaitReady, startReceiving, userMessageFor, blePermissions,
 * adapterMessage, MyApplication)는 [GuideSnippets.kt] 에 있다.
 * 샘플 앱 매니페스트에는 등록하지 않는다 — 컴파일되는 것 자체가 목적이다.
 */

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.ServiceRef

// region guide:heart-rate-model
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
// endregion

// region guide:heart-rate-viewmodel
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
// endregion

// region guide:heart-rate-screen
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
// endregion
