plugins {
    kotlin("jvm")
    id("org.graalvm.buildtools.native") version "0.10.0"
    application
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
}

tasks.test {
    useJUnitPlatform()
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

kotlin {
    jvmToolchain(17)
}
