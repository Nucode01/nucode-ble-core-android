package kr.co.nucode.ble.contract

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState

/** 시나리오 하나의 레인별 기록. 기록 원천은 여러 코루틴이라 잠금으로 모은다. */
internal class ContractLog {
    private val lock = Any()
    private val lanes = Lanes.ALL.associateWith { mutableListOf<String>() }
    private var closed = false

    private val _lastState = MutableStateFlow<String?>(null)
    private val _notifyCount = MutableStateFlow(0)

    /** 마지막 state 기록(`await state` 가 본다). */
    val lastState: StateFlow<String?> get() = _lastState

    /** 지금까지의 notify 기록 수(`await notify` 가 본다). */
    val notifyCount: StateFlow<Int> get() = _notifyCount

    fun add(lane: String, entry: String) {
        synchronized(lock) {
            if (closed) return
            val list = lanes.getValue(lane)
            if (lane == Lanes.ACTIVE && list.lastOrNull() == entry) return
            list += entry
            when (lane) {
                Lanes.STATE -> _lastState.value = entry
                Lanes.NOTIFY -> _notifyCount.value = list.size
            }
        }
    }

    /** 이후 기록을 버린다. 정리 단계(연결 해제·닫기)는 판정에 들어가지 않는다. */
    fun close() = synchronized(lock) { closed = true }

    fun snapshot(): Map<String, List<String>> = synchronized(lock) { lanes.mapValues { it.value.toList() } }

    companion object {
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
        val body = Lanes.ALL.flatMap { lane -> lanes[lane].orEmpty().map { "$prefix$lane|$it" } }
        val verdict = if (failure == null) "${prefix}verdict|PASS" else "${prefix}verdict|FAIL|${failure.replace('\n', ' ')}"
        return body + verdict
    }

    override fun toString(): String = lines().joinToString("\n")
}
