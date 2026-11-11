package kr.co.nucode.ble.contract

import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanResult

/**
 * scanTime 레인 판정(README §기록). 대상 기기 결과마다 바로 앞 대상 결과와의 시각 차이를 본다.
 * 음수가 한 번이라도 있으면 back(고정), 아니고 양수가 있으면 ok, 모두 0 이면 flat, 차이가 아직 없으면 none.
 * startScan 마다 되돌리지 않는다 — 시나리오 실행 하나에 하나만 만든다.
 */
internal class ContractScanTime(private val target: PeripheralId) {
    private var previous: Long? = null
    private var sawPositive = false
    private var sawNegative = false
    private var sawDiff = false

    val value: String
        get() = when {
            sawNegative -> BACK
            sawPositive -> OK
            sawDiff -> FLAT
            else -> NONE
        }

    /** [result] 를 판정에 넣고 현재 값을 돌려준다. 대상 기기가 아니면 값을 바꾸지 않는다. */
    fun record(result: ScanResult): String {
        if (!result.peripheralId.value.equals(target.value, ignoreCase = true)) return value
        previous?.let { before ->
            val diff = result.timestampNanos - before
            sawDiff = true
            if (diff < 0) sawNegative = true
            if (diff > 0) sawPositive = true
        }
        previous = result.timestampNanos
        return value
    }

    companion object {
        const val NONE = "none"
        const val OK = "ok"
        const val FLAT = "flat"
        const val BACK = "back"
    }
}
