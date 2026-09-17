plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "kr.co.nucode.ble.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.nucode.ble.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
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
}

dependencies {
    implementation(project(":core"))
}
