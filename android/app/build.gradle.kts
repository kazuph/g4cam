plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kazuph.g4cam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kazuph.g4cam"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
}

    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.jks")
            storePassword = "g4cam2026"
            keyAlias = "g4cam"
            keyPassword = "g4cam2026"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // CameraX
    val cameraVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    // ML Kit GenAI APIs (Gemini Nano / Gemma 4 via AICore)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("com.google.mlkit:genai-image-description:1.0.0-beta1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // LiteRT-LM 0.10.1 (self-built from source to fix GPU decode crash - Issue #1850)
    implementation(files("libs/litertlm-android-0.10.1.aar"))
    // LiteRT-LM dependencies (gson, kotlin-reflect)
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")

    // OkHttp for model download (fallback)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
}
