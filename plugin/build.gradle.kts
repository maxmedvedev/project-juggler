import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

version = "0.0.6"

val koinVersion: String by rootProject.extra
val kotestVersion: String by rootProject.extra
val mockkVersion: String by rootProject.extra

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))
    implementation("io.insert-koin:koin-core:$koinVersion")
    intellijPlatform {
        intellijIdea("2025.1.1")
        pluginVerifier()
        testFramework(TestFrameworkType.JUnit5)
        // Platform adds test-framework-core (LoggedErrorProcessor), which specs covering LOG.error paths need
        testFramework(TestFrameworkType.Platform)
    }
    // The IDE ships a patched kotlinx-coroutines; a second copy from the test libraries breaks the test application
    fun ModuleDependency.withoutCoroutines() =
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    testImplementation(testFixtures(project(":core")) as ModuleDependency) { withoutCoroutines() }
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion") { withoutCoroutines() }
    testImplementation("io.mockk:mockk:$mockkVersion") { withoutCoroutines() }
    testImplementation("io.insert-koin:koin-test:$koinVersion") { withoutCoroutines() }
    testRuntimeOnly("junit:junit:4.13.2") // the IntelliJ test framework still references JUnit 4 classes
}

tasks {
    patchPluginXml {
        pluginVersion.set(project.version.toString())
        sinceBuild.set("251")
    }

    // Bundle sync-helper distribution with the plugin
    processResources {
        dependsOn(":sync-helper:installDist")

        // Copy sync-helper distribution into plugin resources
        from(project(":sync-helper").layout.buildDirectory.dir("install/sync-helper")) {
            into("sync-helper")
        }
    }

    test {
        useJUnitPlatform()
        // Keep IdeRegistry away from ~/.project-juggler (IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
        val testBaseDir = layout.buildDirectory.dir("project-juggler-test").get().asFile.absolutePath
        systemProperty("project.juggler.base.dir", testBaseDir)
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Sandbox base dir: next to build/idea-sandbox (whose paths it stores), reset by clean; -PprojectJuggler.baseDir overrides
val runIdeBaseDir = providers.gradleProperty("projectJuggler.baseDir")
    .orElse(layout.buildDirectory.dir("project-juggler-sandbox").map { it.asFile.absolutePath })

tasks {
    runIde {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Dproject.juggler.base.dir=${runIdeBaseDir.get()}")
        }
    }
}
