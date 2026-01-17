plugins {
    kotlin("jvm")
    application
}

val koinVersion: String by rootProject.extra

dependencies {
    implementation(project(":core"))
    implementation("io.insert-koin:koin-core:$koinVersion")
}

application {
    mainClass.set("com.projectjuggler.synchelper.SyncHelperMainKt")
    applicationName = "sync-helper"
}

kotlin {
    jvmToolchain(17)
}
