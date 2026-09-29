package kr.co.nucode.ble.internal.core.scan

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleScanMode
import kr.co.nucode.ble.BleScanOptions
import kr.co.nucode.ble.BleServiceDataFilter
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanState
import kr.co.nucode.ble.Severity
import kr.co.nucode.ble.internal.model.PlatformScanSpec
import kr.co.nucode.ble.internal.model.ScanObservation
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 스캔 Core 의 순수 `step` 표 시험. 코루틴·가짜 포트 없이 값만 넣고 값만 본다. */
class ScanCoreTest {

    private val addrA = PeripheralId("AA:BB:CC:DD:EE:01")
    private val addrB = PeripheralId("AA:BB:CC:DD:EE:02")
    private val serviceUuid = BleUuid(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
    private val otherUuid = BleUuid(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))

    private fun request(mode: BleScanMode = BleScanMode.Balanced, filter: BleScanFilter = BleScanFilter()) =
        ScanRequest(filter, BleScanOptions(mode))

    private val all = request()
    private val serviceRequest = request(filter = BleScanFilter(serviceUuids = listOf(serviceUuid)))

    private fun step(state: ScanCoreState, event: ScanEvent) = ScanCore.step(state, event)

    /** 사용자 시작 + 플랫폼 성공까지. */
    private fun scanning(request: ScanRequest = all): ScanCoreState {
        val begun = step(ScanCoreState(), ScanEvent.StartRequested(request))
        return step(begun.state, ScanEvent.PlatformStartFinished(StartResult.Ok)).state
    }

    /** 재발견 등록 하나 + 턴 끝 reconcile(시작이 필요하면 Ok 로 끝낸다). */
    private fun withRediscovery(state: ScanCoreState, address: PeripheralId = addrA): ScanCoreState {
        val registered = step(state, ScanEvent.RediscoveryRequested(address)).state
        val due = step(registered, ScanEvent.ReconcileDue)
        return if (due.effects.any { it is ScanEffect.StartPlatform }) {
            step(due.state, ScanEvent.PlatformStartFinished(StartResult.Ok)).state
        } else {
            due.state
        }
    }

    private fun observation(id: PeripheralId = addrA, name: String? = null, rssi: Int = -50) = ScanObservation(
        id,
        rssi,
        AdvertisementData(name, emptySet(), emptyMap(), emptyMap(), null, null),
    )

    private fun adapterFailure(cause: AdapterState) = FailureMapper.fromAdapterState(cause)
        .copy(code = FailureCode("SCAN_STOPPED_BY_ADAPTER"), phase = FailurePhase.Scan)

    private fun unavailable(failure: kr.co.nucode.ble.BleFailure) = RediscoveryOutcome.Unavailable(failure)

    @Test
    fun S01_initialStateIsIdleWithoutPlatform() {
        val state = ScanCoreState()
        assertEquals(ScanState.Idle(null), state.published)
        assertFalse(ScanCore.isUserScanning(state))
        assertNull(state.platform)
        assertNull(state.userRequest)
        assertTrue(state.rediscoveries.isEmpty())
    }

    @Test
    fun S02_startPublishesAfterPlatformStartSucceeds() {
        val begun = step(ScanCoreState(), ScanEvent.StartRequested(all))
        assertEquals(
            listOf(ScanEffect.StartPlatform(PlatformScanSpec(all.filter, emptySet(), BleScanMode.Balanced), 1L, stopFirst = false)),
            begun.effects,
        )
        assertEquals(ScanState.Idle(null), begun.state.published)
        val done = step(begun.state, ScanEvent.PlatformStartFinished(StartResult.Ok))
        assertEquals(listOf(ScanEffect.PublishState(ScanState.Scanning(all)), ScanEffect.Reply(null)), done.effects)
        assertTrue(ScanCore.isUserScanning(done.state))
        assertEquals(1L, done.state.generation)
        assertEquals(PlatformScanSpec(all.filter, emptySet(), BleScanMode.Balanced), done.state.platform)
    }

    @Test
    fun S03_sameRequestIsANoOp() {
        val state = scanning()
        val again = step(state, ScanEvent.StartRequested(request()))
        assertEquals(listOf(ScanEffect.Reply(null)), again.effects)
        assertEquals(state, again.state)
    }

