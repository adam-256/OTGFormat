plugins {
    kotlin("jvm")
}

// `core` is pure Kotlin/JVM. It must never gain an Android dependency:
// the whole point of the module split is that the byte-layout code can be
// tested in milliseconds against real filesystem tools instead of through
// a sideload-and-plug-in-a-stick cycle.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
