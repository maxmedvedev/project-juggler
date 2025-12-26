import java.security.MessageDigest

plugins {
    kotlin("jvm")
    application
}

version = "0.0.1"

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.projectjuggler.MainKt")
    applicationName = "project-juggler"
}

// Reproducible builds for Homebrew
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}


kotlin {
    jvmToolchain(17)
}

// Generate Version.kt file with version constant
val generateVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/version/kotlin")

    outputs.dir(outputDir)

    doLast {
        val versionFile = outputDir.get().asFile.resolve("com/projectjuggler/Version.kt")
        versionFile.parentFile.mkdirs()
        versionFile.writeText("""
            package com.projectjuggler

            internal const val CLI_VERSION = "${project.version}"
        """.trimIndent())
    }
}

sourceSets {
    main {
        kotlin {
            srcDir(generateVersion.map { it.outputs.files.singleFile })
        }
    }
}

// Homebrew distribution task
val homebrewDist by tasks.registering(Tar::class) {
    description = "Creates a Homebrew-compatible distribution"
    group = "distribution"

    archiveBaseName.set("project-juggler")
    archiveVersion.set(project.version.toString())
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP

    dependsOn("jar")

    into("project-juggler-${project.version}") {
        // JARs in libexec/
        into("libexec") {
            from(tasks.named("jar"))
            from(configurations.runtimeClasspath)
        }

        // Shell script in bin/
        into("bin") {
            from("src/main/resources/project-juggler.sh") {
                rename { "project-juggler" }
                fileMode = 0b111101101  // 0755 (executable)
            }
        }
    }

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirMode = 0b111101101  // 0755
    fileMode = 0b110100100  // 0644 (except overridden for script)
}

// Checksum generation task
val homebrewChecksum by tasks.registering {
    description = "Generates SHA256 checksum for Homebrew formula"
    group = "distribution"

    dependsOn(homebrewDist)

    doLast {
        val distFile = homebrewDist.get().archiveFile.get().asFile
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(distFile.readBytes())
            .joinToString("") { "%02x".format(it) }

        val checksumFile = File(distFile.parentFile, "${distFile.name}.sha256")
        checksumFile.writeText("$checksum  ${distFile.name}\n")

        println("SHA256: $checksum")
        println("File: ${distFile.absolutePath}")
        println("Checksum file: ${checksumFile.absolutePath}")
    }
}
