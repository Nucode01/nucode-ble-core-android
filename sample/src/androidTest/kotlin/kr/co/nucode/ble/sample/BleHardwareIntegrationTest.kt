package kr.co.nucode.ble.sample

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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

    private data class NusCharacteristics(
        val rx: CharacteristicRef,
        val tx: CharacteristicRef,
    )

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
