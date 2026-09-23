package kr.co.nucode.ble.sample

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleSession
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteMode
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.DeliverySemantics
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.android.AndroidBleClientFactory
import kr.co.nucode.ble.android.AndroidBondController
import kr.co.nucode.ble.android.AndroidBondResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BleHardwareIntegrationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*requiredPermissions())

    private lateinit var client: BleClient
    private var activeSession: BleSession? = null

    /** runBlocking 자식이 아닌 보조 로거용 — 테스트가 중간에 던져도 매달리지 않고 [tearDown] 에서 정리된다. */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true to run hardware tests",
            instrumentationArguments().getString(ARG_HARDWARE_BLE) == "true",
        )
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        client = AndroidBleClientFactory.create(
            context,
            BleConfig(
                operationTimeoutMillis = 10_000,
                linkTimeoutMillis = 20_000,
                discoveryTimeoutMillis = 15_000,
                configurationTimeoutMillis = 15_000,
                disconnectCleanupTimeoutMillis = 7_000,
            ),
        )
    }

    @After
    fun tearDown() {
        if (!::client.isInitialized) return
        runBlocking {
            runCatching {
                withTimeout(10_000) { activeSession?.disconnect() }
            }
        }
        logScope.cancel()
        client.close()
    }

    @Test(timeout = 150_000)
    fun longWritesAndReconnectRestoreAreVerifiedByPeripheral() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        val options = reconnectOptions()
        val session = client.connect(peripheralId, options).also { activeSession = it }
        val duplicateConnectTransitions = mutableListOf<ConnectionState>()
        var observeDuplicateConnect = false
        val eventLogJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                Log.i(TAG, "event=$event")
                if (
                    observeDuplicateConnect &&
                    event is OfficialBleClientEvent.Connection &&
                    event.peripheralId == peripheralId &&
                    event.state !is ConnectionState.Ready
                ) {
                    duplicateConnectTransitions += event.state
                }
            }
        }
        val stateLogJob = launch(start = CoroutineStart.UNDISPATCHED) {
            session.connectionState.collect { Log.i(TAG, "state=$it") }
        }
        awaitReady(session)
        val nus = session.nusCharacteristics()
        val messages = Channel<String>(Channel.UNLIMITED)
        val notificationJob = launch(start = CoroutineStart.UNDISPATCHED) {
            session.notifications(nus.tx).collect { messages.send(it.decodeToString().trim()) }
        }

        try {
            session.subscribe(nus.tx)
            messages.awaitSequence()
            val initialStatus = readStatus(session, nus.rx, messages)

            observeDuplicateConnect = true
            val firstDuplicate = client.connect(peripheralId, options)
            val secondDuplicate = client.connect(peripheralId, options)
            delay(500)
            observeDuplicateConnect = false
            assertEquals(ConnectionState.Ready, firstDuplicate.connectionState.value)
            assertEquals(ConnectionState.Ready, secondDuplicate.connectionState.value)
            assertTrue(
                "duplicate connect caused connection transitions: $duplicateConnectTransitions",
                duplicateConnectTransitions.isEmpty(),
            )
            val afterDuplicateConnect = readStatus(session, nus.rx, messages)
            assertEquals(initialStatus.linkValue("c"), afterDuplicateConnect.linkValue("c"))

            verifyLongWrite(
                session = session,
                rx = nus.rx,
                messages = messages,
                mode = BleWriteMode.WithResponse,
                expectedDelivery = DeliverySemantics.RemoteAcknowledged,
            )
            verifyLongWrite(
                session = session,
                rx = nus.rx,
                messages = messages,
                mode = BleWriteMode.WithoutResponse,
                expectedDelivery = DeliverySemantics.SubmittedToPlatform,
            )

            val sequenceBeforeDisconnect = messages.awaitSequence()

            val transition = launch(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(RECONNECT_TIMEOUT_MILLIS) {
                    session.connectionState.drop(1).first { it !is ConnectionState.Ready }
                }
            }
            session.writeCommand(nus.rx, "IT:DISCONNECT")
            transition.join()
            awaitReady(session, RECONNECT_TIMEOUT_MILLIS)

            val restoredStatus = readStatus(session, nus.rx, messages)
            assertTrue(restoredStatus.linkValue("c") >= initialStatus.linkValue("c") + 1)
            assertTrue(restoredStatus.linkValue("d") >= initialStatus.linkValue("d") + 1)
            assertTrue(restoredStatus.part("SUB").requiredLong("s") >= initialStatus.part("SUB").requiredLong("s") + 1)

            val restoredSequence = messages.awaitSequence { it > sequenceBeforeDisconnect }
            assertTrue(
                "notification sequence did not advance after reconnect",
                restoredSequence > sequenceBeforeDisconnect,
            )
        } finally {
            stateLogJob.cancel()
            eventLogJob.cancel()
            notificationJob.cancel()
            messages.close()
        }
    }

    @Test(timeout = 90_000)
    fun duplicateDisconnectEmitsOneTransitionAndSessionRemainsReusable() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        val disconnectingStates = mutableListOf<ConnectionState>()
        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (
                    event is OfficialBleClientEvent.Connection &&
                    event.peripheralId == peripheralId &&
                    event.state is ConnectionState.Disconnecting
                ) {
                    disconnectingStates += event.state
                }
            }
        }
        val firstSession = client.connect(peripheralId).also { activeSession = it }
        awaitReady(firstSession)
        val firstNus = firstSession.nusCharacteristics()
        val firstMessages = Channel<String>(Channel.UNLIMITED)
        val firstNotificationJob = launch(start = CoroutineStart.UNDISPATCHED) {
            firstSession.notifications(firstNus.tx).collect { firstMessages.send(it.decodeToString().trim()) }
        }

        firstSession.subscribe(firstNus.tx)
        firstMessages.awaitSequence()
        readStatus(firstSession, firstNus.rx, firstMessages)

        val disconnected = launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                firstSession.connectionState.filter { it is ConnectionState.Disconnected }.first()
            }
        }
        firstSession.disconnect()
        firstSession.disconnect()
        disconnected.join()
        assertEquals(1, disconnectingStates.size)
        firstNotificationJob.cancel()
        firstMessages.close()

        val secondSession = client.connect(peripheralId).also { activeSession = it }
        awaitReady(secondSession)
        val secondNus = secondSession.nusCharacteristics()
        val secondMessages = Channel<String>(Channel.UNLIMITED)
        val secondNotificationJob = launch(start = CoroutineStart.UNDISPATCHED) {
            secondSession.notifications(secondNus.tx).collect { secondMessages.send(it.decodeToString().trim()) }
        }

        try {
            secondMessages.awaitSequence()
            readStatus(secondSession, secondNus.rx, secondMessages)
        } finally {
            eventJob.cancel()
            secondNotificationJob.cancel()
            secondMessages.close()
        }
        Unit
    }

    /**
     * Verifies that bonding survives reconnects and that a firmware-driven `IT:BOND_RESET`
     * actually clears the peripheral's own bond store.
     *
     * This test is written to be run-to-run idempotent: before touching any local state it
     * probes the peripheral with `IT:BOND_RESET` so a bond left over from a previous
     * (possibly killed) run is detected and cleared automatically. The one state it cannot
     * self-heal is the phone bond being gone while the firmware still holds one: with no
     * shared key the link can never re-encrypt, so `IT:BOND_RESET` is denied forever. That
     * case is diagnosed only on its real signature: the first bond attempt fails AND the phone
     * never entered `BOND_BONDING` (Android logs `btm_sec_pairing_timeout ... state: IDLE`).
     * `DENIED_SECURITY` on the start probe alone is not evidence of residue — the firmware
     * answers it to any unencrypted link whether or not the board holds a bond. If the phone did
     * reach `BOND_BONDING`, SMP ran and the failure is in pairing approval (e.g. the consent
     * request was posted as a notification the harness could not find), so it fails with that
     * cause instead. On the residue signature it fails
     * with a `BOARD_BOND_RESIDUE:` message telling the operator to clear the
     * board's bond store out-of-band (Zephyr shell `bt clear all`, or reflash) before
     * re-running. This start-of-test probe exists precisely because a killed test process
     * cannot guarantee its `finally` cleanup ever ran.
     */
    private fun boardBondResidueMessage(peripheralId: PeripheralId, resetMessage: String, bondOutcome: Any?): String =
        "BOARD_BOND_RESIDUE: peripheral $peripheralId denied IT:BOND_RESET as insecure ($resetMessage) while the " +
            "phone held no bond, and bonding then failed ($bondOutcome). The board keeps a stale bond from a previous " +
            "run that cannot be cleared over an unencrypted link — clear it out-of-band (Zephyr shell `bt clear all`) " +
            "or reflash the board before running this harness again."

    private fun firstBondFailureMessage(
        peripheralId: PeripheralId,
        resetMessage: String,
        bondOutcome: Any?,
        sawBonding: Boolean,
    ): String =
        if (sawBonding) {
            "pairing reached BONDING but was not approved/completed ($bondOutcome) — check the system pairing UI " +
                "(dialog vs notification)"
        } else {
            boardBondResidueMessage(peripheralId, resetMessage, bondOutcome)
        }

    @Test(timeout = 180_000)
    fun bondingPersistsAcrossReconnectAndCanBeResetByTestFirmware() = runBlocking {
        awaitAdapterReady()
        val peripheralId = discoverPeripheral()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testBondController = AndroidBondTestController(context)
        // 수신 진단(GATT_PAYLOAD_OBSERVED)과 하네스 수신(rx=)을 함께 남겨, notification 유실이
        // 라이브러리 전/후 어디서 났는지 로그로 가를 수 있게 한다.
        logScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { Log.i(TAG, "event=$it") }
        }

        var suspectedBoardResidue = false
        val (initialResetStatus, initialResetMessage, boardBondCount) =
            openSubscribedSession(peripheralId).useSession { probe ->
                probe.session.writeCommand(probe.nus.rx, "IT:BOND_RESET")
                val message = probe.messages.awaitMessage { it.startsWith("IT|RESULT|test=BOND_RESET|status=") }
                val status = message.parseFields().required("status")
                if (status == "ACCEPTED") {
                    withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                        probe.session.connectionState.first { it is ConnectionState.Disconnected }
                    }
                }
                // BOND_STATUS 는 암호화 없이도 응답한다 — 거부된 경우 보드 본딩 수를 직접 확인한다.
                val count = if (status == "DENIED_SECURITY") {
                    readBondStatus(probe.session, probe.nus.rx, probe.messages).requiredLong("count")
                } else {
                    null
                }
                Triple(status, message, count)
            }
        when (initialResetStatus) {
            "ACCEPTED" -> Unit
            "DENIED_SECURITY" -> {
                // 비파괴 조회 — 여기서 폰 키를 지우면 "폰 없음/보드 있음"이라는 복구 불가 상태를 하네스가 만든다.
                if (testBondController.hasBond(peripheralId) && boardBondCount == 0L) {
                    // 보드는 비어 있고 폰 키만 남은 상태(이전 실행의 정리 단계가 보드 삭제를 확인하지 못한 경우).
                    // 폰 키만 지우면 되므로 아래 removeBond 로 복구하고 진행한다.
                    Log.w(TAG, "stale phone bond with empty board ($initialResetMessage); removing phone key")
                } else if (!testBondController.hasBond(peripheralId)) {
                    // 펌웨어가 "본딩 없음" 상태에서도 DENIED_SECURITY 를 돌려주는지는 확인되지 않았다.
                    // 그래서 여기서 단정하지 않고, 아래 첫 본딩 시도가 실패할 때만 보드 잔존으로 진단한다
                    // (보드에 stale 본딩이 있으면 폰이 BONDING 에 진입하지 못한다 — 관측된 증상).
                    suspectedBoardResidue = true
                } else {
                    error(
                        "firmware denied IT:BOND_RESET as insecure ($initialResetMessage) while the " +
                            "phone still holds a bond for $peripheralId; bond keys on the phone and board " +
                            "are out of sync. The phone bond is kept so the next run can retry over the " +
                            "encrypted link once the board is inspected.",
                    )
                }
            }
            else -> error("unexpected IT:BOND_RESET status before scenario start: $initialResetMessage")
        }

        val removal = testBondController.removeBond(peripheralId)
        assertTrue(
            "local bond cleanup failed explicitly: $removal",
            removal == BondRemovalResult.Removed || removal == BondRemovalResult.AlreadyAbsent,
        )

        var scenarioFailure: Throwable? = null
        var firmwareBondMayRemain = false
        try {
            val bondController = AndroidBleClientFactory.createBondController(context)
            val observation = testBondController.observeBonding(peripheralId)
            val bondResult = try {
                runCatching { createBondWithSystemApproval(bondController, peripheralId) }
                    .getOrElse {
                        if (suspectedBoardResidue) {
                            error(firstBondFailureMessage(peripheralId, initialResetMessage, it, observation.sawBonding))
                        } else {
                            throw it
                        }
                    }
            } finally {
                observation.close()
            }
            assertTrue(
                if (suspectedBoardResidue) {
                    firstBondFailureMessage(peripheralId, initialResetMessage, bondResult, observation.sawBonding)
                } else {
                    "bond creation failed: $bondResult"
                },
                bondResult == AndroidBondResult.Bonded || bondResult == AndroidBondResult.AlreadyBonded,
            )
            firmwareBondMayRemain = true

            openSubscribedSession(peripheralId).useSession { first ->
                val firstBond = readBondStatus(first.session, first.nus.rx, first.messages)
                assertEquals(1L, firstBond.requiredLong("count"))
                assertTrue("connection is not encrypted: $firstBond", firstBond.requiredLong("level") >= 2L)
            }

            openSubscribedSession(peripheralId).useSession { second ->
                val restoredBond = readBondStatus(second.session, second.nus.rx, second.messages)
                assertEquals(1L, restoredBond.requiredLong("count"))
                assertTrue("bonded reconnect is not encrypted: $restoredBond", restoredBond.requiredLong("level") >= 2L)

                second.session.writeCommand(second.nus.rx, "IT:BOND_RESET")
                second.messages.awaitMessage {
                    it == "IT|RESULT|test=BOND_RESET|status=ACCEPTED"
                }
                withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                    second.session.connectionState.first { it is ConnectionState.Disconnected }
                }
            }
            firmwareBondMayRemain = false // 펌웨어가 ACCEPTED 로 자기 본딩을 지웠다

            val postResetRemoval = testBondController.removeBond(peripheralId)
            assertTrue(
                "local bond cleanup after firmware reset failed explicitly: $postResetRemoval",
                postResetRemoval == BondRemovalResult.Removed ||
                    postResetRemoval == BondRemovalResult.AlreadyAbsent,
            )
            val rebound = createBondWithSystemApproval(bondController, peripheralId)
            assertTrue(
                "bond recreation after firmware reset failed: $rebound",
                rebound == AndroidBondResult.Bonded || rebound == AndroidBondResult.AlreadyBonded,
            )
            firmwareBondMayRemain = true

            openSubscribedSession(peripheralId).useSession { third ->
                val resetStatus = readBondStatus(third.session, third.nus.rx, third.messages)
                assertEquals(1L, resetStatus.requiredLong("count"))
                assertEquals("PASS", resetStatus.required("reset"))
                assertTrue("re-bonded connection is not encrypted: $resetStatus", resetStatus.requiredLong("level") >= 2L)
            }
        } catch (failure: Throwable) {
            scenarioFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            var boardClearedInCleanup = false
            if (firmwareBondMayRemain) {
                runCatching {
                    openSubscribedSession(peripheralId).useSession { cleanup ->
                        cleanup.session.writeCommand(cleanup.nus.rx, "IT:BOND_RESET")
                        val message = cleanup.messages.awaitMessage {
                            it.startsWith("IT|RESULT|test=BOND_RESET|status=")
                        }
                        val status = message.parseFields().required("status")
                        if (status == "ACCEPTED") {
                            withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                                cleanup.session.connectionState.first { it is ConnectionState.Disconnected }
                            }
                            boardClearedInCleanup = true
                        } else {
                            error("cleanup IT:BOND_RESET was not accepted: $message")
                        }
                    }
                }.exceptionOrNull()?.let(cleanupFailures::add)
                if (!boardClearedInCleanup) {
                    // ACK 를 못 받아도 보드가 실제로 지웠을 수 있다 — 암호화 없이도 되는 BOND_STATUS 로 확인한다.
                    // 실패 자체는 cleanupFailures 에 남긴다(유실을 숨기지 않는다). 확인되면 폰 키 정리만 진행한다.
                    runCatching {
                        withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                            client.session(peripheralId).connectionState.first { it is ConnectionState.Disconnected }
                        }
                        openSubscribedSession(peripheralId).useSession { verify ->
                            readBondStatus(verify.session, verify.nus.rx, verify.messages).requiredLong("count")
                        }
                    }.onSuccess { count ->
                        Log.w(TAG, "cleanup ACK missing; board bond count after reset = $count")
                        boardClearedInCleanup = count == 0L
                    }.exceptionOrNull()?.let(cleanupFailures::add)
                }
            }
            // 폰 키는 보드 쪽이 비었다고 확인될 때만 지운다. 불확실하면 보존해야 다음 실행이
            // 암호화 링크로 보드를 지울 수 있다 — 여기서 지우면 수동 복구(BOARD_BOND_RESIDUE)가 필요해진다.
            val boardKnownClear = !firmwareBondMayRemain || boardClearedInCleanup
            if (boardKnownClear) {
                runCatching {
                    val finalRemoval = testBondController.removeBond(peripheralId)
                    check(
                        finalRemoval == BondRemovalResult.Removed ||
                            finalRemoval == BondRemovalResult.AlreadyAbsent,
                    ) { "local bond cleanup after scenario failed explicitly: $finalRemoval" }
                }.exceptionOrNull()?.let(cleanupFailures::add)
            } else {
                cleanupFailures += IllegalStateException(
                    "phone bond for $peripheralId kept on purpose: the board's bond could not be confirmed " +
                        "cleared, so removing the phone key would leave the board with a stale bond " +
                        "(BOARD_BOND_RESIDUE on the next run). Re-run once the peripheral is reachable.",
                )
            }

            val cleanupFailure = cleanupFailures.firstOrNull()?.also { primary ->
                cleanupFailures.drop(1).forEach(primary::addSuppressed)
            }
            if (cleanupFailure != null) {
                if (scenarioFailure != null) {
                    scenarioFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private suspend fun awaitAdapterReady() {
        withTimeout(5_000) {
            client.adapterState.first { state ->
                when (state) {
                    AdapterState.Ready -> true
                    AdapterState.PoweredOff -> error("Bluetooth must be enabled")
                    is AdapterState.Unauthorized -> error("Bluetooth runtime permissions are unavailable")
                    AdapterState.Unsupported -> error("Device does not support Bluetooth LE")
                    AdapterState.Resetting -> false
                }
            }
        }
    }

    private suspend fun discoverPeripheral(): PeripheralId {
        val expectedId = instrumentationArguments().getString(ARG_PERIPHERAL_ID)
        val request = ScanRequest(
            filter = BleScanFilter(serviceUuids = listOf(NUS_SERVICE)),
            options = BleScanOptions(BleScanMode.LowLatency),
        )
        client.startScan(request)
        return try {
            withTimeout(SCAN_TIMEOUT_MILLIS) {
                client.scanResults.first { result ->
                    expectedId == null || result.peripheralId.value.equals(expectedId, ignoreCase = true)
                }.peripheralId
            }
        } finally {
            client.stopScan()
        }
    }

    private suspend fun awaitReady(session: BleSession, timeoutMillis: Long = READY_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) {
            session.connectionState.first { it is ConnectionState.Ready }
        }
    }

    private fun BleSession.nusCharacteristics(): NusCharacteristics {
        val service = services.value.firstOrNull { it.ref.uuid == NUS_SERVICE }
            ?: error("NUS service was not discovered")
        val rx = service.characteristics.firstOrNull { it.ref.uuid == NUS_RX }?.ref
            ?: error("NUS RX characteristic was not discovered")
        val tx = service.characteristics.firstOrNull { it.ref.uuid == NUS_TX }?.ref
            ?: error("NUS TX characteristic was not discovered")
        return NusCharacteristics(rx, tx)
    }

    private suspend fun verifyLongWrite(
        session: BleSession,
        rx: CharacteristicRef,
        messages: ReceiveChannel<String>,
        mode: BleWriteMode,
        expectedDelivery: DeliverySemantics,
    ) {
        session.writeCommand(rx, "IT:RESET")
        val receipt = session.write(
            rx,
            ByteArray(LONG_WRITE_SIZE) { it.toByte() },
            BleWriteOptions(mode = mode, chunking = BleChunking.Automatic),
        )
        assertEquals(LONG_WRITE_SIZE, receipt.bytesCompleted)
        assertTrue(receipt.chunksCompleted > 1)
        assertEquals(expectedDelivery, receipt.delivery)
        messages.awaitMessage { it == "IT|RESULT|test=LONG_WRITE|status=PASS|bytes=600" }
        val status = readStatus(session, rx, messages)
        assertEquals(LONG_WRITE_SIZE.toLong(), status.part("RX").requiredLong("pb"))
        assertTrue(status.part("RX").requiredLong("pc") > 1)
    }

    private suspend fun readStatus(
        session: BleSession,
        rx: CharacteristicRef,
        messages: ReceiveChannel<String>,
    ): PeripheralStatus {
        session.writeCommand(rx, "IT:STATUS")
        val parts = mutableMapOf<String, Map<String, String>>()
        withTimeout(STATUS_TIMEOUT_MILLIS) {
            while (parts.size < STATUS_PART_COUNT) {
                val message = messages.receive()
                if (!message.startsWith("IT|STATUS|")) continue
                val values = message.parseFields()
                parts[values.required("part")] = values
            }
        }
        return PeripheralStatus(parts)
    }

    private suspend fun readBondStatus(
        session: BleSession,
        rx: CharacteristicRef,
        messages: ReceiveChannel<String>,
    ): Map<String, String> {
        session.writeCommand(rx, "IT:BOND_STATUS")
        return messages.awaitMessage { it.startsWith("IT|BOND|") }.parseFields()
    }

    private suspend fun CoroutineScope.openSubscribedSession(peripheralId: PeripheralId): SubscribedSession {
        var session: BleSession? = null
        val messages = Channel<String>(Channel.UNLIMITED)
        var notificationJob: kotlinx.coroutines.Job? = null
        try {
            val connectedSession = client.connect(peripheralId).also {
                session = it
                activeSession = it
            }
            awaitReady(connectedSession)
            val nus = connectedSession.nusCharacteristics()
            notificationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                connectedSession.notifications(nus.tx).collect {
                    val text = it.decodeToString().trim()
                    if (!text.startsWith("IT|NTFY|")) Log.i(TAG, "rx=$text")
                    messages.send(text)
                }
            }
            connectedSession.subscribe(nus.tx)
            messages.awaitSequence()
            return SubscribedSession(connectedSession, nus, messages, notificationJob)
        } catch (failure: Throwable) {
            notificationJob?.cancel()
            messages.close()
            val disconnectFailure = session?.let { partialSession ->
                runCatching {
                    partialSession.disconnect()
                    withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                        partialSession.connectionState.first { it is ConnectionState.Disconnected }
                    }
                }.exceptionOrNull()
            }
            if (disconnectFailure != null) failure.addSuppressed(disconnectFailure)
            throw failure
        }
    }

    private suspend fun <T> SubscribedSession.useSession(
        block: suspend (SubscribedSession) -> T,
    ): T {
        var primaryFailure: Throwable? = null
        try {
            return block(this)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val closeFailure = runCatching { close() }.exceptionOrNull()
            if (closeFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(closeFailure) else throw closeFailure
            }
        }
    }

    private suspend fun BleSession.writeCommand(rx: CharacteristicRef, command: String) {
        val receipt = write(
            rx,
            command.encodeToByteArray(),
            BleWriteOptions(mode = BleWriteMode.WithResponse, chunking = BleChunking.Automatic),
        )
        assertEquals(command.encodeToByteArray().size, receipt.bytesCompleted)
        assertEquals(DeliverySemantics.RemoteAcknowledged, receipt.delivery)
    }

    private suspend fun ReceiveChannel<String>.awaitSequence(predicate: (Long) -> Boolean = { true }): Long {
        val message = awaitMessage { candidate ->
            candidate.startsWith("IT|NTFY|seq=") &&
                predicate(candidate.parseFields().requiredLong("seq"))
        }
        return message.parseFields().requiredLong("seq")
    }

    private suspend fun ReceiveChannel<String>.awaitMessage(predicate: (String) -> Boolean): String =
        withTimeout(STATUS_TIMEOUT_MILLIS) {
            while (true) {
                val message = receive()
                if (predicate(message)) return@withTimeout message
            }
            error("unreachable")
        }

    private fun String.parseFields(): Map<String, String> =
        split('|').drop(2).mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
        }.toMap()

    private fun Map<String, String>.required(key: String): String =
        get(key) ?: error("Missing '$key' in $this")

    private fun Map<String, String>.requiredLong(key: String): Long = required(key).toLong()

    private fun reconnectOptions(): BleConnectOptions = BleConnectOptions(
        reconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
            maxAttempts = 4,
            initialDelayMillis = 500,
            maxDelayMillis = 2_000,
        ),
    )

    private fun instrumentationArguments() = InstrumentationRegistry.getArguments()

    private suspend fun createBondWithSystemApproval(
        controller: AndroidBondController,
        peripheralId: PeripheralId,
    ): AndroidBondResult = coroutineScope {
        val approval = async(Dispatchers.IO) { approveSystemPairing() }
        val result = controller.createBond(
            peripheralId,
            timeoutMillis = BOND_CONFIRMATION_TIMEOUT_MILLIS,
        )
        approval.await()
        result
    }

    private fun approveSystemPairing() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadline = SystemClock.elapsedRealtime() + PAIRING_UI_TIMEOUT_MILLIS

        while (SystemClock.elapsedRealtime() < deadline) {
            val pairButton = device.findObject(By.res("android:id/button1"))
            if (pairButton != null) {
                pairButton.click()
                return
            }

            device.openNotification()
            // 이름이 아직 캐시되지 않으면 알림에 기기명이 없다("Show pairing notification for (null)").
            // 그래서 이름으로 못 찾으면 Settings 패키지의 페어링 요청 알림 자체로 찾는다.
            val request = device.wait(
                Until.findObject(By.textContains(PERIPHERAL_NAME)),
                PAIRING_UI_POLL_MILLIS,
            ) ?: device.findObject(
                By.pkg(NOTIFICATION_SHADE_PACKAGE).text(PAIRING_NOTIFICATION_TEXT),
            )
            if (request != null) {
                request.click()
                device.wait(
                    Until.hasObject(By.res("android:id/button1")),
                    PAIRING_DIALOG_TIMEOUT_MILLIS,
                )
            }
        }

        error("System pairing confirmation UI did not appear")
    }

    private data class NusCharacteristics(
        val rx: CharacteristicRef,
        val tx: CharacteristicRef,
    )

    private data class SubscribedSession(
        val session: BleSession,
        val nus: NusCharacteristics,
        val messages: Channel<String>,
        val notificationJob: kotlinx.coroutines.Job,
    ) {
        suspend fun close() {
            try {
                session.disconnect()
                withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                    session.connectionState.first { it is ConnectionState.Disconnected }
                }
            } finally {
                cancelNotifications()
            }
        }

        fun cancelNotifications() {
            notificationJob.cancel()
            messages.close()
        }
    }

    private data class PeripheralStatus(val parts: Map<String, Map<String, String>>) {
        fun part(name: String): Map<String, String> = parts[name] ?: error("Missing status part $name: $parts")
        fun linkValue(key: String): Long = part("LINK")[key]?.toLong()
            ?: error("Missing '$key' in ${part("LINK")}")
    }

    private companion object {
        const val ARG_HARDWARE_BLE = "hardwareBle"
        const val ARG_PERIPHERAL_ID = "blePeripheralId"
        const val TAG = "BleHardwareTest"
        const val LONG_WRITE_SIZE = 600
        const val STATUS_PART_COUNT = 3
        const val SCAN_TIMEOUT_MILLIS = 20_000L
        const val READY_TIMEOUT_MILLIS = 30_000L
        const val BOND_CONFIRMATION_TIMEOUT_MILLIS = 120_000L
        const val PAIRING_UI_TIMEOUT_MILLIS = 30_000L
        const val PAIRING_UI_POLL_MILLIS = 1_000L
        const val PAIRING_DIALOG_TIMEOUT_MILLIS = 3_000L
        const val PERIPHERAL_NAME = "Nordic_UART_Service"
        /** 알림 셰이드의 행은 게시한 앱(Settings)이 아니라 SystemUI 가 그린다 — 기기 UI 덤프로 확인. */
        const val NOTIFICATION_SHADE_PACKAGE = "com.android.systemui"
        val PAIRING_NOTIFICATION_TEXT: Pattern = Pattern.compile("(?is).*(pairing request|pair request|페어링\\s*요청).*")
        const val RECONNECT_TIMEOUT_MILLIS = 45_000L
        const val DISCONNECT_TIMEOUT_MILLIS = 15_000L
        const val STATUS_TIMEOUT_MILLIS = 10_000L

        val NUS_SERVICE = BleUuid(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        val NUS_RX = BleUuid(UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"))
        val NUS_TX = BleUuid(UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"))

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
