plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sense.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sense.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The one-shot prompt is tools/prompts/moment_*.txt, the same file the offline evals score.
    sourceSets["main"].assets.srcDir("../../tools/prompts")
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