    @Test
    fun S04_differentRequestIsRejectedWithErrorDiagnostic() {
        val state = scanning()
        val other = step(state, ScanEvent.StartRequested(request(mode = BleScanMode.LowLatency)))
        val diagnostic = other.effects[0] as ScanEffect.Diagnostic
        val reply = other.effects[1] as ScanEffect.Reply
        assertEquals(2, other.effects.size)
        assertEquals(Severity.Error, diagnostic.severity)
        assertEquals(FailureCode("SCAN_ALREADY_RUNNING"), diagnostic.failure.code)
        assertEquals(diagnostic.failure, reply.failure)
        assertEquals(FailurePhase.Scan, diagnostic.failure.phase)
        assertEquals(state, other.state)
    }

    @Test
    fun S05_stopEndsOnlyTheUserConsumer() {
        val state = scanning()
        val stopped = step(state, ScanEvent.StopRequested)
        assertEquals(listOf(ScanEffect.StopPlatform, ScanEffect.PublishState(ScanState.Idle(null))), stopped.effects)
        assertNull(stopped.state.platform)
        assertNull(stopped.state.userRequest)

        val idle = step(ScanCoreState(), ScanEvent.StopRequested)
        assertTrue(idle.effects.isEmpty())

        val shared = withRediscovery(state)
        val keep = step(shared, ScanEvent.StopRequested)
        assertEquals(listOf(ScanEffect.PublishState(ScanState.Idle(null))), keep.effects)
        assertEquals(shared.platform, keep.state.platform)
    }

    @Test
    fun S06_adapterLostEndsEveryConsumer() {
        val state = withRediscovery(scanning())
        val failure = adapterFailure(AdapterState.PoweredOff)
        val lost = step(state, ScanEvent.AdapterLost(AdapterState.PoweredOff))
        assertEquals(
            listOf(
                ScanEffect.StopPlatform,
                ScanEffect.EndRediscovery(1L, unavailable(failure)),
                ScanEffect.PublishState(ScanState.Idle(failure)),
                ScanEffect.ClearStartLedger,
                ScanEffect.Diagnostic(Severity.Warning, failure),
            ),
            lost.effects,
        )
        assertEquals(FailureCode("SCAN_STOPPED_BY_ADAPTER"), failure.code)
        assertEquals(FailurePhase.Scan, failure.phase)
        assertEquals(FailureMapper.fromAdapterState(AdapterState.PoweredOff).retryability, failure.retryability)
        assertNull(lost.state.platform)

        val unauthorized = AdapterState.Unauthorized(true)
        val kept = step(state, ScanEvent.AdapterLost(unauthorized))
        assertFalse(kept.effects.contains(ScanEffect.ClearStartLedger))
        assertTrue(kept.effects.contains(ScanEffect.Diagnostic(Severity.Warning, adapterFailure(unauthorized))))

        // 사용자 스캔이 없으면 진단이 없다.
        assertTrue(step(ScanCoreState(), ScanEvent.AdapterLost(unauthorized)).effects.isEmpty())
        val rediscoveryOnly = withRediscovery(ScanCoreState())
        assertEquals(
            listOf(ScanEffect.StopPlatform, ScanEffect.EndRediscovery(1L, unavailable(adapterFailure(unauthorized)))),
            step(rediscoveryOnly, ScanEvent.AdapterLost(unauthorized)).effects,
        )
    }

    @Test
    fun S07_rediscoveryAcquiresAPlatformScanWithoutPublishing() {
        val registered = step(ScanCoreState(), ScanEvent.RediscoveryRequested(addrA))
        assertEquals(listOf(ScanEffect.Registered(1L), ScanEffect.ScheduleReconcile), registered.effects)
        val due = step(registered.state, ScanEvent.ReconcileDue)
        assertEquals(
            listOf(ScanEffect.StartPlatform(PlatformScanSpec(null, setOf(addrA), BleScanMode.LowLatency), 1L, stopFirst = false)),
            due.effects,
        )
        val done = step(due.state, ScanEvent.PlatformStartFinished(StartResult.Ok))
        assertTrue(done.effects.isEmpty())
        assertEquals(ScanState.Idle(null), done.state.published)

        // 전체 LowLatency 스캔이 덮으면 재시작이 없다.
        val covering = scanning(request(mode = BleScanMode.LowLatency))
        val joined = step(step(covering, ScanEvent.RediscoveryRequested(addrA)).state, ScanEvent.ReconcileDue)
        assertTrue(joined.effects.isEmpty())

        // 사용자가 없을 때 마지막 해제는 플랫폼만 멈춘다.
        val released = step(done.state, ScanEvent.RediscoveryReleased(1L))
        assertEquals(listOf(ScanEffect.StopPlatform), released.effects)
    }

