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
        versionCode = 15
        versionName = "0.4.0"
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
            isMinifyEnabled = false
            // LSPosed modules are loaded by the framework at runtime; R8 / shrinking must be
            // disabled to keep all reflective targets intact.
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