package com.projectjuggler.platform

import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.locators.IntelliJLocator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class IntelliJLocatorTest : StringSpec({
    fun installation(executable: Path, displayName: String) = IdeInstallation(executable.toString(), displayName)

    "smoke: findAllInstallations on the real machine does not throw" {
        IntelliJLocator.findAllInstallations()
    }

    "macOS finds IntelliJ apps under /Applications and ~/Applications" {
        val fixture = locatorFixture()
        val system = fixture.root.createExecutable("Applications/IntelliJ IDEA.app/Contents/MacOS/idea")
        val user = fixture.home.createExecutable("Applications/IntelliJ IDEA CE.app/Contents/MacOS/idea")

        IntelliJLocator.findAllInstallations(fixture.env(Platform.MACOS)) shouldContainExactlyInAnyOrder listOf(
            installation(system, "IntelliJ IDEA"),
            installation(user, "IntelliJ IDEA CE"),
        )
    }

    "macOS ignores apps without IntelliJ in the name and non-executable binaries" {
        val fixture = locatorFixture()
        fixture.root.createExecutable("Applications/Other.app/Contents/MacOS/idea")
        fixture.root.createFileWithParents("Applications/IntelliJ IDEA.app/Contents/MacOS/idea")

        IntelliJLocator.findAllInstallations(fixture.env(Platform.MACOS)).shouldBeEmpty()
    }

    "linux finds /opt dirs named intellij* or exactly idea" {
        val fixture = locatorFixture()
        val ultimate = fixture.root.createExecutable("opt/intellij-idea-ultimate/bin/idea.sh")
        val plain = fixture.root.createExecutable("opt/idea/bin/idea.sh")
        fixture.root.createExecutable("opt/idea-IU-251/bin/idea.sh")

        IntelliJLocator.findAllInstallations(fixture.env(Platform.LINUX)) shouldContainExactlyInAnyOrder listOf(
            installation(ultimate, "IntelliJ IDEA Ultimate"),
            installation(plain, "IntelliJ IDEA"),
        )
    }

    "linux finds direct user executables" {
        val fixture = locatorFixture()
        val executable = fixture.home.createExecutable(".local/bin/idea")

        IntelliJLocator.findAllInstallations(fixture.env(Platform.LINUX)) shouldContainExactly listOf(
            installation(executable, "IntelliJ IDEA (User)"),
        )
    }

    "linux finds snap installations" {
        val fixture = locatorFixture()
        val ultimate = fixture.root.createExecutable("snap/intellij-idea-ultimate/current/bin/idea.sh")
        val community = fixture.root.createExecutable("snap/intellij-idea-community/current/bin/idea.sh")

        IntelliJLocator.findAllInstallations(fixture.env(Platform.LINUX)) shouldContainExactlyInAnyOrder listOf(
            installation(ultimate, "IntelliJ IDEA Ultimate (Snap)"),
            installation(community, "IntelliJ IDEA CE (Snap)"),
        )
    }

    "windows finds installations under ProgramFiles and LOCALAPPDATA" {
        val fixture = locatorFixture()
        val programFiles = fixture.root.resolve("Program Files")
        val localAppData = fixture.home.resolve("AppData/Local")
        val system = programFiles.createExecutable("JetBrains/IntelliJ IDEA 2025.1/bin/idea64.exe")
        val user = localAppData.createExecutable("Programs/IntelliJ IDEA Ultimate/bin/idea64.exe")
        val env = fixture.env(
            Platform.WINDOWS,
            mapOf("ProgramFiles" to programFiles.toString(), "LOCALAPPDATA" to localAppData.toString()),
        )

        IntelliJLocator.findAllInstallations(env) shouldContainExactlyInAnyOrder listOf(
            installation(system, "IntelliJ IDEA 2025.1"),
            installation(user, "IntelliJ IDEA Ultimate"),
        )
    }

    "toolbox ch-0 installations are found on every platform" {
        val macFixture = locatorFixture()
        val mac = macFixture.home.createExecutable(
            "Library/Application Support/JetBrains/Toolbox/apps/IDEA-U/ch-0/251.1/IntelliJ IDEA.app/Contents/MacOS/idea"
        )
        val linuxFixture = locatorFixture()
        val linux = linuxFixture.home.createExecutable(".local/share/JetBrains/Toolbox/apps/IDEA-U/ch-0/251.1/bin/idea.sh")
        val windowsFixture = locatorFixture()
        val localAppData = windowsFixture.home.resolve("AppData/Local")
        val windows = localAppData.createExecutable("JetBrains/Toolbox/apps/IDEA-U/ch-0/251.1/bin/idea64.exe")
        val name = "IntelliJ IDEA Ultimate 251.1 (Toolbox)"

        IntelliJLocator.findAllInstallations(macFixture.env(Platform.MACOS)) shouldContainExactly
            listOf(installation(mac, name))
        IntelliJLocator.findAllInstallations(linuxFixture.env(Platform.LINUX)) shouldContainExactly
            listOf(installation(linux, name))
        IntelliJLocator.findAllInstallations(
            windowsFixture.env(Platform.WINDOWS, mapOf("LOCALAPPDATA" to localAppData.toString()))
        ) shouldContainExactly listOf(installation(windows, name))
    }

    "toolbox channels other than ch-0 are not found (known gap 8)" {
        val fixture = locatorFixture()
        fixture.home.createExecutable(".local/share/JetBrains/Toolbox/apps/IDEA-U/ch-1/251.1/bin/idea.sh")

        IntelliJLocator.findAllInstallations(fixture.env(Platform.LINUX)).shouldBeEmpty()
    }

    "duplicates by executable path are collapsed" {
        val fixture = locatorFixture()
        val executable = fixture.home.createExecutable("Applications/IntelliJ IDEA.app/Contents/MacOS/idea")

        // fsRoot == home, so /Applications and ~/Applications are the same directory
        IntelliJLocator.findAllInstallations(fixture.env(Platform.MACOS, fsRoot = fixture.home)) shouldContainExactly
            listOf(installation(executable, "IntelliJ IDEA"))
    }

    "findInstallation returns the scanned match" {
        val fixture = locatorFixture()
        val executable = fixture.root.createExecutable("opt/intellij-idea-ultimate/bin/idea.sh")

        IntelliJLocator.findInstallation(executable.toString(), fixture.env(Platform.LINUX)) shouldBe
            installation(executable, "IntelliJ IDEA Ultimate")
    }

    "findInstallation synthesizes an installation named after the executable file (known gap 8)" {
        val fixture = locatorFixture()

        IntelliJLocator.findInstallation("/custom/place/idea", fixture.env(Platform.MACOS)) shouldBe
            IdeInstallation("/custom/place/idea", "idea")
    }
})
