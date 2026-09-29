package kr.co.nucode.ble.internal.platform.android

import android.Manifest
import android.os.Build
import kr.co.nucode.ble.AdapterState

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

/** Android-only permission matrix. It deliberately reports facts and never requests a permission. */
internal object AndroidPermissionState {
    fun evaluate(
        sdkInt: Int,
        targetSdkInt: Int = sdkInt,
        declared: Set<String>,
        isGranted: (String) -> Boolean,
    ): AdapterState.Unauthorized? = when {
        sdkInt >= Build.VERSION_CODES.S && targetSdkInt >= Build.VERSION_CODES.S -> {
            val required = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            when {
                required.any { it !in declared } -> AdapterState.Unauthorized(canRequest = false)
                required.all(isGranted) -> null
                else -> AdapterState.Unauthorized(canRequest = true)
            }
        }

        sdkInt >= Build.VERSION_CODES.Q -> legacyFineLocation(declared, isGranted)
        else -> legacyAnyLocation(declared, isGranted)
    }

    private fun legacyFineLocation(
        declared: Set<String>,
        isGranted: (String) -> Boolean,
    ): AdapterState.Unauthorized? {
        val required = listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        return when {
            required.any { it !in declared } -> AdapterState.Unauthorized(canRequest = false)
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> null
            else -> AdapterState.Unauthorized(canRequest = true)
        }
    }

    private fun legacyAnyLocation(
        declared: Set<String>,
        isGranted: (String) -> Boolean,
    ): AdapterState.Unauthorized? {
        val bluetooth = listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val locations = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            bluetooth.any { it !in declared } || locations.none { it in declared } ->
                AdapterState.Unauthorized(canRequest = false)
            locations.any { it in declared && isGranted(it) } -> null
            else -> AdapterState.Unauthorized(canRequest = true)
        }
    }
}
