plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.projectjuggler.synchelper.MainKt")
    applicationName = "sync-helper"
}

kotlin {
    jvmToolchain(17)
}
