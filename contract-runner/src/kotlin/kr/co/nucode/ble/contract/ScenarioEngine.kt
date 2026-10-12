package kr.co.nucode.ble.contract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import java.util.concurrent.atomic.AtomicBoolean

/** 시나리오를 돌릴 자리: sim(가짜 플랫폼, 가상 시간) 또는 device(실기기). */
internal interface ScenarioTarget {
    /** 기록 줄의 플랫폼 이름. */
    val platform: String
    val client: BleClient
    val peripheralId: PeripheralId

    /** 기기 사건을 일으킨다. 엔진은 연결이 Ready 일 때만 부른다. */
    suspend fun perform(event: DeviceEvent, session: BleSession, profile: PeripheralProfile)

    /**
     * 기기 사건 rxCheck. 기기가 이 연결에서 nus.rx 로 받은 것을 `<바이트 수> <ok|bad>` 로 돌려준다
     * (실기기는 probe.control 읽기, sim 은 가짜 기기의 세기). 엔진이 앞에 `rx ` 를 붙여 peer 레인에 남긴다.
     */
    suspend fun rxCheck(session: BleSession, profile: PeripheralProfile): String

    /** sim 전용 `adapter` 단계. [value] 는 adapter 레인의 모양(README §기록). 실기기 대상은 지원하지 않는다. */
    suspend fun setAdapter(value: String)
}

