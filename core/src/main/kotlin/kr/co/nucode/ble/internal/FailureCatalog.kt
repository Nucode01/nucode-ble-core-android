package kr.co.nucode.ble.internal

/** 실패가 어떤 맥락에서 발생했는지. 전달 채널 요구를 결정한다. */
internal enum class TriggerKind { SyncCommand, AsyncResource, AsyncInfo, Observation }

/** 실패가 소비 제품에 전달되는 경로. */
internal enum class Channel { Throw, State, Event, LoggerOnly }

/** 한 실패 코드의 발생 경로. evidence 는 `파일:함수` 로 분류 근거를 적는다. */
internal data class Route(val context: String, val kind: TriggerKind, val current: Set<Channel>, val evidence: String)

/** 실패 코드와 그 발생 경로 목록. */
internal data class FailureSpec(val code: String, val routes: List<Route>)

/**
 * 실패 전달 규칙의 정본. v1 문서는 비추적 로컬 문서라 정본이 될 수 없다.
 *
 * 전달 규칙:
 * - SyncCommand(공개 명령 호출 중 확정되고 명령 목적을 좌절시키는 실패) → {Throw}. 이벤트 동반은 허용하되 필수 아님.
 *   명령 목적을 이미 달성한 뒤의 원격 정리 실패(unsubscribe CCCD, disconnect 시작 실패)는 AsyncInfo 다(설계 D8).
 * - AsyncResource(명령 밖에서 생겨 연결·스캔·구독 자원 상태를 바꾸는 실패) → {State, Event}
 * - AsyncInfo(자원 상태를 바꾸지 않는 정보성, 또는 정리 강제 종결처럼 목적(Disconnected)이 달성된 뒤의 정보) → {Event}
 * - Observation(페이로드 관측) → {LoggerOnly}
 *
 * 소스의 모든 실패 코드 리터럴은 여기 등록돼야 하며 FailureCatalogTest 가 강제한다.
 * route.evidence 는 `파일:함수` 로 분류 근거를 적는다.
 */
internal object FailureCatalog {
    fun required(kind: TriggerKind): Set<Channel> = when (kind) {
        TriggerKind.SyncCommand -> setOf(Channel.Throw)
        TriggerKind.AsyncResource -> setOf(Channel.State, Channel.Event)
        TriggerKind.AsyncInfo -> setOf(Channel.Event)
        TriggerKind.Observation -> setOf(Channel.LoggerOnly)
    }

