package kr.co.nucode.ble

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.charRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan `failure-delivery-guards/v1` task 1 — 공개 명령 계약 가드. `api/ble-core.api` 덤프에서
 * [BleClient]/[BleSession] 의 공개 명령을 파싱해 [COMMANDS] 분류표와 1:1 대응을 강제하고,
 * 분류별로 close 이후·어댑터 PoweredOff 에서의 실패 계약을 실제 런타임 위에서 실행 검증한다.
 * 새 공개 명령이 추가되면 분류를 빠뜨린 채 통과할 수 없다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicCommandContractTest {

    /** 명령의 실패 계약 분류. */
    private enum class Kind {
        /** 어댑터 가드 대상 — PoweredOff 에서 ADAPTER_UNAVAILABLE, close 후 CLIENT_CLOSED. */
        Gated,
        /** 어댑터를 보지 않지만 close 후에는 CLIENT_CLOSED. */
        LocalOnly,
        /** 정리 명령 — close 후에도 throw 하지 않는다. */
        Cleanup,
        /** 뷰 접근자 — close 후 CLIENT_CLOSED. */
        Accessor,
        /** 스냅샷 — close 후에도 고정된 스냅샷을 반환한다. */
        Snapshot,
    }

    private class CommandProbe(
        val kind: Kind,
        val invoke: suspend (BleClient, BleSession) -> Unit,
    )

    private val id = PeripheralId("AA:BB:CC:DD:EE:FF")
    private val ref = charRef(UUID.randomUUID(), UUID.randomUUID())

    private val COMMANDS: Map<String, CommandProbe> = mapOf(
        "BleClient.connect" to CommandProbe(Kind.Gated) { c, _ -> c.connect(id, BleConnectOptions()) },
        "BleClient.snapshot" to CommandProbe(Kind.Snapshot) { c, _ -> c.snapshot() },
        "BleClient.startScan" to CommandProbe(Kind.Gated) { c, _ -> c.startScan(ScanRequest()) },
        "BleClient.stopScan" to CommandProbe(Kind.Cleanup) { c, _ -> c.stopScan() },
        "BleClient.session" to CommandProbe(Kind.Accessor) { c, _ -> c.session(id) },
        "BleClient.close" to CommandProbe(Kind.Cleanup) { c, _ -> c.close() },
        "BleSession.connect" to CommandProbe(Kind.Gated) { _, s -> s.connect(BleConnectOptions()) },
        "BleSession.disconnect" to CommandProbe(Kind.Cleanup) { _, s -> s.disconnect() },
        "BleSession.read" to CommandProbe(Kind.Gated) { _, s -> s.read(ref) },
        "BleSession.write" to CommandProbe(Kind.Gated) { _, s -> s.write(ref, byteArrayOf(1), BleWriteOptions()) },
        "BleSession.subscribe" to CommandProbe(Kind.Gated) { _, s -> s.subscribe(ref) },
        "BleSession.unsubscribe" to CommandProbe(Kind.LocalOnly) { _, s -> s.unsubscribe(ref) },
        "BleSession.readDescriptor" to CommandProbe(Kind.Gated) { _, s -> s.readDescriptor(DescriptorRef(ref, ref.uuid)) },
        "BleSession.writeDescriptor" to CommandProbe(Kind.Gated) { _, s -> s.writeDescriptor(DescriptorRef(ref, ref.uuid), byteArrayOf(1)) },
    )

    /** 덤프에서 `BleClient.`/`BleSession.` 의 abstract 공개 명령(getter·notifications 제외)을 뽑는다. */
    private fun parsePublicCommands(): Set<String> {
        val owner = Regex("""kr\.co\.nucode\.ble\.(BleClient|BleSession)\.([A-Za-z0-9_]+)(-[A-Za-z0-9_]+)?\(""")
        return java.io.File("api/ble-core.api").readLines()
            .filter { it.startsWith("METHOD public abstract") }
            .mapNotNull { line -> owner.find(line)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } }
            .filterNot { key ->
                val name = key.substringAfter('.')
                name.startsWith("get") || name == "notifications"
            }
            .toSet()
    }

    private fun TestScope.newClient(probe: () -> AdapterState): Pair<BleClient, BleRuntime> {
        val core = BleRuntime(
            config = BleConfig(dispatcher = StandardTestDispatcher(testScheduler)),
            adapterState = probe,
        )
        return DefaultBleClient(core) to core
    }

    private suspend fun capture(block: suspend () -> Unit): Throwable? =
        try { block(); null } catch (t: Throwable) { t }

    private fun codeOf(t: Throwable?): String? = (t as? BleException)?.failure?.code?.value

    @Test(timeout = 10_000)
    fun `write over one chunk throws PAYLOAD_TOO_LARGE for Disabled and Fixed without progress`() = runTest {
        val (client, core) = newClient { AdapterState.Ready }
        val session = client.session(id)
        core.registry.get(id)!!.apply {
            setStateForTest(ConnectionState.Ready)
            chunkSize = 20
        }
        val cases = listOf(
            Triple(BleWriteOptions(chunking = BleChunking.Disabled), 21, OperationKind.WriteWithResponse),
            Triple(
                BleWriteOptions(mode = BleWriteMode.WithoutResponse, chunking = BleChunking.Fixed(21)),
                40, OperationKind.WriteWithoutResponse,
            ),
        )
        for ((options, size, kind) in cases) {
            val thrown = capture { session.write(ref, ByteArray(size), options) }
            val failure = (thrown as? BleException)?.failure ?: throw AssertionError("BleException 아님: $thrown")
            assertEquals("PAYLOAD_TOO_LARGE", failure.code.value)
            assertEquals("operation", failure.category.value)
            assertEquals(Retryability.Never, failure.retryability)
            assertEquals(kind, failure.context.operationKind)
            assertNull(failure.writeProgress)
        }
    }

    @Test
    fun `every public command in the api dump has a contract classification`() {
        val parsed = parsePublicCommands()
        assertTrue("api/ble-core.api 에서 BleClient/BleSession 명령을 하나도 파싱하지 못했다", parsed.isNotEmpty())
        val missing = parsed - COMMANDS.keys
        val extra = COMMANDS.keys - parsed
        assertEquals(
            "새 공개 명령은 여기에 계약 분류를 추가해야 한다 — missing=$missing extra=$extra",
            parsed, COMMANDS.keys,
        )
    }

    @Test(timeout = 10_000)
    fun `after close commands follow their classification`() = runTest {
        val (client, _) = newClient { AdapterState.Ready }
        val session = client.session(id)
        client.close()
        runCurrent()
        val violations = mutableListOf<String>()
        for ((name, probe) in COMMANDS) {
            val thrown = capture { probe.invoke(client, session) }
            when (probe.kind) {
                Kind.Gated, Kind.LocalOnly, Kind.Accessor ->
                    if (codeOf(thrown) != "CLIENT_CLOSED") violations += "$name(${probe.kind}): $thrown"
                Kind.Cleanup, Kind.Snapshot ->
                    if (thrown != null) violations += "$name(${probe.kind}): $thrown"
            }
        }
        assertTrue("close 이후 계약 위반: $violations", violations.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `with adapter powered off gated commands fail with ADAPTER_UNAVAILABLE`() = runTest {
        val (client, _) = newClient { AdapterState.PoweredOff }
        val session = client.session(id)
        runCurrent()
        val violations = mutableListOf<String>()
        // close 는 이후 명령을 모두 CLIENT_CLOSED 로 바꾸므로 마지막에 따로 부른다.
        for ((name, probe) in COMMANDS.filterKeys { it != "BleClient.close" }) {
            val thrown = capture { probe.invoke(client, session) }
            when (probe.kind) {
                Kind.Gated ->
                    if (codeOf(thrown) != "ADAPTER_UNAVAILABLE") violations += "$name(${probe.kind}): $thrown"
                Kind.LocalOnly ->
                    if (codeOf(thrown) == "ADAPTER_UNAVAILABLE") violations += "$name(${probe.kind}): $thrown"
                Kind.Cleanup ->
                    if (thrown != null) violations += "$name(${probe.kind}): $thrown"
                Kind.Accessor, Kind.Snapshot -> Unit
            }
        }
        val closeThrown = capture { client.close() }
        if (closeThrown != null) violations += "BleClient.close(Cleanup): $closeThrown"
        assertNotEquals(0, COMMANDS.size)
        assertTrue("PoweredOff 계약 위반: $violations", violations.isEmpty())
    }
}
