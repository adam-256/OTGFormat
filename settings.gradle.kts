pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // Plugin versions are declared here rather than in a root `plugins { ... }`
    // block, for two reasons.
    //
    // A root block resolves the plugin artifact at configuration time even with
    // `apply false`, which would fetch the Android Gradle Plugin on every
    // machine — including the ones with no route to Google's repository, where
    // the JVM test suite has to keep working. A version declared here is only
    // resolved when a project actually requests that plugin, so AGP is fetched
    // only when :android is included.
    //
    // And once the Kotlin plugin is on the root classpath, a subproject that
    // asks for it *with* a version is rejected outright: "already on the
    // classpath with an unknown version, so compatibility cannot be checked".
    // Declaring versions once, here, avoids that entirely.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "otgformat"

include("core")
include("usb")
include("jvm-test")

// The Android app is included only where an SDK is present.
//
// `core`, `usb` and `jvm-test` are plain JVM modules and deliberately carry the
// whole verified surface of this project, so the test suite has to keep running
// on machines — CI containers included — that have no Android SDK and no access
// to Google's Maven repository. Including `:android` unconditionally would make
// `./gradlew test` fail there during configuration, before a single test ran.
val androidSdkDir: String? = sequenceOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    file("local.properties").takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("="),
).firstOrNull { !it.isNullOrBlank() && file(it).isDirectory }

if (androidSdkDir != null) {
    include("android")
} else {
    gradle.rootProject {
        logger.lifecycle(
            "No Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties); " +
                "skipping the :android module. The core, usb and jvm-test modules build and test without it.",
        )
    }
}
