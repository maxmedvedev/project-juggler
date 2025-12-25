plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("org.graalvm.buildtools.native") version "0.10.0"
    application
}

group = "com.ideajuggler"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // CLI framework
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
}

application {
    mainClass.set("com.ideajuggler.MainKt")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("idea-juggler")
            mainClass.set("com.ideajuggler.MainKt")
            debug.set(false)
            verbose.set(true)
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")

            // Optimize for size and startup
            buildArgs.add("-O3")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
