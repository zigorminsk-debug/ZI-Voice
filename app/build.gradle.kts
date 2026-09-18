plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zigorminsk.zivoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zigorminsk.zivoice"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            // Релизный APK собирается неподписанным; подпись выполняется в CI
            // (см. .github/workflows/android.yml) при наличии секретов.
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
