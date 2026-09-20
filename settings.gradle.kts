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
