@file:Suppress("unused")

package kr.co.nucode.ble.sample.llms

/*
 * guide/llms.md 의 "Canonical patterns" Kotlin 블록 컴파일 검증본.
 *
 * `// region llms:<이름>` 과 `// endregion` 사이가 문서의 코드 블록과 글자 그대로 같다.
 * 문서의 예시를 고치면 여기를 같이 고치고 `./gradlew :sample:assembleDebug` 로 확인한다.
 * import 는 문서가 전제한 목록("Imports assumed") 그대로다. 이름이 GuideSnippets 와 겹치므로
 * 패키지를 따로 둔다(디렉터리는 sample 과 같다).
 *
 * 샘플 앱은 이 선언들을 호출하지 않는다. 컴파일되는 것 자체가 목적이다.
 */

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.co.nucode.ble.*
import kr.co.nucode.ble.android.*

// region llms:canonical-patterns
// Example UUIDs (Nordic UART Service). Replace with the real device's.
val NUS_SERVICE = ServiceRef(BleUuid(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")))
val NUS_RX = CharacteristicRef(NUS_SERVICE, BleUuid(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"))) // write
val NUS_TX = CharacteristicRef(NUS_SERVICE, BleUuid(UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"))) // notify

// One client per process.
fun createClient(context: Context): BleClient = AndroidBleClientFactory.create(context, BleConfig())

// Scan for a duration and pick the strongest RSSI. Register the collector before startScan.
suspend fun scanNearest(client: BleClient, filter: BleScanFilter, durationMillis: Long = 5_000): ScanResult? = coroutineScope {
    val latest = HashMap<PeripheralId, ScanResult>()
    val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.scanResults.collect { latest[it.peripheralId] = it } }
    client.startScan(ScanRequest(filter, BleScanOptions(BleScanMode.LowLatency)))
    try { delay(durationMillis) } finally { client.stopScan() }
    collector.cancelAndJoin()
    latest.values.maxByOrNull { it.rssi }
}

class ConnectFailedException(val state: ConnectionState?) : Exception("not Ready: $state")

// connect() returns when the attempt STARTS. Always wait for Ready OR Disconnected, with a timeout.
suspend fun connectAndAwaitReady(
    client: BleClient,
    id: PeripheralId,
    options: BleConnectOptions = BleConnectOptions(),
    timeoutMillis: Long = 40_000,
): BleSession {
    val session = client.connect(id, options)
    val settled = withTimeoutOrNull(timeoutMillis) {
        session.connectionState.first { it is ConnectionState.Ready || it is ConnectionState.Disconnected }
    }
    when (settled) {
        ConnectionState.Ready -> return session
        is ConnectionState.Disconnected -> {
            val reason = settled.reason
            if (reason is BleDisconnectReason.Failed) throw BleException(reason.failure)
            throw ConnectFailedException(settled)
        }
        else -> { session.disconnect(); throw ConnectFailedException(null) } // timeout; autoReconnect has no deadline. disconnect() also clears desired subscriptions
    }
}

// Notify: collector first (UNDISPATCHED), then subscribe. Cancel the returned Job to stop collecting.
suspend fun startReceiving(scope: CoroutineScope, session: BleSession, ref: CharacteristicRef, onValue: (ByteArray) -> Unit): Job {
    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { session.notifications(ref).collect { onValue(it) } }
    try { session.subscribe(ref) } catch (e: Throwable) { job.cancel(); throw e }
    return job
}

// Write with partial-failure handling. Never auto-replay when writeProgress != null.
suspend fun writeAll(session: BleSession, ref: CharacteristicRef, data: ByteArray): WriteProgress? =
    try {
        session.write(ref, data, BleWriteOptions(BleWriteMode.WithResponse, BleChunking.Automatic))
        null
    } catch (e: BleException) {
        e.failure.writeProgress ?: throw e // null progress => nothing was sent; safe to retry later
    }

// Request/response: register the reply collector before writing; time-limit only the reply wait.
suspend fun request(session: BleSession, command: ByteArray, timeoutMillis: Long = 3_000): ByteArray = coroutineScope {
    val reply = async(start = CoroutineStart.UNDISPATCHED) { session.notifications(NUS_TX).first() }
    try {
        session.write(NUS_RX, command)
        withTimeout(timeoutMillis) { reply.await() }
    } finally {
        reply.cancel()
    }
}

// Persistent link: autoReconnect=true + subscribe once. Restores across link loss and Bluetooth off/on.
suspend fun openPersistent(client: BleClient, id: PeripheralId, scope: CoroutineScope, onValue: (ByteArray) -> Unit): Pair<BleSession, Job> {
    val session = connectAndAwaitReady(client, id, BleConnectOptions(autoReconnect = true))
    val job = try { startReceiving(scope, session, NUS_TX, onValue) } catch (e: Exception) { session.disconnect(); throw e }
    return session to job
}

// Receiving right now? Use activeSubscriptions, not events.
fun isReceiving(session: BleSession, ref: CharacteristicRef): Flow<Boolean> =
    combine(session.connectionState, session.activeSubscriptions) { state, active -> state == ConnectionState.Ready && ref in active }

// Branch on retryability/code, never on message or exception class.
fun shouldRetryConnect(failure: BleFailure): Boolean = when (failure.retryability) {
    Retryability.TransientMayHelp, Retryability.NewConnectionMayHelp, Retryability.Unknown -> true
    Retryability.AfterEnvironmentChange, Retryability.Never -> false
}

// Bond first (device-verified order), then connect.
suspend fun bondThenConnect(context: Context, client: BleClient, id: PeripheralId): BleSession {
    val result = AndroidBleClientFactory.createBondController(context).createBond(id, timeoutMillis = 30_000)
    check(result == AndroidBondResult.Bonded || result == AndroidBondResult.AlreadyBonded) { "bond failed: $result" }
    return connectAndAwaitReady(client, id)
}

// Diagnostics: logger + privacy policy. Raw bytes only for Safe refs when enabled; Sensitive always Redacted.
fun debugConfig(): BleConfig = BleConfig(
    logger = BleLogger { record -> android.util.Log.d("ble", "${record.severity} ${record.failure.code.value} ${record.values}") },
    diagnosticOptions = DiagnosticOptions(rawPayloadsEnabled = true, maxRawPayloadBytes = 64, sensitiveCharacteristics = setOf(NUS_RX, NUS_TX)),
)
// endregion
