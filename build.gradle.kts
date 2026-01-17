plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
}

group = "com.projectjuggler"

val koinVersion by extra("4.1.1")

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
