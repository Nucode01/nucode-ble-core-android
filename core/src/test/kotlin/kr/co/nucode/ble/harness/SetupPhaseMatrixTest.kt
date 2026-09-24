package kr.co.nucode.ble.harness

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleDisconnectReason
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot
import org.junit.Assert.fail
import org.junit.Test

/**
 * 연결 단계 × 사건 조합 표(plan link-setup v1 task 1 — LinkSetup 도입 전 안전망).
 * 원하는 구독이 있는 세션(복원 단계가 있다)을 붙은 뒤 처리의 한 단계에 콜백 유실로 붙잡아 두고
 * 사건 하나를 넣은 뒤 최종 상태·handle 정리·복원 중단·activeSubscriptions·불변식을 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupPhaseMatrixTest {

    /** 붙은 뒤 처리의 붙잡을 단계. [hold] 가 그 단계의 콜백을 유실시킨다. */
    private enum class Phase(val hold: (FakeAndroidPlatform) -> Unit) {
        Discovering({ it.onDiscover = CallbackVariant.Missing }),
        MtuWait({ it.onMtu = CallbackVariant.Missing }),
        Restoring({ it.onDescriptorWrite = CallbackVariant.Missing }),
    }

    /** 단계에 들어가는 길. */
    private enum class Mode {
        /** autoReconnect=false 세션: 링크 끊김 → Disconnected(Failed) → 새 connect(). */
        Fresh,
        /** autoReconnect=true 세션: 링크 끊김 → Recovering → 같은 handle 의 STATE_CONNECTED. */
        Relink,
        /** autoReconnect=true 세션: 어댑터 꺼짐 → 복귀 때 라이브러리가 새로 거는 첫 연결. */
        AutoFresh,
    }

    private enum class Event { Disconnect, Close, AdapterOff, LinkLoss, SubscribeOther, Unsubscribe }

    private val id = PeripheralId("AA:BB:CC:DD:EE:F4")
    private val serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val otherUuid = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
    private val ref = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(charUuid))
    private val other = CharacteristicRef(ServiceRef(BleUuid(serviceUuid)), BleUuid(otherUuid))

    private val config = BleConfig(
        operationTimeoutMillis = 50,
        linkTimeoutMillis = 100,
        discoveryTimeoutMillis = 100,
        configurationTimeoutMillis = 100,
        disconnectCleanupTimeoutMillis = 50,
        rediscoveryTimeoutMillis = 50,
    )

    private val auto = BleConnectOptions(autoReconnect = true)
    private val manual = BleConnectOptions(autoReconnect = false)

    private fun characteristic(uuid: UUID) = GattCharacteristicSnapshot(
        serviceUuid = serviceUuid,
        charUuid = uuid,
        instanceIndex = 0,
        propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
    )

    private fun serviceSnapshot() = GattServiceSnapshot(
        serviceUuid = serviceUuid,
        characteristics = listOf(characteristic(charUuid), characteristic(otherUuid)),
    )

    private fun short(state: ConnectionState?): String = when (state) {
        is ConnectionState.Disconnected -> when (val r = state.reason) {
            is BleDisconnectReason.Failed -> "Disconnected(${r.failure.code.value})"
            else -> "Disconnected($r)"
        }
        is ConnectionState.Recovering -> "Recovering(${state.lastFailure.code.value})"
        else -> state.toString()
    }

    private fun count(h: Harness, call: String) = h.fake.calls.count { it == call }

    /**
     * 구독이 있는 Ready 세션을 [mode] 의 길로 [phase] 에 붙잡는다. 전제가 어긋나면 `전제 실패` 로 끝난다.
     */
    private fun TestScope.enterPhase(h: Harness, mode: Mode, phase: Phase) {
        h.fake.services = listOf(serviceSnapshot())
        h.connectToReady(id, if (mode == Mode.Fresh) manual else auto)
        val result = h.subscribe(id, ref)
        if (result == null || result.isFailure) fail("전제 실패: subscribe 가 성공하지 않음 ($result)")
        when (mode) {
            Mode.Fresh -> {
                h.fake.linkLoss(id, 8)
                runCurrent()
                if (h.session(id).connectionState.value !is ConnectionState.Disconnected) fail("전제 실패: 링크 끊김 뒤 Disconnected 아님 ${h.states(id)}")
                phase.hold(h.fake)
                h.connect(id, manual)
            }
            Mode.Relink -> {
                h.fake.linkLoss(id, 8)
                runCurrent()
                if (h.session(id).connectionState.value !is ConnectionState.Recovering) fail("전제 실패: 링크 끊김 뒤 Recovering 아님 ${h.states(id)}")
                phase.hold(h.fake)
                h.fake.emitConnectionState(id, BluetoothProfile.STATE_CONNECTED, 0)
            }
            Mode.AutoFresh -> {
                h.fake.setAdapter(AdapterState.PoweredOff)
                runCurrent()
                if (h.session(id).connectionState.value !is ConnectionState.Disconnected) fail("전제 실패: 어댑터 꺼짐 뒤 Disconnected 아님 ${h.states(id)}")
                phase.hold(h.fake)
                val connects = count(h, "connect")
                h.fake.setAdapter(AdapterState.Ready)
                runCurrent()
                if (count(h, "connect") != connects + 1) fail("전제 실패: 어댑터 복귀 connect 없음 ${h.fake.calls}")
            }
        }
        repeat(5) { runCurrent() }
        val state = h.session(id).connectionState.value
        val descriptors = count(h, "descriptor")
        // 첫 Ready 의 subscribe 가 CCCD 한 번. 복원 write 는 그 뒤.
        when (phase) {
            Phase.Discovering ->
                if (state != ConnectionState.DiscoveringServices || h.fake.calls.last() != "discover") {
                    fail("전제 실패: $mode/$phase 탐색 대기 아님 state=$state calls=${h.fake.calls}")
                }
            Phase.MtuWait ->
                if (state != ConnectionState.Configuring || h.fake.calls.last() != "mtu" || descriptors != 1) {
                    fail("전제 실패: $mode/$phase MTU 대기 아님 state=$state calls=${h.fake.calls}")
                }
            Phase.Restoring ->
                if (state != ConnectionState.Configuring || h.fake.calls.last() != "descriptor" || descriptors != 2) {
                    fail("전제 실패: $mode/$phase 복원 CCCD 대기 아님 state=$state calls=${h.fake.calls}")
                }
        }
        if (h.session(id).activeSubscriptions.value.isNotEmpty()) fail("전제 실패: 붙은 뒤 처리 중 activeSubscriptions 가 남음")
    }

    private data class Observed(
        val last: String,
        val closesAfterEvent: Int,
        val descriptorsAfterEnd: Int,
        val commandResult: String?,
        val violations: List<InvariantViolation>,
        val activeLeak: Boolean,
        /** subscribe 거절 뒤 [other] 가 원하는 구독에 없고, 끝난 unsubscribe 뒤 [ref] 가 없는가. */
        val desiredOk: Boolean,
        /** 끝나는 사건 뒤 실제 복원 job 손잡이가 남았는가. */
        val restoreJobLeft: Boolean,
    )

    private fun runCell(mode: Mode, phase: Phase, event: Event): Observed {
        var observed: Observed? = null
        runTest {
            val h = harness(config)
            enterPhase(h, mode, phase)
            val session = h.core.registry.get(id) ?: throw AssertionError("전제 실패: 런타임 세션 없음")
            val closesBefore = count(h, "close")
            var closedAt: Int? = null
            var userDisconnected = false
            var commandResult: String? = null
            when (event) {
                Event.Disconnect -> { h.disconnect(id); userDisconnected = true }
                Event.Close -> { closedAt = h.states(id).size; h.close() }
                Event.AdapterOff -> { h.fake.setAdapter(AdapterState.PoweredOff); runCurrent() }
                Event.LinkLoss -> { h.fake.linkLoss(id, 8); runCurrent() }
                Event.SubscribeOther -> commandResult = h.subscribe(id, other)?.let(::describe) ?: "pending"
                Event.Unsubscribe -> {
                    val job = async { runCatching { h.session(id).unsubscribe(ref) } }
                    runCurrent()
                    commandResult = if (job.isCompleted) describe(job.getCompleted()) else "pending"
                }
            }
            val terminal = event in setOf(Event.Disconnect, Event.Close, Event.AdapterOff, Event.LinkLoss)
            val descriptorsAtEnd = count(h, "descriptor")
            if (terminal) h.advancePastAllDeadlines()
            val desired = h.core.registry.get(id)?.subscriptionState?.desiredSnapshot
            val desiredOk = when {
                event == Event.SubscribeOther -> desired != null && other !in desired
                event == Event.Unsubscribe && commandResult == "ok" -> desired != null && ref !in desired
                else -> true
            }
            val states = h.states(id)
            val last = states.last()
            val activeLeak = last != ConnectionState.Ready && last != ConnectionState.Configuring &&
                h.actives(id).last().isNotEmpty()
            val history = ConnectionHistory(
                states = states,
                actives = h.actives(id),
                timeline = h.timeline(id),
                connEvents = h.connEvents(id),
                userDisconnected = userDisconnected,
                pastAllDeadlines = terminal,
                autoReconnect = mode != Mode.Fresh,
                platformWaiting = if (event == Event.Close) false else h.core.registry.get(id)?.awaitingPlatformRelink ?: false,
                closedAt = closedAt,
            )
            observed = Observed(
                last = short(last),
                closesAfterEvent = count(h, "close") - closesBefore,
                descriptorsAfterEnd = count(h, "descriptor") - descriptorsAtEnd,
                commandResult = commandResult,
                violations = checkConnectionInvariants(history),
                activeLeak = activeLeak,
                desiredOk = desiredOk,
                restoreJobLeft = terminal && h.core.linkSetup.restoreJob(session) != null,
            )
            if (event != Event.Close) h.close()
        }
        return observed!!
    }

    private fun describe(result: Result<Unit>): String = result.fold(
        onSuccess = { "ok" },
        onFailure = { (it as? BleException)?.failure?.code?.value ?: it.toString() },
    )

    /**
     * 칸별 기대(현재 규칙):
     * - disconnect → Requested(아직 끝나지 않은 generation), close → ClientClosed, 어댑터 꺼짐 → Failed(ADAPTER_UNAVAILABLE).
     * - 붙은 뒤 처리 중 링크 끊김 → Failed(LINK_FAILED) 로 handle 을 닫는다. autoReconnect=false 첫 연결,
     *   플랫폼 재연결 generation(plan B 결정 A), autoReconnect 세션의 첫 연결 모두 같다 — Ready 전 실패는
     *   OS 대기(Recovering)로 돌아가지 않는다.
     * - 끝나는 사건은 handle 을 정확히 한 번 닫고, 끝난 뒤 복원 CCCD write 는 없다.
     * - subscribe(다른 ref) 는 Ready 가 아니므로 NOT_READY 로 거절되고 단계는 그대로다(대기열에 넣지 않는다).
     * - unsubscribe 는 탐색·MTU 대기 중엔 즉시 끝나고 원하는 구독에서 지운다. 복원 CCCD 가 걸려 있으면
     *   해제 CCCD 가 그 뒤에 줄을 서므로 끝나지 않고(pending) 단계도 그대로다.
     */
    private data class Expected(val last: String, val closes: Int, val commandResult: String? = null)

    private fun expected(phase: Phase, event: Event): Expected = when (event) {
        Event.Disconnect -> Expected("Disconnected(Requested)", 1)
        Event.Close -> Expected("Disconnected(ClientClosed)", 1)
        Event.AdapterOff -> Expected("Disconnected(ADAPTER_UNAVAILABLE)", 1)
        Event.LinkLoss -> Expected("Disconnected(LINK_FAILED)", 1)
        Event.SubscribeOther -> Expected(phaseState(phase), 0, "NOT_READY")
        Event.Unsubscribe -> Expected(phaseState(phase), 0, if (phase == Phase.Restoring) "pending" else "ok")
    }

    private fun phaseState(phase: Phase) = if (phase == Phase.Discovering) "DiscoveringServices" else "Configuring"

    @Test
    fun `붙은 뒤 처리 단계 × 사건 조합 표`() {
        val mismatches = mutableListOf<String>()
        var cells = 0
        for (mode in Mode.values()) for (phase in Phase.values()) for (event in Event.values()) {
            cells++
            val cell = "$mode/$phase/$event"
            val observed = try {
                runCell(mode, phase, event)
            } catch (e: AssertionError) {
                mismatches += "$cell: ${e.message}"
                continue
            }
            val want = expected(phase, event)
            if (observed.last != want.last) mismatches += "$cell: 최종 상태 ${observed.last} (기대 ${want.last})"
            if (observed.closesAfterEvent != want.closes) mismatches += "$cell: handle close ${observed.closesAfterEvent}회 (기대 ${want.closes})"
            if (observed.descriptorsAfterEnd != 0) mismatches += "$cell: 끝난 뒤 복원 CCCD write ${observed.descriptorsAfterEnd}회"
            if (observed.commandResult != want.commandResult) mismatches += "$cell: 명령 결과 ${observed.commandResult} (기대 ${want.commandResult})"
            if (observed.desiredOk.not()) mismatches += "$cell: 원하는 구독 집합이 명령 결과와 맞지 않음"
            if (observed.restoreJobLeft) mismatches += "$cell: 끝난 뒤 복원 job 손잡이가 남음"
            if (observed.activeLeak) mismatches += "$cell: Ready/Configuring 아닌데 activeSubscriptions 남음"
            if (observed.violations.isNotEmpty()) mismatches += "$cell: 불변식 위반 ${observed.violations}"
        }
        if (cells != Mode.values().size * Phase.values().size * Event.values().size) fail("칸 수 불일치 $cells")
        if (mismatches.isNotEmpty()) fail("조합 표 ${mismatches.size}건 불일치:\n" + mismatches.joinToString("\n"))
    }
    /**
     * 표가 닿지 않는 정리 경로 ① — 복원 CCCD 가 걸린 채 해제 콜백이 오지 않으면 정리는 deadline 까지 열려 있다.
     * 그 사이에도 복원 job 손잡이는 정리 시작(beginCleanup)에서 이미 끊겨 있어야 한다.
     */
    @Test
    fun `복원 중 해제 콜백이 없어도 정리 시작에서 복원 job 을 끊는다`() = runTest {
        val h = harness(config)
        enterPhase(h, Mode.Relink, Phase.Restoring)
        val session = h.core.registry.get(id) ?: throw AssertionError("전제 실패: 런타임 세션 없음")
        if (h.core.linkSetup.restoreJob(session) == null) fail("전제 실패: 복원 job 이 진행 중이 아님")
        h.fake.onDisconnect = CallbackVariant.Missing
        h.disconnect(id)
        if (h.session(id).connectionState.value is ConnectionState.Disconnected) fail("전제 실패: 정리 창이 열리지 않음 ${h.states(id)}")
        if (h.core.linkSetup.restoreJob(session) != null) fail("정리 창 안에서 복원 job 손잡이가 남음")
        h.close()
    }

    /**
     * 표가 닿지 않는 정리 경로 ② — 복원 CCCD 가 응답 없이 operation timeout 으로 poison 되면
     * terminateForOperationInvalidation 이 세션을 끝낸다. 그때 복원 job 손잡이도 끊겨야 한다.
     */
    @Test
    fun `복원 CCCD 가 operation timeout 으로 무효화되면 복원 job 을 끊는다`() = runTest {
        val h = harness(config)
        enterPhase(h, Mode.Relink, Phase.Restoring)
        val session = h.core.registry.get(id) ?: throw AssertionError("전제 실패: 런타임 세션 없음")
        if (h.core.linkSetup.restoreJob(session) == null) fail("전제 실패: 복원 job 이 진행 중이 아님")
        testScheduler.advanceTimeBy(config.operationTimeoutMillis + 1)
        runCurrent()
        if (h.session(id).connectionState.value !is ConnectionState.Disconnected) fail("전제 실패: 무효화로 끝나지 않음 ${h.states(id)}")
        if (h.core.linkSetup.restoreJob(session) != null) fail("무효화 종결 뒤 복원 job 손잡이가 남음")
        h.close()
    }

    /** 실제 복원이 진행 중일 때 disconnect·close 가 그 job 자체를 취소하고 손잡이를 비운다(옛 PL-B3·PL-F 의 복원 job 단언). */
    @Test
    fun `복원 중 disconnect 와 close 는 진행 중인 복원 job 을 취소한다`() {
        for (close in listOf(false, true)) runTest {
            val h = harness(config)
            enterPhase(h, Mode.Relink, Phase.Restoring)
            val session = h.core.registry.get(id) ?: throw AssertionError("전제 실패: 런타임 세션 없음")
            val job = h.core.linkSetup.restoreJob(session) ?: throw AssertionError("전제 실패: 복원 job 이 진행 중이 아님")
            if (close) h.close() else h.disconnect(id)
            runCurrent()
            val path = if (close) "close" else "disconnect"
            if (!job.isCancelled) fail("$path 뒤 복원 job 이 취소되지 않음")
            if (h.core.linkSetup.restoreJob(session) != null) fail("$path 뒤 복원 job 손잡이가 남음")
            if (!close) h.close()
        }
    }
}
