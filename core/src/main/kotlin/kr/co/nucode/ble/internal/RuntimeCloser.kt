package kr.co.nucode.ble.internal

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.nucode.ble.*
import kr.co.nucode.ble.internal.adapter.AdapterMonitor
import kr.co.nucode.ble.internal.event.ClientEventBus
import kr.co.nucode.ble.internal.gatt.GattBridge
import kr.co.nucode.ble.internal.scan.Scanner
import kr.co.nucode.ble.internal.session.SessionRegistry

/**
 * [BleRuntime] 의 닫기 칸 — close 게이트, 정리 완료 신호, 닫힌 뒤 스냅샷, 정리 실패 기록을 소유한다.
 * 정리 때만 쓰는 부품은 함수 값으로 받아 close 시점에 평가한다 — [BleRuntime] 이 이 객체를 eventBus·gattBridge
 * 보다 먼저 만들어 `isClosed` 람다로 넘기므로 초기화 순환이 없다.
 */
internal class RuntimeCloser(
    private val onRuntimeThread: ThreadLocal<Boolean>,
    private val coreDispatcherOnly: CoroutineContext,
    private val coreScope: CoroutineScope,
    private val scannerOf: () -> Scanner,
    private val registryOf: () -> SessionRegistry,
    private val gattBridgeOf: () -> GattBridge,
    private val adapterMonitorOf: () -> AdapterMonitor,
    private val eventBusOf: () -> ClientEventBus,
    private val clientClosed: () -> BleException,
    private val cleanupFailure: (code: String, phase: FailurePhase, retryability: Retryability) -> BleFailure,
) {
    private val scanner: Scanner get() = scannerOf()
    private val registry: SessionRegistry get() = registryOf()
    private val gattBridge: GattBridge get() = gattBridgeOf()
    private val adapterMonitor: AdapterMonitor get() = adapterMonitorOf()
    private val eventBus: ClientEventBus get() = eventBusOf()

    private fun transportFailure(code: String, phase: FailurePhase, retryability: Retryability): BleFailure =
        cleanupFailure(code, phase, retryability)
    private val closeGate = AtomicBoolean(false)
    private val closed: Boolean get() = closeGate.get()
    private val closeCompleted = CompletableDeferred<Unit>()
    @Volatile
    private var closedOfficialSnapshot: ClientSnapshot? = null

    /**
     * 지연 정리(`runCloseCleanup`) 도중 잡힌 예외(plan `runtime-no-runblocking/v2` review MEDIUM).
     * `null` = 정리가 예외 없이 끝났다. 정리는 코어 스레드에서만 도는데, 그 스레드의 uncaught
     * handler 로 예외를 올리면 Android 프로세스가 죽는다 — 그래서 여기 기록하고 삼킨다.
     * 테스트 전용 관측 지점이라 `internal`이다.
     */
    @Volatile
    internal var closeFailure: Throwable? = null
        private set

    fun isClosed(): Boolean = closed

    /** [close] 가 예약한 정리가 끝났는지 기다린다(plan `runtime-no-runblocking` task 2(B)). */
    internal suspend fun awaitClosed() = closeCompleted.await()

    /** Internal projection seam: no facade keeps a parallel snapshot or session map. */
    internal suspend fun officialSnapshot(): ClientSnapshot {
        closedOfficialSnapshot?.let { return it }
        return withContext(coreDispatcherOnly) {
            if (closed) {
                // The close caller may have set admission before it could queue
                // cleanup. Suspending yields the owner to that cleanup instead
                // of returning a pre-close snapshot during this interval.
                closeCompleted.await()
                return@withContext requireNotNull(closedOfficialSnapshot)
            }
            ClientSnapshot(
            adapterState = adapterMonitor.adapterState.value,
            isScanning = scanner.isScanning,
            sessions = registry.all().map { session ->
                SessionSnapshot(
                    peripheralId = session.deviceId,
                    connectionState = session.connectionState.value,
                    services = session.services.value,
                    subscriptions = session.subscriptionState.desiredSnapshot,
                )
            },
            )
        }
    }

    /**
     * 정리 본문(plan `runtime-no-runblocking` task 2(B)) — [close] 에서 추출했다. 코어
     * 스레드 위라면 [close] 가 인라인으로, 아니면 [coreDispatcherOnly] 에 예약된 코루틴이
     * 이 함수를 돈다. 정리가 실패해도 `closeCompleted` 는 반드시 완료돼야 한다 —
     * 그러지 않으면 [officialSnapshot] 이 영원히 멈춘다.
     *
     * `runCloseCleanupBody` 가 던지면(예: `gattBridge.close` 안 driver 호출 실패) 여기서
     * 삼킨다(plan v2 review MEDIUM). [close] 는 코어 스레드 위에서 이 함수를 inline 으로도
     * 부르므로, 삼키지 않으면 그 예외가 스레드의 uncaught handler 까지 올라가 Android
     * 프로세스를 죽인다 — 두 호출 경로(inline/예약된 코루틴)가 같은 의미(호출자에게 절대
     * throw 하지 않는다, 대신 기록한다)를 갖도록 여기 한 곳에서만 catch 한다.
     */
    private fun runCloseCleanup() {
        try {
            // 세션별 실패는 반환값으로 받아 보고한다 — 디스패처 close(finally) 보다 먼저 보고해야
            // CLEANUP_FAILURE 가 logger 큐로도 나갈 기회를 얻는다(호환 스트림은 어느 경우든 받는다).
            runCloseCleanupBody()?.let { failure ->
                closeFailure = failure
                reportCleanupFailure(failure)
            }
        } catch (t: Throwable) {
            closeFailure = t
            reportCleanupFailure(t)
            // 실패 지점에 따라 closedOfficialSnapshot 이 아직 안 섰을 수 있다 — officialSnapshot()
            // 이 typed 결과로 끝나려면 폴백이라도 세워야 한다.
            if (closedOfficialSnapshot == null) {
                closedOfficialSnapshot = ClientSnapshot(
                    adapterState = adapterMonitor.adapterState.value,
                    isScanning = false,
                    sessions = emptyList(),
                )
            }
        } finally {
            // receiver·디스패처·스코프 정리는 정상·실패 공통으로 딱 한 번 — 보고 뒤라 디스패처 close 가 보고를 삼키지 않는다.
            runCatching { adapterMonitor.close() }
            runCatching { eventBus.close() }
            runCatching { coreScope.cancel() }
            closeCompleted.complete(Unit)
        }
    }

    /**
     * plan v3 review MEDIUM Fix 2 — 세션별로 `runCatching`(fault-isolate)한다. 한 세션의
     * `gattBridge.close`(driver.close)가 던지면 v2 코드는 그 예외가 이 함수 전체를 끝내
     * 뒤 세션들이 정리되지 않고(GATT 핸들 누수) `adapterMonitor.close()`/`eventBus.close()`/
     * `coreScope.cancel()` 꼬리까지 스킵됐다 — 한 핸들의 실패가 나머지 정리를 막으면 안 된다.
     * 첫 예외만 [firstFailure] 에 모아 반환한다 — 호출자인 [runCloseCleanup] 이 그 값을
     * [closeFailure] 에 기록하고 로거로 보고한다(호출자에게는 절대 전파되지 않는다). 스냅샷
     * 구성은 세션 실패 여부와 무관하게 항상 돌고, receiver 해제(`adapterMonitor.close()`)·`eventBus.close()`·
     * `coreScope.cancel()` 는 [runCloseCleanup] 의 finally 가 담당한다(예기치 못한 throw 에도 실행).
     */
    private fun runCloseCleanupBody(): Throwable? {
        scanner.stopScan()
        // 재발견 소비자도 끝낸다 — 남아 있으면 close 뒤에도 플랫폼 스캔이 돈다.
        scanner.endRediscoveriesForClose(clientClosed().failure)
        val sessions = registry.all()
        var firstFailure: Throwable? = null
        sessions.forEach { session ->
            runCatching {
                // Scheduler completion은 GattBridge.close의 poison/drain이 유한 종결한다.
                // 복원 job 은 lifecycle close 가 게시하는 종결 stage 에서 LinkSetup 의 링크 이탈 listener 가 끊는다.
                // lifecycle close 가 ClientClosed 를 고정·종결하고 suppressReconnect 를 세운다. 플랫폼 재연결
                // 대기 중 세션도 ClientClosed 로 끝난다.
                gattBridge.close(session)
            }.onFailure { t -> if (firstFailure == null) firstFailure = t }
        }
        closedOfficialSnapshot = ClientSnapshot(
            adapterState = adapterMonitor.adapterState.value,
            isScanning = false,
            sessions = sessions.map { session ->
                SessionSnapshot(
                    peripheralId = session.deviceId,
                    connectionState = session.connectionState.value,
                    services = session.services.value,
                    subscriptions = session.subscriptionState.desiredSnapshot,
                )
            },
        )
        sessions.forEach { runCatching { registry.remove(it.deviceId) } }
        // receiver 해제·디스패처/스코프 정리는 runCloseCleanup 의 finally 로 미룬다 — 예기치 못한 throw 에도
        // 반드시 돌게(receiver 누수 방지) + 보고가 디스패처 close 보다 먼저이게.
        return firstFailure
    }

    /**
     * 정리 실패를 구성된(BleConfig) logger 로 보고한다(plan v2 review MEDIUM (a)) — 구성된 logger 가
     * 있으면 진단 reporter([ClientEventBus.diagnosticIngress])를 직접 부른다. `emit(diagnostic(...))` 을 쓰지 않는 이유:
     * 그 공개 경로는 `closed` 게이트를 검사해 이미 닫힌 시점에는 조용히 버린다(BleRuntime.emit 참고) —
     * 정리는 게이트가 선 뒤에만 돌므로 그 경로로는 절대 보고되지 않는다. `diagnosticIngress.report`
     * 는 게이트를 보지 않고 이 실패를 호환 이벤트 스트림과(아직 열려 있으면) logger 워커 큐로
     * non-blocking 하게 흘린다 — [closeFailure] 는 logger 유무와 무관하게 항상 기록되는
     * 테스트/진단 보조 관측점이다.
     */
    private fun reportCleanupFailure(error: Throwable) {
        // plan v3 review LOW Fix 4 — client 가 이미 닫힌 뒤의 실패는 CLIENT_CLOSED 와 같은
        // 이유로 재시도 대상이 아니다: 재시도할 대상 client 자체가 더는 없다.
        val failure = transportFailure(code = "CLEANUP_FAILURE", phase = FailurePhase.Close, retryability = Retryability.Never)
        try {
            eventBus.diagnosticIngress.report(Severity.Error, failure) {
                mapOf(
                    "cleanup_exception" to DiagnosticValue.Text(
                        // 메시지에는 주소 등이 실릴 수 있어 클래스 이름만 싣는다.
                        error::class.java.simpleName.take(DiagnosticValue.MAX_TEXT_LENGTH),
                    ),
                )
            }
        } catch (_: Throwable) {
            // Hostile/failing logger path cannot escape cleanup; closeFailure above already holds the record.
        }
    }

    /**
     * Close admission first, then schedule cleanup on its owner and return
     * immediately — [close] never blocks the caller's dispatcher. On the core
     * thread, cleanup runs inline (no dispatch round-trip). Off the core
     * thread, cleanup is scheduled onto [coreDispatcherOnly], which carries no
     * `Job`, so [coreScope]`.cancel()` inside cleanup cannot cancel cleanup
     * itself. All `Session` mutation is confined to this single dispatcher —
     * there is no separate state store (design §3 rule 2, `PublicModels.kt`
     * `BleConfig` KDoc) — so a command that already began mutating `Session`
     * state before this gate flips runs to completion on that same thread
     * before cleanup gets its turn there too (not a FIFO-ordering claim about
     * commands not yet admitted). [officialSnapshot] / [awaitClosed] observe cleanup completion —
     * at the moment [close] returns, the platform GATT `close()` may not have run
     * yet; if the process exits immediately after [close] returns, the
     * platform reclaims any still-open handles on process death.
     *
     * Cleanup never rethrows to a caller — there is none to rethrow to, since
     * both the inline and the scheduled path run [runCloseCleanup], which
     * catches every `Throwable`, records it in [closeFailure], and reports it
     * through the configured logger when one exists (plan
     * `runtime-no-runblocking/v2` review MEDIUM). [awaitClosed] always
     * completes even when cleanup failed partway through.
     */
    fun close() {
        // This gate is deliberately before dispatch: no command can be admitted
        // between a close caller and deferred cleanup.
        if (!closeGate.compareAndSet(false, true)) return
        if (onRuntimeThread.get() == true) {
            runCloseCleanup()
        } else {
            CoroutineScope(coreDispatcherOnly).launch { runCloseCleanup() }
        }
    }
}
