package kr.co.nucode.ble.harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kr.co.nucode.ble.BleClientEvent
import kr.co.nucode.ble.BleConfig
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.DefaultBleClient
import kr.co.nucode.ble.internal.DefaultBleSession
import org.junit.Assert.fail

/**
 * [FakeAndroidPlatform] 을 테스트 생성자 [BleRuntime] 에 배선한 하네스.
 * 코어와 fake 는 같은 [StandardTestDispatcher] 를 쓰고, 기록기는 Unconfined 로 모든 게시를 받는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Harness(
    private val testScope: TestScope,
    val core: BleRuntime,
    val client: DefaultBleClient,
    val fake: FakeAndroidPlatform,
    private val defaultOptions: BleConnectOptions,
    private val fakeJob: Job,
) {
    private val stateHistory = mutableMapOf<PeripheralId, MutableList<ConnectionState>>()
    private val activeHistory = mutableMapOf<PeripheralId, MutableList<Set<CharacteristicRef>>>()
    private val timelines = mutableMapOf<PeripheralId, MutableList<TimelinePoint>>()

    /** scanState 의 모든 게시. */
    val scanStates: MutableList<ScanState> = mutableListOf()

    /** core.officialEvents 의 모든 이벤트. */
    val events: MutableList<BleClientEvent> = mutableListOf()

    private val recorder = UnconfinedTestDispatcher(testScope.testScheduler)

    init {
        testScope.backgroundScope.launch(recorder) { client.scanState.collect { scanStates += it } }
        testScope.backgroundScope.launch(recorder) { core.officialEvents.collect { events += it } }
    }

    fun session(id: PeripheralId): DefaultBleSession {
        val session = client.session(id)
        if (id !in stateHistory) {
            val states = mutableListOf<ConnectionState>().also { stateHistory[id] = it }
            val actives = mutableListOf<Set<CharacteristicRef>>().also { activeHistory[id] = it }
            // 두 흐름을 같은 목록에 도착 순서대로 넣어 교차 순서를 남긴다(I4).
            val timeline = mutableListOf<TimelinePoint>().also { timelines[id] = it }
            testScope.backgroundScope.launch(recorder) {
                session.connectionState.collect {
                    states += it
                    timeline += TimelinePoint.State(it)
                }
            }
            testScope.backgroundScope.launch(recorder) {
                session.activeSubscriptions.collect {
                    actives += it
                    timeline += TimelinePoint.Active(it)
                }
            }
        }
        return session
    }

    /** [ref] 의 notifications 를 구독해 전달된 값을 [timeline] 에 상태 게시와 교차 순서대로 남긴다(I10). */
    fun recordNotifications(id: PeripheralId, ref: CharacteristicRef) {
        val session = session(id)
        val timeline = timelines.getValue(id)
        testScope.backgroundScope.launch(recorder) {
            session.notifications(ref).collect { timeline += TimelinePoint.Notification(ref, it) }
        }
    }

    /** 세션 connectionState 의 게시 기록(첫 [session] 호출 이후). */
    fun states(id: PeripheralId): List<ConnectionState> = stateHistory[id].orEmpty()

    /** 세션 activeSubscriptions 의 게시 기록. */
    fun actives(id: PeripheralId): List<Set<CharacteristicRef>> = activeHistory[id].orEmpty()

    /** connectionState·activeSubscriptions 를 도착 순서대로 합친 기록. */
    fun timeline(id: PeripheralId): List<TimelinePoint> = timelines[id].orEmpty()

    /** core.officialEvents 의 Connection 중 [id] 의 상태 순서(발행마다 하나 — StateFlow 처럼 합쳐지지 않는다). */
    fun connEvents(id: PeripheralId): List<ConnectionState> =
        events.filterIsInstance<OfficialBleClientEvent.Connection>().filter { it.peripheralId == id }.map { it.state }

    /**
     * 공개 connect 를 걸고 현재 시점까지만 실행한다(Ready 를 기대하지 않는 시나리오용).
     * [options] 가 null 이면 하네스 생성 시의 기본 연결 옵션으로 건다.
     */
    fun connect(id: PeripheralId, options: BleConnectOptions? = null): ConnectionState {
        val session = session(id)
        val effective = options ?: defaultOptions
        testScope.launch { session.connect(effective) }
        testScope.runCurrent()
        return session.connectionState.value
    }

    /**
     * 공개 connect → 스크립트대로 콜백 → Ready 까지 진행. 10ms 씩 최대 [MAX_WAIT_STEPS] 번 진행해도
     * Ready 가 아니면 테스트를 실패시킨다(불변식 판정과 별개인 시나리오 전제).
     */
    fun connectToReady(id: PeripheralId, options: BleConnectOptions? = null): ConnectionState {
        connect(id, options)
        waitUntil("connect 가 Ready 에 도달") { session(id).connectionState.value == ConnectionState.Ready }
        return session(id).connectionState.value
    }

    /** [condition] 이 참이 될 때까지 10ms 씩 진행한다. 상한을 넘기면 [what] 을 담아 실패한다. */
    fun waitUntil(what: String, condition: () -> Boolean) {
        testScope.runCurrent()
        var steps = 0
        while (!condition()) {
            if (++steps > MAX_WAIT_STEPS) {
                fail("대기 상한 초과: $what (상태 기록=${stateHistory.values} calls=${fake.calls})")
            }
            testScope.advanceTimeBy(10)
            testScope.runCurrent()
        }
    }

    /** 공개 subscribe 를 걸고 CCCD 스크립트를 흘린다. 완료되지 않으면 예외를 담은 Result 를 준다. */
    fun subscribe(id: PeripheralId, ref: CharacteristicRef): Result<Unit>? {
        val job = testScope.async { runCatching { session(id).subscribe(ref) } }
        testScope.runCurrent()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return if (job.isCompleted) job.getCompleted() else null
    }

    /** 사용자 disconnect 를 시작하고 현재 시점까지 실행. */
    fun disconnect(id: PeripheralId) {
        testScope.launch { session(id).disconnect() }
        testScope.runCurrent()
    }

    /** 가상 시간을 모든 deadline 너머로 보낸다. */
    fun advancePastAllDeadlines() {
        testScope.advanceUntilIdle()
        testScope.runCurrent()
    }

    /** 런타임을 닫고 fake 가 예약해 둔 콜백을 모두 취소한다(닫힌 런타임에 콜백이 들어가지 않게). */
    fun close() {
        client.close()
        testScope.runCurrent()
        fakeJob.cancel()
        testScope.runCurrent()
    }

    companion object {
        const val MAX_WAIT_STEPS = 500
    }
}

