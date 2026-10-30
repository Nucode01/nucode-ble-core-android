package kr.co.nucode.ble.rssi

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kr.co.nucode.ble.internal.rssi.RssiTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 플랫폼 RSSI 요청 차례(CANDIDATE-34): 차례를 놓지 못하면 프로세스의 모든 readRssi 가 멈추므로 넘김 규칙을 고정한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class RssiTurnTest {

    @Test
    fun `차례는 요청한 순서대로 넘어간다`() = runTest {
        val turn = RssiTurn()
        val order = mutableListOf<Int>()
        turn.acquire()
        (1..3).forEach { i ->
            launch {
                turn.acquire()
                order += i
                turn.release()
            }
        }
        runCurrent()
        assertEquals(emptyList<Int>(), order)
        turn.release()
        runCurrent()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `기다리다 취소된 대기자는 줄에서 빠진다`() = runTest {
        val turn = RssiTurn()
        var second = false
        turn.acquire()
        val first = launch { turn.acquire() }
        launch {
            turn.acquire()
            second = true
            turn.release()
        }
        runCurrent()
        first.cancel()
        runCurrent()
        turn.release()
        runCurrent()
        assertTrue(second)
        assertFalse(first.isActive)
    }

    @Test
    fun `차례를 넘겨받은 직후 취소된 대기자는 차례를 다음 대기자에게 넘긴다`() = runTest {
        val turn = RssiTurn()
        var second = false
        turn.acquire()
        val first = launch { turn.acquire() }
        launch {
            turn.acquire()
            second = true
            turn.release()
        }
        runCurrent()
        // first 가 차례를 받았지만 재개하기 전에 취소된다 — 받은 차례는 second 에게 가야 한다.
        turn.release()
        first.cancel()
        runCurrent()
        assertTrue(second)
        // 마지막 대기자가 놓은 뒤 차례는 비어 있다.
        withTimeout(1) { turn.acquire() }
        turn.release()
    }

    @Test
    fun `여러 스레드에서 동시에 얻고 놓아도 한 번에 하나다`() = runBlocking {
        val turn = RssiTurn()
        val inside = AtomicInteger()
        val most = AtomicInteger()
        withContext(Dispatchers.Default) {
            repeat(500) {
                launch {
                    turn.acquire()
                    try {
                        most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        yield()
                        inside.decrementAndGet()
                    } finally {
                        turn.release()
                    }
                }
            }
        }
        assertEquals(1, most.get())
        withTimeout(1_000) { turn.acquire() }
        turn.release()
    }
}
