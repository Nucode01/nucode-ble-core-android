package kr.co.nucode.ble.internal.adapter

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleException
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.internal.platform.android.AndroidCentral
import kr.co.nucode.ble.internal.platform.android.AndroidPermissionState

/**
 * D7(M-8+#16) 권한 판정 순수 함수 — `sdkInt`/매니페스트 선언 셋/런타임 grant 조회를 인자로
 * 받는다. `Build.VERSION.SDK_INT` 를 직접 참조하지 않는 이유는 테스트 JVM 에서 그 값이
 * 항상 0 이라 버전 매트릭스(31+/29~30/24~28)를 단위 테스트로 돌릴 수 없기 때문이다 —
 * AdapterMonitor 멤버가 아니라 파일 최상위 `internal` 함수로 둬 인스턴스 없이 테스트한다.
 *
 * 판정 순서(plan task 7 §구현②):
 * 1) 버전별 필요 권한 중 [declared] 에 없는 것이 있으면 → `Unauthorized(canRequest = false)`
 *    — 매니페스트 미선언. 요청 API 를 불러도 소용없다(앱 빌드 결함).
 * 2) 전부 선언은 됐는데 런타임 권한이 미허용이면 → `Unauthorized(canRequest = true)`.
 * 3) 전부 충족되면 → `null`(허용됨 — `Unauthorized` 아님. 어댑터 on/off 판정은 호출부 몫).
 *
 * pre-31 의 `BLUETOOTH`/`BLUETOOTH_ADMIN` 은 선언 여부만 본다 — 설치 시 부여되는 일반
 * 권한이라 grant 검사가 무의미하다(review #2 근거와 동일선상). 위치 권한은 선언+허용을
 * 모두 본다 — N~P 는 FINE/COARSE 어느 한쪽만 선언·허용돼도 충족된다(OR).
 *
 * [targetSdkInt] < 31 인 앱은 Android 12+ 기기에서도 legacy 권한 셋(BLUETOOTH/ADMIN+위치)으로
 * 합법 동작한다 — 그 조합을 SCAN/CONNECT 미선언 = "앱 빌드 결함(canRequest=false)" 으로
 * 오판하지 않도록 legacy 매트릭스로 내려보낸다(review L).
 *
 * ★ 알려진 한계(실기기 후 재검토): 31+ 에서 `BLUETOOTH_SCAN` 을 `neverForLocation` 없이
 * 선언한 앱은 스캔 결과 수신에 위치 권한도 필요하다 — 이 매트릭스는 그 플래그를 읽지
 * 않으므로(PackageInfo.requestedPermissionsFlags) 해당 조합을 통과로 오판할 수 있다.
 */
internal fun evaluatePermissionState(
    sdkInt: Int,
    declared: Set<String>,
    isGranted: (String) -> Boolean,
    targetSdkInt: Int = sdkInt,
): AdapterState.Unauthorized? = AndroidPermissionState.evaluate(
    sdkInt = sdkInt,
    targetSdkInt = targetSdkInt,
    declared = declared,
    isGranted = isGranted,
)

/**
 * 어댑터/권한 상태의 유일한 소유자(plan runtime-split) — 상태 계산·브로드캐스트 리시버·단일 쓰기 지점
 * ([updateAdapterState])과 "어느 전이가 무엇을 부르는가" 판정을 가진다. 전이가 부르는 다른 자원의 동작
 * (온기·스캔·세션 종결, 세션 재연결 재개)은 소유자인 런타임이 좁은 콜백([onAdapterLost]/[onAdapterReturned])
 * 으로 준다. 코어 스레드에서만 불린다(리시버는 [scope] 로 홉한다).
 *
 * @param context null 이면 리시버를 등록하지 않는다(테스트 생성자 경로).
 */
