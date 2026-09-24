package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * 카탈로그의 모든 route(AsyncInfo 포함)가 전부 그 전달 채널을 단언하는 테스트 메서드에
 * 연결됐는지 강제한다(plan `failure-delivery-guards/v1` 목표 1, `platform-harness/v1` 목표 3).
 * 연결은 "그 테스트가 이 route 의 채널을 단언한다"는 작성자 주장이다 — 기계 확인은 @Test 이고 @Ignore 아닌
 * 메서드 존재와 그 메서드 본문의 코드 문자열 등장까지이고, 단언의 의미는 review 가 확인한다.
 */
class FailureRouteVerificationTest {

    @Test
    fun `필수 route 집합과 검증 연결 키 집합이 같다`() {
        val required = FailureCatalog.specs.flatMap { spec ->
            spec.routes.map { "${spec.code} | ${it.context}" }
        }.toSortedSet()
        val exempt = UNVERIFIABLE_ROUTES.keys
        val overlap = ROUTE_VERIFIED_BY.keys.intersect(exempt)
        assertTrue("연결과 예외 목록에 동시에 있음: $overlap", overlap.isEmpty())

        val covered = (ROUTE_VERIFIED_BY.keys + exempt).toSortedSet()
        assertEquals(
            "연결 누락=${(required - covered).sorted()}, 낡은 키=${(covered - required).sorted()}",
            required,
            covered,
        )
    }

    @Test
    fun `각 연결은 테스트 클래스패스의 실행되는 @Test 메서드를 가리킨다`() {
        val broken = ROUTE_VERIFIED_BY.mapNotNull { (route, target) ->
            val (fqcn, method) = split(target) ?: return@mapNotNull "$route -> $target (형식은 fqcn#method)"
            val type = runCatching { Class.forName(fqcn) }.getOrNull()
                ?: return@mapNotNull "$route -> 클래스 없음: $fqcn"
            val found = type.declaredMethods.firstOrNull { it.name == method }
            when {
                found == null -> "$route -> 메서드 없음: $target"
                !found.isAnnotationPresent(Test::class.java) -> "$route -> @Test 아님: $target"
                found.isAnnotationPresent(Ignore::class.java) -> "$route -> @Ignore 메서드: $target"
                type.isAnnotationPresent(Ignore::class.java) -> "$route -> @Ignore 클래스: $fqcn"
                else -> null
            }
        }
        assertTrue("가리키는 테스트가 없는 연결:\n${broken.joinToString("\n")}", broken.isEmpty())
    }

