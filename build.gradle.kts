plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9" apply false
}

group = "com.projectjuggler"

val koinVersion by extra("4.1.1")
val kotestVersion by extra("5.8.0")
val mockkVersion by extra("1.13.9")

subprojects {
    group = rootProject.group

    repositories {
        mavenCentral()
    }

    // Reproducible builds for all subprojects
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