    @Test
    fun S08_matchesFollowsFilterRules() {
        fun adv(
            name: String? = null,
            uuids: Set<BleUuid> = emptySet(),
            manufacturer: Map<Int, ByteArray> = emptyMap(),
            service: Map<BleUuid, ByteArray> = emptyMap(),
        ) = AdvertisementData(name, uuids, manufacturer, service, null, null)

        assertTrue(ScanCore.matches(BleScanFilter(), adv()))
        val prefix = BleScanFilter(namePrefix = "nRF")
        assertTrue(ScanCore.matches(prefix, adv(name = "nRF52")))
        assertFalse(ScanCore.matches(prefix, adv(name = "other")))
        assertFalse(ScanCore.matches(prefix, adv(name = null)))

        val uuidOrManufacturer = BleScanFilter(
            serviceUuids = listOf(serviceUuid),
            manufacturerData = listOf(BleManufacturerDataFilter(0x0059, byteArrayOf(0x10, 0x00), byteArrayOf(0xF0.toByte(), 0x00))),
        )
        assertTrue(ScanCore.matches(uuidOrManufacturer, adv(uuids = setOf(serviceUuid))))
        assertTrue(ScanCore.matches(uuidOrManufacturer, adv(manufacturer = mapOf(0x0059 to byteArrayOf(0x1F, 0x77)))))
        assertFalse(ScanCore.matches(uuidOrManufacturer, adv(manufacturer = mapOf(0x0059 to byteArrayOf(0x2F, 0x77)))))
        assertFalse(ScanCore.matches(uuidOrManufacturer, adv(uuids = setOf(otherUuid))))

        val serviceData = BleScanFilter(serviceData = listOf(BleServiceDataFilter(serviceUuid, byteArrayOf(1, 2))))
        assertTrue(ScanCore.matches(serviceData, adv(service = mapOf(serviceUuid to byteArrayOf(1, 2, 3)))))
        assertFalse(ScanCore.matches(serviceData, adv(service = mapOf(serviceUuid to byteArrayOf(1)))))

        // namePrefix 는 AND, 나머지 조건은 OR.
        val both = BleScanFilter(serviceUuids = listOf(serviceUuid), namePrefix = "nRF")
        assertTrue(ScanCore.matches(both, adv(name = "nRF", uuids = setOf(serviceUuid))))
        assertFalse(ScanCore.matches(both, adv(name = "x", uuids = setOf(serviceUuid))))
        assertFalse(ScanCore.matches(both, adv(name = "nRF")))
    }

    @Test
    fun S09_resultsAreRoutedByGeneration() {
        val state = scanning(serviceRequest.let { request(filter = BleScanFilter(namePrefix = "nRF")) })
        val obs = observation(name = "nRF52")
        assertEquals(listOf(ScanEffect.Deliver(obs)), step(state, ScanEvent.PlatformResult(1L, obs)).effects)
        assertTrue(step(state, ScanEvent.PlatformResult(1L, observation(name = "no"))).effects.isEmpty())
        assertTrue(step(state, ScanEvent.PlatformResult(0L, obs)).effects.isEmpty())
        assertTrue(step(state, ScanEvent.PlatformResult(2L, obs)).effects.isEmpty())
        val stopped = step(state, ScanEvent.StopRequested).state
        assertTrue(step(stopped, ScanEvent.PlatformResult(1L, obs)).effects.isEmpty())

        val shared = withRediscovery(state)
        val gen = shared.generation
        val found = step(shared, ScanEvent.PlatformResult(gen, observation(id = PeripheralId("aa:bb:cc:dd:ee:01"), name = "nRF52")))
        assertEquals(ScanEffect.EndRediscovery(1L, RediscoveryOutcome.Found), found.effects[0])
        assertTrue(found.effects[1] is ScanEffect.Deliver)
        val unmatched = step(shared, ScanEvent.PlatformResult(gen, observation(name = "zzz")))
        assertEquals(listOf<ScanEffect>(ScanEffect.EndRediscovery(1L, RediscoveryOutcome.Found)), unmatched.effects)

        val onlyRediscovery = withRediscovery(ScanCoreState())
        val foundOnly = step(onlyRediscovery, ScanEvent.PlatformResult(onlyRediscovery.generation, observation()))
        assertEquals(listOf<ScanEffect>(ScanEffect.EndRediscovery(1L, RediscoveryOutcome.Found)), foundOnly.effects)
    }

