plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loverofdarkness.remotekeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loverofdarkness.remotekeyboard"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false }

    buildTypes {
        debug {
            // Debug builds should remain debuggable and fast; resource/code shrinking
            // is intentionally limited to the release build.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    // BluetoothHidDevice is a hidden/System API; the local stub is compile-only.
    compileOnly(files("stubs/hidden-android.jar"))
}
