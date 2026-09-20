plugins {
    kotlin("jvm") version "2.0.21" apply false
}

// Repositories are declared centrally in settings.gradle.kts
// (dependencyResolutionManagement) so that the Android module can add Google's
// Maven without every JVM module needing to know about it.
//
// The Android Gradle Plugin's version is declared inside android/build.gradle.kts
// rather than here. A root `plugins { ... apply false }` entry still resolves
// the plugin artifact at configuration time, which would make the whole build
// fail on a machine with no access to Google's repository — exactly the
// machines the JVM test suite is meant to run on.
