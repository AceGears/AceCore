import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// AceArcade desktop (Windows, macOS). Compose Multiplatform; the game detection rules, engine model and key mapping are
// compiled from the Android app's sources (they have no Android dependencies), so both apps recognise games the same way.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            include(
                "com/acecore/desktop/**",
                "com/acecore/app/core/detection/**",
                "com/acecore/app/core/model/**",
                "com/acecore/app/core/scripts/**",
                "com/acecore/app/core/config/WebKey.kt",
                "com/acecore/app/runtime/RuntimeRegistry.kt",
                "com/acecore/app/runtime/godot/GodotPack.kt",
            )
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

val appVersion = "1.0.1"   // desktop installers need MAJOR.MINOR.PATCH with MAJOR > 0

compose.desktop {
    application {
        mainClass = "com.acecore.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "AceArcade"
            packageVersion = appVersion
            description = "AceArcade game launcher"
            vendor = "AceGears"
            copyright = "AceArcade is free software (GPL-3.0-or-later)"
            // Engines AceArcade bundles per platform (downloaded by CI into resources/windows|macos/runtimes).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.net.http", "jdk.httpserver")
            windows {
                iconFile.set(project.file("icons/AceArcade.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "7f2b9d0e-6a51-4f7c-9a7e-acea3c0de001"
            }
            macOS {
                iconFile.set(project.file("icons/AceArcade.icns"))
                bundleID = "com.acegears.acearcade"
                packageName = "AceArcade"
            }
        }
    }
}
