plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


/** 이 앱 폴더를 건드린 커밋 수 → 앱 코드가 바뀔 때만 버전이 올라감 (CI 의 version.json 과 같은 계산) */
fun gitCount(): Int = runCatching {
    val repo = rootDir.parentFile
    val path = projectDir.relativeTo(repo).invariantSeparatorsPath
    ProcessBuilder("git", "rev-list", "--count", "HEAD", "--", path).directory(repo).start()
        .inputStream.bufferedReader().readText().trim().toInt()
}.getOrDefault(0)

android {
    namespace = "com.chodang.ledsign"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chodang.ledsign"
        minSdk = 24
        targetSdk = 34
        versionCode = 100 + gitCount()
        versionName = "1.${100 + gitCount()}"
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
