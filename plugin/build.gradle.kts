plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

version = "0.0.2"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        intellijIdea("2025.1.1")
    }
}

tasks {
    patchPluginXml {
        pluginVersion.set(project.version.toString())
        sinceBuild.set("251")
        untilBuild.set("261.*")
    }

    // Bundle sync-helper distribution with the plugin
    processResources {
        dependsOn(":sync-helper:installDist")

        // Copy sync-helper distribution into plugin resources
        from(project(":sync-helper").layout.buildDirectory.dir("install/sync-helper")) {
            into("sync-helper")
        }
    }
}

kotlin {
    jvmToolchain(17)
}
