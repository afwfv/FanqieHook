plugins {
    // AGP 9 has built-in Kotlin support; the kotlin.android plugin is removed.
    id("com.android.application")
}

android {
    namespace = "dev.operit.fanqiehook"
    // libxposed:service 102.0.0 requires compileSdk 37; targetSdk stays 35 so
    // runtime behavior is unchanged (compileSdk only affects compile-time APIs).
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.operit.fanqiehook"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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

    buildFeatures {
        // MainActivity displays the module version from BuildConfig.
        buildConfig = true
    }
}

// AGP 9 built-in Kotlin: top-level kotlin.compilerOptions replaces android.kotlinOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit: runtime DEX search used to find obfuscated-class targets whose class name
    // changes between Fanqie releases. Only a small subset of hooks actually need it; see
    // `FanqieHook_hooks_assessment.md`. Pulled in as `implementation` (not `compileOnly`)
    // because DexKit loads its own native shim at runtime.
    // Coordinate is `org.luckypray:dexkit` (NOT `io.github.lsposed:dexkit` — that's the old 1.x line).
    implementation("org.luckypray:dexkit:2.0.4")

    // Framework communication service: activation detection + remote preferences
    // (module app ↔ framework shared storage ↔ host process).
    implementation("io.github.libxposed:service:102.0.0")

    // Embedded HTTP server for the in-host web console (book source API).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Settings UI (module app side).
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
