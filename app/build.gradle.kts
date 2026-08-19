plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.micanchor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.micanchor"
        minSdk = 24
        // Targeting 28 to match the Smart Clock 2 (MT8167, Android 9 era).
        // Keeps us clear of Android 10+ foreground-service-type enforcement
        // that this device will never apply anyway.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
