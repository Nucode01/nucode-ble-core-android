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
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId

/** 시나리오를 돌릴 자리: sim(가짜 플랫폼, 가상 시간) 또는 device(실기기). */
internal interface ScenarioTarget {
    /** 기록 줄의 플랫폼 이름. */
    val platform: String
    val client: BleClient
    val peripheralId: PeripheralId

    /** 기기 사건을 일으킨다. 엔진은 연결이 Ready 일 때만 부른다. */
    suspend fun perform(event: DeviceEvent, session: BleSession, profile: PeripheralProfile)
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

    suspend fun run(scenario: Scenario): ScenarioReport = coroutineScope {
        val log = ContractLog()
        val session = target.client.session(target.peripheralId)
        val recorders = startRecorders(log, session)
        val pending = mutableMapOf<String, Deferred<String?>>()

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
            failure = pending.entries.firstOrNull { (_, d) -> d.isCompleted && d.getCompleted() == null }
                ?.let { "async '${it.key}' 호출이 제한 시간 안에 끝나지 않음" }
        }
        log.close()
        recorders.forEach { it.cancel() }
        pending.values.forEach { it.cancel() }

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
        pending: MutableMap<String, Deferred<String?>>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): String? = when (step) {
        is Step.Call -> if (step.async) {
            // 시간 초과면 결과 줄 없이 null — `await result` 나 끝의 판정이 실패로 잡는다.
            pending[step.name!!] = scope.async {
                withTimeoutOrNull(step.timeoutMs) { invoke(step, session) }?.also { log.add(Lanes.RESULT, it) }
            }
            null
        } else {
            val result = withTimeoutOrNull(step.timeoutMs) { invoke(step, session) }
            if (result == null) "${step.label} 호출이 ${step.timeoutMs}ms 안에 끝나지 않음" else { log.add(Lanes.RESULT, result); null }
        }
        is Step.AwaitState -> withTimeoutOrNull(step.timeoutMs) {
            log.lastState.first { it != null && ContractMatch.entry(step.pattern, it) }
        }.let { if (it == null) "state '${step.pattern}' 를 ${step.timeoutMs}ms 안에 보지 못함(마지막 ${log.lastState.value})" else null }
        is Step.AwaitNotify -> withTimeoutOrNull(step.timeoutMs) {
            log.notifyCount.first { it >= step.count }
        }.let { if (it == null) "알림 ${step.count}개를 ${step.timeoutMs}ms 안에 받지 못함(${log.notifyCount.value}개)" else null }
        is Step.AwaitResult -> {
            val call = pending.getValue(step.name)
            when {
                withTimeoutOrNull(step.timeoutMs) { call.join() } == null ->
                    "await result '${step.name}' 가 ${step.timeoutMs}ms 안에 끝나지 않음"
                call.getCompleted() == null -> "async '${step.name}' 호출이 제한 시간 안에 끝나지 않음"
                else -> null
            }
        }
        is Step.Device -> {
            val state = session.connectionState.value
            if (state != ConnectionState.Ready) {
                "기기 사건은 Ready 에서만 쓴다(지금 ${ContractLog.render(state)})"
            } else {
                target.perform(step.event, session, profile)
                null
            }
        }
        is Step.Sleep -> { delay(step.ms); null }
    }

    /** 공개 API 한 번. result 레인 한 줄을 돌려준다. */
    private suspend fun invoke(call: Step.Call, session: BleSession): String {
        val ref = call.char?.let { profile.spec(it).ref }
        return try {
            when (call.op) {
                CallOp.Connect -> session.connect(BleConnectOptions(autoReconnect = call.autoReconnect))
                CallOp.Disconnect -> session.disconnect()
                CallOp.Read -> session.read(ref!!)
                CallOp.Write -> session.write(
                    ref!!,
                    call.bytes!!,
                    BleWriteOptions(mode = if (call.withResponse) BleWriteMode.WithResponse else BleWriteMode.WithoutResponse),
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