internal class AdapterMonitor(
    private val scope: CoroutineScope,
    private val adapter: () -> BluetoothAdapter?,
    private val central: AndroidCentral?,
    private val adapterStateProbe: (() -> AdapterState)?,
    private val context: Context?,
    private val onAdapterLost: (AdapterState) -> Unit,
    private val onAdapterReturned: () -> Unit,
) {

    private val _adapterState = MutableStateFlow(computeAdapterState())

    /** 어댑터/권한 상태. `Unauthorized(canRequest)`로 요청 가능/미허용을 구분한다(설계 §5, D7). */
    val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    /**
     * 어댑터 검사가 권한 검사보다 먼저다(plan task 7 §테스트) — 내부 테스트 생성자
     * (`adapter = { null }`)는 [central] 도 null 이라 권한 판정 경로를 타면 항상 빈 선언
     * 집합으로 `Unauthorized(false)` 가 나오는데, 이 순서 덕분에 그 경로 자체를 안 타고
     * `Unsupported` 로 먼저 끝난다 — `BleCoreWiringTest` 56개가 이 순서에 기대고 있다.
     *
     * 권한 판정 자체(D7/M-8+#16, BLE 스캔 결과 배달 조건 — review #2)는 [central] 이
     * 있으면 [AndroidCentral.permissionState] 로, 없으면(테스트 생성자 경로) 이 파일 최상위
     * [evaluatePermissionState] 에 빈 선언 집합/미허용을 직접 채워 위임한다 — 둘 다 최종
     * 판정 로직은 [AndroidPermissionState.evaluate] 로 수렴한다.
     */
    private fun computeAdapterState(): AdapterState {
        adapterStateProbe?.let { return it() }
        val adapterInstance = adapter() ?: return AdapterState.Unsupported
        val unauthorized = if (central != null) {
            central.permissionState()
        } else {
            // 테스트 생성자 경로(Context 없음): 선언 집합이 비어 있고 grant 조회가 불가한 현재 동작과 동치.
            evaluatePermissionState(sdkInt = Build.VERSION.SDK_INT, declared = emptySet(), isGranted = { false })
        }
        unauthorized?.let { return it }
        return if (adapterInstance.isEnabled) AdapterState.Ready else AdapterState.PoweredOff
    }

    /**
     * 어댑터 on/off 브로드캐스트(review #3 해동) — 잡는 즉시 [_adapterState] 를 다시
     * 계산해 반영한다. `scope.launch` 로 홉하는 이유는 다른 워커 콜백과 동일하다
     * (GattBridge/Scanner KDoc) — `onReceive` 는 브로드캐스트를 발송한 시스템 스레드에서
     * 불리고, `_adapterState` 를 coreScope 단일 스레드 밖에서 직접 건드리면 그 확정이
     * 깨진다.
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
            scope.launch { refreshAdapterState() }
        }
    }

    /**
     * [adapterStateReceiver] 본문 추출(plan G task 1) — 리시버는 등록이 안 되는 테스트
     * 생성자 경로에서 검증할 수 없어(브로드캐스트 자체가 없다), 이 함수를 직접 호출해
     * [adapterStateProbe] 플립 → [updateAdapterState] 전이(스윕 1회 규칙 포함)를 검증한다.
     * 프로덕션에서는 항상 [scope] 위(코어 단일 스레드)에서만 불린다 — 이 함수 자체는
     * 스레드 확정을 강제하지 않는다. 런타임의 `sweepSessions`(plan task 6 지시)와 같은
     * 테스트 seam 규약: 테스트는 이 함수를 코어 디스패처 밖(테스트 스레드)에서
     * 직접 부른다 — `BleCoreWiringTest` 가 `core.sweepSessions(...)`를 같은 방식으로
     * 직접 부르는 것과 동일 전례.
     */
    fun refreshAdapterState() {
        updateAdapterState(computeAdapterState())
    }

    /** 리시버 등록 — 런타임이 워커 조립 뒤 init 에서 부른다(생성 시점 타이밍 유지). */
    fun start() {
        context?.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    /** 리시버 해제 — 런타임 close 정리의 첫 단계. */
    fun close() {
        context?.unregisterReceiver(adapterStateReceiver)
    }

    /**
     * adapterState 쓰기의 유일한 지점(review High). Ready → non-Ready 전이 감지를 쓰는 쪽
     * (리시버·명령 재계산)이 아니라 여기서 한다 — 어느 경로가 먼저 전이를 관측하든 스윕이
     * 정확히 1회 돈다. 감지가 리시버에만 있으면, BT off 직후 명령이 먼저 재계산해 값을
     * 써버린 경우 리시버가 "이미 non-Ready 였다"고 읽어 스윕을 건너뛴다(콜백 안 오는
     * 기기에서 세션 Connected 동결 — D4 가 막으려던 바로 그 상태).
     *
     * D4/M-6 — Ready 로 들어오거나 non-Ready 사이를 오가는 전이는 스윕 대상이 아니다
     * (이미 스윕된 세션만 남아 Disconnected continue 가드로 no-op).
     */
    private fun updateAdapterState(newState: AdapterState) {
        val wasReady = _adapterState.value is AdapterState.Ready
        _adapterState.value = newState
        if (wasReady && newState !is AdapterState.Ready) onAdapterLost(newState)
        if (!wasReady && newState is AdapterState.Ready) resumeAfterAdapter()
    }


    /** 어댑터 복귀 — 세션마다 lifecycle 에 넘긴다(재연결 판정·시작은 GattBridge.resumeAfterAdapter 소유). */
    private fun resumeAfterAdapter() {
        onAdapterReturned()
    }

    /**
     * 즉시 알 수 있는 실패는 throw 한다(plan §명령 실패의 두 갈래) — 권한 없음 · 어댑터 off.
     * 끊김·재시도 같은 비동기 실패는 여기서 다루지 않고 events 로만 나간다.
     *
     * 매번 [computeAdapterState] 로 재계산한다(review #3 "냉동 해제") — [adapterStateReceiver]
     * 는 `ACTION_STATE_CHANGED` 브로드캐스트만 잡는다. 사용자가 설정 앱에서 위치/BLUETOOTH
     * 권한을 새로 허용하는 것처럼 그 리시버가 못 잡는 변화는, 늦어도 다음 명령 진입에서
     * 이 재계산으로 반영된다.
     */
    fun ensureAdapterReady(
        operation: AndroidApiOperation,
    ) {
        updateAdapterState(computeAdapterState())
        when (val state = _adapterState.value) {
            is AdapterState.Unauthorized -> throw BleException(
                FailureMapper.permissionDenied(
                    phase = FailurePhase.Adapter,
                    operation = operation,
                    canRequest = state.canRequest,
                ),
            )
            AdapterState.Unsupported, AdapterState.PoweredOff, AdapterState.Resetting ->
                throw BleException(FailureMapper.fromAdapterState(state))
            AdapterState.Ready -> Unit
        }
    }
}
