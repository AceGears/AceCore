import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing is read from keystore.properties (git-ignored) or environment variables.
// Production credentials must never be committed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.acecore.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.acecore.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 45
        versionName = "0.10.19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Optional single-ABI build (CI emulator tests: arm64 only, so an x86_64 emulator runs it through ARM translation).
        (project.findProperty("acecore.abi") as String?)?.let { ndk { abiFilters += it } }
    }

    signingConfigs {
        // One fixed development key for every debug build (local and CI), so a new build installs over the previous one.
        // It is public on purpose: it only identifies AceCore development builds, never a Play release.
        getByName("debug") {
            storeFile = rootProject.file("ci/acecore-dev.keystore")
            storePassword = "acecore-dev"
            keyAlias = "acecore"
            keyPassword = "acecore-dev"
        }
        create("release") {
            val storePath = signingValue("storeFile", "ACECORE_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "ACECORE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ACECORE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ACECORE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The RGSS natives ship the NDK's libc++_shared; keep one copy if a library brings its own.
        jniLibs { pickFirsts += listOf("**/libc++_shared.so", "**/libgodot_android.so") }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Godot 4 Android engine (MIT), runs Godot 4.0–4.7 game packs. Keep GodotPack.ENGINE_MINOR in step.
    implementation("org.godotengine:godot:4.7.0.stable")
    // GodotActivity extends FragmentActivity; the Godot library only declares it as a runtime dependency.
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
}

// Local builds without CI: engine libraries can be delivered as split zips (<name>.zip.001, .002, …) in
// app/native-libs/. Each set is joined and unpacked into src/main/jniLibs before the build. No-op when absent.
val unpackNativeLibs by tasks.registering {
    val partsDir = layout.projectDirectory.dir("native-libs")
    val outDir = layout.projectDirectory.dir("src/main/jniLibs")
    val workDir = layout.buildDirectory.dir("native-libs")
    val partName = Regex("""(.+\.zip)\.(\d{3})""")
    onlyIf { partsDir.asFile.listFiles()?.any { partName.matches(it.name) } == true }
    doLast {
        val sets = partsDir.asFile.listFiles()!!.filter { partName.matches(it.name) }
            .groupBy { partName.matchEntire(it.name)!!.groupValues[1] }
        for ((name, parts) in sets) {
            val joined = workDir.get().asFile.resolve(name).apply { parentFile.mkdirs() }
            joined.outputStream().use { out -> parts.sortedBy { it.name }.forEach { p -> p.inputStream().use { it.copyTo(out) } } }
            ZipInputStream(joined.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.filter { !it.isDirectory }.forEach { e ->
                    val target = outDir.asFile.resolve(e.name).canonicalFile
                    require(target.path.startsWith(outDir.asFile.canonicalPath)) { "bad entry ${e.name}" }
                    target.parentFile.mkdirs(); target.outputStream().use { zip.copyTo(it) }
                }
            }
            logger.lifecycle("Unpacked $name (${parts.size} parts) into ${outDir.asFile}")
        }
    }
}
tasks.named("preBuild") { dependsOn(unpackNativeLibs) }
