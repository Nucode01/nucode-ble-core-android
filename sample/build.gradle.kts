plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 계약 시나리오 폴더(루트 저장소 contract/). core/build.gradle.kts 와 같은 규칙이다.
val bleContractDir: File = providers.gradleProperty("bleContractDir")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("../contract"))

android {
    namespace = "kr.co.nucode.ble.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.nucode.ble.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 소비 앱이 R8 을 켰을 때 core 가 keep 규칙 없이 통과하는지 확인하는 용도(core 자체는 난독화하지 않는다).
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    // 사용법 문서 §14(UI까지 잇는 예시)의 컴파일 검증용. MainActivity 는 계속 의존성 0 View 다.
    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    // contract-runner 는 core 단위 테스트와 같은 러너 소스다. 시나리오 파일은 test APK 의 assets 로 싣는다.
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin", rootProject.file("contract-runner/src/kotlin"))
    sourceSets["androidTest"].assets.srcDirs(bleContractDir)
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
