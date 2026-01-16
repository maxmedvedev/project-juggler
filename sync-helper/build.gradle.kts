plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.projectjuggler.synchelper.SyncHelperMainKt")
    applicationName = "sync-helper"
}

kotlin {
    jvmToolchain(17)
}