    /** 분류는 코드 읽기 결과이며 plan 3 의 전달 테스트로 검증 전이다. */
    val specs: List<FailureSpec> = listOf(
        FailureSpec(
            "ADAPTER_UNAVAILABLE",
            listOf(
                Route("command adapter preflight", TriggerKind.SyncCommand, setOf(Channel.Throw), "BleRuntime.kt:ensureAdapterReady"),
                Route("connect command driver start", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, GattConnectionLifecycle.kt:connect, BleRuntime.kt:connect"),
                Route("adapter off sweep", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:sweepSessions"),
            ),
        ),
        FailureSpec(
            "BLE_UNSUPPORTED",
            listOf(
                Route("command adapter preflight", TriggerKind.SyncCommand, setOf(Channel.Throw), "BleRuntime.kt:ensureAdapterReady"),
                Route("connect command driver start", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, BleRuntime.kt:connect"),
            ),
        ),
        FailureSpec(
            "CHARACTERISTIC_NOT_FOUND",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattOperationEngine.kt:subscribe, GattOperationEngine.kt:startNextOperation, OperationQueue.kt:write"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:unsubscribe, GattOperationEngine.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:restoreSubscriptionsBeforeReady, BleRuntime.kt:emitRestoreFailure, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
            ),
        ),
        FailureSpec(
            "CLEANUP_FAILURE",
            listOf(
                Route("close cleanup", TriggerKind.AsyncInfo, setOf(Channel.Event), "BleRuntime.kt:reportCleanupFailure"),
            ),
        ),
        FailureSpec(
            "CLIENT_CLOSED",
            listOf(
                Route("any command after close", TriggerKind.SyncCommand, setOf(Channel.Throw), "BleRuntime.kt:onCore, BleRuntime.kt:checkOpen"),
            ),
        ),
        FailureSpec(
            "CONFIGURATION_FAILED",
            listOf(
                Route("link lifecycle (MTU status, fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:handleMtuChanged"),
            ),
        ),
        FailureSpec(
            "CONFIGURATION_TIMEOUT",
            listOf(
                Route("link lifecycle (configuration deadline)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:onLifecycleDeadline"),
            ),
        ),
        FailureSpec(
            "CONNECTION_OPTIONS_CONFLICT",
            listOf(
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "BleRuntime.kt:emitConnectConflict"),
            ),
        ),
        FailureSpec(
            "CONNECT_DURING_DISCONNECT",
            listOf(
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "BleRuntime.kt:emitConnectConflict"),
            ),
        ),
        FailureSpec(
            "DISCONNECT_CLEANUP_TIMEOUT",
            listOf(
                Route("link lifecycle (disconnect cleanup deadline)", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:onLifecycleDeadline — completeLifecycleCleanup 로 Disconnected 도달 후 정보"),
            ),
        ),
        FailureSpec(
            "DISCONNECT_START_FAILED",
            listOf(
                Route("disconnect command", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:disconnect, GattConnectionLifecycle.kt:beginCleanup"),
                Route("internal cleanup (timeout/discovery/link/permission)", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:beginCleanup"),
            ),
        ),
        FailureSpec(
            "DUPLICATE_CHARACTERISTIC_REFERENCE",
            listOf(
                Route("link lifecycle (service discovery result)", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:handleServicesDiscovered"),
            ),
        ),
        FailureSpec(
            "GATT_OPERATION_CALLBACK_MISMATCH",
            listOf(
                Route("GATT callback routing", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:emitCallbackMismatch"),
            ),
        ),
        FailureSpec(
            "GATT_OPERATION_DUPLICATE_CALLBACK",
            listOf(
                Route("GATT callback routing", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:emitDuplicateCallback"),
            ),
        ),
        FailureSpec(
            "GATT_OPERATION_START_FAILED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattOperationEngine.kt:startNextOperation"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:unsubscribe, GattOperationEngine.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:restoreSubscriptionsBeforeReady, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
                Route("link lifecycle (requestMtu start, fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:requestMtu"),
            ),
        ),
        FailureSpec(
            "GATT_PAYLOAD_OBSERVED",
            listOf(
                Route("notify/read/write payload", TriggerKind.Observation, setOf(Channel.LoggerOnly), "GattOperationEngine.kt:emitPayloadObservation"),
            ),
        ),
        FailureSpec(
            "GATT_STATUS_FAILED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattOperationEngine.kt:completeUnitOperation, GattOperationEngine.kt:handleCharacteristicRead, OperationQueue.kt:write"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:restoreSubscriptionsBeforeReady, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
            ),
        ),
        FailureSpec(
            "INTERNAL_TRANSPORT_ERROR",
            listOf(
                Route("subscription restore (non-BleException)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:failureFromInternalException, BleRuntime.kt:restoreSubscriptionsBeforeReady, SubscriptionState.kt:markActive(성공 시에만)"),
            ),
        ),
        FailureSpec(
            "LINK_FAILED",
            listOf(
                Route("link lifecycle (link status callback)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:handleConnectionStateChange"),
                Route("late non-terminal link status callback", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:handleConnectionStateChange"),
            ),
        ),
        FailureSpec(
            "LINK_START_FAILED",
            listOf(
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, GattConnectionLifecycle.kt:connect, BleRuntime.kt:connect"),
            ),
        ),
        FailureSpec(
            "LINK_TIMEOUT",
            listOf(
                Route("link lifecycle (link deadline)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:onLifecycleDeadline"),
            ),
        ),
        FailureSpec(
            "NOT_CONNECTED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattOperationEngine.kt:startNextOperation, GattOperationEngine.kt:exactIdentity"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:restoreSubscriptionsBeforeReady, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
            ),
        ),
        FailureSpec(
            "NOT_READY",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "BleRuntime.kt:notReady"),
            ),
        ),
        FailureSpec(
            "OPERATION_INVALIDATED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattOperationEngine.kt:invalidateScheduledOperations, OperationQueue.kt:write"),
                Route("caller cancellation of started operation", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattOperationEngine.kt:poisonStartedOperation, GattConnectionLifecycle.kt:terminateForOperationInvalidation"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattOperationEngine.kt:unsubscribe"),
            ),
        ),
        FailureSpec(
            "OPERATION_TIMEOUT",
            listOf(
                Route("read/write/subscribe command (started)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State, Channel.Event), "GattOperationEngine.kt:awaitAfterStart, GattOperationEngine.kt:poisonStartedOperation, GattConnectionLifecycle.kt:terminateForOperationInvalidation"),
                Route("read/write/subscribe command (still queued)", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattOperationEngine.kt:awaitAfterStart"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.State, Channel.Event), "GattOperationEngine.kt:emitUnsubscribeFailure, GattOperationEngine.kt:poisonStartedOperation"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattOperationEngine.kt:poisonStartedOperation, GattConnectionLifecycle.kt:terminateForOperationInvalidation"),
            ),
        ),
        FailureSpec(
            "PERIPHERAL_NOT_FOUND",
            listOf(
                Route("connect command (rediscovery not found)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "GattConnectionLifecycle.kt:connect, BleRuntime.kt:connect"),
            ),
        ),
        FailureSpec(
            "PERMISSION_DENIED",
            listOf(
                Route("command adapter preflight", TriggerKind.SyncCommand, setOf(Channel.Throw), "BleRuntime.kt:ensureAdapterReady"),
                Route("startScan command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "AndroidScanner.kt:start, Scanner.kt:startScan — ScanContractTest"),
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, GattConnectionLifecycle.kt:connect, BleRuntime.kt:connect"),
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "GattOperationEngine.kt:startNextOperation, GattConnectionLifecycle.kt:beginCleanup"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.State, Channel.Event), "GattOperationEngine.kt:startNextOperation, GattOperationEngine.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattOperationEngine.kt:startNextOperation, BleRuntime.kt:restoreSubscriptionsBeforeReady"),
                Route("link lifecycle (discovery/MTU start)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:discoverServices, GattConnectionLifecycle.kt:requestMtu"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "AndroidPeripheralRediscovery.kt:rediscover, GattConnectionLifecycle.kt:connect"),
                Route("adapter off sweep", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:sweepSessions"),
            ),
        ),
        FailureSpec(
            "RECONNECT_ATTEMPT",
            listOf(
                Route("reconnect attempt (official policy)", TriggerKind.AsyncInfo, setOf(Channel.State, Channel.Event), "Reconnector.kt:scheduleReconnect"),
                Route("reconnect attempt (legacy policy)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Reconnector.kt:scheduleReconnect"),
            ),
        ),
        FailureSpec(
            "RECONNECT_ATTEMPT_TIMEOUT",
            listOf(
                Route("reconnect attempt", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "Reconnector.kt:reconnect(prepareDisconnect(Failed)+publishState) — ReconnectStateContractTest"),
            ),
        ),
        FailureSpec(
            "RECONNECT_CONNECT_START_FAILED",
            listOf(
                Route("reconnect attempt", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "Reconnector.kt:reconnect"),
            ),
        ),
        FailureSpec(
            "RECONNECT_EXHAUSTED",
            listOf(
                Route("reconnect attempt (official policy)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "Reconnector.kt:scheduleReconnect(prepareDisconnect(Failed)+publishState) — ReconnectStateContractTest"),
            ),
        ),
        FailureSpec(
            "RECONNECT_GIVE_UP",
            listOf(
                Route("reconnect attempt (legacy policy)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "Reconnector.kt:scheduleReconnect(prepareDisconnect(Failed)+publishState) — ReconnectStateContractTest"),
            ),
        ),
        FailureSpec(
            "SCANNER_UNAVAILABLE",
            listOf(
                Route("startScan command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "AndroidScanner.kt:start, Scanner.kt:startScan — ScanContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "AndroidPeripheralRediscovery.kt:rediscover, GattConnectionLifecycle.kt:connect"),
            ),
        ),
        FailureSpec(
            "SCAN_ALREADY_RUNNING",
            listOf(
                Route("startScan command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "Scanner.kt:startScan — ScanContractTest"),
            ),
        ),
        FailureSpec(
            "SCAN_RUNTIME_FAILED",
            listOf(
                Route("scan runtime callback", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "Scanner.kt:handleFailure(scanState=Idle(failure)) — ScanContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "AndroidPeripheralRediscovery.kt:rediscover, GattConnectionLifecycle.kt:connect"),
            ),
        ),
        FailureSpec(
            "SCAN_START_FAILED",
            listOf(
                Route("startScan command (startOverride seam only)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "Scanner.kt:startScan — AndroidScanner.start 는 이 코드를 만들지 않는다; ScanContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "AndroidPeripheralRediscovery.kt:rediscover, GattConnectionLifecycle.kt:connect"),
            ),
        ),
        FailureSpec(
            "SCAN_STOPPED_BY_ADAPTER",
            listOf(
                Route("adapter off sweep", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:sweepSessions, Scanner.kt:stopForAdapter(scanState=Idle(failure)) — ScanContractTest"),
            ),
        ),
        FailureSpec(
            "SERVICE_DISCOVERY_FAILED",
            listOf(
                Route("link lifecycle (discovery status)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:handleServicesDiscovered"),
            ),
        ),
        FailureSpec(
            "SERVICE_DISCOVERY_START_FAILED",
            listOf(
                Route("link lifecycle (discovery start)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:discoverServices"),
            ),
        ),
        FailureSpec(
            "SERVICE_DISCOVERY_TIMEOUT",
            listOf(
                Route("link lifecycle (discovery deadline)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:onLifecycleDeadline"),
            ),
        ),
        FailureSpec(
            "UNKNOWN_DEVICE",
            listOf(
                Route("session command with unregistered id", TriggerKind.SyncCommand, setOf(Channel.Throw), "BleRuntime.kt:unknownDevice"),
                Route("connect command (invalid address)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, BleRuntime.kt:connect"),
            ),
        ),
    )

    fun violations(): List<Pair<FailureSpec, Route>> =
        specs.flatMap { spec ->
            spec.routes
                .filter { !it.current.containsAll(required(it.kind)) }
                .map { spec to it }
        }
}
