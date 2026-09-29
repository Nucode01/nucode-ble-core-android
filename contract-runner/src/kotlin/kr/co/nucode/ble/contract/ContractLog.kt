package kr.co.nucode.ble.contract

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.ScanState

/**
 * 시나리오 하나의 레인별 기록. 기록 원천은 여러 코루틴이라 잠금으로 모은다.
 * 확장 레인(README §기록)은 [enabled] 에 있을 때만 기록하고 출력한다.
 */
internal class ContractLog(private val enabled: Set<String> = emptySet()) {
    private val lock = Any()
    private val lanes = Lanes.ALL.associateWith { mutableListOf<String>() }
    private var closed = false

    private val lastValues = Lanes.AWAIT_IS.associateWith { MutableStateFlow<String?>(null) }
    private val counts = Lanes.AWAIT_COUNT.associateWith { MutableStateFlow(0) }

    /** 레인을 기록하는가. 기본 네 레인은 늘 기록한다. */
    fun records(lane: String): Boolean = lane !in Lanes.EXTENSION || lane in enabled

    /** 마지막 기록(`await <레인> is` 가 본다). */
    fun last(lane: String): StateFlow<String?> = lastValues.getValue(lane)

    /** 지금까지의 기록 수(`await <레인> count` 가 본다). */
    fun count(lane: String): StateFlow<Int> = counts.getValue(lane)

    val lastState: StateFlow<String?> get() = last(Lanes.STATE)

    fun add(lane: String, entry: String) {
        synchronized(lock) {
            if (closed || !records(lane)) return
            val list = lanes.getValue(lane)
            if (lane in Lanes.COALESCED && list.lastOrNull() == entry) return
            list += entry
            lastValues[lane]?.value = entry
            counts[lane]?.value = list.size
        }
    }

    /** 이후 기록을 버린다. 정리 단계(연결 해제·닫기)는 판정에 들어가지 않는다. */
    fun close() = synchronized(lock) { closed = true }

    fun snapshot(): Map<String, List<String>> = synchronized(lock) { lanes.mapValues { it.value.toList() } }

    companion object {
        fun render(state: AdapterState): String = when (state) {
            AdapterState.Ready -> "Ready"
            AdapterState.PoweredOff -> "PoweredOff"
            AdapterState.Resetting -> "Resetting"
            AdapterState.Unsupported -> "Unsupported"
            is AdapterState.Unauthorized -> "Unauthorized(${state.canRequest})"
        }

        fun render(state: ScanState): String = when (state) {
            is ScanState.Scanning -> "Scanning"
            is ScanState.Idle -> state.lastFailure?.let { "Idle(Failed:${it.code.value})" } ?: "Idle"
        }

        fun render(state: ConnectionState): String = when (state) {
            is ConnectionState.Disconnected -> "Disconnected(${render(state.reason)})"
            ConnectionState.Connecting -> "Connecting"
            ConnectionState.DiscoveringServices -> "DiscoveringServices"
            ConnectionState.Configuring -> "Configuring"
            ConnectionState.Ready -> "Ready"
            ConnectionState.Disconnecting -> "Disconnecting"
            is ConnectionState.Recovering -> "Recovering(${state.lastFailure.code.value})"
        }

        private fun render(reason: BleDisconnectReason): String = when (reason) {
            BleDisconnectReason.Initial -> "Initial"
            BleDisconnectReason.Requested -> "Requested"
            BleDisconnectReason.ClientClosed -> "ClientClosed"
            is BleDisconnectReason.Failed -> "Failed:${reason.failure.code.value}"
        }
    }
}

/** 시나리오 하나의 실행 결과. [failure] 가 null 이면 통과. */
internal class ScenarioReport(
    val scenario: Scenario,
    val platform: String,
    val lanes: Map<String, List<String>>,
    val failure: String?,
) {
    val passed: Boolean get() = failure == null

    /** README §실행 대상과 러너의 한 줄 형식. 5단계 비교가 모은다. */
    fun lines(): List<String> {
        val prefix = "CONTRACT|$platform|${scenario.id}|"
        val body = Lanes.ALL.filter { it !in Lanes.EXTENSION || it in scenario.expect }
            .flatMap { lane -> lanes[lane].orEmpty().map { "$prefix$lane|$it" } }
        val verdict = if (failure == null) "${prefix}verdict|PASS" else "${prefix}verdict|FAIL|${failure.replace('\n', ' ')}"
        return body + verdict
    }

    override fun toString(): String = lines().joinToString("\n")
}
