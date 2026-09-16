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
import kr.co.nucode.ble.BleCore
import kr.co.nucode.ble.BleEvent
import kr.co.nucode.ble.DeviceId
import kr.co.nucode.ble.DiscoveredService

class SampleApp : Application() {
    lateinit var core: BleCore
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 링버퍼 cap 2000 — plan §잠재 에러 "로그 무한 증가"
    private val buffer = ArrayDeque<String>()
    val logLines: List<String> get() = buffer.toList()
    var onLogAppended: ((String) -> Unit)? = null // main thread 에서만 호출

    // discovered 캐시 — main thread 전용(events collect 가 Main.immediate). plan task 5 §④.
    val discoveredServices = mutableMapOf<DeviceId, List<DiscoveredService>>()

    override fun onCreate() {
        super.onCreate()
        core = BleCore(this, BleConfig())
        scope.launch {
            core.events.collect {
                if (it is BleEvent.ServicesDiscovered) {
                    discoveredServices[it.deviceId] = it.services
                }
                log(formatEvent(it))
            }
        }
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

private fun formatEvent(e: BleEvent): String = when (e) {
    is BleEvent.ScanResult -> "SCAN ${e.deviceId} rssi=${e.rssi} name=${e.name}"

    is BleEvent.ConnStateChanged -> "CONN ${e.deviceId} ${e.state}"

    is BleEvent.ServicesDiscovered -> {
        val charCount = e.services.sumOf { it.characteristics.size }
        "SVCS ${e.deviceId} services=${e.services.size} chars=$charCount"
    }

    is BleEvent.CharValue -> {
        val hex = e.bytes.take(8).joinToString(separator = "") { "%02X".format(it) }
        "NTFY ${e.deviceId} ${e.charRef.charUuid} ${e.bytes.size}B $hex"
    }

    is BleEvent.MtuChanged -> "MTU  ${e.deviceId} mtu=${e.mtu} chunk=${e.chunkSize}"

    is BleEvent.Diagnostic -> {
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
