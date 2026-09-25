package kr.co.nucode.ble.internal

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kr.co.nucode.ble.internal.scan.ScanStartLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStartLedgerTest {

    @Test
    fun `여러 스레드가 동시에 시작해도 창 안 허용 시작은 5회를 넘지 않는다`() {
        // 비원자 구현이면 판정을 함께 통과하도록 시작 람다 안에서 잠깐 머문다. 반복해 경합을 재현한다.
        repeat(ROUNDS) { round ->
            val ledger = ScanStartLedger { 0L }
            val allowed = AtomicInteger()
            val barrier = CyclicBarrier(THREADS)
            val done = CountDownLatch(THREADS)
            repeat(THREADS) {
                thread {
                    try {
                        barrier.await()
                        ledger.tryStart {
                            Thread.sleep(2)
                            allowed.incrementAndGet()
                            true
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("round $round", ScanStartLedger.START_BUDGET, allowed.get())
        }
    }

    @Test
    fun `시작 시각은 플랫폼 시작이 돌아온 뒤 시계로 기록하고 판정은 호출 전 시계로 한다`() {
        // 플랫폼은 scanner 등록 왕복 뒤에 시작을 기록한다 — 시작 람다 안에서 시계를 진행해 흉내 낸다. 첫 시작만 지연을
        // 다르게(500 ms) 둬 "호출 전 시각 + 고정 상수" 기록이 통과하지 못하게 한다.
        var now = 0L
        val ledger = ScanStartLedger { now }
        val firstBegin = now
        val delays = listOf(500L, 2_000L, 2_000L, 2_000L, 2_000L)
        delays.forEach { delay ->
            ledger.tryStart {
                now += delay
                true
            }
            now += 10
        }
        val firstEnd = firstBegin + delays.first()
        val window = ScanStartLedger.START_WINDOW_MILLIS + ScanStartLedger.START_WINDOW_MARGIN_MILLIS

        now = firstBegin + window + 1
        assertEquals("호출 전 시각 기준으로는 허용되는 위험 지점", 500L, ledger.tryStart { now += 2_000; true })

        now = firstEnd + window
        assertEquals(1L, ledger.tryStart { now += 2_000; true })

        now = firstEnd + window + 1
        assertEquals(null, ledger.tryStart { true })
    }

    @Test
    fun `운영 생성자는 elapsedRealtime 시계의 프로세스 ledger 를 넘긴다`() {
        // 블록 주석과 // 주석 줄을 빼고 줄 단위로 본다 — 주석 처리한 배선이 통과하지 않게. 텍스트 가드라 실수로 끊긴
        // 배선을 잡는 데까지다(일부러 우회하는 편집은 못 막는다).
        val source = File("src/main/kotlin/kr/co/nucode/ble/internal/BleRuntime.kt").readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val constructor = Regex("""constructor\(context: Context, config: BleConfig.*?\) : this\((.*?)\n    \)""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.groupValues?.get(1)
        assertTrue("운영 생성자를 찾지 못함", constructor != null)
        // 배선은 세 고리다: 운영 생성자 → BleRuntime 필드 → Scanner. 하나라도 끊기면 클라이언트별 기록으로 돌아간다.
        assertTrue(
            "운영 생성자가 processScanStartLedger 를 넘기지 않는다",
            Regex("""^\s*scanStartLedger = processScanStartLedger,?\s*$""", RegexOption.MULTILINE).containsMatchIn(constructor!!),
        )
        assertTrue(
            "Scanner 에 scanStartLedger 를 넘기지 않는다",
            Regex("""^\s*startLedger = scanStartLedger \?: """, RegexOption.MULTILINE).containsMatchIn(source),
        )
        assertTrue(
            "processScanStartLedger 가 SystemClock.elapsedRealtime() 시계가 아니다",
            Regex("""^private val processScanStartLedger = ScanStartLedger \{ SystemClock\.elapsedRealtime\(\) \}$""", RegexOption.MULTILINE)
                .containsMatchIn(source),
        )
    }

    private companion object {
        const val THREADS = 16
        const val ROUNDS = 20
    }
}
