package com.projectjuggler.plugin.services

import com.projectjuggler.plugin.test.TestApplicationExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.writeText

class CurrentIdePathsTest : StringSpec({
    extensions(TestApplicationExtension())

    val property = "jb.vmOptionsFile"
    // Temp dirs lie outside the IDE home, so VMOptionsCopy takes the property's path as the user file
    val vmOptionsDir = createTempDir("current-ide-paths")
    var previous: String? = null

    beforeTest { previous = System.getProperty(property) }
    afterTest {
        previous?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    "without jb.vmOptionsFile the vmoptions file is the platform file" {
        System.clearProperty(property)

        CurrentIdePaths.detect().vmOptionsFile shouldBe VMOptionsCopy.getPlatformOptionsFile()
    }

    "an existing user vmoptions file is the vmoptions file" {
        val userFile = vmOptionsDir.resolve("idea.vmoptions").apply { writeText("-Xmx2g\n") }
        System.setProperty(property, userFile.toString())

        CurrentIdePaths.detect().vmOptionsFile shouldBe userFile.toAbsolutePath()
    }

    "a missing user vmoptions file falls back to the platform file" {
        System.setProperty(property, vmOptionsDir.resolve("missing.vmoptions").toString())

        CurrentIdePaths.detect().vmOptionsFile shouldBe VMOptionsCopy.getPlatformOptionsFile()
    }
})
