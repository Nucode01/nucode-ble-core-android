package kr.co.nucode.ble.sample

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.ADAPTER
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.EVENT
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.LINK_FAILED
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.NOTIFY
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.OFF_FINAL
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.PROBE_VALUE
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.SCAN
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.SCAN_STOPPED
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.STATE
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.SUBS
import kr.co.nucode.ble.sample.DeviceChecklist.Companion.label
import kr.co.nucode.ble.sample.DeviceChecklist.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 기기 점검표. iOS 수동 점검표(`ios/Samples/ManualTest/README.md`, 판정 `ChecklistDetectors.swift`)의 항목 번호를 따른다.
 * 기대는 contract/states.md §2·§3·§6. 항목마다 테스트 메서드 하나이고, 새 클라이언트로 시작해 라디오를 되돌리고 끝낸다.
 * 판정은 logcat `CHECK|android|<항목>|…` 줄이 전부다(테스트 자체는 판정 FAIL 이면 실패한다). 오케스트레이터는 `lab/checklist-android.sh`.
 *
 * 시험 기기(nRF52840 DK, `peripheral_uart`)가 광고 중이고 다른 중앙과 연결되어 있지 않아야 한다.
 * 7(재부팅)과 8a(프로세스 죽음)는 오케스트레이터가 폰을 재부팅하거나 프로세스를 죽이는 사이에 부른다.
 */
