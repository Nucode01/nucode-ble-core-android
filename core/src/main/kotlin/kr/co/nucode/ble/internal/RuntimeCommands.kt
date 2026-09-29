package kr.co.nucode.ble.internal

import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.gatt.ConnectAdmission
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.internal.gatt.OperationQueue
import kr.co.nucode.ble.internal.model.BleEvent
import kr.co.nucode.ble.internal.model.ConnectOptions
import kr.co.nucode.ble.internal.model.WriteResult
import kr.co.nucode.ble.internal.model.WriteType
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.ConnectionStage
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionRegistry
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [BleRuntime] 의 공개 명령 칸 — closed 검사·코어 스레드 전환·실패 구성과 scan/connect/GATT 명령 본문을 가진다.
 * 상태는 없다: 닫기 상태는 [isClosed](RuntimeCloser 소유), 세션은 [registry], 자원은 각 소유자에게 묻는다.
 */
internal class RuntimeCommands(
    private val isClosed: () -> Boolean,
    private val coreDispatcherOnly: CoroutineContext,
    private val registry: SessionRegistry,
    private val scanner: Scanner,
    private val gattBridge: GattBridge,
    private val operationQueue: OperationQueue,
    private val adapterReaction: AdapterReaction,
    private val emit: (BleEvent) -> Unit,
) {
    /** Converts remaining internal migration errors at the public command boundary only. */
    private suspend fun <T> transportCommand(
        phase: FailurePhase,
        deviceId: PeripheralId? = null,
        charRef: CharacteristicRef? = null,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        // Caller cancellation is control flow, never a transport failure.
        throw e
    } catch (e: BleException) {
        throw e
    }

    private fun diagnostic(severity: Severity, failure: BleFailure): BleEvent.Diagnostic =
        BleEvent.Diagnostic(severity, failure)

    internal fun transportFailure(
        code: String,
        phase: FailurePhase,
        deviceId: PeripheralId? = null,
        charRef: CharacteristicRef? = null,
        retryability: Retryability = Retryability.Unknown,
    ): BleFailure = BleFailure(
        category = FailureCategories.of(code),
        code = FailureCode(code),
        phase = phase,
        retryability = retryability,
        // A command for an existing peripheral belongs to that session.  Unknown
        // devices and client-level operations deliberately retain an empty context;
        // manufacturing a session for diagnostics would create false correlation.
        context = deviceId?.let(registry::get)?.lifecycleFailureContext() ?: FailureContext(),
    )

    internal fun failureFromInternalException(
        error: Exception,
        phase: FailurePhase,
        deviceId: PeripheralId? = null,
        charRef: CharacteristicRef? = null,
    ): BleFailure = when (error) {
        is BleException -> error.failure
        else -> transportFailure("INTERNAL_TRANSPORT_ERROR", phase, deviceId, charRef)
    }

    private fun unknownDevice(deviceId: PeripheralId): BleException = BleException(
        transportFailure(
            code = "UNKNOWN_DEVICE",
            phase = FailurePhase.Validation,
            deviceId = deviceId,
            retryability = Retryability.Never,
        ),
    )

    private fun notReady(deviceId: PeripheralId): BleException = BleException(
        transportFailure(
            code = "NOT_READY",
            phase = FailurePhase.Operation,
            deviceId = deviceId,
            retryability = Retryability.NewConnectionMayHelp,
        ),
    )

    // ── 공개 명령 API(설계 §5) ────────────────────────────────────────────────────
    // 코어 상태가 coreScope 단일 스레드에 고정돼 있어, 상태를 만지는 모든 진입은
    // onCore 로 그 디스패처 위에 넘어간다(plan §접근 배선①, D5/review #7).

    /**
     * 명령 공통 진입로 — closed 검사 + 코어 스레드 전환(plan D5, review #7).
     * ★ Job 없이 디스패처만 넘긴다: `withContext(coreScope.coroutineContext)` 로 coreScope 의
     *   `SupervisorJob` 을 섞으면 호출자의 Job 이 그 Job 으로 덮여, 호출자의 `withTimeout`·
     *   취소가 이 블록에 전파되지 않고(타임아웃이 발화해도 블록이 coreScope 자식이라 안
     *   죽는다) close 후 호출은 `JobCancellationException` 으로 호출자 코루틴을 조용히
     *   죽인다(에러도 로그도 없이). 디스패처만 넘기면 블록은 호출자 Job 의 자식으로 남아
     *   취소가 정상 전파되고, close 후 호출은 아래 [isClosed] 검사로 정의된 실패가 된다.
     */
    private suspend fun <T> onCore(block: suspend () -> T): T {
        if (isClosed()) throw clientClosed()
        return withContext(coreDispatcherOnly) {
            // 재검사(review M): 진입 검사 통과 후 디스패처 큐 적재 전에 close() 가 끼어들면,
            // 이 블록이 정리 **뒤에** 실행돼 플랫폼 자원(스캔·connectGatt)을 되살릴 수 있다 —
            // 그 자원은 회수 경로가 없다(이후 명령이 전부 ISE 로 막히므로).
            if (isClosed()) throw clientClosed()
            block()
        }
    }

    internal fun clientClosed(): BleException = BleException(
        transportFailure(
            code = "CLIENT_CLOSED",
            phase = FailurePhase.Close,
            retryability = Retryability.Never,
        ),
    )

    internal fun checkOpen() {
        if (isClosed()) throw clientClosed()
    }

    /**
     * View 생성은 코어 디스패처를 기다리지 않는다(plan `runtime-no-runblocking` task 1(A)).
     * `Session` 생성자는 StateFlow·coordinator 초기화뿐 — launch 도 부수효과도 없어 코어
     * 스레드 밖에서 만들어도 안전하고, registry 멤버십은 이제 동기화돼 있다(위 KDoc).
     *
     * v2 의 조건부 `remove`("이 호출이 만든 것만 지운다")는 아직 부족했다(plan v3 review
     * HIGH round-2 재발). `existing == null`은 "이 호출이 그 Session 을 만들었다"는 뜻이
     * 아니라 "이 호출이 [registry.get] 시점에 그 Session 을 못 봤다"는 뜻일 뿐이다 —
     * `checkOpen()` 과 `getOrCreate` 사이에서 다른 스레드가 같은 [deviceId] 로 먼저
     * `getOrCreate`(다른 `sessionForView` 호출이든, `connect()` 의 ~862 `existing ?:
     * registry.getOrCreate`든)를 끝내 살아 있는 Session S' 을 등록해뒀을 수 있다. 그 경우
     * 이 호출의 `getOrCreate`는 새로 만들지 않고 S' 을 그대로 받아온다(registry 는
     * `@Synchronized` — 같은 키에 두 인스턴스가 생기지 않는다) — 그런데 [existing] 은 이미
     * null 로 캡처된 뒤라, 아래서 gate 가 서 있으면 "내가 만들었으니 지운다" 분기가 S' 을
     * 지운다. S' 을 만든/채택한 쪽이 이 호출이 아니라도 상관없이 지워지므로, `close()` 의
     * 지연 정리(`runCloseCleanup`, `registry.all()` 스냅샷)가 그 스냅샷을 뜨기 전에 S' 이
     * 사라지면 `gattBridge.close`(driver.close)가 영원히 안 불린다(GATT 핸들 누수) —
     * v2 리뷰가 고친 경합과 형태만 다른 같은 결함이다.
     *
     * 그래서 `remove` 자체를 없앤다. gate 뒤에 살아남는 Session 은 두 갈래뿐이고 둘 다
     * 안전하다:
     * - cleanup 이 이미 `registry.all()` 스냅샷을 뜬 *뒤에* 이 호출이 `getOrCreate`로 새
     *   Session 을 등록한 경우 — 그 Session 은 스냅샷에 없어 cleanup 이 못 보지만, 애초에
     *   GATT 핸들을 가진 적이 없다(방금 생성된 순수 로컬 객체, driver.connect 를 부른 적
     *   없음) — close 할 게 없어 안전하게 GC 된다. 그리고 이 함수가 곧바로 `checkOpen()`
     *   재검사(아래 `if (closed)`)로 던지므로, 호출자는 이 Session 을 절대 손에 넣지
     *   못한다 — 좀비 Session 이 공개 API 로 새어나가지 않는다.
     * - cleanup 이 아직 스냅샷을 뜨지 *않았고* 이 호출(또는 동시의 다른 경로)이 만든
     *   Session 이 registry 에 남아 있는 경우 — cleanup 이 뒤이어 그 스냅샷에서 이 Session
     *   을 보고 정상적으로 정리한다(멱등, 한 번만).
     * 어느 갈래든 [checkOpen] 이 먼저 닫힌 클라이언트를 걸러 좀비 노출이 없고, cleanup 은
     * 자신이 실제로 스냅샷한 Session 집합만 책임진다 — 이 함수가 그 집합을 사후에 축소할
     * 권한을 갖지 않는다.
     */
    internal fun sessionForView(requestedId: PeripheralId): Session {
        checkOpen()
        val session = registry.getOrCreate(requestedId.normalized())
        if (isClosed()) throw clientClosed()
        return session
    }

    internal suspend fun startScan(filter: BleScanFilter = BleScanFilter(), options: BleScanOptions = BleScanOptions()): Unit =
        transportCommand(FailurePhase.Scan) {
        onCore {
            adapterReaction.ensureAdapterReady(AndroidApiOperation.StartScan)
            scanner.startScan(filter, options)
        }
    }

    internal suspend fun stopScan() {
        if (isClosed()) return
        // 정지는 정리 동작이다 — 취소된 호출자라도 스캔이 남으면 안 되므로 NonCancellable 로
        // 블록을 끝까지 돌리고, 취소는 그 뒤 ensureActive() 로 호출자에게 그대로 전파한다.
        withContext(NonCancellable + coreDispatcherOnly) {
            if (!isClosed()) scanner.stopScan()
        }
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun connect(requestedId: PeripheralId, options: ConnectOptions = ConnectOptions()): Unit {
        val deviceId = requestedId.normalized()
        return transportCommand(FailurePhase.Connect, deviceId) {
        onCore {
            val existing = registry.get(deviceId)
            // 중복 connect 판정은 연결 stage 소유자(lifecycle)가 한다 — 실패 구성·게시·throw 는 여기서 그대로.
            if (existing != null) {
                when (val admission = gattBridge.admitConnect(existing, options)) {
                    ConnectAdmission.Proceed -> Unit
                    ConnectAdmission.Joined -> return@onCore
                    is ConnectAdmission.Conflict ->
                        emitConnectConflict(deviceId = deviceId, code = admission.code, retryability = admission.retryability)
                }
            }

            adapterReaction.ensureAdapterReady(AndroidApiOperation.ConnectGatt)
            if (existing == null) registry.getOrCreate(deviceId)
            // 시작 실패의 종결·게시·suppressReconnect 는 lifecycle connect 가 끝낸 뒤 같은 BleException 을 재던진다.
            gattBridge.connect(deviceId, options)
            // review LOW-1 — 재발견 대기 중 close 가 연결을 끝냈으면 정상 반환하지 않는다.
            if (isClosed()) throw clientClosed()
        }
        }
    }

    /**
     * 공개 connect 옵션을 런타임 연결로 옮긴다. `autoReconnect = true` 는 Android `autoConnect = true` 로
     * 연결하고 재연결 주체를 플랫폼에 넘긴다(handle 유지, link deadline 없음). 그 밖에는 `autoConnect = false`
     * 이고 링크가 끊기면 재연결 없이 Disconnected(Failed) 로 끝난다. handle 을 OS 대기용으로 남길지
     * (suppressReconnect)는 lifecycle 이 연결 시작 시점에 모드로 정한다.
     */
    internal suspend fun connectOfficial(requestedId: PeripheralId, options: BleConnectOptions) {
        onCore { connect(requestedId.normalized(), ConnectOptions(autoConnect = options.autoReconnect)) }
    }

    /**
     * `connect`는 Unit을 반환하므로 conflict를 조용한 no-op으로 만들면 호출자가 새 옵션이
     * 적용됐다고 오해한다. Slice 3의 typed failure model 전까지는 기존 즉시 실패 관례에 맞춰
     * deterministic IllegalStateException과 Diagnostic을 함께 남긴다.
     */
    private fun emitConnectConflict(deviceId: PeripheralId, code: String, retryability: Retryability): Nothing {
        val failure = transportFailure(code, FailurePhase.Connect, deviceId, retryability = retryability)
        emit(
            diagnostic(
                Severity.Warning,
                failure,
            ),
        )
        throw BleException(failure)
    }

    internal suspend fun disconnect(requestedId: PeripheralId): Unit {
        val deviceId = requestedId.normalized()
        transportCommand(FailurePhase.Close, deviceId) {
        if (isClosed()) return@transportCommand
        // 해제는 정리 동작이다 — 취소된 호출자라도 링크 정리가 시작돼야 하므로 NonCancellable 로
        // 블록을 끝까지 돌리고, 취소는 그 뒤 ensureActive() 로 호출자에게 그대로 전파한다(stopScan 과 같은 규칙).
        withContext(NonCancellable + coreDispatcherOnly) {
            if (isClosed()) return@withContext
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            // 사용자 명시 해제는 구독 의도를 복원하지 않는다(v1 계약) — 링크 유실·재연결 경로는 의도를 유지한다.
            session.subscriptionState.clearDesired()
            // 단계별 판정(플랫폼 재연결 대기 종결·어댑터 복귀 재연결 취소·이미 끝난 세션·정리 시작)은 lifecycle 이 한다.
            gattBridge.disconnect(deviceId)
        }
        currentCoroutineContext().ensureActive()
        }
    }

    /**
     * 현재 연결 generation에서 characteristic 값을 한 번 읽는다. 응답 bytes는 notification
     * event로 방출하지 않고 이 호출 결과로만 반환한다.
     */
    internal suspend fun read(requestedId: PeripheralId, charRef: CharacteristicRef): ByteArray {
        val deviceId = requestedId.normalized()
        return transportCommand(FailurePhase.Operation, deviceId, charRef) {
            onCore {
        adapterReaction.ensureAdapterReady(AndroidApiOperation.ReadCharacteristic)
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
        if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
        gattBridge.read(deviceId, charRef)
    }
        }
    }

    /**
     * write 만 [WriteResult] 를 반환한다 — 부분 전달을 타입으로 강제해야 하므로(설계 §5).
     * 즉시 실패는 이 함수가 직접 throw 한다 — 순서: 어댑터 사용 불가 → 모르는 PeripheralId → 세션 미Ready.
     * 청크 단위 실패는 [OperationQueue.write] 가 처리한다.
     */
    internal suspend fun write(
        requestedId: PeripheralId,
        charRef: CharacteristicRef,
        bytes: ByteArray,
        writeType: WriteType = WriteType.Auto,
        chunking: BleChunking = BleChunking.Automatic,
    ): WriteResult {
        val deviceId = requestedId.normalized()
        return transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
        adapterReaction.ensureAdapterReady(AndroidApiOperation.WriteCharacteristic)
        val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
        if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
        operationQueue.write(deviceId, charRef, bytes, writeType, chunking)
    }
    }
    }

    internal suspend fun subscribe(requestedId: PeripheralId, charRef: CharacteristicRef) {
        val deviceId = requestedId.normalized()
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
            adapterReaction.ensureAdapterReady(AndroidApiOperation.WriteDescriptor)
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            if (!session.canStartPublicGattOperation()) throw notReady(deviceId)
            // 구독 의도·활성 표시는 큐 완료 쪽(SubscriptionCommands.subscribe)이 ack 성공 때만 남긴다 —
            // 호출자가 취소돼도 접수된 subscribe 는 끝까지 가서 남는다. 여기서는 살아 있는 호출자에게만 결과를 판정한다.
            val startGeneration = session.connectionCoordinator.generation
            gattBridge.subscribe(deviceId, charRef)
            // ack 뒤 재개 전에 사용자 disconnect(clearDesired)·정리가 시작됐으면 던진다.
            if (!session.connectionCoordinator.accepts(startGeneration, ConnectionStage.Ready)) {
                throw BleException(GattFailureFactory.operationInvalidated(session))
            }
        }
        }
    }

    /**
     * 구독 해제 — D8 best-effort(plan design decisions v1, review L-1). 유일한 throw 는
     * `UNKNOWN_DEVICE` typed failure다. [GattBridge.unsubscribe] 는 더 이상 throw 하지 않는다 —
     * CCCD write 실패는 Diagnostic 으로만 나간다. desired intent 제거는 CCCD 해제를 요청하기 전에
     * 먼저 한다(성패와 무관) — 소비자 의도("이벤트 그만")는 로컬 라우팅을 껐다는 사실만으로
     * 이미 달성된다. [subscribe] 가 여전히 throw 하는 것과 비대칭이다 — 켜는 방향은 실패가
     * 안 보이면 notify 를 하염없이 기다리게 되지만, 끄는 방향은 로컬에서 이미 지웠으므로
     * 잃을 게 없다(규칙 1 — throw 로 소비자에게 재시도 상태 기계를 강제하지 않는다).
     */
    internal suspend fun unsubscribe(requestedId: PeripheralId, charRef: CharacteristicRef) {
        val deviceId = requestedId.normalized()
        transportCommand(FailurePhase.Operation, deviceId, charRef) {
        onCore {
            val session = registry.get(deviceId) ?: throw unknownDevice(deviceId)
            // 의도는 요청 순간 지운다(CCCD 해제 전) — 진행 중인 복원 루프가 이 ref 를 다시 켜지 않게
            // 매 항목 의도를 재확인한다(LinkSetup, plan cross-review-fixes LOW-7).
            session.subscriptionState.removeDesired(charRef)
            gattBridge.unsubscribe(deviceId, charRef)
        }
        }
    }
}
