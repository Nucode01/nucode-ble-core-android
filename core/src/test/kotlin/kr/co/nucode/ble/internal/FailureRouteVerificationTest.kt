package kr.co.nucode.ble.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 규칙이 보호하는 route(SyncCommand·AsyncResource·Observation)가 전부 그 전달 채널을 단언하는 테스트 메서드에
 * 연결됐는지 강제한다(plan `failure-delivery-guards/v1` 목표 1).
 * 연결은 "그 테스트가 이 route 의 채널을 단언한다"는 작성자 주장이다 — 기계 확인은 메서드 존재와
 * 테스트 소스의 코드 문자열 등장까지이고, 단언의 의미는 review 가 확인한다.
 */
class FailureRouteVerificationTest {

    @Test
    fun `필수 route 집합과 검증 연결 키 집합이 같다`() {
        val required = FailureCatalog.specs.flatMap { spec ->
            spec.routes.filter { it.kind in REQUIRED_KINDS }.map { "${spec.code} | ${it.context}" }
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
    fun `각 연결은 테스트 클래스패스의 실제 메서드를 가리킨다`() {
        val broken = ROUTE_VERIFIED_BY.mapNotNull { (route, target) ->
            val (fqcn, method) = split(target) ?: return@mapNotNull "$route -> $target (형식은 fqcn#method)"
            val type = runCatching { Class.forName(fqcn) }.getOrNull()
                ?: return@mapNotNull "$route -> 클래스 없음: $fqcn"
            if (type.declaredMethods.none { it.name == method }) "$route -> 메서드 없음: $target" else null
        }
        assertTrue("가리키는 테스트가 없는 연결:\n${broken.joinToString("\n")}", broken.isEmpty())
    }

    /** 보조 검사(거짓 연결 방지) — 연결된 테스트 클래스의 소스에 route 의 코드 문자열이 등장해야 한다. */
    @Test
    fun `연결된 테스트 소스에 route 코드 문자열이 등장한다`() {
        val missing = ROUTE_VERIFIED_BY.mapNotNull { (route, target) ->
            val code = route.substringBefore(" | ")
            val fqcn = split(target)?.first ?: return@mapNotNull "$route -> 형식 오류: $target"
            val source = File("src/test/kotlin/${fqcn.replace('.', '/')}.kt")
            when {
                !source.isFile -> "$route -> 소스 없음: ${source.path}"
                "\"$code\"" !in source.readText() -> "$route -> ${source.name} 에 \"$code\" 없음"
                else -> null
            }
        }
        assertTrue("코드 문자열이 없는 연결:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    private fun split(target: String): Pair<String, String>? {
        val parts = target.split('#')
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
    }

    private companion object {
        val REQUIRED_KINDS = setOf(TriggerKind.SyncCommand, TriggerKind.AsyncResource, TriggerKind.Observation)

        const val ADAPTER = "kr.co.nucode.ble.AdapterStateTransitionTest"
        const val WIRING = "kr.co.nucode.ble.BleCoreWiringTest"
        const val DELIVERY = "kr.co.nucode.ble.FailureRouteDeliveryTest"
        const val ACTIVE = "kr.co.nucode.ble.ActiveSubscriptionsTest"
        const val SCAN = "kr.co.nucode.ble.ScanContractTest"
        const val RECONNECT_STATE = "kr.co.nucode.ble.ReconnectStateContractTest"

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
        )

        /** 기존 fixture 로 발생시킬 수 없어(main seam 추가 금지) 연결하지 못한 route → 사유. 비어 있어야 정상. */
        val UNVERIFIABLE_ROUTES: Map<String, String> = emptyMap()
    }
}
