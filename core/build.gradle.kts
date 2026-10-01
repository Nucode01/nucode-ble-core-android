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
val sdkVersion = "0.2.2"

kotlin {
    explicitApi()
}

// 계약 시나리오 폴더(루트 저장소 contract/). 루트 저장소 안에서는 ../contract, CI 는 -PbleContractDir 로 준다.
val bleContractDirProperty = providers.gradleProperty("bleContractDir")
val bleContractDir: File = bleContractDirProperty.map { rootProject.file(it) }.getOrElse(rootProject.file("../contract"))

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
    // contract-runner: 계약 시나리오 러너. core 단위 테스트(sim)와 sample 계측 테스트(device)가 같은 소스를 쓴다.
    // contract/consumers/kotlin: 소비 앱 흐름을 공개 API 로 다시 쓴 코드(전달 1단계). 공개 API 가 바뀌면 여기서 컴파일이 깨진다.
    sourceSets["test"].java.srcDirs(
        "src/test/kotlin",
        rootProject.file("contract-runner/src/kotlin"),
        File(bleContractDir, "consumers/kotlin"),
    )

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

// GuideSnippetsSyncTest 가 읽는 가이드 문서·예시 파일이 바뀌면 단위 테스트를 up-to-date 로 건너뛰지 않게 한다.
tasks.withType<Test>().configureEach {
    systemProperty("ble.contract.dir", bleContractDir.absolutePath)
    systemProperty("ble.contract.required", bleContractDirProperty.isPresent.toString())
    if (bleContractDir.isDirectory) {
        inputs.dir(bleContractDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("bleContract")
    }
    inputs.dir(rootProject.file("guide")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("sample/src/main/kotlin/kr/co/nucode/ble/sample") { include("Guide*.kt") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