    @Test
    fun S10_rssiOf127IsDelivered() {
        val state = scanning()
        val obs = observation(rssi = 127)
        assertEquals(listOf(ScanEffect.Deliver(obs)), step(state, ScanEvent.PlatformResult(state.generation, obs)).effects)
    }

    @Test
    fun S11_runningScanFailureEndsEveryConsumer() {
        val state = withRediscovery(scanning())
        val failure = FailureMapper.scanRuntimeFailed(2)
        val failed = step(state, ScanEvent.PlatformFailed(state.generation, failure))
        assertEquals(
            listOf(
                ScanEffect.Diagnostic(Severity.Error, failure),
                ScanEffect.StopPlatform,
                ScanEffect.EndRediscovery(1L, unavailable(failure)),
                ScanEffect.PublishState(ScanState.Idle(failure)),
            ),
            failed.effects,
        )
        assertEquals(ScanState.Idle(failure), failed.state.published)

        val stale = step(state, ScanEvent.PlatformFailed(state.generation - 1, failure))
        assertEquals(listOf<ScanEffect>(ScanEffect.Diagnostic(Severity.Warning, failure)), stale.effects)
        assertEquals(state, stale.state)
        val alone = scanning()
        val afterStop = step(step(alone, ScanEvent.StopRequested).state, ScanEvent.PlatformFailed(alone.generation, failure))
        assertEquals(listOf<ScanEffect>(ScanEffect.Diagnostic(Severity.Warning, failure)), afterStop.effects)
    }

    @Test
    fun S12_joinRestartKeepsPublishedStateAndFailureEndsTheUserScan() {
        val state = scanning(serviceRequest)
        val registered = step(state, ScanEvent.RediscoveryRequested(addrA)).state
        val due = step(registered, ScanEvent.ReconcileDue)
        assertEquals(
            listOf(
                ScanEffect.StartPlatform(
                    PlatformScanSpec(serviceRequest.filter, setOf(addrA), BleScanMode.LowLatency),
                    state.generation + 1,
                    stopFirst = true,
                ),
            ),
            due.effects,
        )
        assertEquals(ScanState.Scanning(serviceRequest), due.state.published)
        val failure = FailureMapper.scanStartFailed()
        val failed = step(due.state, ScanEvent.PlatformStartFinished(StartResult.Failed(failure)))
        assertEquals(
            listOf(
                ScanEffect.Diagnostic(Severity.Error, failure),
                ScanEffect.EndRediscovery(1L, unavailable(failure)),
                ScanEffect.PublishState(ScanState.Idle(failure)),
            ),
            failed.effects,
        )
        assertNull(failed.state.platform)
        assertNull(failed.state.userRequest)
    }

    @Test
    fun S13_closeEndsRediscoveriesAndStopsWithoutUser() {
        val failure = FailureMapper.scanStartFailed()
        val noUser = withRediscovery(withRediscovery(ScanCoreState(), addrA), addrB)
        assertEquals(
            listOf(
                ScanEffect.StopPlatform,
                ScanEffect.EndRediscovery(1L, unavailable(failure)),
                ScanEffect.EndRediscovery(2L, unavailable(failure)),
            ),
            step(noUser, ScanEvent.CloseRequested(failure)).effects,
        )
        val withUser = withRediscovery(withRediscovery(scanning(), addrA), addrB)
        assertEquals(
            listOf(
                ScanEffect.EndRediscovery(1L, unavailable(failure)),
                ScanEffect.EndRediscovery(2L, unavailable(failure)),
            ),
            step(withUser, ScanEvent.CloseRequested(failure)).effects,
        )
    }

