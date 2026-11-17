// The shared part of ObsiTube, ObsiRead and Obsi Bible. Each app builds it as its :core module.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.obsi.core"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.animation:animation")
    api("androidx.compose.material3:material3:1.5.0-alpha14")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.core:core-ktx:1.17.0")
    api("androidx.activity:activity-compose:1.11.0")
    api("androidx.documentfile:documentfile:1.1.0")
    api("dev.rikka.shizuku:api:13.1.5")
    api("dev.rikka.shizuku:provider:13.1.5")
    api("com.github.MuntashirAkon:libadb-android:3.1.1")
    api("com.github.MuntashirAkon:sun-security-android:1.1")
    api("org.conscrypt:conscrypt-android:2.5.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("io.coil-kt.coil3:coil-compose:3.3.0")
    api("org.jsoup:jsoup:1.21.1")
    api("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
