package kr.co.nucode.ble.harness

import kr.co.nucode.ble.internal.port.PlatformScanPort
import kr.co.nucode.ble.internal.model.PlatformScanSpec
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.internal.model.ScanObservation

/**
 * 단위 테스트용 [PlatformScanPort] — 시작 시도(성공·실패 모두)의 spec 과 Scanner 가 만든 **같은** 콜백
 * 람다(시작 generation 캡처)를 기록하고, 정지 횟수를 센다. 테스트는 이 람다로만 결과·실패를 싣는다.
 * [onStart] 가 시작 반환값을 정한다(null = 성공).
 */
internal class RecordingScanPort(
    var onStart: (PlatformScanSpec) -> BleFailure? = { null },
) : PlatformScanPort {
    class Start(
        val spec: PlatformScanSpec,
        val onResult: (ScanObservation) -> Unit,
        val onFailure: (BleFailure) -> Unit,
        val failure: BleFailure?,
    )

    /** 모든 시작 시도(실패 포함), 순서대로. */
    val starts: MutableList<Start> = mutableListOf()
    var stops: Int = 0
        private set

    /** 마지막 시작이 성공했고 그 뒤 정지가 없었다. */
    var running: Boolean = false
        private set

    override fun start(
        spec: PlatformScanSpec,
        onResult: (ScanObservation) -> Unit,
        onFailure: (BleFailure) -> Unit,
    ): BleFailure? {
        check(!running) { "플랫폼 스캔이 도는 중에 다시 시작했다" }
        val failure = onStart(spec)
        starts += Start(spec, onResult, onFailure, failure)
        running = failure == null
        return failure
    }

    override fun stop() {
        stops++
        running = false
    }
}