/**
 * contract/README.md 의 단계를 공개 API 로 실행하고 레인별로 기록한 뒤 기대값과 대조한다.
 * 시간은 코루틴 시계를 쓴다 — runTest 안에서는 가상 시간, 실기기에서는 실제 시간이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ScenarioEngine(
    private val target: ScenarioTarget,
    private val profile: PeripheralProfile,
) {

    /**
     * async 호출 하나. [claimed] 는 결과 줄을 남길 권리 — 먼저 끝난 쪽(호출의 반환 또는 cancel 단계)만 남긴다.
     * 그래서 끝난 호출을 취소해도 줄이 둘이 되지 않고, 취소한 호출의 늦은 반환도 남지 않는다.
     */
    private class Pending(val label: String, val deferred: Deferred<String?>, val claimed: AtomicBoolean)

    /** found 레인: startScan 을 부르기 직전에 켜지고, 대상 기기의 첫 결과를 남기면 꺼진다. */
    private val foundArmed = AtomicBoolean(false)

    suspend fun run(scenario: Scenario): ScenarioReport = coroutineScope {
        val log = ContractLog(scenario.expect.keys)
        foundArmed.set(false)
        val session = target.client.session(target.peripheralId)
        val recorders = startRecorders(log, session)
        val pending = mutableMapOf<String, Pending>()

        var failure: String? = null
        for ((index, step) in scenario.steps.withIndex()) {
            failure = try {
                execute(step, session, log, pending, this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "러너 오류 ${e::class.java.simpleName}: ${e.message}"
            }?.let { "steps[$index] $it" }
            if (failure != null) break
        }
        if (failure == null) delay(SETTLE_MILLIS)
        if (failure == null) {
            failure = pending.entries.firstOrNull { (_, p) -> p.timedOut() }
                ?.let { "async '${it.key}' 호출이 제한 시간 안에 끝나지 않음" }
        }
        log.close()
        recorders.forEach { it.cancel() }
        pending.values.forEach { it.deferred.cancel() }

        val lanes = log.snapshot()
        if (failure == null) {
            failure = scenario.expect.entries.firstNotNullOfOrNull { (lane, expected) ->
                val actual = lanes[lane].orEmpty()
                if (ContractMatch.matches(expected, actual)) null else "$lane 레인 불일치: 기대 $expected 실제 $actual"
            }
        }
        ScenarioReport(scenario, target.platform, lanes, failure)
    }

    /** 단계 하나. 실패면 이유를, 성공이면 null 을 돌려준다. */
    private suspend fun execute(
        step: Step,
        session: BleSession,
        log: ContractLog,
        pending: MutableMap<String, Pending>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): String? = when (step) {
        is Step.Call -> if (step.async) {
            // 시간 초과면 결과 줄 없이 null — `await result` 나 끝의 판정이 실패로 잡는다.
            val claimed = AtomicBoolean(false)
            pending[step.name!!] = Pending(step.label, scope.async {
                withTimeoutOrNull(step.timeoutMs) { invoke(step, session) }
                    ?.also { if (claimed.compareAndSet(false, true)) log.add(Lanes.RESULT, it) }
            }, claimed)
            null
        } else {
            val result = withTimeoutOrNull(step.timeoutMs) { invoke(step, session) }
            if (result == null) "${step.label} 호출이 ${step.timeoutMs}ms 안에 끝나지 않음" else { log.add(Lanes.RESULT, result); null }
        }
        is Step.AwaitLane -> withTimeoutOrNull(step.timeoutMs) {
            log.last(step.lane).first { it != null && ContractMatch.entry(step.pattern, it) }
        }.let { if (it == null) "${step.lane} '${step.pattern}' 를 ${step.timeoutMs}ms 안에 보지 못함(마지막 ${log.last(step.lane).value})" else null }
        is Step.AwaitCount -> withTimeoutOrNull(step.timeoutMs) {
            log.count(step.lane).first { it >= step.count }
        }.let { if (it == null) "${step.lane} 기록 ${step.count}개를 ${step.timeoutMs}ms 안에 받지 못함(${log.count(step.lane).value}개)" else null }
        is Step.AwaitResult -> {
            val call = pending.getValue(step.name)
            when {
                withTimeoutOrNull(step.timeoutMs) { call.deferred.join() } == null ->
                    "await result '${step.name}' 가 ${step.timeoutMs}ms 안에 끝나지 않음"
                call.timedOut() -> "async '${step.name}' 호출이 제한 시간 안에 끝나지 않음"
                else -> null
            }
        }
        is Step.Cancel -> {
            // 호출자(러너의 async 코루틴)만 취소한다. 라이브러리가 작업을 어떻게 하는지는 레인이 판정한다.
            val call = pending.getValue(step.name)
            if (call.claimed.compareAndSet(false, true)) {
                call.deferred.cancel()
                log.add(Lanes.RESULT, "${call.label} cancelled")
            }
            null
        }
        is Step.Device -> readyOr(session) {
            target.perform(step.event, session, profile)
        }
        is Step.RxCheck -> readyOr(session) {
            log.add(Lanes.PEER, "rx ${target.rxCheck(session, profile)}")
        }
        is Step.SetAdapter -> { target.setAdapter(step.value); null }
        is Step.Sleep -> { delay(step.ms); null }
    }

    /** 제한 시간 안에 끝나지 않은 async 호출(취소한 호출은 아니다). */
    private fun Pending.timedOut(): Boolean =
        deferred.isCompleted && !deferred.isCancelled && deferred.getCompleted() == null

    /** 기기 사건은 연결이 Ready 일 때만 쓴다(아니면 그 단계에서 실패). */
    private suspend fun readyOr(session: BleSession, block: suspend () -> Unit): String? {
        val state = session.connectionState.value
        if (state != ConnectionState.Ready) return "기기 사건은 Ready 에서만 쓴다(지금 ${ContractLog.render(state)})"
        block()
        return null
    }

    /** 형식의 filter `name` 은 공개 API `BleScanFilter.namePrefix` 에 그 글자열을 넣는 것이다(README, 전달 계획 CANDIDATE-19). */
    private fun toScanRequest(filter: ScanFilterSpec?): ScanRequest = ScanRequest(
        filter = BleScanFilter(
            serviceUuids = filter?.services.orEmpty().map { BleUuid(it.uuid) },
            namePrefix = filter?.name,
        ),
    )

    private suspend fun snapshotLine(): String {
        val snapshot = target.client.snapshot()
        val sessions = snapshot.sessions.map { session ->
            val who = if (session.peripheralId.value.equals(target.peripheralId.value, ignoreCase = true)) profile.name else "other"
            val subs = session.subscriptions.map(profile::symbolOf).sorted().joinToString(",", "{", "}")
            "$who:${ContractLog.render(session.connectionState)}:$subs"
        }.sorted().joinToString(";")
        return "snapshot ok adapter=${ContractLog.render(snapshot.adapterState)} scanning=${snapshot.isScanning} sessions=[$sessions]"
    }

    /** 공개 API 한 번. result 레인 한 줄을 돌려준다. */
    private suspend fun invoke(call: Step.Call, session: BleSession): String {
        val ref = call.char?.let { profile.spec(it).ref }
        return try {
            when (call.op) {
                CallOp.Snapshot -> return snapshotLine()
                CallOp.StartScan -> {
                    foundArmed.set(true)
                    target.client.startScan(toScanRequest(call.filter))
                }
                CallOp.StopScan -> target.client.stopScan()
                CallOp.Close -> target.client.close()
                CallOp.Connect -> session.connect(BleConnectOptions(autoReconnect = call.autoReconnect))
                CallOp.Disconnect -> session.disconnect()
                CallOp.Read -> session.read(ref!!)
                CallOp.Write -> session.write(
                    ref!!,
                    call.bytes!!,
                    BleWriteOptions(
                        mode = if (call.withResponse) BleWriteMode.WithResponse else BleWriteMode.WithoutResponse,
                        chunking = call.chunking,
                    ),
                )
                CallOp.Subscribe -> session.subscribe(ref!!)
                CallOp.Unsubscribe -> session.unsubscribe(ref!!)
            }
            "${call.label} ok"
        } catch (e: BleException) {
            "${call.label} fail ${e.failure.code.value}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "${call.label} fail EXCEPTION:${e::class.java.simpleName}"
        }
    }

    /** 기록 원천을 첫 단계 전에 모두 구독한다(UNDISPATCHED — 첫 게시를 놓치지 않게). */
    private fun kotlinx.coroutines.CoroutineScope.startRecorders(log: ContractLog, session: BleSession): List<Job> {
        val id = target.peripheralId
        val jobs = mutableListOf<Job>()
        if (log.records(Lanes.ADAPTER)) {
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                target.client.adapterState.collect { log.add(Lanes.ADAPTER, ContractLog.render(it)) }
            }
        }
        if (log.records(Lanes.SCAN)) {
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                target.client.scanState.collect { log.add(Lanes.SCAN, ContractLog.render(it)) }
            }
        }
        if (log.records(Lanes.FOUND)) {
            // 결과 횟수는 플랫폼마다 달라 남기지 않는다. 대상 기기의 첫 결과 하나만 기호 이름(기기 name)으로 남긴다.
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                target.client.scanResults.collect {
                    if (it.peripheralId.value.equals(id.value, ignoreCase = true) && foundArmed.compareAndSet(true, false)) {
                        log.add(Lanes.FOUND, profile.name)
                    }
                }
            }
        }
        if (log.records(Lanes.SERVICES)) {
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                session.services.collect { list ->
                    val present = list.flatMap { it.characteristics }.map { it.ref }.toSet()
                    val symbols = profile.characteristics.values.filter { it.ref in present }.map { it.symbol }.sorted()
                    log.add(Lanes.SERVICES, symbols.joinToString(",", "{", "}"))
                }
            }
        }
        if (log.records(Lanes.PROPERTIES)) {
            // services 와 같은 원천·같은 characteristic 을 고른다. 같은 characteristic 이 두 번 나오면 첫 항목을 쓴다.
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                session.services.collect { list ->
                    val present = list.flatMap { it.characteristics }.reversed().associateBy { it.ref }
                    val byName = profile.characteristics.values.mapNotNull { spec ->
                        present[spec.ref]?.let { spec.symbol to it.properties }
                    }.toMap()
                    log.add(Lanes.PROPERTIES, ContractLog.renderProperties(byName))
                }
            }
        }
        // 상태는 합쳐지는 StateFlow 가 아니라 전이마다 하나씩 나오는 공식 이벤트로 남긴다(README §기록).
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            target.client.events.filterIsInstance<OfficialBleClientEvent.Connection>().collect {
                if (it.peripheralId == id) log.add(Lanes.STATE, ContractLog.render(it.state))
            }
        }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            session.activeSubscriptions.collect { refs ->
                log.add(Lanes.ACTIVE, refs.map(profile::symbolOf).sorted().joinToString(",", "{", "}"))
            }
        }
        for (spec in profile.characteristics.values) {
            if (spec.runnerOnly || "notify" !in spec.properties) continue
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                session.notifications(spec.ref).collect { log.add(Lanes.NOTIFY, spec.symbol) }
            }
        }
        return jobs
    }

    companion object {
        /** 마지막 단계 뒤 늦게 오는 기록을 받는 시간(README §단계). */
        const val SETTLE_MILLIS = 500L
    }
}
