plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

// 좌표. 기존 kr.co.nucode:ble 라인(1.0.0~1.2.5)과 **의도적으로 다른 artifactId** 를 쓴다 —
// 설계 §0 비목표: "기존 SDK 호환 — 마이그레이션이 아니라 대체다".
// 대체는 병존 기간이 있고, 병존하려면 좌표가 달라야 한다. 같은 좌표로 올리면
// `ble:1.2.+` 로 해석하는 소비자가 API 가 전혀 다른 구현을 조용히 받는다.
val sdkGroupId = "kr.co.nucode"
val sdkArtifactId = "ble-core"
val sdkVersion = "0.1.0"

kotlin {
    explicitApi()
}

android {
    namespace = "kr.co.nucode.ble"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // 공개 API 가 Flow / StateFlow 를 노출하므로(설계 §5) api 로 선언한다.
    // implementation 이면 소비자 컴파일 클래스패스에 코루틴이 안 올라가서
    // core.events 를 collect 조차 못 한다.
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroupId
            artifactId = sdkArtifactId
            version = sdkVersion

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("nucode BLE Core")
                description.set("사내 공용 BLE 라이브러리 (central 전용). Kotlin 구현")
            }
        }
    }
    repositories {
        mavenLocal()
    }
}
