// Versions are declared here, not in the root build file: a root-level
// `apply false` entry would still resolve the Android Gradle Plugin at
// configuration time and break the build on machines without access to
// Google's Maven repository. This module is only included when an SDK is
// present (see settings.gradle.kts).
plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "dev.otgformat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.otgformat.app"
        // 26 is the floor, not a preference: the format runs in a foreground
        // service started with startForegroundService() and posts to a
        // notification channel, both of which arrived in Oreo.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // `usb` brings `core` with it (declared `api`), and holds every piece of
    // logic that has been verified on the JVM.
    implementation(project(":usb"))

    // The real libaums artifact. The `usb` module compiles against its
    // interface only; this is what supplies it at runtime.
    implementation("me.jahnen.libaums:core:0.10.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
