plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qingjiexi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qingjiexi.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名生成可直接安装的 APK（便于分发测试）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // 离线/CI 环境跳过 release 的 lint 校验（不影响编译产物）
        checkReleaseBuilds = false
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}