@RunWith(AndroidJUnit4::class)
class DeviceChecklistTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*ContractDeviceTest.requiredPermissions())

    @Before
    fun requireHardware() {
        assumeTrue(
            "Pass -e hardwareBle true (or -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true)",
            InstrumentationRegistry.getArguments().getString("hardwareBle") == "true",
        )
    }

    /** 1. Ready(autoReconnect, probe.value 구독) + 스캔 중 블루투스 끄기. */
    @Test(timeout = 180_000)
    fun item1_bluetoothOffWhileReady() = checklist("1") {
        val (client, session) = readySubscribed(autoReconnect = true) ?: return@checklist
        startScanning(client)
        val offAt = bluetooth(false)
        val verdict = judgeOff(session, offAt, autoReconnect = true, scanning = true)
        measureOff(offAt, "bluetooth")
        report(verdict)
    }

    /** 2. 꺼진 채 read·connect. connect 는 ADAPTER_UNAVAILABLE, read 는 값을 기록(계약 미지정), 연결 상태 그대로. */
    @Test(timeout = 180_000)
    fun item2_commandsWhileOff() = checklist("2") {
        val (client, session) = readySubscribed(autoReconnect = true) ?: return@checklist
        val offAt = bluetooth(false)
        val off = judgeOff(session, offAt, autoReconnect = true, scanning = false)
        measureOff(offAt, "bluetooth")
        if (off.status != "PASS") return@checklist report(Verdict.fail("전제(1) 실패: ${off.summary}"))
        report(commandsWhileOff(client, session))
    }

    /** 3. 2 뒤(꺼진 동안 connect 거절 포함) 블루투스 켜기 → 한 번 Connecting→…→Ready, 구독 복원, 알림. */
    @Test(timeout = 240_000)
    fun item3_bluetoothOnReconnectsOnce() = checklist("3") {
        val (client, session) = readySubscribed(autoReconnect = true) ?: return@checklist
        val offAt = bluetooth(false)
        val off = judgeOff(session, offAt, autoReconnect = true, scanning = false)
        measureOff(offAt, "bluetooth")
        if (off.status != "PASS") return@checklist report(Verdict.fail("전제(1) 실패: ${off.summary}"))
        val commands = commandsWhileOff(client, session)
        val onAt = bluetooth(true)
        val back = judgeReturn(session, onAt)
        report(if (back.status == "PASS") Verdict.pass("${back.summary}; 꺼진 동안 ${commands.summary}") else back)
    }

    /** 4. autoReconnect=false 로 1 반복 뒤 켜기 → 30초 동안 Connecting 없음, Disconnected(Failed:ADAPTER_UNAVAILABLE) 유지. */
    @Test(timeout = 180_000)
    fun item4_noReconnectWithoutAutoReconnect() = checklist("4") {
        val (_, session) = readySubscribed(autoReconnect = false) ?: return@checklist
        val offAt = bluetooth(false)
        val off = judgeOff(session, offAt, autoReconnect = false, scanning = false)
        measureOff(offAt, "bluetooth")
        if (off.status != "PASS") return@checklist report(Verdict.fail("끌 때: ${off.summary}"))
        val onAt = bluetooth(true)
        awaitEntry(onAt, ADAPTER, 20_000) { it == "Ready" } || return@checklist report(Verdict.fail("켠 뒤 어댑터 Ready 없음"))
        val readyAt = firstSince(onAt, ADAPTER) { it == "Ready" }!!.atNanos
        delay(NO_RECONNECT_WINDOW_MILLIS)
        val after = since(onAt, EVENT).map { it.detail }
        val state = session.connectionState.value.label()
        report(
            when {
                after.isNotEmpty() -> Verdict.fail("켠 뒤 연결 전이: ${after.joinToString("→")}")
                state != OFF_FINAL -> Verdict.fail("켠 뒤 상태 $state")
                else -> Verdict.pass(
                    "끌 때 ${off.summary}; 어댑터 Ready 뒤 ${(now() - readyAt) / 1_000_000}ms 동안 전이 없음, $OFF_FINAL 유지",
                )
            },
        )
    }

    /** 5. 꺼진 채 새 클라이언트(새 실행) → connect ADAPTER_UNAVAILABLE → 켜고 곧바로 connect → Ready. */
    @Test(timeout = 240_000)
    fun item5_launchWhileOffThenConnect() = checklist("5") {
        // 대상 주소는 켜진 동안 다른 클라이언트로 찾아 둔다(꺼진 채로는 스캔할 수 없다). iOS 5 와 같은 준비다.
        val finder = newClient()
        awaitAdapter(finder, 10_000) { it == AdapterState.Ready } ?: return@checklist report(Verdict.fail("어댑터 Ready 아님"))
        val id = discover(finder)
        finder.close()
        bluetooth(false)
        shell("cmd bluetooth_manager wait-for-state:STATE_OFF")
        delay(1_000)

        val client = newClient()
        delay(500)
        val adapterAtLaunch = client.adapterState.value.label()
        val session = client.session(id)
        observe(session)
        val offCall = now()
        val offCode = call("connect while off") { session.connect(BleConnectOptions(autoReconnect = false)) }
        delay(2_000)
        val changed = since(offCall, EVENT).map { it.detail }

        val onAt = bluetooth(true)
        // 켜기 명령 직후(어댑터가 아직 켜지는 중) 한 번 — 계약상 PoweredOff 면 곧바로 ADAPTER_UNAVAILABLE 이다. 기록만 한다.
        val immediate = call("connect right after enable") { session.connect(BleConnectOptions(autoReconnect = false)) }
        val atImmediate = client.adapterState.value.label()
        var readyCall = "not needed"
        if (immediate != "ok") {
            awaitAdapter(client, 20_000) { it == AdapterState.Ready } ?: return@checklist report(Verdict.fail("켠 뒤 어댑터 Ready 없음"))
            readyCall = call("connect at adapter Ready") { session.connect(BleConnectOptions(autoReconnect = false)) }
        }
        val ready = awaitReady(session, 30_000)
        val recordedReady = ready && awaitEntry(onAt, EVENT, 5_000) { it == "Ready" }
        val path = since(onAt, EVENT).map { it.detail }
        val info = "시작 어댑터 $adapterAtLaunch, 꺼진 채 connect $offCode(상태 전이 ${changed.size}개), " +
            "켜기 직후 connect $immediate(어댑터 $atImmediate), Ready 때 connect $readyCall, 경로 ${path.joinToString("→")}"
        report(
            when {
                offCode != "ADAPTER_UNAVAILABLE" -> Verdict.fail("꺼진 채 connect $offCode(기대 ADAPTER_UNAVAILABLE); $info")
                changed.isNotEmpty() -> Verdict.fail("거절된 connect 뒤 상태 전이; $info")
                !ready -> Verdict.fail("켠 뒤 30초 안에 Ready 없음; $info")
                !recordedReady -> Verdict.fail("Ready 상태 뒤 공개 Ready 이벤트 없음; $info")
                path.any { it.startsWith("Disconnected(Failed") } -> Verdict.fail("Ready 전 실패 상태; $info")
                else -> Verdict.pass(info)
            },
        )
    }

    /** 6. 블루투스 켠 채 비행기 모드 켜고 끄기 → 1+3 과 같음. 비행기 모드가 블루투스를 끄지 않으면 NA. */
    @Test(timeout = 240_000)
    fun item6_airplaneMode() = checklist("6") {
        val radios = "airplane_mode_radios=${setting("global", "airplane_mode_radios")} " +
            "toggleable=${setting("global", "airplane_mode_toggleable_radios")} " +
            "apm_enhancement_enabled=${setting("global", "apm_enhancement_enabled")} " +
            "bluetooth_apm_state=${setting("secure", "bluetooth_apm_state")}"
        mark("settings", radios)
        val (client, session) = readySubscribed(autoReconnect = true) ?: return@checklist
        startScanning(client)
        val offAt = airplane(true)
        if (!awaitEntry(offAt, ADAPTER, 15_000) { it != "Ready" }) {
            return@checklist report(Verdict.na("비행기 모드가 블루투스를 끄지 않음(15초 동안 어댑터 Ready): $radios"))
        }
        val off = judgeOff(session, offAt, autoReconnect = true, scanning = true)
        measureOff(offAt, "airplane")
        if (off.status != "PASS") return@checklist report(Verdict.fail("켤 때(1): ${off.summary}"))
        val onAt = airplane(false)
        val back = judgeReturn(session, onAt)
        report(if (back.status == "PASS") Verdict.pass("켤 때 ${off.summary}; 끌 때 ${back.summary}; $radios") else back)
    }

    /** 7. 재부팅 직후 첫 연결이 실패 없이 Ready. 재부팅은 오케스트레이터가 한다. */
    @Test(timeout = 180_000)
    fun item7_firstConnectAfterReboot() = checklist("7") {
        val uptime = SystemClock.elapsedRealtime()
        mark("uptime", "${uptime}ms /proc/uptime=${shell("cat /proc/uptime")}")
        val client = newClient()
        awaitAdapter(client, 30_000) { it == AdapterState.Ready } ?: return@checklist report(Verdict.fail("어댑터 Ready 아님"))
        val id = discover(client)
        val session = client.session(id)
        observe(session)
        val at = now()
        val code = call("connect") { session.connect(BleConnectOptions(autoReconnect = false)) }
        val ready = code == "ok" && awaitReady(session, 30_000)
        // connectionState와 client.events는 별도 수집기다. 상태가 Ready여도 이벤트 기록은 늦을 수 있다.
        val recordedReady = ready && awaitEntry(at, EVENT, 5_000) { it == "Ready" }
        val path = since(at, EVENT).map { it.detail }
        val info = "uptime ${uptime / 1000}s, connect $code, 경로 ${path.joinToString("→")}"
        report(
            when {
                !ready -> Verdict.fail("첫 연결 Ready 없음; $info")
                !recordedReady -> Verdict.fail("Ready 상태 뒤 공개 Ready 이벤트 없음; $info")
                path != RECONNECT_PATH -> Verdict.fail("경로가 Connecting→DiscoveringServices→Configuring→Ready 가 아님; $info")
                else -> Verdict.pass(info)
            },
        )
    }

    /** 8a 앞 절반: Ready + 구독을 잡은 채 오케스트레이터가 프로세스를 죽일 때까지 기다린다. */
    @Test(timeout = 300_000)
    fun item8a_holdReadyUntilKilled() = checklist("8a-hold") {
        readySubscribed(autoReconnect = true) ?: return@checklist
        Log.i(DeviceChecklist.TAG, "HOLD|android|8a|ready peripheral=${peripheralId?.value}")
        delay(HOLD_MILLIS)
        report(Verdict.fail("${HOLD_MILLIS / 1000}초 안에 프로세스가 죽지 않음"))
    }

    /** 8a 뒤 절반: 프로세스가 죽은 뒤 새 프로세스에서 다시 연결 → Ready, 구독, 알림. */
    @Test(timeout = 180_000)
    fun item8a_reconnectAfterProcessDeath() = checklist("8a-reconnect") {
        val result = readySubscribed(autoReconnect = true) ?: return@checklist
        report(Verdict.pass("새 프로세스 연결 Ready, activeSubscriptions ${result.second.activeSubscriptions.value.map { it.label() }}, 알림 수신"))
    }

    // ── 공통 단계 ─────────────────────────────────────────────────────────────

    private fun checklist(item: String, body: suspend DeviceChecklist.() -> Unit) {
        val line = DeviceChecklist(item).use { run ->
            runBlocking { run.restoreRadios(); run.body() }
            run.lastVerdict
        }
        assertEquals("CHECK $item: ${line?.summary}", true, line == null || line.status != "FAIL")
    }

    /** 새 클라이언트 → 기기 찾기 → 연결 → Ready → probe.value 구독 → 알림 확인. 실패하면 FAIL 을 보고하고 null. */
    private suspend fun DeviceChecklist.readySubscribed(autoReconnect: Boolean): Pair<BleClient, BleSession>? {
        val client = newClient()
        awaitAdapter(client, 10_000) { it == AdapterState.Ready }
            ?: return null.also { report(Verdict.fail("전제: 어댑터 Ready 아님(${client.adapterState.value.label()})")) }
        val id: PeripheralId = discover(client)
        val session = client.session(id)
        observe(session)
        connectReadySubscribed(session, autoReconnect)?.let { reason ->
            report(Verdict.fail("전제: $reason"))
            return null
        }
        return client to session
    }

    private suspend fun DeviceChecklist.startScanning(client: BleClient) {
        val at = now()
        startUserScan(client)
        awaitEntry(at, SCAN, 3_000) { it == "Scanning" }
    }

    /**
     * 1 의 판정(ChecklistDetectors.AdapterOffDetector 대응). [offAt] 뒤 대상의 연결 전이(client.events, 전이마다 하나)는
     * `[Disconnected(Failed:ADAPTER_UNAVAILABLE)]` 이거나 autoReconnect 면 앞에 `Recovering(LINK_FAILED)` 하나만 허용.
     * 스캔 중이었으면 스캔은 `Idle(Failed:SCAN_STOPPED_BY_ADAPTER)` 로 끝난다.
     */
    private suspend fun DeviceChecklist.judgeOff(session: BleSession, offAt: Long, autoReconnect: Boolean, scanning: Boolean): Verdict {
        if (!awaitEntry(offAt, ADAPTER, 15_000) { it != "Ready" }) return Verdict.fail("어댑터가 Ready 를 벗어나지 않음")
        val final = awaitEntry(offAt, EVENT, 20_000) { it == OFF_FINAL }
        // 최종 상태 뒤 늦게 오는 전이가 없는지 조금 더 본다.
        delay(2_000)
        val events = since(offAt, EVENT).map { it.detail }
        val allowed = events == listOf(OFF_FINAL) || (autoReconnect && events == listOf(LINK_FAILED, OFF_FINAL))
        val scans = since(offAt, SCAN).map { it.detail }
        val adapter = firstSince(offAt, ADAPTER) { it != "Ready" }!!
        val finalEntry = firstSince(offAt, EVENT) { it == OFF_FINAL }
        val timing = "어댑터 ${adapter.detail} +${(adapter.atNanos - offAt) / 1_000_000}ms, 최종 +${finalEntry?.let { (it.atNanos - offAt) / 1_000_000 } ?: "-"}ms"
        val path = events.joinToString("→").ifEmpty { "(전이 없음)" }
        val state = session.connectionState.value.label()
        return when {
            !final -> Verdict.fail("20초 안에 $OFF_FINAL 없음(전이 $path, 상태 $state)")
            !allowed -> Verdict.fail("예상 밖 전이 $path")
            state != OFF_FINAL -> Verdict.fail("상태 $state")
            scanning && scans.lastOrNull() != SCAN_STOPPED -> Verdict.fail("스캔이 ${scans.joinToString("→").ifEmpty { "Scanning 유지" }}")
            scanning && scans.size != 1 -> Verdict.fail("스캔 전이 ${scans.joinToString("→")}")
            else -> Verdict.pass("$path${if (scanning) ", 스캔 ${scans.last()}" else ""} ($timing)")
        }
    }

    /** 2 의 판정(OffCommandsDetector 대응). read 코드는 계약 미지정이라 기록하고 NOT_CONNECTED·NOT_READY·ADAPTER_UNAVAILABLE 이면 통과. */
    private suspend fun DeviceChecklist.commandsWhileOff(client: BleClient, session: BleSession): Verdict {
        val at = now()
        val read = call("read probe.value while off") { session.read(PROBE_VALUE) }
        val connect = call("connect(autoReconnect=true) while off") {
            client.connect(session.peripheralId, BleConnectOptions(autoReconnect = true))
        }
        delay(3_000)
        val changed = since(at, EVENT, STATE).map { "${it.kind}:${it.detail}" }
        val state = session.connectionState.value.label()
        val info = "read $read, connect $connect, 상태 $state"
        return when {
            connect != "ADAPTER_UNAVAILABLE" -> Verdict.fail("connect $connect(기대 ADAPTER_UNAVAILABLE); $info")
            read !in READ_CODES_WHILE_OFF -> Verdict.fail("read $read(기대 ${READ_CODES_WHILE_OFF.joinToString("|")}); $info")
            changed.isNotEmpty() -> Verdict.fail("명령 뒤 상태 전이 ${changed.joinToString(",")}; $info")
            state != OFF_FINAL -> Verdict.fail("명령 뒤 상태 $state")
            else -> Verdict.pass("$info 그대로")
        }
    }

    /** 3 의 판정(ReconnectDetector 대응). 어댑터 복귀 뒤 한 번 Connecting→DiscoveringServices→Configuring→Ready, 구독, 알림. */
    private suspend fun DeviceChecklist.judgeReturn(session: BleSession, onAt: Long): Verdict {
        if (!awaitEntry(onAt, ADAPTER, 20_000) { it == "Ready" }) return Verdict.fail("켠 뒤 어댑터 Ready 없음")
        val adapterReady = firstSince(onAt, ADAPTER) { it == "Ready" }!!
        val ready = awaitEntry(onAt, EVENT, RECONNECT_TIMEOUT_MILLIS) { it == "Ready" }
        if (!ready) {
            return Verdict.fail("어댑터 Ready 뒤 30초 안에 Ready 없음(전이 ${since(onAt, EVENT).joinToString("→") { it.detail }})")
        }
        val readyEntry = firstSince(onAt, EVENT) { it == "Ready" }!!
        val subscribed = awaitEntry(onAt, SUBS, 5_000) { "probe.value" in it }
        val notifyAt = now()
        requestNotifications(session, 3)
        val notified = awaitEntry(notifyAt, NOTIFY, NOTIFICATION_TIMEOUT_MILLIS)
        delay(3_000)
        val events = since(onAt, EVENT)
        val path = events.joinToString("→") { it.detail }
        val early = events.firstOrNull { it.atNanos < adapterReady.atNanos }
        val info = "경로 $path, 어댑터 Ready +${(adapterReady.atNanos - onAt) / 1_000_000}ms → Ready +${(readyEntry.atNanos - onAt) / 1_000_000}ms"
        return when {
            early != null -> Verdict.fail("어댑터가 꺼진 동안 ${early.detail}; $info")
            events.map { it.detail } != RECONNECT_PATH -> Verdict.fail("재연결 경로가 한 번의 Connecting→…→Ready 가 아님; $info")
            !subscribed || PROBE_VALUE !in session.activeSubscriptions.value ->
                Verdict.fail("activeSubscriptions 에 probe.value 없음(${session.activeSubscriptions.value.map { it.label() }}); $info")
            !notified -> Verdict.fail("Ready 뒤 알림 없음; $info")
            else -> Verdict.pass("$info, activeSubscriptions {probe.value}, 알림 재개")
        }
    }

    private companion object {
        val RECONNECT_PATH = listOf("Connecting", "DiscoveringServices", "Configuring", "Ready")
        val READ_CODES_WHILE_OFF = setOf("NOT_CONNECTED", "NOT_READY", "ADAPTER_UNAVAILABLE")
        const val RECONNECT_TIMEOUT_MILLIS = 30_000L
        const val NOTIFICATION_TIMEOUT_MILLIS = 60_000L
        const val NO_RECONNECT_WINDOW_MILLIS = 30_000L
        const val HOLD_MILLIS = 240_000L
    }
}
