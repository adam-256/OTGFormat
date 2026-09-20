plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
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
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Images are sparse, but the FAT regions are real writes; keep them
    // somewhere predictable and inside the build directory.
    systemProperty("otgformat.scratch", layout.buildDirectory.dir("images").get().asFile.absolutePath)
    // The 64 GiB case walks a large FAT; give the JVM room and the test time.
    maxHeapSize = "1g"
}
