package kr.co.nucode.ble.internal.core.adapter

import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.AwaitDecision
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Lost
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Proceed
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Publish
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Reject
import kr.co.nucode.ble.internal.core.adapter.AdapterEffect.Returned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 어댑터 Core 의 순수 `step` 표 시험. 코루틴·가짜 포트 없이 값만 넣고 값만 본다. */
class AdapterCoreTest {

    private val nonReady = listOf(
        AdapterState.PoweredOff,
        AdapterState.Resetting,
        AdapterState.Unknown,
        AdapterState.Unauthorized(true),
        AdapterState.Unauthorized(false),
        AdapterState.Unsupported,
    )
    private val allValues = nonReady + AdapterState.Ready
    private val operations = listOf(AndroidApiOperation.ConnectGatt, AndroidApiOperation.StartScan)

    private fun at(value: AdapterState) = AdapterCoreState(value)

    private fun admit(value: AdapterState, op: AndroidApiOperation, afterWait: Boolean) =
        AdapterCore.step(at(value), AdapterEvent.Admit(op, afterWait))

    private fun observe(from: AdapterState, to: AdapterState) =
        AdapterCore.step(at(from), AdapterEvent.Observed(AdapterFacts.Probed(to)))

    private fun rejected(step: AdapterStep) = (step.effects.single() as Reject).failure

    @Test
    fun A01_initialIsClassifiedFactsWithoutEffects() {
        val facts = listOf(
            AdapterFacts.Absent,
            AdapterFacts.Present(null, true),
            AdapterFacts.Present(AdapterState.Unauthorized(true), true),
            AdapterFacts.Probed(AdapterState.Unknown),
        )
        facts.forEach { assertEquals(AdapterCoreState(AdapterCore.classify(it)), AdapterCore.initial(it)) }
        assertEquals(AdapterState.Unknown, AdapterCore.initial(AdapterFacts.Probed(AdapterState.Unknown)).current)
    }

    @Test
    fun A02_classifyMapsFactsToValues() {
        assertEquals(AdapterState.Unsupported, AdapterCore.classify(AdapterFacts.Absent))
        val denied = AdapterState.Unauthorized(true)
        assertEquals(denied, AdapterCore.classify(AdapterFacts.Present(denied, true)))
        assertEquals(denied, AdapterCore.classify(AdapterFacts.Present(denied, false)))
        assertEquals(AdapterState.Ready, AdapterCore.classify(AdapterFacts.Present(null, true)))
        assertEquals(AdapterState.PoweredOff, AdapterCore.classify(AdapterFacts.Present(null, false)))
        allValues.forEach { assertEquals(it, AdapterCore.classify(AdapterFacts.Probed(it))) }
    }

    @Test
    fun A03_admitJudgesEachValue() {
        operations.forEach { op ->
            assertEquals(listOf(Proceed), admit(AdapterState.Ready, op, false).effects)
            listOf(AdapterState.PoweredOff, AdapterState.Resetting).forEach {
                assertEquals(FailureCode("ADAPTER_UNAVAILABLE"), rejected(admit(it, op, false)).code)
            }
            assertEquals(FailureCode("BLE_UNSUPPORTED"), rejected(admit(AdapterState.Unsupported, op, false)).code)
            val requestable = rejected(admit(AdapterState.Unauthorized(true), op, false))
            assertEquals(FailureCode("PERMISSION_DENIED"), requestable.code)
            assertEquals(FailurePhase.Adapter, requestable.phase)
            assertEquals(Retryability.AfterEnvironmentChange, requestable.retryability)
            val fixed = rejected(admit(AdapterState.Unauthorized(false), op, false))
            assertEquals(FailureCode("PERMISSION_DENIED"), fixed.code)
            assertEquals(Retryability.Never, fixed.retryability)
        }
    }

    @Test
    fun A04_unknownWaitsOnceThenRejects() {
        operations.forEach { op ->
            assertEquals(listOf(AwaitDecision(2_000L)), admit(AdapterState.Unknown, op, false).effects)
            assertEquals(FailureCode("ADAPTER_UNAVAILABLE"), rejected(admit(AdapterState.Unknown, op, true)).code)
            assertEquals(listOf(Proceed), admit(AdapterState.Ready, op, true).effects)
            assertEquals(FailureCode("ADAPTER_UNAVAILABLE"), rejected(admit(AdapterState.PoweredOff, op, true)).code)
        }
        assertEquals(2_000L, AdapterCore.UNKNOWN_WAIT_MILLIS)
    }

    @Test
    fun A05_leavingReadyReportsLossOnce() {
        nonReady.forEach { to ->
            val step = observe(AdapterState.Ready, to)
            assertEquals(to, step.state.current)
            assertEquals(listOf(Publish(to), Lost(to)), step.effects)
        }
        val between = observe(AdapterState.PoweredOff, AdapterState.Resetting)
        assertEquals(listOf(Publish(AdapterState.Resetting)), between.effects)
        val fromUnknown = observe(AdapterState.Unknown, AdapterState.Unauthorized(true))
        assertEquals(listOf(Publish(AdapterState.Unauthorized(true))), fromUnknown.effects)
    }

    @Test
    fun A06_returningToReadyReportsReturn() {
        nonReady.forEach { from ->
            val step = observe(from, AdapterState.Ready)
            assertEquals(AdapterState.Ready, step.state.current)
            assertEquals(listOf(Publish(AdapterState.Ready), Returned), step.effects)
        }
        assertEquals(listOf(Publish(AdapterState.Ready)), observe(AdapterState.Ready, AdapterState.Ready).effects)
    }

    @Test
    fun A07_queries() {
        allValues.forEach {
            assertEquals(it == AdapterState.Ready, AdapterCore.isReady(it))
            assertEquals(it != AdapterState.Unknown, AdapterCore.isDecided(it))
        }
        assertTrue(AdapterCore.isReady(AdapterState.Ready))
        assertFalse(AdapterCore.isDecided(AdapterState.Unknown))
    }

    @Test
    fun AP01_admitNeverChangesState() {
        allValues.forEach { value ->
            listOf(false, true).forEach { afterWait ->
                operations.forEach { op ->
                    assertEquals(at(value), admit(value, op, afterWait).state)
                }
            }
        }
    }

    @Test
    fun AP02_rejectionsEqualLegacyFailures() {
        operations.forEach { op ->
            listOf(true, false).forEach { canRequest ->
                assertEquals(
                    FailureMapper.permissionDenied(FailurePhase.Adapter, op, canRequest),
                    rejected(admit(AdapterState.Unauthorized(canRequest), op, false)),
                )
            }
            listOf(AdapterState.Unsupported, AdapterState.PoweredOff, AdapterState.Resetting).forEach {
                assertEquals(FailureMapper.fromAdapterState(it), rejected(admit(it, op, false)))
            }
            assertEquals(
                FailureMapper.fromAdapterState(AdapterState.Unknown),
                rejected(admit(AdapterState.Unknown, op, true)),
            )
        }
    }
}
