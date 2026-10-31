plugins {
    kotlin("jvm")
    application
}

val koinVersion: String by rootProject.extra
val kotestVersion: String by rootProject.extra
val mockkVersion: String by rootProject.extra

dependencies {
    implementation(project(":core"))
    implementation("io.insert-koin:koin-core:$koinVersion")
    testImplementation(testFixtures(project(":core")))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
}

application {
    mainClass.set("com.projectjuggler.synchelper.SyncHelperMainKt")
    applicationName = "sync-helper"
}

tasks.test {
    useJUnitPlatform()
    // Keep IdeRegistry away from ~/.project-juggler (IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
    val testBaseDir = layout.buildDirectory.dir("project-juggler-test").get().asFile.absolutePath
    systemProperty("project.juggler.base.dir", testBaseDir)
}

kotlin {
    jvmToolchain(17)
}
