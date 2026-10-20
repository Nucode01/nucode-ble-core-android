# 2. 설치

## 2.1 JitPack 에서 받기

라이브러리는 JitPack(`https://jitpack.io`)에서 받습니다. JitPack 은 GitHub 저장소 `Nucode01/nucode-ble-core-android` 의 릴리스 태그를 빌드해 냅니다.
저장소가 public 이라 인증 없이 받습니다.

좌표는 `com.github.Nucode01:nucode-ble-core-android:0.3.1` 입니다. JitPack 빌드를 확인한 버전은 0.3.0 과 0.3.1 입니다. 0.2.x 태그는 JitPack 에서 빌드해 보지 않았습니다.

## 2.2 앱에 추가하기

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Nucode01:nucode-ble-core-android:0.3.1")
}
```

Gradle 이 `Could not resolve com.github.Nucode01:nucode-ble-core-android` 로 실패하면 저장소 목록에 `https://jitpack.io` 가 있는지, 태그 이름이 맞는지 확인하세요. JitPack 은 태그를 처음 요청받을 때 빌드하므로 첫 해석은 몇 분 걸릴 수 있습니다.

### 로컬 Maven 으로 쓰기 (대안)

라이브러리 소스를 고쳐 바로 시험할 때는 라이브러리 저장소에서 로컬 Maven 에 배포합니다.

```bash
./gradlew :core:publishReleasePublicationToMavenLocal
```

`~/.m2/repository/kr/co/nucode/ble-core/<버전>/` 에 aar · sources.jar · pom · module 이 생깁니다. 로컬 좌표는 `kr.co.nucode:ble-core:<버전>` 입니다. 이 명령을 실행한 컴퓨터에서만 받을 수 있습니다.
앱의 `settings.gradle.kts` 저장소 목록 맨 앞에 `mavenLocal()` 을 더하면 로컬 것을 먼저 씁니다.

## 2.3 권한

**라이브러리 매니페스트는 권한을 하나도 선언하지 않습니다.** 선언하는 것은 아래 한 줄뿐입니다.

```xml
<!-- core/src/main/AndroidManifest.xml (라이브러리) -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

`required="false"` 라서 BLE 가 없는 기기에도 앱을 설치할 수 있습니다. BLE 가 필수인 앱이면 앱 매니페스트에서 `required="true"` 로 올립니다.
권한은 앱 매니페스트에 직접 선언합니다. 앱 `targetSdk` 가 31 이상이면 샘플 앱의 선언을 그대로 쓰면 됩니다.

```xml
<!-- app/src/main/AndroidManifest.xml (앱) — <manifest> 에 xmlns:tools 가 필요합니다 -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

**위 선언은 앱 `targetSdk` 31 이상이 전제입니다.** targetSdk 가 30 이하면 코어는 Android 12 이상 기기에서도 옛 방식(아래 표 두 번째 줄)으로 판정합니다.
그런데 `maxSdkVersion="30"` 이 붙은 권한은 API 31 이상 기기에서 선언되지 않은 것으로 취급되므로, 이 선언 그대로면 `Unauthorized(canRequest = false)` 가 됩니다.
targetSdk 30 이하 앱은 `BLUETOOTH` · `BLUETOOTH_ADMIN` · `ACCESS_FINE_LOCATION` 을 `maxSdkVersion` **없이** 선언하고, 런타임에는 `ACCESS_FINE_LOCATION` 을 요청하세요.

코어는 실행 중인 기기의 API 레벨에 따라 아래처럼 판정합니다.

| 기기 API 레벨 | 매니페스트에 있어야 하는 것 | 런타임에 요청할 것 | 코어가 `Ready` 로 보는 조건 |
| --- | --- | --- | --- |
| 31 이상 (앱 targetSdk 31 이상) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | 둘 다 | 둘 다 허용 |
| 29 ~ 30 (또는 31 이상인데 targetSdk 30 이하) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (targetSdk 30 이하 앱이 31 이상 기기에서 쓰려면 `maxSdkVersion` 없이) | `ACCESS_FINE_LOCATION` | FINE 위치 허용 |
| 24 ~ 28 | `BLUETOOTH`, `BLUETOOTH_ADMIN`, 그리고 FINE 또는 COARSE 위치 | 선언한 위치 권한 | 선언한 위치 권한 중 하나 허용 |

- `BLUETOOTH` · `BLUETOOTH_ADMIN` 은 설치할 때 주어지는 권한이라 선언 여부만 봅니다.
- 필요한 선언이 하나라도 빠지면 `AdapterState.Unauthorized(canRequest = false)` 입니다. 권한을 요청해도 소용없는 **앱 빌드 문제**입니다.
- 선언은 됐는데 허용이 안 됐으면 `Unauthorized(canRequest = true)` 입니다. 이때 권한을 요청합니다.
- "다시 묻지 않음"으로 영구 거부됐는지는 코어가 판단하지 않습니다. `Activity` 가 필요해서 앱이 `shouldShowRequestPermissionRationale` 로 판단합니다.
- 스캔 결과로 위치를 추정하지 않는다면 `neverForLocation` 을 붙이세요. 빼면 Android 12 이상에서도 스캔 결과에 위치 권한이 필요한데,
  코어는 이 플래그를 읽지 않으므로 이 경우를 `Ready` 로 잘못 판정할 수 있습니다(코어 주석에 적힌 알려진 한계).
- Android 11 이하에서는 기기의 위치(GPS) 설정이 꺼져 있으면 스캔 결과가 오지 않는 것으로 알려져 있습니다. 코어는 이 설정을 검사하지 않습니다(이 동작은 이 저장소에서 검증하지 않았습니다).
- `AndroidBondController`(본딩)는 Android 12 이상에서 `BLUETOOTH_CONNECT` 가 허용돼 있어야 합니다. 허용이 없으면 `SecurityException` 이 그대로 올라옵니다.

런타임 요청에 쓸 권한 목록과 상태별 안내 문구입니다.

```kotlin
/** 런타임에 요청할 권한(앱 targetSdk 31 이상 기준). */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** 사용자에게 보여 줄 안내 문구. 쓸 수 있는 상태(Ready)면 null. */
fun adapterMessage(state: AdapterState): String? = when (state) {
    AdapterState.Ready -> null
    AdapterState.PoweredOff -> "블루투스를 켜 주세요"
    AdapterState.Resetting -> "블루투스가 다시 시작되는 중입니다"
    AdapterState.Unknown -> "블루투스 상태를 확인하는 중입니다"
    AdapterState.Unsupported -> "이 기기는 BLE 를 지원하지 않습니다"
    is AdapterState.Unauthorized ->
        if (state.canRequest) "블루투스 권한을 허용해 주세요" else "매니페스트에 권한 선언이 빠졌습니다(앱 빌드 문제)"
}
```

> **중요 — `adapterState` 는 권한 변경을 스스로 알지 못합니다.**
> `adapterState` 가 다시 계산되는 때는 두 경우뿐입니다. (1) 블루투스 켜짐/꺼짐 방송을 받았을 때,
> (2) `startScan` · `connect` · `read` · `write` · `subscribe` 를 호출한 첫머리.
> 그래서 권한을 받은 직후 `adapterState.first { it == AdapterState.Ready }` 로 기다리면 영원히 기다릴 수 있습니다.
> 권한 결과를 받으면 곧바로 `startScan` 같은 명령을 부르세요. 그 호출이 상태를 다시 판정하고, 여전히 안 되면 `PERMISSION_DENIED` 를 던집니다.
