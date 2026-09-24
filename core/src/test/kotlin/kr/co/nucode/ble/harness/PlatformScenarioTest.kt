package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
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
        /** 하네스 기본 연결 옵션(autoReconnect 여부). */
        val options: BleConnectOptions = BleConnectOptions(),
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

    private val auto = BleConnectOptions(autoReconnect = true)

    /** 표·위반 문자열용 짧은 상태 이름(실패는 코드만). */
    private fun short(state: ConnectionState?): String = when (state) {
        is ConnectionState.Disconnected -> when (val r = state.reason) {
            is BleDisconnectReason.Failed -> "Disconnected(${r.failure.code.value})"
            else -> "Disconnected($r)"
        }
        is ConnectionState.Recovering -> "Recovering(${state.lastFailure.code.value})"
        else -> state.toString()
    }

    private fun label(v: CallbackVariant): String = when (v) {
        is CallbackVariant.Arrive -> "Arrive(${v.status})"
        is CallbackVariant.ArriveBeforeStartReturns -> "ArriveBeforeStartReturns(${v.status})"
        CallbackVariant.Missing -> "Missing"
        is CallbackVariant.Late -> "Late(${v.delayMillis},${v.status})"
        is CallbackVariant.Duplicate -> "Duplicate(${v.status})"
    }

    private fun policyName(p: BleConnectOptions) = if (p.autoReconnect) "autoReconnect" else "autoReconnect=off"

    /** 정리 해제(driver.disconnect) 콜백이 올지 모르는 경로의 두 변형. */
    private val cleanupDisconnect = listOf(CallbackVariant.Arrive(0), CallbackVariant.Missing)

    /** 구독을 걸고 성공·activeSubscriptions 비어 있지 않음을 시나리오 전제로 단언한다. */
    private fun subscribeOrFail(h: Harness) {
        val result = h.subscribe(id, ref)
        if (result == null || result.isFailure) fail("전제 실패: subscribe 가 성공하지 않음 ($result)")
        if (h.session(id).activeSubscriptions.value.isEmpty()) fail("전제 실패: subscribe 뒤 activeSubscriptions 가 비어 있음")
    }

    /** I9 비교용 세대 통계(모든 deadline 이 지난 뒤 본다). */
    private fun statsOf(h: Harness) = GenerationStats(
        connectCalls = h.fake.calls.count { it == "connect" },
        connectingEntries = h.states(id).count { it is ConnectionState.Connecting },
        last = h.states(id).lastOrNull(),
    )

    /** 플랫폼 재연결 대기(Recovering)를 시나리오 전제로 단언한다. */
    private fun requireRecovering(h: Harness) {
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: Recovering 아님 ${h.states(id)}")
    }

    /** OS autoConnect 흉내 — 같은 handle(epoch)에서 STATE_CONNECTED 를 내고 Ready 까지 진행. */
    private fun relinkToReady(h: Harness) {
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("플랫폼 재연결이 Ready 에 도달") { h.session(id).connectionState.value == ConnectionState.Ready }
    }

    /**
     * autoReconnect: Ready → 링크 끊김(8) → 플랫폼 재연결 대기 중 현재 handle 에서 STATE_DISCONNECTED([late]) →
     * 플랫폼 재연결(STATE_CONNECTED) → Ready. [late] 가 null 이면 대조 실행(I9).
     */
    private fun lateInWait(late: Int?): kotlinx.coroutines.test.TestScope.(Harness) -> Outcome = { h ->
        h.connectToReady(id)
        h.fake.linkLoss(id, 8)
        runCurrent()
        requireRecovering(h)
        if (late != null) h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, late)
        runCurrent()
        relinkToReady(h)
        Outcome()
    }

    /**
     * autoReconnect: 이전(사용자 해제로 닫힌) handle 을 남긴 뒤 새 연결 Ready → 링크 끊김 → 플랫폼 재연결이
     * 시작된 뒤 이전 handle 에서 STATE_DISCONNECTED(0) 를 낸다. [late] 가 false 면 대조 실행(I9).
     */
    private fun oldHandleDuringRelink(late: Boolean): kotlinx.coroutines.test.TestScope.(Harness) -> Outcome = { h ->
        h.connectToReady(id)
        val oldHandle = h.fake.epoch
        h.disconnect(id)
        h.advancePastAllDeadlines()
        h.connectToReady(id)
        if (h.fake.epoch == oldHandle) fail("전제 실패: 새 connect 가 handle 을 바꾸지 않음 calls=${h.fake.calls}")
        h.fake.linkLoss(id, 8)
        runCurrent()
        requireRecovering(h)
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        runCurrent()
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
        // S Ready 중 링크 끊김 — autoReconnect=off 는 종결, autoReconnect 는 대기 뒤 플랫폼 재연결로 Ready.
        // (autoReconnect status=8 + 구독 복원은 PlatformAutoReconnectTest b)
        for (status in listOf(8, 19)) {
            add(Scenario("link loss while Ready", "status=$status ${policyName(BleConnectOptions())}") { h ->
                h.connectToReady(id)
                h.fake.linkLoss(id, status)
                Outcome()
            })
        }
        add(Scenario("link loss while Ready", "status=19 ${policyName(auto)} +platform re-link", auto) { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 19)
            runCurrent()
            requireRecovering(h)
            relinkToReady(h)
            Outcome(expectedLast = ConnectionState.Ready)
        })
        // S 구독 → 링크 끊김 — I4 를 결합 기록의 모든 점에서 본다(autoReconnect 판은 PlatformAutoReconnectTest b).
        add(Scenario("subscribe→link loss", policyName(BleConnectOptions())) { h ->
            h.fake.services = listOf(serviceSnapshot())
            h.connectToReady(id)
            subscribeOrFail(h)
            h.fake.linkLoss(id, 8)
            Outcome()
        })
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
        // S Ready 중 어댑터 off — 뒤이어 STATE_DISCONNECTED 가 오지 않는 경우와 오는 경우(status 0·8).
        for (p in listOf(BleConnectOptions(), auto)) for (late in listOf<Int?>(null, 0, 8)) {
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
        add(Scenario("late STATE_DISCONNECTED", "after link loss ended") { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            h.fake.emitConnectionState(id, BluetoothProfile.STATE_DISCONNECTED, 0)
            Outcome()
        })
        // S 플랫폼 재연결 대기 중 현재 handle 의 늦은 STATE_DISCONNECTED — 재연결 뒤 세대 통계가 대조와 같아야 한다(I9).
        // (재연결 없이 대기가 유지되는지는 PlatformAutoReconnectTest h)
        for (late in listOf(0, 8)) {
            add(Scenario("late STATE_DISCONNECTED", "platform re-link wait status=$late (current handle)", auto, body = lateInWait(late), control = lateInWait(null)))
        }
        // S 플랫폼 재연결 진행 중 이전 handle 의 늦은 STATE_DISCONNECTED — 출처 식별로 버려져야 한다(I9).
        add(Scenario("late STATE_DISCONNECTED", "during platform re-link (previous handle)", auto, body = oldHandleDuringRelink(true), control = oldHandleDuringRelink(false)))
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
        add(Scenario("close", "after failed terminal (no retry)") { h ->
            h.connectToReady(id)
            h.fake.linkLoss(id, 8)
            h.advancePastAllDeadlines()
            val failed = h.session(id).connectionState.value
            if (failed !is ConnectionState.Disconnected) fail("전제 실패: 종결되지 않음 ${h.states(id)}")
            Outcome(closedAt = closeInScenario(h), expectedLast = failed)
        })
        // S 플랫폼 재연결 대기 중 notification — 끝난 연결 generation 의 값은 앱에 전달되지 않아야 한다(I10).
        add(Scenario("notify during platform re-link wait", policyName(auto), auto) { h ->
            h.connectToReady(id)
            subscribeOrFail(h)
            h.recordNotifications(id, ref)
            runCurrent()
            h.fake.linkLoss(id, 8)
            runCurrent()
            requireRecovering(h)
            h.fake.emitNotification(id, ref, byteArrayOf(7))
            runCurrent()
            relinkToReady(h)
            Outcome(expectedLast = ConnectionState.Ready)
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

    /** 플랫폼 재연결 대기(Recovering) 중 unsubscribe 는 예외 없이 끝나고, 재연결 뒤 구독을 되살리지 않는다. */
    @Test
    fun `재연결 대기 중 unsubscribe 는 로컬 해제만 하고 재연결 뒤 CCCD 를 쓰지 않는다`() = runTest {
        val h = harness(config, auto)
        h.connectToReady(id)
        subscribeOrFail(h)
        h.fake.linkLoss(id, 8)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: Recovering 아님 ${h.states(id)}")
        val descriptorsBefore = h.fake.calls.count { it == "descriptor" }
        val job = async { runCatching { h.session(id).unsubscribe(ref) } }
        runCurrent()
        if (!job.isCompleted) fail("Recovering 중 unsubscribe 가 끝나지 않음")
        job.getCompleted().exceptionOrNull()?.let { fail("Recovering 중 unsubscribe 가 예외를 던짐: $it") }
        relinkToReady(h)
        runCurrent()
        val descriptorsAfter = h.fake.calls.count { it == "descriptor" } - descriptorsBefore
        if (descriptorsAfter != 0) fail("unsubscribe 뒤 CCCD write $descriptorsAfter 회 calls=${h.fake.calls}")
        if (h.session(id).activeSubscriptions.value.isNotEmpty()) fail("재연결 뒤 activeSubscriptions 가 비어 있지 않음: ${h.session(id).activeSubscriptions.value}")
        h.close()
    }

    private fun notificationsIn(h: Harness) = h.timeline(id).count { it is TimelinePoint.Notification }

    /** 양성 대조 — Ready 에서 온 notify 는 전달된다. */
    @Test
    fun `Ready 중 notify 는 한 번 전달된다`() = runTest {
        val h = harness(config)
        h.connectToReady(id)
        subscribeOrFail(h)
        h.recordNotifications(id, ref)
        runCurrent()
        h.fake.emitNotification(id, ref, byteArrayOf(1))
        runCurrent()
        if (notificationsIn(h) != 1) fail("Ready notify 전달 수 ${notificationsIn(h)} != 1 timeline=${h.timeline(id)}")
        h.close()
    }

    /** 양성 대조 — 플랫폼 재연결의 새 generation(Ready 전, 서비스 탐색 대기) 에서 온 notify 는 전달된다. */
    @Test
    fun `플랫폼 재연결 새 generation 의 notify 는 전달된다`() = runTest {
        val h = harness(config, auto)
        h.connectToReady(id)
        subscribeOrFail(h)
        h.recordNotifications(id, ref)
        runCurrent()
        h.fake.linkLoss(id, 8)
        runCurrent()
        // 재연결 뒤 서비스 탐색 콜백을 붙잡아 Ready 전 단계에 머물게 한다.
        h.fake.onDiscover = CallbackVariant.Missing
        requireRecovering(h)
        h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
        h.waitUntil("재연결 뒤 서비스 탐색 시작") { h.fake.calls.count { it == "discover" } >= 2 }
        val state = h.session(id).connectionState.value
        if (state is ConnectionState.Ready || state is ConnectionState.Recovering || state is ConnectionState.Disconnected) {
            fail("전제 실패: 재연결 시도 진행 중(Connecting 이후·Ready 전) 아님 $state ${h.states(id)}")
        }
        h.fake.emitNotification(id, ref, byteArrayOf(2))
        runCurrent()
        if (notificationsIn(h) != 1) fail("재연결 시도 중 notify 전달 수 ${notificationsIn(h)} != 1 timeline=${h.timeline(id)}")
        h.close()
    }

    /** 대조 — 사용자 disconnect 가 끝난 뒤 온 notify 는 전달되지 않는다. */
    @Test
    fun `사용자 disconnect 완료 뒤 notify 는 전달되지 않는다`() = runTest {
        val h = harness(config)
        h.connectToReady(id)
        subscribeOrFail(h)
        h.recordNotifications(id, ref)
        runCurrent()
        h.disconnect(id)
        h.advancePastAllDeadlines()
        if (h.session(id).connectionState.value != ConnectionState.Disconnected(BleDisconnectReason.Requested)) {
            fail("전제 실패: disconnect 가 끝나지 않음 ${h.states(id)}")
        }
        h.fake.emitNotification(id, ref, byteArrayOf(3))
        runCurrent()
        if (notificationsIn(h) != 0) fail("disconnect 뒤 notify 전달 수 ${notificationsIn(h)} != 0 timeline=${h.timeline(id)}")
        h.close()
    }

    @Test
    fun `시나리오 x 변형의 불변식 위반 집합은 고정 기준선과 같다`() {
        val observed = sortedSetOf<String>()
        val table = StringBuilder()
        val unmet = mutableListOf<String>()
        for (s in scenarios()) {
            var control: GenerationStats? = null
            var controlTrail = ""
            if (s.control != null) runTest {
                val h = harness(config, s.options)
                h.session(id)
                s.control.invoke(this, h)
                h.advancePastAllDeadlines()
                control = statsOf(h)
                controlTrail = h.states(id).joinToString(" > ") { short(it) }
                h.close()
            }
            runTest {
                val h = harness(config, s.options)
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
        val KNOWN_INVARIANT_VIOLATIONS: Set<String> = emptySet()
    }
}
