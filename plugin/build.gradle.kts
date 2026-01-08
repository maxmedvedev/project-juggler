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

    // Bundle CLI distribution with the plugin
    processResources {
        dependsOn(":cli:installDist")

        // Copy CLI distribution into plugin resources
        from(project(":cli").layout.buildDirectory.dir("install/cli")) {
            into("project-juggler-cli")
        }
    }
}

kotlin {
    jvmToolchain(17)
}
