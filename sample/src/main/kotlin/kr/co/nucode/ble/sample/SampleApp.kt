package kr.co.nucode.ble.sample

import android.app.Application
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanResult
import kr.co.nucode.ble.android.AndroidBleClientFactory

class SampleApp : Application() {
    lateinit var client: BleClient
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 링버퍼 cap 2000 — plan §잠재 에러 "로그 무한 증가"
    private val buffer = ArrayDeque<String>()
    val logLines: List<String> get() = buffer.toList()
    var onLogAppended: ((String) -> Unit)? = null // main thread 에서만 호출

    // discovered 캐시 — main thread 전용(events collect 가 Main.immediate). plan task 5 §④.
    val discoveredServices = mutableMapOf<PeripheralId, List<GattService>>()

    override fun onCreate() {
        super.onCreate()
        client = AndroidBleClientFactory.create(this, BleConfig())
        scope.launch {
            client.events.collect {
                if (it is OfficialBleClientEvent.Services) {
                    discoveredServices[it.peripheralId] = it.services
                }
                if (it !is OfficialBleClientEvent.Scan) log(formatEvent(it))
            }
        }
        scope.launch { client.scanResults.collect { log(formatScanResult(it)) } }
    }

    fun log(line: String) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            scope.launch { log(line) }
            return
        }
        val timestamped = "${TIME_FORMAT.format(Date())}  $line"
        buffer.addLast(timestamped)
        if (buffer.size > MAX_LOG_LINES) {
            buffer.removeFirst()
        }
        onLogAppended?.invoke(timestamped)
    }

    fun clearLog() {
        buffer.clear()
    }

    private companion object {
        const val MAX_LOG_LINES = 2000
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

private fun formatEvent(e: BleClientEvent): String = when (e) {
    is OfficialBleClientEvent.Scan -> formatScanResult(e.result)
    is OfficialBleClientEvent.Connection -> "CONN ${e.peripheralId} ${e.state}"
    is OfficialBleClientEvent.Services -> {
        val charCount = e.services.sumOf { it.characteristics.size }
        "SVCS ${e.peripheralId} services=${e.services.size} chars=$charCount"
    }
    is OfficialBleClientEvent.SubscriptionRestoreFailed ->
        "RESTORE ${e.peripheralId} ${e.characteristic.uuid} ${e.failure.code.value}"
    is OfficialBleClientEvent.Diagnostic -> {
        val failure = e.failure
        val platform = when (val detail = failure.platformDetail) {
            is kr.co.nucode.ble.PlatformDetail.Android ->
                "${detail.apiOperation ?: detail.domain}:${detail.statusCode ?: detail.reasonCode ?: "-"}"

            is kr.co.nucode.ble.PlatformDetail.Ios ->
                "${detail.apiOperation ?: detail.domain}:${detail.code}"

            null -> "-"
        }
        "DIAG [${e.severity}] ${failure.category.value}/${failure.code.value} " +
            "phase=${failure.phase} platform=$platform${failure.message?.let { " $it" }.orEmpty()}"
    }
}

private fun formatScanResult(result: ScanResult): String =
    "SCAN ${result.peripheralId} rssi=${result.rssi} name=${result.advertisement.localName}"
