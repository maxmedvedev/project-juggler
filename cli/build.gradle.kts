import java.security.MessageDigest

plugins {
    kotlin("jvm")
    application
}

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
    mainClass.set("com.ideajuggler.MainKt")
    applicationName = "idea-juggler"
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

// Homebrew distribution task
val homebrewDist by tasks.registering(Tar::class) {
    description = "Creates a Homebrew-compatible distribution"
    group = "distribution"

    archiveBaseName.set("idea-juggler")
    archiveVersion.set(project.version.toString())
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP

    into("idea-juggler-${project.version}") {
        into("libexec") {
            from(tasks.named("jar"))
            from(configurations.runtimeClasspath)
        }
    }

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirMode = 0b111101101  // 0755
    fileMode = 0b110100100  // 0644
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
