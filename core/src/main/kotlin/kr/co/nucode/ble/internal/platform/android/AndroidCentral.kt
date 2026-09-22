package kr.co.nucode.ble.internal.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kr.co.nucode.ble.AdapterState

/**
 * Android framework adapter — 어댑터 인스턴스와 권한 판정 입력을 제공한다. 상태 조합·발행은 BleRuntime 의 책임.
 *
 * [context] 는 호출자가 `applicationContext` 를 넘기는 것이 계약이다 — 이 클래스는 좁히지 않는다.
 */
internal class AndroidCentral(val context: Context) {
    /** 매니페스트 선언 권한 — 불변이라 생성 시 1회만 조회한다. 실패하면 빈 집합. */
    private val declaredPermissions: Set<String> = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
    } catch (_: Exception) {
        emptySet()
    }

    fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun permissionState(): AdapterState.Unauthorized? = AndroidPermissionState.evaluate(
        sdkInt = Build.VERSION.SDK_INT,
        targetSdkInt = context.applicationInfo.targetSdkVersion,
        declared = declaredPermissions,
        isGranted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
    )
}
