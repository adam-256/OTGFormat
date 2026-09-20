// This root build file intentionally declares no plugins.
//
// Plugin versions live in settings.gradle.kts under `pluginManagement`, and
// repositories under `dependencyResolutionManagement`. A root
// `plugins { ... apply false }` entry still resolves the plugin artifact at
// configuration time, which would drag the Android Gradle Plugin onto every
// machine that configures this build — including ones with no access to
// Google's Maven repository, where the JVM modules still have to build and
// test. It would also put the Kotlin plugin on the root classpath, which makes
// any subproject that requests it with a version fail outright.