    @Test
    fun SP01_userStartThrottledRejectsWithoutTouchingPlatform() {
        val begun = step(ScanCoreState(), ScanEvent.StartRequested(all))
        val done = step(begun.state, ScanEvent.PlatformStartFinished(StartResult.Throttled(12_345L)))
        val diagnostic = done.effects[0] as ScanEffect.Diagnostic
        assertEquals(Severity.Error, diagnostic.severity)
        assertEquals(FailureCode("SCAN_START_THROTTLED"), diagnostic.failure.code)
        assertEquals(listOf(diagnostic, ScanEffect.Reply(diagnostic.failure)), done.effects)
        assertEquals(
            "Platform scan start budget exhausted (5 starts within 31000 ms); next start possible in 12345 ms",
            diagnostic.failure.message,
        )
        assertEquals(ScanState.Idle(null), done.state.published)
        assertNull(done.state.userRequest)
        assertNull(done.state.platform)
        assertEquals(0L, done.state.generation)
    }

    @Test
    fun SP02_sameTurnRediscoveriesShareOneReconcile() {
        var state = ScanCoreState()
        val effects = listOf(addrA, addrB, PeripheralId("AA:BB:CC:DD:EE:03")).map { address ->
            val next = step(state, ScanEvent.RediscoveryRequested(address))
            state = next.state
            next.effects
        }
        assertEquals(1, effects.flatten().count { it == ScanEffect.ScheduleReconcile })
        assertEquals(listOf(ScanEffect.Registered(3L)), effects[2])
        val due = step(state, ScanEvent.ReconcileDue)
        assertEquals(1, due.effects.count { it is ScanEffect.StartPlatform })
        assertEquals(
            PlatformScanSpec(null, setOf(addrA, addrB, PeripheralId("AA:BB:CC:DD:EE:03")), BleScanMode.LowLatency),
            (due.effects.single() as ScanEffect.StartPlatform).spec,
        )
        assertFalse(due.state.reconcileScheduled)
    }

    @Test
    fun SP03_throttledReconcileEndsOnlyUncoveredRediscoveries() {
        val throttle = StartResult.Throttled(500L)
        fun run(state: ScanCoreState): ScanStep {
            val due = step(step(state, ScanEvent.RediscoveryRequested(addrA)).state, ScanEvent.ReconcileDue)
            return step(due.state, ScanEvent.PlatformStartFinished(throttle))
        }
        // Balanced 전체 스캔은 재발견을 덮지 못한다.
        val balanced = run(scanning())
        assertEquals(1, balanced.effects.size)
        val end = balanced.effects.single() as ScanEffect.EndRediscovery
        assertEquals(1L, end.id)
        assertEquals(FailureCode("SCAN_START_THROTTLED"), (end.outcome as RediscoveryOutcome.Unavailable).failure.code)
        assertTrue(ScanCore.isUserScanning(balanced.state))
        assertEquals(ScanState.Scanning(all), balanced.state.published)
        // 도는 스캔이 없으면 덮지 못한다.
        assertEquals(1, run(ScanCoreState()).effects.size)
        // LowLatency 전체 스캔은 덮으므로 이 경로에 오지 않는다.
        val covering = scanning(request(mode = BleScanMode.LowLatency))
        assertTrue(step(step(covering, ScanEvent.RediscoveryRequested(addrA)).state, ScanEvent.ReconcileDue).effects.isEmpty())
    }

