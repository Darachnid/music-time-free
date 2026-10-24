plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gay.ac1rv.nowplayingart"
    compileSdk = 36

    defaultConfig {
        applicationId = "gay.ac1rv.nowplayingart"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // Pixel / Android 15+ (16 KB pages): compressed .so avoids "App not installed" on sideload.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core-ktx:1.15.0")
        force("androidx.core:core:1.15.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // client only — `server` redeclares PebbleKit2 permissions owned by coredevices.coreapp
    implementation("io.rebble.pebblekit2:client:1.2.0")
    implementation("com.getpebble:pebblekit:4.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
