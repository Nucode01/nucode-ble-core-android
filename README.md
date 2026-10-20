# nucode BLE Core (Android)

Android 앱이 BLE 기기(peripheral)에 **central** 로 붙을 때 쓰는 Kotlin 라이브러리입니다.
GATT 작업 직렬화, 연결·끊김·재연결, 실패 분류를 코루틴 API 뒤에서 처리합니다.

진입점은 `BleClient` 와 기기 하나를 다루는 `BleSession` 이고, `AndroidBleClientFactory` 로 만듭니다.
사용법은 [개발자 가이드](guide/README.md)에 예시 코드와 함께 있습니다.

## 요구 사항

| 항목 | 값 |
| --- | --- |
| minSdk | 24 (Android 7.0) |
| compileSdk (라이브러리 빌드) | 36 |
| 의존성 | `kotlinx-coroutines-android` 1.9.0 (`api` 로 전달됩니다) |
| 빌드 도구 | AGP 8.13.2 · Kotlin 2.0.21 · Gradle 8.13 · JVM 11 |
| 좌표 (JitPack) | `com.github.Nucode01:nucode-ble-core-android:0.3.1` |

라이브러리 매니페스트는 권한을 선언하지 않습니다. 앱이 선언합니다([설치 · 권한](guide/install.md)).

## 설치

JitPack(`https://jitpack.io`)에서 받습니다. 저장소가 public 이라 인증 없이 받습니다. JitPack 에서 받을 수 있는 첫 버전은 0.3.1 입니다.

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

로컬 Maven 으로 쓰는 법과 자세한 절차는 [설치](guide/install.md)를 봅니다.

## 가이드

| 문서 | 내용 |
| --- | --- |
| [개발자 가이드](guide/README.md) | 소개 · 5분 빠른 시작 · 목차 |
| [설치](guide/install.md) | JitPack 에서 받기, 로컬 Maven 대안, 앱에 추가, 권한 |
| [핵심 개념](guide/concepts.md) | `BleClient`·`BleSession`, 상태, 실패, 스레드 |
| [문제 해결과 한계](guide/troubleshooting.md) | 자주 틀리는 것, 검증 범위 |

## 현재 상태

| 항목 | 상태 |
|---|---|
| 버전 | 0.3.1. 변경 내용은 [CHANGELOG](CHANGELOG.md) |
| 공개 API | [core/api/ble-core.api](core/api/ble-core.api) 가 기준이고, 단위 시험이 대조합니다 |
| 실기기 검증 | Pixel 5a / Android 14 + nRF52840 시험 펌웨어 한 조합. 결과는 [실기기 검증 기록](docs/v1/android-real-device-results.md) |

### 알려진 문제

- 실기기 첫 연결이 GATT status 133 으로 실패할 수 있습니다(HCI 0x3E). 첫 직접 연결의 빠른 133 은 라이브러리가 최대 2번 다시 겁니다(CHANGELOG Unreleased). 이 재시도가 실기기에서 실제로 동작한 것은 아직 관측하지 못했습니다.
- 여러 OEM · API level 조합은 검증하지 않았습니다. 범위는 [문제 해결과 한계](guide/troubleshooting.md)에 있습니다.

## 개발

```bash
./gradlew test                    # 단위 시험 (debug · release)
./gradlew :sample:assembleRelease # R8 을 켠 sample 빌드
```

계약 시험에 필요한 폴더, 실기기 시험, CI 는 [개발 안내](DEVELOPMENT.md)를 봅니다.

## 변경 이력

[CHANGELOG.md](CHANGELOG.md)
