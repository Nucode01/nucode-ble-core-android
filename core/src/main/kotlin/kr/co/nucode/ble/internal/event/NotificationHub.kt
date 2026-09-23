package kr.co.nucode.ble.internal.event

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId

/**
 * notification 전용 경로. `(PeripheralId, CharacteristicRef)` 키마다 수집자별 [Channel] 을
 * 두어, 유실이 그 수집자 자신의 지연으로만 일어나게 한다(plan J §접근 1–2).
 *
 * [deliver] 는 GATT 콜백 경로에서 불리므로 절대 블로킹·throw 하지 않는다 — 채널이 가득 차면
 * 그 수집자에게 갈 최신 값만 버리고 [onDrop] 으로 카운트한다(기존 emit 의 drop-newest 정책).
 */
internal class NotificationHub(
    private val capacity: Int,
    private val onDrop: () -> Unit,
) {
    private data class Key(val id: PeripheralId, val ref: CharacteristicRef)

    private val channels = ConcurrentHashMap<Key, CopyOnWriteArrayList<Channel<ByteArray>>>()

    /** 테스트 관찰용 — 현재 등록된 수집자 수. */
    internal val collectorCount: Int
        get() = channels.values.sumOf { it.size }

    /** 수집자마다 배열을 복사해 넘긴다 — 한 수집자의 변조가 다른 수집자에 보이지 않는다. */
    fun deliver(id: PeripheralId, ref: CharacteristicRef, bytes: ByteArray) {
        val targets = channels[Key(id, ref)] ?: return
        for (ch in targets) {
            if (!ch.trySend(bytes.copyOf()).isSuccess) onDrop()
        }
    }

    /**
     * `callbackFlow` 가 아니라 `flow {}` 인 이유: 등록이 collect 호출 코루틴에서 동기로
     * 일어나야 collect 직후 방출된 값을 놓치지 않는다. 취소 시 `finally` 에서 해제한다.
     */
    fun subscribe(id: PeripheralId, ref: CharacteristicRef): Flow<ByteArray> = flow {
        val key = Key(id, ref)
        val ch = Channel<ByteArray>(capacity)
        channels.compute(key) { _, list -> (list ?: CopyOnWriteArrayList()).apply { add(ch) } }
        try {
            for (v in ch) emit(v)
        } finally {
            channels.computeIfPresent(key) { _, list ->
                list.remove(ch)
                if (list.isEmpty()) null else list
            }
        }
    }
}