    @Test
    fun SP04_coversRulesDecideRestarts() {
        fun restarts(state: ScanCoreState, event: ScanEvent): Boolean {
            val next = step(step(state, event).state, ScanEvent.ReconcileDue)
            return next.effects.any { it is ScanEffect.StartPlatform }
        }
        // 모드 순위: 도는 쪽이 낮으면 재시작.
        assertTrue(restarts(scanning(request(mode = BleScanMode.LowPower)), ScanEvent.RediscoveryRequested(addrA)))
        assertTrue(restarts(scanning(request(mode = BleScanMode.Balanced)), ScanEvent.RediscoveryRequested(addrA)))
        assertFalse(restarts(scanning(request(mode = BleScanMode.LowLatency)), ScanEvent.RediscoveryRequested(addrA)))
        // 필터 있는 사용자 스캔은 주소가 없으면 재시작, 이미 포함하면 덮는다.
        val filtered = scanning(request(mode = BleScanMode.LowLatency, filter = serviceRequest.filter))
        assertTrue(restarts(filtered, ScanEvent.RediscoveryRequested(addrA)))
        val joined = withRediscovery(filtered)
        assertFalse(restarts(joined, ScanEvent.RediscoveryRequested(addrA)))
        // 재발견이 끝나도 모드를 내리려 재시작하지 않는다.
        val released = step(joined, ScanEvent.RediscoveryReleased(1L)).state
        assertTrue(step(released, ScanEvent.ReconcileDue).effects.isEmpty())
        val downgraded = withRediscovery(scanning(request(mode = BleScanMode.Balanced, filter = serviceRequest.filter)))
        assertEquals(BleScanMode.LowLatency, downgraded.platform?.mode)
        val afterRelease = step(downgraded, ScanEvent.RediscoveryReleased(1L))
        assertTrue(afterRelease.effects.isEmpty())
        assertTrue(step(afterRelease.state, ScanEvent.ReconcileDue).effects.isEmpty())
        // 주소 전용 스캔은 사용자 필터를 담지 못하므로 사용자 시작이 합집합으로 재시작한다.
        val userWhileRediscovering = step(withRediscovery(ScanCoreState()), ScanEvent.StartRequested(request(mode = BleScanMode.LowLatency)))
        assertEquals(
            listOf(ScanEffect.StartPlatform(PlatformScanSpec(all.filter, emptySet(), BleScanMode.LowLatency), 2L, stopFirst = true)),
            userWhileRediscovering.effects,
        )
        assertEquals(ScanState.Idle(null), userWhileRediscovering.state.published)
    }

    @Test
    fun SP05_invalidAddressIsRefusedWithoutRegistering() {
        val refused = step(ScanCoreState(), ScanEvent.RediscoveryRequested(PeripheralId("not-a-mac")))
        val effect = refused.effects.single() as ScanEffect.RediscoveryRefused
        assertEquals(FailureCode("SCAN_START_FAILED"), effect.failure.code)
        assertEquals(ScanCoreState(), refused.state)
        val running = scanning()
        assertEquals(running, step(running, ScanEvent.RediscoveryRequested(PeripheralId("AA:BB"))).state)

        val lower = step(ScanCoreState(), ScanEvent.RediscoveryRequested(PeripheralId("aa:bb:cc:dd:ee:01")))
        val due = step(lower.state, ScanEvent.ReconcileDue)
        assertEquals(setOf(addrA), (due.effects.single() as ScanEffect.StartPlatform).spec.addresses)
    }

    @Test
    fun SP06_ledgerIsClearedOnlyForPoweredOffAndResetting() {
        val state = scanning()
        listOf(AdapterState.PoweredOff, AdapterState.Resetting).forEach {
            assertTrue(step(state, ScanEvent.AdapterLost(it)).effects.contains(ScanEffect.ClearStartLedger))
        }
        listOf(AdapterState.Unknown, AdapterState.Unsupported, AdapterState.Unauthorized(true), AdapterState.Unauthorized(false)).forEach {
            assertFalse(step(state, ScanEvent.AdapterLost(it)).effects.contains(ScanEffect.ClearStartLedger))
        }
    }

