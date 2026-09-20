plugins {
    kotlin("jvm")
}

/**
 * The libaums adapter, as a plain Kotlin/JVM module.
 *
 * libaums' `BlockDeviceDriver` is a pure interface — `blockSize`, `blocks`,
 * `init()`, `read(Long, ByteBuffer)`, `write(Long, ByteBuffer)` — with no
 * Android types anywhere in its signature. That means the adapter between it
 * and `SectorDevice`, which is where a blocks-versus-bytes or off-by-one
 * mistake would destroy someone's drive, can be compiled and unit-tested on a
 * plain JVM in milliseconds. The same argument as Phase 0: prove the risky
 * layer before any of it goes near hardware.
 *
 * libaums ships as an AAR, which a JVM module cannot consume directly, so its
 * `classes.jar` is extracted below. It is `compileOnly` because the Android app
 * supplies the real artifact at runtime, and on the test classpath so the tests
 * can implement the interface with fakes.
 */
val libaumsAar: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

dependencies {
    libaumsAar("me.jahnen.libaums:core:0.10.0@aar")
}

val extractLibaumsClasses by tasks.registering(Copy::class) {
    from(libaumsAar.elements.map { aars -> aars.map { zipTree(it.asFile).matching { include("classes.jar") } } })
    into(layout.buildDirectory.dir("libaums"))
    rename { "libaums-core.jar" }
}

// Point at the extracted jar itself. `files(task)` would put the task's output
// *directory* on the classpath, where a nested jar is invisible to the compiler.
val libaumsClasses: FileCollection =
    files(layout.buildDirectory.file("libaums/libaums-core.jar")).builtBy(extractLibaumsClasses)

dependencies {
    api(project(":core"))
    compileOnly(libaumsClasses)
    testImplementation(libaumsClasses)
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
    // The transparency test holds two whole volumes in memory at once.
    maxHeapSize = "1g"
}
