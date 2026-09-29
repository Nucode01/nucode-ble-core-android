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
 *   단 unsubscribe 의 OPERATION_TIMEOUT·PERMISSION_DENIED 는 연결을 종결시켜 State 를 바꾸므로 AsyncResource 다(terminal-cause v2 결정 3).
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
                Route("command adapter preflight", TriggerKind.SyncCommand, setOf(Channel.Throw), "AdapterMonitor.kt:ensureAdapterReady"),
                Route("connect command driver start", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, ConnectStarter.kt:connect, RuntimeCommands.kt:connect"),
                Route("adapter off sweep", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "AdapterMonitor.kt:updateAdapterState(스윕 판정), ConnectionCleanup.kt:adapterLost, ConnectionCleanup.kt:endPlatformWait(플랫폼 재연결 대기 중 세션도 종료)"),
            ),
        ),
        FailureSpec(
            "BLE_UNSUPPORTED",
            listOf(
                Route("command adapter preflight", TriggerKind.SyncCommand, setOf(Channel.Throw), "AdapterMonitor.kt:ensureAdapterReady"),
                Route("connect command driver start", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, RuntimeCommands.kt:connect"),
            ),
        ),
        FailureSpec(
            "CHARACTERISTIC_NOT_FOUND",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "SubscriptionCommands.kt:subscribe, OperationExecutor.kt:startOperation, OperationQueue.kt:write"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "SubscriptionCommands.kt:unsubscribe, SubscriptionCommands.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkSetup.kt:restoreSubscriptions, ClientEventBus.kt:emitRestoreFailure, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
            ),
        ),
        FailureSpec(
            "CLEANUP_FAILURE",
            listOf(
                Route("close cleanup", TriggerKind.AsyncInfo, setOf(Channel.Event), "RuntimeCloser.kt:reportCleanupFailure"),
            ),
        ),
        FailureSpec(
            "CLIENT_CLOSED",
            listOf(
                Route("any command after close", TriggerKind.SyncCommand, setOf(Channel.Throw), "RuntimeCommands.kt:onCore, RuntimeCommands.kt:checkOpen"),
            ),
        ),
        FailureSpec(
            "CONFIGURATION_FAILED",
            listOf(
                Route("link lifecycle (MTU status, fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "LinkCallbacks.kt:handleMtuChanged"),
            ),
        ),
        FailureSpec(
            "CONFIGURATION_TIMEOUT",
            listOf(
                Route("link lifecycle (configuration deadline)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "ConnectionCleanup.kt:lifecycleDeadline"),
            ),
        ),
        FailureSpec(
            "CONNECTION_OPTIONS_CONFLICT",
            listOf(
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "ConnectStarter.kt:admitConnect(판정) + RuntimeCommands.kt:emitConnectConflict(throw·Diagnostic)"),
            ),
        ),
        FailureSpec(
            "CONNECT_DURING_DISCONNECT",
            listOf(
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "ConnectStarter.kt:admitConnect(판정) + RuntimeCommands.kt:emitConnectConflict(throw·Diagnostic)"),
            ),
        ),
        FailureSpec(
            "DISCONNECT_CLEANUP_TIMEOUT",
            listOf(
                Route("link lifecycle (disconnect cleanup deadline)", TriggerKind.AsyncInfo, setOf(Channel.Event), "ConnectionCleanup.kt:lifecycleDeadline — completeLifecycleCleanup 로 Disconnected 도달 후 정보"),
            ),
        ),
        FailureSpec(
            "DISCONNECT_START_FAILED",
            listOf(
                Route("disconnect command", TriggerKind.AsyncInfo, setOf(Channel.Event), "ConnectionCleanup.kt:disconnectRequested, ConnectionCleanup.kt:beginCleanup, ConnectionCleanup.kt:endPlatformWait(OS 대기 취소 — 사용자 해제·close·어댑터 꺼짐)"),
                Route("internal cleanup (timeout/discovery/link/permission)", TriggerKind.AsyncInfo, setOf(Channel.Event), "ConnectionCleanup.kt:beginCleanup"),
            ),
        ),
        FailureSpec(
            "DUPLICATE_CHARACTERISTIC_REFERENCE",
            listOf(
                Route("link lifecycle (service discovery result)", TriggerKind.AsyncInfo, setOf(Channel.Event), "LinkCallbacks.kt:handleServicesDiscovered"),
            ),
        ),
        FailureSpec(
            "GATT_OPERATION_CALLBACK_MISMATCH",
            listOf(
                Route("GATT callback routing", TriggerKind.AsyncInfo, setOf(Channel.Event), "OperationExecutor.kt:execute"),
            ),
        ),
        FailureSpec(
            "GATT_OPERATION_DUPLICATE_CALLBACK",
            listOf(
                Route("GATT callback routing", TriggerKind.AsyncInfo, setOf(Channel.Event), "OperationCallbacks.kt:emitDuplicateCallback"),
            ),
        ),
        FailureSpec(
            "GATT_OPERATION_START_FAILED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "OperationExecutor.kt:startOperation"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "SubscriptionCommands.kt:unsubscribe, SubscriptionCommands.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkSetup.kt:restoreSubscriptions, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
                Route("link lifecycle (requestMtu start, fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "GattConnectionLifecycle.kt:requestMtu"),
            ),
        ),
        FailureSpec(
            "GATT_PAYLOAD_OBSERVED",
            listOf(
                Route("notify/read/write payload", TriggerKind.Observation, setOf(Channel.LoggerOnly), "OperationExecutor.kt:emitPayloadObservation"),
            ),
        ),
        FailureSpec(
            "GATT_STATUS_FAILED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "OperationExecutor.kt:complete, OperationCallbacks.kt:handleCharacteristicRead, OperationQueue.kt:write"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "SubscriptionCommands.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkSetup.kt:restoreSubscriptions, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
            ),
        ),
        FailureSpec(
            "INTERNAL_TRANSPORT_ERROR",
            listOf(
                Route("subscription restore (non-BleException)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "RuntimeCommands.kt:failureFromInternalException, LinkSetup.kt:restoreSubscriptions, SubscriptionState.kt:markActive(성공 시에만)"),
                Route("core scope uncaught exception (session-owned)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "BleRuntime.kt:onUncaughtCoreException, ConnectionCleanup.kt:internalFailure(SessionOwner 컨텍스트로 귀속 — 그 세션만 종결·handle close)"),
                Route("core scope uncaught exception (unattributed)", TriggerKind.AsyncInfo, setOf(Channel.Event), "BleRuntime.kt:onUncaughtCoreException(귀속 불명 — Diagnostic 만)"),
            ),
        ),
        FailureSpec(
            "LINK_FAILED",
            listOf(
                Route("link lifecycle (link status callback)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkCallbacks.kt:handleConnectionStateChange"),
                Route("unattributed platform disconnect (status 0)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkCallbacks.kt:handleConnectionStateChange, ConnectionCleanup.kt:finalizeCause(unattributedLinkDisconnect + emitDiagnostic; Event 는 항상 나간다)"),
                Route("late non-terminal link status callback", TriggerKind.AsyncInfo, setOf(Channel.Event), "LinkCallbacks.kt:handleConnectionStateChange"),
                Route("ended-generation link status callback", TriggerKind.AsyncInfo, setOf(Channel.Event), "LinkCallbacks.kt:attributedGeneration(CallbackAttribution Drop \"ended generation\" + 비성공 status 일 때만 Warning Diagnostic, 성공 status 는 조용히 버림)"),
            ),
        ),
        FailureSpec(
            "LINK_START_FAILED",
            listOf(
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, ConnectStarter.kt:connect, RuntimeCommands.kt:connect"),
                Route("adapter return relink start", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "ConnectStarter.kt:resumeAfterAdapter(connect 즉시 실패 → Disconnected(Failed) + Warning Diagnostic, 던질 호출자 없음)"),
            ),
        ),
        FailureSpec(
            "LINK_TIMEOUT",
            listOf(
                Route("link lifecycle (link deadline)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "ConnectionCleanup.kt:lifecycleDeadline"),
            ),
        ),
        FailureSpec(
            "NOT_CONNECTED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "OperationExecutor.kt:startOperation, OperationExecutor.kt:exactIdentity"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkSetup.kt:restoreSubscriptions, SubscriptionState.kt:markActive(성공 시에만) — ActiveSubscriptionsTest"),
            ),
        ),
        FailureSpec(
            "NOT_READY",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "RuntimeCommands.kt:notReady"),
            ),
        ),
        FailureSpec(
            "OPERATION_INVALIDATED",
            listOf(
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw), "OperationExecutor.kt:invalidateScheduledOperations, OperationQueue.kt:write"),
                Route("unsubscribe command", TriggerKind.AsyncInfo, setOf(Channel.Event), "SubscriptionCommands.kt:unsubscribe"),
                Route("connect command (user disconnect during rediscovery)", TriggerKind.SyncCommand, setOf(Channel.Throw), "GattConnectionLifecycle.kt:disconnect(Scanner.kt:endRediscovery) → ConnectStarter.kt:connect(재발견 뒤 종결 판정, Disconnected(Requested) 는 그대로)"),
            ),
        ),
        FailureSpec(
            "OPERATION_TIMEOUT",
            listOf(
                Route("read/write/subscribe command (started)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State, Channel.Event), "GattQueueCore.kt:deadlineJudged, OperationExecutor.kt:execute, ConnectionCleanup.kt:operationInvalidated"),
                Route("unsubscribe command", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "SubscriptionCommands.kt:emitUnsubscribeFailure, GattQueueCore.kt:deadlineJudged"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattQueueCore.kt:deadlineJudged, ConnectionCleanup.kt:operationInvalidated"),
            ),
        ),
        FailureSpec(
            "PAYLOAD_TOO_LARGE",
            listOf(
                Route("write command (chunking Disabled over chunk size, or Fixed over chunk size)", TriggerKind.SyncCommand, setOf(Channel.Throw), "OperationQueue.kt:write(WriteResult.TooLarge), DefaultBleSession.kt:write, GattFailureFactory.kt:payloadTooLarge"),
            ),
        ),
        FailureSpec(
            "PERIPHERAL_NOT_FOUND",
            listOf(
                Route("connect command (rediscovery not found)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "ConnectStarter.kt:connect(autoConnect 아님), RuntimeCommands.kt:connect"),
                Route("connect rediscovery not found (autoReconnect fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "ConnectStarter.kt:connect(autoConnect — Warning Diagnostic 뒤 connectGatt(autoConnect = true) OS 대기)"),
            ),
        ),
        FailureSpec(
            "PERMISSION_DENIED",
            listOf(
                Route("command adapter preflight", TriggerKind.SyncCommand, setOf(Channel.Throw), "AdapterMonitor.kt:ensureAdapterReady"),
                Route("startScan command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "AndroidScanner.kt:start, Scanner.kt:startScan — ScanContractTest"),
                Route("connect command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, ConnectStarter.kt:connect, RuntimeCommands.kt:connect"),
                Route("read/write/subscribe command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State, Channel.Event), "OperationExecutor.kt:startOperation, ConnectionCleanup.kt:beginCleanup"),
                Route("unsubscribe command", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "OperationExecutor.kt:startOperation, SubscriptionCommands.kt:emitUnsubscribeFailure"),
                Route("subscription restore", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "OperationExecutor.kt:startOperation(emitDiagnostic + beginCleanup)"),
                Route("link lifecycle (discovery/MTU start)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "GattConnectionLifecycle.kt:discoverServices, GattConnectionLifecycle.kt:requestMtu"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Scanner.kt:rediscover ← AndroidScanner.kt:start(startScan 의 SecurityException → PERMISSION_DENIED) → Unavailable, ConnectStarter.kt:connect"),
                Route("adapter off sweep", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "AdapterMonitor.kt:updateAdapterState(스윕 판정), ConnectionCleanup.kt:adapterLost, ConnectionCleanup.kt:endPlatformWait(플랫폼 재연결 대기 중 세션도 종료)"),
            ),
        ),
        FailureSpec(
            "SCANNER_UNAVAILABLE",
            listOf(
                Route("startScan command", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "AndroidScanner.kt:start, Scanner.kt:startScan — ScanContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Scanner.kt:rediscover ← AndroidScanner.kt:start(BluetoothLeScanner null → SCANNER_UNAVAILABLE) → Unavailable, ConnectStarter.kt:connect"),
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
                Route("stale scan failure (ended/other scan)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Scanner.kt:handleFailure(generation 불일치·정지 뒤 → 상태 불변, Warning) — ScanContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Scanner.kt:handleFailure(재발견 중 onScanFailed → 모든 소비자 종료) → Unavailable, ConnectStarter.kt:connect"),
            ),
        ),
        FailureSpec(
            "SCAN_START_FAILED",
            listOf(
                Route("startScan command (platform start rejected)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "Scanner.kt:startScan ← AndroidScanner.start(필터 IllegalArgumentException·startScan 의 비-Security Exception; SecurityException 은 PERMISSION_DENIED); Scanner.kt:rediscover 는 MAC 형식이 아닌 주소를 플랫폼 전에 거른다 — ScanContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Scanner.kt:rediscover(MAC 형식 아님·예상 밖 비취소 예외) · AndroidScanner.kt:start(필터 IllegalArgumentException·그 밖의 비-Security Exception) → Unavailable, ConnectStarter.kt:connect"),
            ),
        ),
        FailureSpec(
            "SCAN_START_THROTTLED",
            listOf(
                Route("startScan command (start budget exceeded)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.Event), "Scanner.kt:startScan ← Scanner.kt:reconcile(30 s + 여유 1 s 안 성공 시작 5회 → 플랫폼 호출 없이 거부, 도는 스캔 유지) — ScannerContractTest"),
                Route("connect rediscovery scan (fallback)", TriggerKind.AsyncInfo, setOf(Channel.Event), "Scanner.kt:scheduleReconcile(한도 초과 → 도는 스캔이 덮지 못하는 재발견만) → Unavailable, ConnectStarter.kt:connect"),
            ),
        ),
        FailureSpec(
            "SCAN_STOPPED_BY_ADAPTER",
            listOf(
                Route("adapter off sweep", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "AdapterMonitor.kt:updateAdapterState(스윕 판정), Scanner.kt:stopForAdapter(scanState=Idle(failure)) — ScanContractTest"),
            ),
        ),
        FailureSpec(
            "SERVICE_DISCOVERY_FAILED",
            listOf(
                Route("link lifecycle (discovery status)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "LinkCallbacks.kt:handleServicesDiscovered(status 실패 + GATT_SUCCESS 인데 서비스 0개 — emptyServiceDiscovery)"),
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
                Route("link lifecycle (discovery deadline)", TriggerKind.AsyncResource, setOf(Channel.State, Channel.Event), "ConnectionCleanup.kt:lifecycleDeadline"),
            ),
        ),
        FailureSpec(
            "UNKNOWN_DEVICE",
            listOf(
                Route("session command with unregistered id", TriggerKind.SyncCommand, setOf(Channel.Throw), "RuntimeCommands.kt:unknownDevice"),
                Route("connect command (invalid address)", TriggerKind.SyncCommand, setOf(Channel.Throw, Channel.State), "AndroidGattDriver.kt:connect, RuntimeCommands.kt:connect"),
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
