plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chodang.ledsign"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chodang.ledsign"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "0.8.0"
    }

    // 업데이트 설치가 되도록 항상 같은 키로 서명합니다 (공개 디버그용 키).
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/shared-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // 블루투스 권한은 앱 화면에서 먼저 받은 뒤에만 쓰므로 릴리스 빌드에서 lint 로 멈추지 않게 합니다.
        checkReleaseBuilds = false
        abortOnError = false
    }
}
