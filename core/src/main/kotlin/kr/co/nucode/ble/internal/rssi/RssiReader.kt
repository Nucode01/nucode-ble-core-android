package kr.co.nucode.ble.internal.rssi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.gatt.GattFailureFactory
import kr.co.nucode.ble.internal.port.ClockPort
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.port.GattDriverEvent
import kr.co.nucode.ble.normalized
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.internal.session.SessionOwner

/**
 * 런타임 하나의 RSSI 읽기(CANDIDATE-34, states.md §8·§10). GATT 큐 밖이라 진행 중인 write 를 기다리지 않는다.
 * 상태는 코어 스레드에서만 만진다. 같은 기기·같은 연결 generation 의 끝나지 않은 요청이 있으면 그 결과를 나눠 받는다.
 * 요청 코루틴은 [turn] 을 얻은 뒤 플랫폼 요청을 보내고, 결과나 기한(보낸 때부터 [timeoutMillis])까지 차례를 쥔다 —
 * 늦게 온 응답이 다음 요청을 막지 않게. 호출자 취소·무효화는 호출자만 끝내고 그 대기를 끊지 않는다.
 */
internal class RssiReader(
    private val scope: CoroutineScope,
    private val driver: GattDriver,
    private val clock: ClockPort,
    private val turn: RssiTurn,
    private val timeoutMillis: Long,
    private val isClosed: () -> Boolean,
    private val clientClosed: () -> BleFailure,
) {
    private class Request(val generation: Long) {
        val outcome = CompletableDeferred<Int>()
    }

    /** 기기별로 아직 결과가 없는 요청. 결과가 나거나 무효화되면 빠진다. */
    private val requests = HashMap<PeripheralId, Request>()

    /** 플랫폼 요청을 보내고 콜백을 기다리는 기기와 응답 칸. 차례가 하나라 많아야 하나다. */
    private var replySlot: Pair<PeripheralId, CompletableDeferred<GattDriverEvent.RemoteRssiRead>>? = null

    /** 진입 검사를 통과한 Ready [session] 의 RSSI(dBm). 실패는 [BleException] 이다. */
    suspend fun read(session: Session): Int {
        val generation = session.connectionCoordinator.generation
        val request = requests[session.deviceId]?.takeIf { it.generation == generation }
            ?: Request(generation).also { request ->
                requests[session.deviceId] = request
                scope.launch(SessionOwner(session.deviceId)) { run(session, request) }
            }
        return request.outcome.await()
    }

    /** RSSI 콜백. 진행 중 요청의 기기와 같을 때만 그 요청에 붙인다. */
    fun onRemoteRssi(event: GattDriverEvent.RemoteRssiRead) {
        val (deviceId, reply) = replySlot ?: return
        if (event.deviceId.normalized() == deviceId) reply.complete(event)
    }

    /** 세션의 작업이 무효화됐다(해제·링크 유실·close). 기다리는 호출을 끝낸다. 내부 요청은 결과나 기한까지 차례를 쥔다. */
    fun invalidate(session: Session) {
        val request = requests.remove(session.deviceId) ?: return
        val failure = if (isClosed()) clientClosed() else GattFailureFactory.rssiInvalidated(session)
        request.outcome.completeExceptionally(BleException(failure))
    }

    private suspend fun run(session: Session, request: Request) {
        try {
            turn.acquire()
        } catch (e: CancellationException) {
            fail(session.deviceId, request, clientClosed())
            throw e
        }
        try {
            // 차례를 기다리는 동안 무효화됐으면 보내지 않는다.
            if (request.outcome.isActive) send(session, request)
        } finally {
            turn.release()
        }
    }

    private suspend fun send(session: Session, request: Request) {
        val deviceId = session.deviceId
        if (!session.connectionCoordinator.readyFor(request.generation)) {
            return fail(deviceId, request, GattFailureFactory.rssiInvalidated(session))
        }
        val started = try {
            driver.readRemoteRssi(deviceId, session.gatt)
        } catch (e: BleException) {
            return fail(deviceId, request, e.failure)
        } catch (_: SecurityException) {
            return fail(deviceId, request, GattFailureFactory.rssiPermissionDenied(session))
        } catch (_: Exception) {
            false
        }
        if (!started) return fail(deviceId, request, GattFailureFactory.rssiStartRejected(session))
        val reply = CompletableDeferred<GattDriverEvent.RemoteRssiRead>()
        replySlot = deviceId to reply
        val event = try {
            withContext(NonCancellable) { clock.awaitWithinOrNull(timeoutMillis) { reply.await() } }
        } finally {
            if (replySlot?.second === reply) replySlot = null
        }
        when {
            event == null -> fail(deviceId, request, GattFailureFactory.rssiReadTimeout(session))
            event.status != GATT_SUCCESS -> fail(deviceId, request, GattFailureFactory.rssiStatusFailed(session, event.status))
            else -> {
                forget(deviceId, request)
                request.outcome.complete(event.rssi)
            }
        }
    }

    private fun fail(deviceId: PeripheralId, request: Request, failure: BleFailure) {
        forget(deviceId, request)
        request.outcome.completeExceptionally(BleException(failure))
    }

    private fun forget(deviceId: PeripheralId, request: Request) {
        if (requests[deviceId] === request) requests.remove(deviceId)
    }

    private companion object {
        const val GATT_SUCCESS = 0
    }
}
