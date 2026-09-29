package kr.co.nucode.ble.internal.platform.android

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.internal.port.ClockPort

/**
 * [ClockPort] 의 Android 구현. 단조 시각은 [elapsed](기본 `SystemClock.elapsedRealtime()` — deep sleep 에도 진행),
 * 벽시계는 `System.currentTimeMillis()`. 디스패처를 바꾸지 않는다(호출 코루틴 문맥 그대로).
 */
internal class AndroidClock(
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) : ClockPort {
    override fun elapsedMillis(): Long = elapsed()

    override fun wallMillis(): Long = System.currentTimeMillis()

    override suspend fun sleep(millis: Long) = delay(millis)

    override suspend fun <T> awaitWithin(millis: Long, block: suspend () -> T): T =
        withTimeout(millis) { block() }

    override suspend fun <T> awaitWithinOrNull(millis: Long, block: suspend () -> T): T? =
        withTimeoutOrNull(millis) { block() }
}