/** [Harness.timeline] 의 한 점 — 상태 게시, 구독 집합 게시 또는 앱에 전달된 notification. */
internal sealed interface TimelinePoint {
    data class State(val state: ConnectionState) : TimelinePoint
    data class Active(val refs: Set<CharacteristicRef>) : TimelinePoint
    class Notification(val ref: CharacteristicRef, val bytes: ByteArray) : TimelinePoint
}

/** 하네스 생성. [config] 의 dispatcher 는 테스트 스케줄러의 StandardTestDispatcher 로 덮어쓴다. */
internal fun TestScope.harness(
    config: BleConfig = BleConfig(),
    /** [Harness.connect]·[Harness.connectToReady] 에 옵션을 주지 않았을 때 쓰는 연결 옵션. */
    options: BleConnectOptions = BleConnectOptions(),
    /** true 면 재발견을 실제 Scanner 소비자로 배선한다(fake 스캔 문 사용). 기본은 재발견 없음. */
    scannerRediscovery: Boolean = false,
): Harness {
    val dispatcher = StandardTestDispatcher(testScheduler)
    // fake 콜백 scope — Harness.close() 가 취소한다.
    val fakeJob = SupervisorJob()
    val fake = FakeAndroidPlatform(CoroutineScope(dispatcher + fakeJob))
    val core = BleRuntime(
        config = config.copy(dispatcher = dispatcher),
        gattDriver = fake,
        gattOperationTargetResolver = fake.resolver,
        adapterState = fake.adapterProbe,
        scanPort = fake.scanPort,
        scannerRediscovery = scannerRediscovery,
    )
    fake.runtime = core
    val harness = Harness(this, core, DefaultBleClient(core), fake, options, fakeJob)
    runCurrent()
    return harness
}
