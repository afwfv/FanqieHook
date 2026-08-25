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
        versionCode = 14
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // LSPosed modules are loaded by the framework at runtime; R8 / shrinking must be
            // disabled to keep all reflective targets intact.
            signingConfig = signingConfigs.getByName("debug")
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