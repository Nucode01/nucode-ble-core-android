package kr.co.nucode.ble.internal.platform.android

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kr.co.nucode.ble.internal.model.AdapterHandle
import kr.co.nucode.ble.internal.model.AdapterSource

/**
 * [AdapterSource] 의 Android 구현 — [BluetoothAdapter] 조회와 `ACTION_STATE_CHANGED` 리시버를 소유한다.
 *
 * @param adapter 매 호출 시 어댑터를 다시 얻는 provider(낡은 참조 금지).
 * @param context null 이면 리시버를 등록하지 않는다(테스트 생성자 경로).
 */
internal class AndroidAdapterSource(
    private val adapter: () -> BluetoothAdapter?,
    private val context: Context?,
) : AdapterSource {

    private var onStateChanged: (() -> Unit)? = null

    /**
     * 어댑터 on/off 브로드캐스트(review #3 해동). `onReceive` 는 브로드캐스트를 발송한 시스템
     * 스레드에서 불린다 — 코어 스레드 홉은 통지받는 쪽(AdapterMonitor)이 한다.
     *
     * 시스템이 직접 보내는 브로드캐스트(앱이 아니라 플랫폼 발신)라 API 33+ 의
     * `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` 플래그 요구가 면제된다 — 그 플래그는
     * "다른 앱이 내 리시버로 브로드캐스트를 보낼 수 있는가"를 통제하는 것이라 발신자가
     * 시스템 자신인 표준 브로드캐스트에는 적용되지 않는다(plan §미해결·전제 — 실기기
     * 미검증). 그래서 `Context.registerReceiver(receiver, filter)` 2-인자로 충분하다.
     */
    private val adapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            onStateChanged?.invoke()
        }
    }

    override fun current(): AdapterHandle? = adapter()?.let { instance -> AdapterHandle { instance.isEnabled } }

    override fun startListening(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        context?.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun stopListening() {
        context?.unregisterReceiver(adapterStateReceiver)
    }
}
