plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("org.graalvm.buildtools.native") version "0.10.0" apply false
    id("org.jetbrains.intellij") version "1.17.4" apply false
}

group = "com.ideajuggler"
version = "1.0.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    // Reproducible builds for all subprojects
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
