plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.gripvisionai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gripvisionai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Firebase AI Logic (for Online Gemini Vision mode)
    implementation(platform("com.google.firebase:firebase-bom:34.3.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // MediaPipe Hand Landmarker (for Offline AI mode)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
