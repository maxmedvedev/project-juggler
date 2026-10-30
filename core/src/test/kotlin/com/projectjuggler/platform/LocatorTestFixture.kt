package com.projectjuggler.platform

import com.projectjuggler.locators.LocatorEnvironment
import io.kotest.core.TestConfiguration
import com.projectjuggler.test.createTempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

/** Fake home and filesystem root in a temp dir, for building IDE trees that the locators scan. */
internal class LocatorTestFixture(base: Path) {
    val home: Path = base.resolve("home").createDirectories()
    val root: Path = base.resolve("root").createDirectories()

    fun env(platform: Platform, vars: Map<String, String> = emptyMap(), fsRoot: Path = root) =
        LocatorEnvironment(platform, home, fsRoot, vars::get)
}

internal fun TestConfiguration.locatorFixture() = LocatorTestFixture(createTempDir("test-locator"))

internal fun Path.createFileWithParents(relative: String): Path =
    resolve(relative).also { it.parent.createDirectories(); it.createFile() }

internal fun Path.createExecutable(relative: String): Path =
    createFileWithParents(relative).also { it.toFile().setExecutable(true) }
