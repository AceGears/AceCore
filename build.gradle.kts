plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Desktop app (Windows / macOS)
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}
