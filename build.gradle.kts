plugins {
    // AGP 9.1 is the minimum that supports compileSdk 37 (needed by libxposed:service 102);
    // requires Gradle 9.3.1+. Kotlin is built into AGP 9 — no kotlin.android plugin needed.
    id("com.android.application") version "9.1.1" apply false
}
