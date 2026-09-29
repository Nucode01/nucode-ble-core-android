package kr.co.nucode.ble.internal.platform.android

import kr.co.nucode.ble.internal.port.AdapterHandle
import kr.co.nucode.ble.internal.port.AdapterSource
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kr.co.nucode.ble.AdapterState

/**
 * [AdapterSource] 의 Android 구현 — [BluetoothAdapter] 조회와 `ACTION_STATE_CHANGED` 리시버를 소유한다.
 *
 * 어댑터는 매 호출 시 [central] 에서 다시 얻는다(낡은 참조 금지). [registerReceiver] 가 true 이고
 * [central] 이 있을 때만 그 context 로 리시버를 등록한다(테스트 생성자 경로는 등록 안 함).
 */
internal class AndroidAdapterSource(
    private val central: AndroidCentral?,
    registerReceiver: Boolean,
) : AdapterSource {
    private val adapter: () -> BluetoothAdapter? = { central?.adapter() }
    private val context: Context? = central?.context?.takeIf { registerReceiver }

    // startListening 에서 쓰고 브로드캐스트 메인 스레드에서 읽는다.
    @Volatile
    private var onStateChanged: (() -> Unit)? = null

    /**
     * 어댑터 on/off 브로드캐스트(review #3 해동). `onReceive` 는 메인 스레드에서 불린다(2-인자
     * `registerReceiver` 는 handler 를 지정하지 않는다) — 코어 스레드 홉은 통지받는 쪽(AdapterMonitor)이 한다.
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

    override fun permissionState(): AdapterState.Unauthorized? =
        if (central != null) {
            central.permissionState()
        } else {
            // 테스트 생성자 경로(Context 없음): 선언 집합이 비어 있고 grant 조회가 불가한 현재 동작과 동치.
            evaluatePermissionState(sdkInt = Build.VERSION.SDK_INT, declared = emptySet(), isGranted = { false })
        }

    override fun startListening(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        context?.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun stopListening() {
        context?.unregisterReceiver(adapterStateReceiver)
    }
}
