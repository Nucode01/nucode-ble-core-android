package kr.co.nucode.ble.internal.rssi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * 플랫폼 RSSI 요청 차례 하나(CANDIDATE-34, states.md §8). Android 스택의 RSSI 대기 슬롯(`BTM_ReadRSSI`)은 어댑터 전체에
 * 하나이고, 차 있으면 요청을 응답 없이 버린다. 그래서 운영 조립은 프로세스에 하나 만들어 모든 BleRuntime 에 넘긴다.
 * 시험·기본값은 런타임마다 하나다. 여러 런타임이 각자 코어 스레드에서 부르므로 상태는 `this` 잠금 안에서만 바꾼다.
 * 차례는 요청한 순서대로 넘긴다.
 */
internal class RssiTurn {
    private var held = false
    private val queue = ArrayDeque<CompletableDeferred<Unit>>()

    /** 차례를 얻을 때까지 멈춘다. 기다리다 취소되면 줄에서 빠지고, 이미 넘겨받은 차례는 다음 대기자에게 넘긴다. */
    suspend fun acquire() {
        val ticket = synchronized(this) {
            if (!held) {
                held = true
                return
            }
            CompletableDeferred<Unit>().also { queue.addLast(it) }
        }
        try {
            ticket.await()
        } catch (e: CancellationException) {
            val granted = synchronized(this) { !queue.remove(ticket) }
            if (granted) release()
            throw e
        }
    }

    /** 차례를 다음 대기자에게 넘기거나 비운다. [acquire] 가 돌아온 쪽이 한 번만 부른다. */
    fun release() {
        val next = synchronized(this) {
            queue.removeFirstOrNull().also { if (it == null) held = false }
        }
        next?.complete(Unit)
    }
}