    /**
     * 보조 검사(거짓 연결 방지) — 연결된 테스트 메서드의 본문에 route 의 코드 문자열이 등장해야 한다.
     * 같은 파일의 다른 테스트에만 있는 코드로는 통과하지 않는다.
     */
    @Test
    fun `연결된 테스트 메서드 본문에 route 코드 문자열이 등장한다`() {
        val missing = ROUTE_VERIFIED_BY.mapNotNull { (route, target) ->
            val code = route.substringBefore(" | ")
            val (fqcn, method) = split(target) ?: return@mapNotNull "$route -> 형식 오류: $target"
            val source = File("src/test/kotlin/${fqcn.replace('.', '/')}.kt")
            if (!source.isFile) return@mapNotNull "$route -> 소스 없음: ${source.path}"
            val body = methodBody(source.readLines(), method)
                ?: return@mapNotNull "$route -> ${source.name} 에서 fun $method 선언을 찾지 못함"
            // 본문 추출이 다른 메서드까지 삼켰으면(식 본문·닫는 줄 모양이 다른 경우) 그 결과로는 판정하지 않는다.
            val overrun = body.lines().drop(1).firstOrNull { OTHER_DECLARATION.containsMatchIn(it) || it.trim().startsWith("@Test") }
            when {
                overrun != null -> "$route -> $method 본문 추출이 다른 선언까지 넘어감: ${overrun.trim()}"
                "\"$code\"" !in body -> "$route -> $method 본문에 \"$code\" 없음"
                else -> null
            }
        }
        assertTrue("코드 문자열이 없는 연결:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    /**
     * [method] 선언 줄(`fun \`이름\`` 또는 `fun 이름`)부터 같은 들여쓰기의 닫는 `}` 까지를 본문으로 본다.
     * 닫는 줄을 못 찾으면 다음 `@Test` 앞(없으면 파일 끝)까지로 잘라 낸다.
     */
    private fun methodBody(lines: List<String>, method: String): String? {
        val declaration = Regex("""^(\s*)(?:[\w@]+\s+)*fun\s+(?:`\Q$method\E`|\Q$method\E)\s*\(""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        if (start < 0) return null
        val indent = declaration.find(lines[start])!!.groupValues[1]
        val rest = lines.drop(start + 1)
        val close = rest.indexOfFirst { it.trimEnd() == "$indent}" }
        val end = if (close >= 0) close else rest.indexOfFirst { it.trim().startsWith("@Test") }.let { if (it < 0) rest.size else it }
        return (listOf(lines[start]) + rest.take(end + 1)).joinToString("\n")
    }

    private fun split(target: String): Pair<String, String>? {
        val parts = target.split('#')
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
    }

    private companion object {
        /** 본문 안에서 발견되면 추출이 경계를 넘은 것으로 보는 다른 `fun` 선언. */
        val OTHER_DECLARATION = Regex("""^\s*(?:[\w@]+\s+)*fun\s""")
        const val ADAPTER = "kr.co.nucode.ble.AdapterStateTransitionTest"
        const val WIRING = "kr.co.nucode.ble.BleCoreWiringTest"
        const val DELIVERY = "kr.co.nucode.ble.FailureRouteDeliveryTest"
        const val ACTIVE = "kr.co.nucode.ble.ActiveSubscriptionsTest"
        const val SCAN = "kr.co.nucode.ble.ScanContractTest"
        const val RECONNECT_STATE = "kr.co.nucode.ble.ReconnectStateContractTest"
        const val TERMINAL_CAUSE = "kr.co.nucode.ble.internal.TerminalCauseLifecycleTest"
        const val INFO = "kr.co.nucode.ble.FailureRouteInfoDeliveryTest"

        /** "CODE | context" → "fqcn#JVM 메서드 이름". */
        val ROUTE_VERIFIED_BY: Map<String, String> = mapOf(
            "ADAPTER_UNAVAILABLE | command adapter preflight" to
                "$ADAPTER#b PoweredOff에서 connect·startScan·read·write·subscribe 모두 ADAPTER_UNAVAILABLE을 던진다",
            "ADAPTER_UNAVAILABLE | connect command driver start" to
                "$DELIVERY#connect driver start ADAPTER_UNAVAILABLE throws and terminalizes state",
            "ADAPTER_UNAVAILABLE | adapter off sweep" to
                "$ADAPTER#e Ready 세션에서 op가 BT off를 처음 관측하면 스윕하고 ADAPTER_UNAVAILABLE을 던지며 이후 refresh는 재스윕하지 않는다",
            "BLE_UNSUPPORTED | command adapter preflight" to
                "$ADAPTER#op는 Unsupported 어댑터에서 BLE_UNSUPPORTED를 던진다",
            "BLE_UNSUPPORTED | connect command driver start" to
                "$DELIVERY#connect driver start BLE_UNSUPPORTED throws and terminalizes state",
            "CHARACTERISTIC_NOT_FOUND | read/write/subscribe command" to
                "$WIRING#Slice1 public read는 내부 start rejection을 그대로 전달하고 scheduler를 비운다",
            "CHARACTERISTIC_NOT_FOUND | subscription restore" to
                "$ACTIVE#restore unresolved target CHARACTERISTIC_NOT_FOUND keeps ref out of active with event",
            "CLIENT_CLOSED | any command after close" to
                "$WIRING#D1 close 이후 진입은 CLIENT_CLOSED typed failure로 막힌다",
            "CONFIGURATION_TIMEOUT | link lifecycle (configuration deadline)" to
                "$DELIVERY#configuration deadline CONFIGURATION_TIMEOUT publishes Disconnected Failed and Diagnostic",
            "CONNECTION_OPTIONS_CONFLICT | connect command" to
                "$WIRING#Slice2 duplicate connect의 options mismatch와 disconnecting connect는 generation을 바꾸지 않는다",
            "CONNECT_DURING_DISCONNECT | connect command" to
                "$WIRING#Slice2 duplicate connect의 options mismatch와 disconnecting connect는 generation을 바꾸지 않는다",
            "GATT_OPERATION_START_FAILED | read/write/subscribe command" to
                "$DELIVERY#read start rejection throws GATT_OPERATION_START_FAILED",
            "GATT_OPERATION_START_FAILED | subscription restore" to
                "$ACTIVE#failed CCCD restore reaches Ready without ref in active",
            "GATT_PAYLOAD_OBSERVED | notify/read/write payload" to
                "kr.co.nucode.ble.PayloadObservationRoutingTest#engine payload observation reaches configured logger",
            "GATT_STATUS_FAILED | read/write/subscribe command" to
                "$DELIVERY#read status callback failure throws GATT_STATUS_FAILED",
            "GATT_STATUS_FAILED | subscription restore" to
                "$ACTIVE#restore CCCD status failure GATT_STATUS_FAILED keeps ref out of active with event",
            "INTERNAL_TRANSPORT_ERROR | subscription restore (non-BleException)" to
                "$ACTIVE#restore non-BleException INTERNAL_TRANSPORT_ERROR keeps ref out of active with event",
            "LINK_FAILED | link lifecycle (link status callback)" to
                "$DELIVERY#link status callback LINK_FAILED publishes Disconnected Failed and Diagnostic",
            "LINK_START_FAILED | connect command" to
                "$DELIVERY#connect start rejection LINK_START_FAILED throws and terminalizes state",
            "LINK_TIMEOUT | link lifecycle (link deadline)" to
                "$DELIVERY#link deadline LINK_TIMEOUT publishes Disconnected Failed and Diagnostic",
            "NOT_CONNECTED | read/write/subscribe command" to
                "$DELIVERY#read without platform handle throws NOT_CONNECTED",
            "NOT_CONNECTED | subscription restore" to
                "$ACTIVE#restore without platform handle NOT_CONNECTED keeps ref out of active with event",
            "NOT_READY | read/write/subscribe command" to
                "$WIRING#Slice2 public read write subscribe는 Configuring에서 driver start 전에 거부된다",
            "OPERATION_INVALIDATED | read/write/subscribe command" to
                "$WIRING#Slice1 public read started caller cancellation은 generation을 poison한다",
            "OPERATION_INVALIDATED | caller cancellation of started operation" to
                "$DELIVERY#started read caller cancellation publishes Disconnected Failed OPERATION_INVALIDATED and Diagnostic",
            "OPERATION_TIMEOUT | read/write/subscribe command (started)" to
                "$DELIVERY#started read timeout throws OPERATION_TIMEOUT publishes Disconnected Failed and Diagnostic",
            "OPERATION_TIMEOUT | read/write/subscribe command (still queued)" to
                "$DELIVERY#queued read cut by caller timeout throws OPERATION_TIMEOUT",
            "OPERATION_TIMEOUT | subscription restore" to
                "$ACTIVE#restore CCCD ack timeout OPERATION_TIMEOUT publishes Disconnected Failed with event",
            "PERIPHERAL_NOT_FOUND | connect command (rediscovery not found)" to
                "kr.co.nucode.ble.internal.GattBridgeRediscoveryTest#NotFound 는 fail-fast 로 PERIPHERAL_NOT_FOUND 를 던지고 driver connect 를 부르지 않는다",
            "PERMISSION_DENIED | command adapter preflight" to
                "$ADAPTER#op는 Unauthorized 어댑터에서 PERMISSION_DENIED를 던진다",
            "PERMISSION_DENIED | startScan command" to
                "$SCAN#i 스캔 시작 권한 거부는 PERMISSION_DENIED 를 던지고 Diagnostic 을 낸다",
            "PERMISSION_DENIED | connect command" to
                "$DELIVERY#connect SecurityException PERMISSION_DENIED throws and terminalizes state",
            "PERMISSION_DENIED | read/write/subscribe command" to
                "$DELIVERY#read SecurityException throws PERMISSION_DENIED terminalizes state and Diagnostic",
            "PERMISSION_DENIED | subscription restore" to
                "$ACTIVE#restore CCCD SecurityException PERMISSION_DENIED publishes Disconnected Failed with event",
            "PERMISSION_DENIED | link lifecycle (discovery/MTU start)" to
                "$DELIVERY#discovery start SecurityException PERMISSION_DENIED publishes Disconnected Failed and Diagnostic",
            "PERMISSION_DENIED | adapter off sweep" to
                "$DELIVERY#permission revoked sweep publishes Disconnected Failed PERMISSION_DENIED and Diagnostic",
            "RECONNECT_ATTEMPT_TIMEOUT | reconnect attempt" to
                "$RECONNECT_STATE#재연결 시도 timeout은 Disconnected Failed RECONNECT_ATTEMPT_TIMEOUT을 내고 다음 Recovering이 그 원인을 싣는다",
            "RECONNECT_CONNECT_START_FAILED | reconnect attempt" to
                "kr.co.nucode.ble.internal.ReconnectorTest#reconnect start rejection shares the owner failure with terminal state",
            "RECONNECT_EXHAUSTED | reconnect attempt (official policy)" to
                "$RECONNECT_STATE#공식 ExponentialBackoff 소진 시 최종 상태는 Disconnected Failed RECONNECT_EXHAUSTED다",
            "RECONNECT_GIVE_UP | reconnect attempt (legacy policy)" to
                "$RECONNECT_STATE#legacy 정책 포기 시 최종 상태는 Disconnected Failed RECONNECT_GIVE_UP이다",
            "SCANNER_UNAVAILABLE | startScan command" to
                "$SCAN#h 플랫폼 스캐너가 없으면 SCANNER_UNAVAILABLE 을 던지고 Diagnostic 을 낸다",
            "SCAN_ALREADY_RUNNING | startScan command" to
                "$SCAN#a 다른 요청으로 재시작하면 SCAN_ALREADY_RUNNING 을 던지고 상태와 Diagnostic 은 유지된다",
            "SCAN_RUNTIME_FAILED | scan runtime callback" to
                "$SCAN#e 런타임 실패는 Idle(lastFailure) 로 전이한다",
            "SCAN_START_FAILED | startScan command (startOverride seam only)" to
                "$SCAN#b 플랫폼 시작 실패는 그 실패를 던지고 Scanning 이 되지 않는다",
            "SCAN_STOPPED_BY_ADAPTER | adapter off sweep" to
                "$SCAN#f 스캔 중 어댑터가 꺼지면 Idle(SCAN_STOPPED_BY_ADAPTER)",
            "SERVICE_DISCOVERY_FAILED | link lifecycle (discovery status)" to
                "$DELIVERY#discovery status SERVICE_DISCOVERY_FAILED publishes Disconnected Failed and Diagnostic",
            "SERVICE_DISCOVERY_START_FAILED | link lifecycle (discovery start)" to
                "$DELIVERY#discovery start rejection SERVICE_DISCOVERY_START_FAILED publishes Disconnected Failed and Diagnostic",
            "SERVICE_DISCOVERY_TIMEOUT | link lifecycle (discovery deadline)" to
                "$DELIVERY#discovery deadline SERVICE_DISCOVERY_TIMEOUT publishes Disconnected Failed and Diagnostic",
            "UNKNOWN_DEVICE | session command with unregistered id" to
                "$DELIVERY#session command with unregistered id throws UNKNOWN_DEVICE",
            "UNKNOWN_DEVICE | connect command (invalid address)" to
                "$DELIVERY#connect invalid address UNKNOWN_DEVICE throws and terminalizes state",
            // ── AsyncInfo(Event) ──
            "CHARACTERISTIC_NOT_FOUND | unsubscribe command" to
                "$INFO#unsubscribe unresolved target emits CHARACTERISTIC_NOT_FOUND Diagnostic",
            "GATT_OPERATION_START_FAILED | unsubscribe command" to
                "$INFO#unsubscribe CCCD start rejection emits GATT_OPERATION_START_FAILED Diagnostic",
            "GATT_STATUS_FAILED | unsubscribe command" to
                "$INFO#unsubscribe CCCD status failure emits GATT_STATUS_FAILED Diagnostic",
            "OPERATION_INVALIDATED | unsubscribe command" to
                "$INFO#unsubscribe pending invalidated by disconnect emits OPERATION_INVALIDATED Diagnostic",
            "OPERATION_TIMEOUT | unsubscribe command" to
                "$INFO#unsubscribe CCCD ack timeout emits OPERATION_TIMEOUT Diagnostic and Disconnected Failed",
            "PERMISSION_DENIED | unsubscribe command" to
                "$INFO#unsubscribe CCCD SecurityException emits PERMISSION_DENIED Diagnostic and Disconnected Failed",
            "CLEANUP_FAILURE | close cleanup" to
                "$INFO#close cleanup driver failure emits CLEANUP_FAILURE Diagnostic",
            "DISCONNECT_CLEANUP_TIMEOUT | link lifecycle (disconnect cleanup deadline)" to
                "$INFO#disconnect cleanup deadline emits DISCONNECT_CLEANUP_TIMEOUT Diagnostic",
            "DISCONNECT_START_FAILED | disconnect command" to
                "$INFO#disconnect command start failure emits DISCONNECT_START_FAILED Diagnostic",
            "DISCONNECT_START_FAILED | internal cleanup (timeout/discovery/link/permission)" to
                "$INFO#internal cleanup start failure emits DISCONNECT_START_FAILED Diagnostic",
            "CONFIGURATION_FAILED | link lifecycle (MTU status, fallback)" to
                "$INFO#MTU status failure emits CONFIGURATION_FAILED Diagnostic and falls back to Ready",
            "GATT_OPERATION_START_FAILED | link lifecycle (requestMtu start, fallback)" to
                "$INFO#requestMtu start rejection emits GATT_OPERATION_START_FAILED Diagnostic and falls back to Ready",
            "DUPLICATE_CHARACTERISTIC_REFERENCE | link lifecycle (service discovery result)" to
                "$INFO#duplicate characteristic in discovery result emits DUPLICATE_CHARACTERISTIC_REFERENCE Diagnostic",
            "LINK_FAILED | unattributed platform disconnect (status 0)" to
                "$TERMINAL_CAUSE#Ready 중 원인 없는 status 0 해제는 Initial 이 아니라 LINK_FAILED statusCode 0",
            "LINK_FAILED | late non-terminal link status callback" to
                "$INFO#late non-terminal link status failure emits LINK_FAILED Diagnostic without changing state",
            "GATT_OPERATION_CALLBACK_MISMATCH | GATT callback routing" to
                "$INFO#read callback for other characteristic emits GATT_OPERATION_CALLBACK_MISMATCH Diagnostic",
            "GATT_OPERATION_DUPLICATE_CALLBACK | GATT callback routing" to
                "$INFO#read callback without pending operation emits GATT_OPERATION_DUPLICATE_CALLBACK Diagnostic",
            "SCANNER_UNAVAILABLE | connect rediscovery scan (fallback)" to
                "$INFO#rediscovery without scanner emits SCANNER_UNAVAILABLE Diagnostic and falls back to connect",
            "PERMISSION_DENIED | connect rediscovery scan (fallback)" to
                "$INFO#rediscovery scan permission denial emits PERMISSION_DENIED Diagnostic and falls back to connect",
            "SCAN_RUNTIME_FAILED | connect rediscovery scan (fallback)" to
                "$INFO#rediscovery scan runtime failure emits SCAN_RUNTIME_FAILED Diagnostic and falls back to connect",
            "SCAN_START_FAILED | connect rediscovery scan (fallback)" to
                "$INFO#rediscovery scan start failure emits SCAN_START_FAILED Diagnostic and falls back to connect",
            "RECONNECT_ATTEMPT | reconnect attempt (official policy)" to
                "$INFO#official policy reconnect attempt publishes Recovering and RECONNECT_ATTEMPT Diagnostic",
            "RECONNECT_ATTEMPT | reconnect attempt (legacy policy)" to
                "$INFO#legacy policy reconnect attempt emits RECONNECT_ATTEMPT Diagnostic",
        )

        /** 기존 fixture 로 발생시킬 수 없어(main seam 추가 금지) 연결하지 못한 route → 사유. 비어 있어야 정상. */
        val UNVERIFIABLE_ROUTES: Map<String, String> = emptyMap()
    }
}
