package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.internal.ReconnectPolicy
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import org.junit.Assert.fail
import org.junit.Test

/**
 * 연결 시나리오 × 콜백 변형 표를 돌려 전역 불변식(I1·I2·I3·I4·I6·I7)을 검사한다
 * (plan platform-harness v1 목표 2). 무작위 없이 명시한 조합만 돈다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformScenarioTest {

    /** 시나리오 본문이 돌려주는 메타(불변식 검사 입력). */
    private data class Outcome(
        val userDisconnected: Boolean = false,
        val descriptorWritesAfterReconnect: Int? = null,
        /** 시나리오 안에서 close() 한 시점의 상태 기록 길이(I8). */
        val closedAt: Int? = null,
        /** 시나리오가 기대하는 최종 상태(불변식과 별개인 시나리오 기대값). null 이면 검사 안 함. */
        val expectedLast: ConnectionState? = null,
    )

    private class Scenario(
        val name: String,
        val variant: String,
        val reconnect: BleReconnectPolicy = BleReconnectPolicy.Disabled,
        val legacy: ReconnectPolicy = ReconnectPolicy(),
        /** I9 대조 실행 — 같은 시나리오에서 늦은 콜백만 뺀 본문. null 이면 I9 대상 아님. */
        val control: (kotlinx.coroutines.test.TestScope.(Harness) -> Outcome)? = null,
        val body: kotlinx.coroutines.test.TestScope.(Harness) -> Outcome,
    )

    private val id = PeripheralId("AA:BB:CC:DD:EE:F2")
    private val serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val ref = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))

    // 가상 시간을 짧게 두는 timeout(HarnessSmokeTest 와 같은 취지).
    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private val backoff = BleReconnectPolicy.ExponentialBackoff(2, 100, 200)

    /** 표·위반 문자열용 짧은 상태 이름(실패는 코드만). */
    private fun short(state: ConnectionState?): String = when (state) {
        is ConnectionState.Disconnected -> when (val r = state.reason) {
            is BleDisconnectReason.Failed -> "Disconnected(${r.failure.code.value})"
            else -> "Disconnected($r)"
        }
        is ConnectionState.Recovering -> "Recovering(${state.attempt})"
        else -> state.toString()
    }

    private fun label(v: CallbackVariant): String = when (v) {
        is CallbackVariant.Arrive -> "Arrive(${v.status})"
        is CallbackVariant.ArriveBeforeStartReturns -> "ArriveBeforeStartReturns(${v.status})"
        CallbackVariant.Missing -> "Missing"
        is CallbackVariant.Late -> "Late(${v.delayMillis},${v.status})"
        is CallbackVariant.Duplicate -> "Duplicate(${v.status})"
    }

    private fun policyName(p: BleReconnectPolicy) = if (p is BleReconnectPolicy.Disabled) "reconnect=off" else "reconnect=backoff(2,100,200)"

    /** 정리 해제(driver.disconnect) 콜백이 올지 모르는 경로의 두 변형. */
    private val cleanupDisconnect = listOf(CallbackVariant.Arrive(0), CallbackVariant.Missing)

    /** 구독을 걸고 성공·activeSubscriptions 비어 있지 않음을 시나리오 전제로 단언한다. */
    private fun subscribeOrFail(h: Harness) {
        val result = h.subscribe(id, ref)
        if (result == null || result.isFailure) fail("전제 실패: subscribe 가 성공하지 않음 ($result)")
        if (h.session(id).activeSubscriptions.value.isEmpty()) fail("전제 실패: subscribe 뒤 activeSubscriptions 가 비어 있음")
    }

    /** 첫 재연결 시작이 나갈 때까지 진행(상한 있음). */
    private fun awaitRetainedConnects(h: Harness, count: Int) =
        h.waitUntil("retainedConnect $count 회") { h.fake.calls.count { it == "retainedConnect" } >= count }

    /** I9 비교용 세대 통계(모든 deadline 이 지난 뒤 본다). */
    private fun statsOf(h: Harness) = GenerationStats(
        retainedConnects = h.fake.calls.count { it == "retainedConnect" },
        connectingEntries = h.states(id).count { it is ConnectionState.Connecting },
        recoveringAttempts = h.states(id).filterIsInstance<ConnectionState.Recovering>().map { it.attempt },
        last = h.states(id).lastOrNull(),
    )

    /**
     * Ready → 링크 끊김(8) → 재시도 대기(백오프) 중, 다음 재연결 시작 전에 현재 handle 에서
     * STATE_DISCONNECTED([late]) 를 낸다. [late] 가 null 이면 대조 실행(I9).
     */
    private fun lateInBackoff(late: Int?, attemptsFail: Boolean = false): kotlinx.coroutines.test.TestScope.(Harness) -> Outcome = { h ->
        h.connectToReady(id)
        // 모든 재연결 시도가 실패하면 소진(Failed)으로 끝난다 — I9 가 Failed 최종 상태에서 오탐하지 않는지 본다.
        if (attemptsFail) h.fake.reconnectFailStatus = 133
        h.fake.linkLoss(id, 8)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: Recovering 아님 ${h.states(id)}")
        if (h.fake.calls.any { it == "retainedConnect" }) fail("전제 실패: 백오프 대기 전에 재연결 시작 calls=${h.fake.calls}")
        if (late != null) h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, late)
        Outcome()
    }

    /**
     * 이전(닫히지 않은 채 대체된) handle 을 남긴 뒤 Ready → 링크 끊김 → 재연결 시도가 시작된 뒤
     * 이전 handle 에서 STATE_DISCONNECTED(0) 를 낸다. [late] 가 false 면 대조 실행(I9).
     */
    private fun oldHandleDuringAttempt(late: Boolean): kotlinx.coroutines.test.TestScope.(Harness) -> Outcome = { h ->
        h.connectToReady(id)
        val oldHandle = h.fake.epoch
        h.disconnect(id)
        h.advancePastAllDeadlines()
        h.connectToReady(id)
        if (h.fake.epoch == oldHandle) fail("전제 실패: 새 connect 가 handle 을 바꾸지 않음 calls=${h.fake.calls}")
        h.fake.linkLoss(id, 8)
        awaitRetainedConnects(h, 1)
        if (late) h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0, fromEpoch = oldHandle)
        Outcome()
    }

    private val clientClosed = ConnectionState.Disconnected(BleDisconnectReason.ClientClosed)

    /** 시나리오 안에서 close() 하고 그 시점을 기록한다(I8 — close 뒤 상태를 본다). */
    private fun closeInScenario(h: Harness): Int {
        val at = h.states(id).size
        h.close()
        return at
    }

    private fun scenarios(): List<Scenario> = buildList {
        // S 연결 → Ready
        for (v in listOf(CallbackVariant.Arrive(0), CallbackVariant.Late(50), CallbackVariant.Duplicate(0))) {
            add(Scenario("connect→Ready", "onConnect=${label(v)}") { h ->
                h.fake.onConnect = v
                h.connectToReady(id)
                Outcome()
            })
        }
        // S 사용자 해제
        for (v in listOf(CallbackVariant.Arrive(0), CallbackVariant.Missing, CallbackVariant.Late(30), CallbackVariant.Duplicate(0))) {
            add(Scenario("user disconnect", "onDisconnect=${label(v)}") { h ->
                h.connectToReady(id)
                h.fake.onDisconnect = v
                h.disconnect(id)
                Outcome(userDisconnected = true)
            })
        }
        // S Ready 중 링크 끊김(재연결 on/off)
        for (status in listOf(8, 19)) for (p in listOf(BleReconnectPolicy.Disabled, backoff)) {
            add(Scenario("link loss while Ready", "status=$status ${policyName(p)}", p) { h ->
                h.connectToReady(id)
                h.fake.linkLoss(id, status)
                Outcome()
            })
        }
        // S 구독 → 링크 끊김(재연결 on/off) — I4 를 결합 기록의 모든 점에서 본다.
        for (p in listOf(BleReconnectPolicy.Disabled, backoff)) {
            add(Scenario("subscribe→link loss", policyName(p), p) { h ->
                h.fake.services = listOf(serviceSnapshot())
                h.connectToReady(id)
                subscribeOrFail(h)
                h.fake.linkLoss(id, 8)
                Outcome()
            })
        }
        // S 링크 timeout / 탐색 timeout / MTU 누락 — 정리 해제 콜백이 오는지 모르므로 두 변형.
        for (d in cleanupDisconnect) {
            add(Scenario("link timeout", "onConnect=Missing onDisconnect=${label(d)}") { h ->
                h.fake.onConnect = CallbackVariant.Missing
                h.fake.onDisconnect = d
                h.connect(id)
                Outcome()
            })
            add(Scenario("discovery timeout", "onDiscover=Missing onDisconnect=${label(d)}") { h ->
                h.fake.onDiscover = CallbackVariant.Missing
                h.fake.onDisconnect = d
                h.connect(id)
                Outcome()
            })
            add(Scenario("mtu missing", "onMtu=Missing onDisconnect=${label(d)}") { h ->
                h.fake.onMtu = CallbackVariant.Missing
                h.fake.onDisconnect = d
                h.connect(id)
                Outcome()
            })
        }
        // S 재연결 시도 성공
        add(Scenario("reconnect attempt", "success", backoff) { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            Outcome()
        })
        // S 재연결 시도 실패 후 성공 — 두 번째 시도에서 Ready 까지 가야 시나리오가 성립한다.
        add(Scenario("reconnect attempt", "fail(133) then success", backoff) { h ->
            h.connectToReady(id)
            h.fake.reconnectFailStatus = 133
            h.fake.linkLoss(id, 8)
            // 실패 status 는 시작 시점에 캡처되므로 첫 시작이 나간 직후 스크립트를 성공으로 바꾼다.
            awaitRetainedConnects(h, 1)
            h.fake.reconnectFailStatus = null
            awaitRetainedConnects(h, 2)
            h.waitUntil("두 번째 재연결 시도가 Ready") { h.session(id).connectionState.value == ConnectionState.Ready }
            if (h.states(id).none { it is ConnectionState.Recovering && it.attempt == 2 }) {
                fail("전제 실패: Recovering(2) 를 거치지 않음 ${h.states(id)}")
            }
            Outcome()
        })
        // S 재연결 소진
        add(Scenario("reconnect attempt", "exhaustion(133 always)", backoff) { h ->
            h.connectToReady(id)
            h.fake.reconnectFailStatus = 133
            h.fake.linkLoss(id, 8)
            Outcome()
        })
        // S 재연결 콜백 누락(attempt/link deadline)
        add(Scenario("reconnect attempt", "onReconnect=Missing", backoff) { h ->
            h.connectToReady(id)
            h.fake.onReconnect = CallbackVariant.Missing
            h.fake.linkLoss(id, 8)
            Outcome()
        })
        // S 재연결 시작 거부
        add(Scenario("reconnect attempt", "start rejected", backoff) { h ->
            h.connectToReady(id)
            h.fake.rejectReconnectStart = true
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            // 시작 거부도 시도 하나다 — 정책 maxAttempts 만큼 재연결 시작이 나가야 한다(잃은 시도·빈 generation 없음).
            val starts = h.fake.calls.count { it == "retainedConnect" }
            println("start rejected trail: ${h.states(id).joinToString(" > ") { short(it) }} calls=${h.fake.calls}")
            if (starts != backoff.maxAttempts) fail("재연결 시작 $starts 회 != maxAttempts ${backoff.maxAttempts} trail=${h.states(id).map { short(it) }}")
            Outcome()
        })
        // S 재연결 시작이 권한 예외를 던진다 — 크래시 없이 PERMISSION_DENIED 로 끝나고 더 시도하지 않는다.
        add(Scenario("reconnect attempt", "start throws PERMISSION_DENIED", backoff) { h ->
            h.connectToReady(id)
            h.fake.reconnectThrows = BleException(
                FailureMapper.permissionDenied(FailurePhase.Connect, AndroidApiOperation.Connect),
            )
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            val last = short(h.states(id).last())
            if (last != "Disconnected(PERMISSION_DENIED)") fail("권한 예외 최종 상태 $last trail=${h.states(id).map { short(it) }}")
            val starts = h.fake.calls.count { it == "retainedConnect" }
            if (starts != 1) fail("권한 실패 뒤 재시도 $starts 회 calls=${h.fake.calls}")
            Outcome()
        })
        // S 재연결 시작이 예기치 않은 예외를 던진다 — 시작 거부와 같이 닫히고 예산대로 재시도한다.
        add(Scenario("reconnect attempt", "start throws RuntimeException", backoff) { h ->
            h.connectToReady(id)
            h.fake.reconnectThrows = RuntimeException("boom")
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            if (h.states(id).none { short(it) == "Disconnected(RECONNECT_CONNECT_START_FAILED)" }) {
                fail("시작 거부 원인 없음 trail=${h.states(id).map { short(it) }}")
            }
            val starts = h.fake.calls.count { it == "retainedConnect" }
            if (starts != backoff.maxAttempts) fail("재연결 시작 $starts 회 != maxAttempts calls=${h.fake.calls}")
            Outcome()
        })
        // S 재연결 콜백이 시작 반환 전에 큐에 들어가는 경합(production 에선 마이크로초 창) — 일부러 고정한다.
        add(Scenario("reconnect race", "onReconnect=ArriveBeforeStartReturns(0)", backoff) { h ->
            h.connectToReady(id)
            h.fake.onReconnect = CallbackVariant.ArriveBeforeStartReturns(0)
            h.fake.linkLoss(id, 8)
            Outcome()
        })
        add(Scenario("reconnect race", "onReconnect=ArriveBeforeStartReturns fail(133) always", backoff) { h ->
            h.connectToReady(id)
            h.fake.onReconnect = CallbackVariant.ArriveBeforeStartReturns(0)
            h.fake.reconnectFailStatus = 133
            h.fake.linkLoss(id, 8)
            Outcome()
        })
        // S Ready 중 어댑터 off — 뒤이어 STATE_DISCONNECTED 가 오지 않는 경우와 오는 경우(status 0·8).
        for (p in listOf(BleReconnectPolicy.Disabled, backoff)) for (late in listOf<Int?>(null, 0, 8)) {
            val variant = policyName(p) + if (late == null) "" else " +STATE_DISCONNECTED($late)"
            add(Scenario("adapter off while Ready", variant, p) { h ->
                h.connectToReady(id)
                val handle = h.fake.epoch
                h.fake.setAdapter(AdapterState.PoweredOff)
                if (late != null) h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, late, fromEpoch = handle)
                Outcome()
            })
        }
        // S 구독 → 사용자 해제 → 다시 연결(I7)
        add(Scenario("subscribe→disconnect→connect", "all Arrive") { h ->
            h.fake.services = listOf(serviceSnapshot())
            h.connectToReady(id)
            subscribeOrFail(h)
            h.disconnect(id)
            h.advancePastAllDeadlines()
            val before = h.fake.calls.size
            h.connectToReady(id)
            h.advancePastAllDeadlines()
            Outcome(descriptorWritesAfterReconnect = h.fake.calls.drop(before).count { it == "descriptor" })
        })
        // S 세션이 끝난 뒤 늦은 STATE_DISCONNECTED(status 0) — H1 모양. 닫힌(이전) handle 에서 온다.
        add(Scenario("late STATE_DISCONNECTED", "after user disconnect ended (stale handle)") { h ->
            h.connectToReady(id)
            val handle = h.fake.epoch
            h.disconnect(id)
            h.advancePastAllDeadlines()
            if (h.fake.epoch == handle) fail("전제 실패: 사용자 해제가 driver close 로 handle 을 닫지 않음 calls=${h.fake.calls}")
            h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0, fromEpoch = handle)
            Outcome(userDisconnected = true)
        })
        // 같은 늦은 콜백이 현재 handle 출처로 오는 경우(출처 식별이 막지 못하는 모양).
        add(Scenario("late STATE_DISCONNECTED", "after user disconnect ended (current handle)") { h ->
            h.connectToReady(id)
            h.disconnect(id)
            h.advancePastAllDeadlines()
            h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0)
            Outcome(userDisconnected = true)
        })
        add(Scenario("late STATE_DISCONNECTED", "after link loss ended", BleReconnectPolicy.Disabled) { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0)
            Outcome()
        })
        add(Scenario("late STATE_DISCONNECTED", "during reconnect backoff", backoff) { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            runCurrent()
            h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0)
            Outcome()
        })
        // S 재시도 대기(백오프) 중 현재 handle 의 늦은 STATE_DISCONNECTED — H1 phantom generation(I9).
        for (late in listOf(0, 8)) {
            add(Scenario("late STATE_DISCONNECTED", "pure backoff wait status=$late (current handle)", backoff, body = lateInBackoff(late), control = lateInBackoff(null)))
        }
        // S 같은 늦은 콜백이지만 모든 시도가 실패해 Failed 로 끝난다 — I9 최종 상태 비교 오탐 없음.
        add(Scenario("late STATE_DISCONNECTED", "pure backoff wait status=0, attempts fail (current handle)", backoff, body = lateInBackoff(0, attemptsFail = true), control = lateInBackoff(null, attemptsFail = true)))
        // S 재연결 시도 중 이전 handle 의 늦은 STATE_DISCONNECTED — 출처 식별로 버려져야 한다(I9).
        add(Scenario("late STATE_DISCONNECTED", "during attempt (previous handle)", backoff, body = oldHandleDuringAttempt(true), control = oldHandleDuringAttempt(false)))
        // S close — 시나리오 안에서 close 하고 그 뒤 상태를 본다(I8). 종결되지 않았거나 재연결 대기 중이면 ClientClosed.
        add(Scenario("close", "while Ready") { h ->
            h.connectToReady(id)
            Outcome(closedAt = closeInScenario(h), expectedLast = clientClosed)
        })
        add(Scenario("close", "while Connecting") { h ->
            h.fake.onConnect = CallbackVariant.Missing
            h.connect(id)
            Outcome(closedAt = closeInScenario(h), expectedLast = clientClosed)
        })
        add(Scenario("close", "while Disconnecting (user disconnect pending)") { h ->
            h.connectToReady(id)
            h.fake.onDisconnect = CallbackVariant.Missing
            h.disconnect(id)
            if (h.session(id).connectionState.value != ConnectionState.Disconnecting) fail("전제 실패: Disconnecting 아님 ${h.states(id)}")
            Outcome(closedAt = closeInScenario(h), expectedLast = clientClosed)
        })
        add(Scenario("close", "while Recovering (backoff)", backoff) { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            runCurrent()
            if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: Recovering 아님 ${h.states(id)}")
            Outcome(closedAt = closeInScenario(h), expectedLast = clientClosed)
        })
        add(Scenario("close", "after failed terminal (no retry)") { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            val failed = h.session(id).connectionState.value
            if (failed !is ConnectionState.Disconnected) fail("전제 실패: 종결되지 않음 ${h.states(id)}")
            Outcome(closedAt = closeInScenario(h), expectedLast = failed)
        })
        // S 재연결 백오프 대기 중 어댑터 off — 회복이 끝나 ADAPTER_UNAVAILABLE 종착, 예약된 재연결은 취소.
        add(Scenario("adapter off during reconnect backoff", policyName(backoff), backoff) { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            runCurrent()
            if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: Recovering 아님 ${h.states(id)}")
            val retainedBefore = h.fake.calls.count { it == "retainedConnect" }
            h.fake.setAdapter(AdapterState.PoweredOff)
            h.advancePastAllDeadlines()
            if (h.fake.calls.count { it == "retainedConnect" } != retainedBefore) fail("adapter off 뒤 재연결 시작 calls=${h.fake.calls}")
            val last = h.session(id).connectionState.value
            val code = ((last as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value
            if (code != "ADAPTER_UNAVAILABLE") fail("adapter off 가 회복을 끝내지 않음: ${h.states(id)}")
            Outcome(expectedLast = last)
        })
        // S 사용자 해제 진행 중(Disconnecting) 어댑터 off — 먼저 고정된 Requested 유지, handle 은 닫힌다.
        add(Scenario("adapter off during Disconnecting", "onDisconnect=Missing") { h ->
            h.connectToReady(id)
            h.fake.onDisconnect = CallbackVariant.Missing
            h.disconnect(id)
            val handle = h.fake.epoch
            h.fake.setAdapter(AdapterState.PoweredOff)
            h.advancePastAllDeadlines()
            if (h.fake.epoch == handle) fail("adapter off 가 handle 을 닫지 않음 calls=${h.fake.calls}")
            Outcome(userDisconnected = true, expectedLast = ConnectionState.Disconnected(BleDisconnectReason.Requested))
        })
        // S legacy 정책(런타임 ReconnectPolicy) 재연결 소진 — GIVE_UP 종착 한 번(I3).
        add(Scenario("reconnect attempt", "legacy exhaustion(133 always)", legacy = ReconnectPolicy(maxAttempts = 1, baseDelayMillis = 50, maxDelayMillis = 50)) { h ->
            h.connectLegacyToReady(id)
            h.fake.reconnectFailStatus = 133
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            val code = ((h.session(id).connectionState.value as? ConnectionState.Disconnected)?.reason as? BleDisconnectReason.Failed)?.failure?.code?.value
            if (code != "RECONNECT_GIVE_UP") fail("legacy 소진이 GIVE_UP 이 아님: ${h.states(id)}")
            Outcome()
        })
    }

    private fun serviceSnapshot() = GattServiceSnapshot(
        serviceUuid = serviceUuid,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                instanceIndex = 0,
                propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            ),
        ),
    )

    @Test
    fun `시나리오 x 변형의 불변식 위반 집합은 고정 기준선과 같다`() {
        val observed = sortedSetOf<String>()
        val table = StringBuilder()
        val unmet = mutableListOf<String>()
        for (s in scenarios()) {
            var control: GenerationStats? = null
            var controlTrail = ""
            if (s.control != null) runTest {
                val h = harness(config, s.reconnect, s.legacy)
                h.session(id)
                s.control.invoke(this, h)
                h.advancePastAllDeadlines()
                control = statsOf(h)
                controlTrail = h.states(id).joinToString(" > ") { short(it) }
                h.close()
            }
            runTest {
                val h = harness(config, s.reconnect, s.legacy)
                h.session(id)
                val outcome = s.body(this, h)
                h.advancePastAllDeadlines()
                val history = ConnectionHistory(
                    states = h.states(id),
                    actives = h.actives(id),
                    timeline = h.timeline(id),
                    connEvents = h.connEvents(id),
                    userDisconnected = outcome.userDisconnected,
                    pastAllDeadlines = true,
                    descriptorWritesAfterReconnect = outcome.descriptorWritesAfterReconnect,
                    closedAt = outcome.closedAt,
                    lateRun = if (s.control != null) statsOf(h) else null,
                    controlRun = control,
                )
                val last = h.states(id).last()
                if (outcome.expectedLast != null && outcome.expectedLast != last) {
                    unmet += "${s.name} | ${s.variant}: expected ${short(outcome.expectedLast)} got ${short(last)}"
                }
                val violations = checkConnectionInvariants(history)
                violations.forEach { observed += "${s.name} | ${s.variant} | ${it.id}" }
                table.append("  ${s.name} | ${s.variant} -> ${short(h.states(id).last())}  [${h.states(id).joinToString(" > ") { short(it) }}]")
                if (violations.isNotEmpty()) table.append("  ${violations.joinToString { "${it.id}(${it.detail})" }}")
                if (s.control != null) table.append("  control=[$controlTrail]")
                table.append('\n')
                h.close()
            }
        }
        assertViolationsPinned(KNOWN_INVARIANT_VIOLATIONS, observed, "최종 상태 표:\n$table")
        if (unmet.isNotEmpty()) fail("시나리오 기대 최종 상태 불일치:\n${unmet.joinToString("\n")}\n최종 상태 표:\n$table")
    }

    companion object {
        /**
         * 현재 main 이 내는 불변식 위반 기준선. 2~4단계가 줄여 간다.
         * 새 항목이 생기면 회귀(red), 고쳐진 항목은 이 목록에서 빼야 green 이다.
         * 형식: "<시나리오> | <변형> | <불변식>".
         */
        val KNOWN_INVARIANT_VIOLATIONS: Set<String> = setOf(
            // 사용자 해제 뒤 새 connect 가 이전 구독을 복원한다(CCCD write·active 남음) — 이 plan 범위 밖.
            "subscribe→disconnect→connect | all Arrive | I7",
        )
    }
}
