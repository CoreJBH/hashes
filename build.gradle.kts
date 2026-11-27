plugins {
    // AGP 8.11 builds against API 36 (compileSdk and targetSdk 36); the same
    // plugin and Kotlin versions the sibling apps are built with, so the whole
    // toolchain is one known-good set.
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
