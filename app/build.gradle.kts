plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.operit.fanqiehook"
    // The host app declares compileSdkVersion=35 (Android 15); match it so reflection against
    // framework classes added in API 35 (e.g. longVersionCode) stays compile-clean.
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.operit.fanqiehook"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.8.2"

        // DexKit 的 libdexkit.so 必须在宿主进程内加载，因此只需要宿主实际使用的 ABI。
        // 已核验：番茄小说 73532 与 73732 的 APK 都只打包 arm64-v8a（117 / 116 个 .so 全在
        // arm64-v8a 下），红果同基线。x86 / x86_64 只对模拟器有意义，armeabi-v7a 则永远
        // 用不上——宿主是 arm64-only，32 位设备根本装不上宿主。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // CI 固定签名：由 workflow 注入本地 debug.keystore，保证每次构建签名一致。
        // 未配置 KEYSTORE_PATH 时（本地开发）不启用，release 回退 debug 签名。
        create("ci") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 收缩：本模块 APK 的体积几乎全在 Kotlin 标准库与 DexKit 里
            // （自身代码仅 ~15 KB）。开启后必须配合 proguard-rules.pro 的保留规则——
            // 尤其是 java_init.list 按类名引用的入口类，以及 DexKit 会被 JNI 按签名回调的包。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null)
                signingConfigs.getByName("ci") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit: runtime DEX search used to find obfuscated-class targets whose class name
    // changes between Fanqie releases. Only a small subset of hooks actually need it. Pulled
    // in as `implementation` (not `compileOnly`)
    // because DexKit loads its own native shim at runtime.
    // Coordinate is `org.luckypray:dexkit` (NOT `io.github.lsposed:dexkit` — that's the old 1.x line).
    implementation("org.luckypray:dexkit:2.0.4")
}