    @Test
    fun SP07_startBudgetWaitAndRecording() {
        assertNull(ScanCore.startBudgetWait(emptyList(), 100L))
        assertNull(ScanCore.startBudgetWait(listOf(0L, 1L, 2L, 3L), 10L))
        val five = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)
        assertEquals(1_000L + 31_000L - 6_000L + 1, ScanCore.startBudgetWait(five, 6_000L))
        assertNull(ScanCore.startBudgetWait(five, 1_000L + 31_000L + 1))
        assertEquals(1L, ScanCore.startBudgetWait(five, 1_000L + 31_000L))
        assertEquals(listOf(2_000L, 3_000L, 4_000L, 5_000L, 9L), ScanCore.recordStart(five, 9L))
        assertEquals(listOf(9L), ScanCore.recordStart(emptyList(), 9L))
        assertEquals(5, ScanCore.START_BUDGET)
        assertEquals(30_000L, ScanCore.START_WINDOW_MILLIS)
        assertEquals(1_000L, ScanCore.START_WINDOW_MARGIN_MILLIS)
    }

    @Test
    fun SP08_threwRollsBackTheUserRequestOrFailsConsumers() {
        val begun = step(ScanCoreState(), ScanEvent.StartRequested(all))
        val threw = step(begun.state, ScanEvent.PlatformStartFinished(StartResult.Threw(afterStop = true)))
        assertTrue(threw.effects.isEmpty())
        assertNull(threw.state.userRequest)
        assertEquals(ScanState.Idle(null), threw.state.published)

        val failure = FailureMapper.scanStartFailed()
        val state = scanning(serviceRequest)
        val due = step(step(state, ScanEvent.RediscoveryRequested(addrA)).state, ScanEvent.ReconcileDue)
        val failedByThrow = step(due.state, ScanEvent.PlatformStartFinished(StartResult.Threw(afterStop = true)))
        val failedByResult = step(due.state, ScanEvent.PlatformStartFinished(StartResult.Failed(failure)))
        assertEquals(failedByResult.effects, failedByThrow.effects)
        assertEquals(failedByResult.state, failedByThrow.state)
    }

    @Test
    fun SP09_generationCountsEveryAttemptOutsideThrottling() {
        fun started(result: StartResult): ScanCoreState {
            val begun = step(ScanCoreState(generation = 7L), ScanEvent.StartRequested(all))
            return step(begun.state, ScanEvent.PlatformStartFinished(result)).state
        }
        assertEquals(8L, started(StartResult.Ok).generation)
        assertEquals(8L, started(StartResult.Failed(FailureMapper.scanStartFailed())).generation)
        assertEquals(8L, started(StartResult.Threw(afterStop = true)).generation)
        assertEquals(7L, started(StartResult.Threw(afterStop = false)).generation)
        assertEquals(7L, started(StartResult.Throttled(1L)).generation)
        // 합류 재시작도 새 값을 쓴다.
        val running = scanning(serviceRequest)
        val due = step(step(running, ScanEvent.RediscoveryRequested(addrA)).state, ScanEvent.ReconcileDue)
        assertEquals(running.generation + 1, (due.effects.single() as ScanEffect.StartPlatform).generation)
        assertEquals(running.generation + 1, step(due.state, ScanEvent.PlatformStartFinished(StartResult.Ok)).state.generation)
    }

    @Test
    fun SP10_endRequestedCompletesEveryConsumerOfTheAddress() {
        val state = withRediscovery(withRediscovery(withRediscovery(ScanCoreState(), addrA), addrB), addrA)
        val ended = step(state, ScanEvent.RediscoveryEndRequested(PeripheralId("aa:bb:cc:dd:ee:01"), RediscoveryOutcome.NotFound))
        assertEquals(
            listOf(
                ScanEffect.EndRediscovery(1L, RediscoveryOutcome.NotFound),
                ScanEffect.EndRediscovery(3L, RediscoveryOutcome.NotFound),
            ),
            ended.effects,
        )
        assertEquals(state, ended.state)
        val none = step(state, ScanEvent.RediscoveryEndRequested(PeripheralId("AA:BB:CC:DD:EE:99"), RediscoveryOutcome.NotFound))
        assertTrue(none.effects.isEmpty())
    }

    @Test
    fun SP11_releaseStopsOnlyWhenNobodyIsLeft() {
        val two = withRediscovery(withRediscovery(ScanCoreState(), addrA), addrB)
        val first = step(two, ScanEvent.RediscoveryReleased(1L))
        assertTrue(first.effects.isEmpty())
        assertEquals(listOf(ScanEffect.StopPlatform), step(first.state, ScanEvent.RediscoveryReleased(2L)).effects)

        val withUser = withRediscovery(scanning())
        val released = step(withUser, ScanEvent.RediscoveryReleased(1L))
        assertTrue(released.effects.isEmpty())
        assertEquals(withUser.platform, released.state.platform)
    }
}
