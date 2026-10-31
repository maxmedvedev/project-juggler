package com.projectjuggler.plugin.services

import com.projectjuggler.platform.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class CurrentIdeExecutableTest : StringSpec({
    val home = Path.of("/ide/home")

    "macOS uses the script name under MacOS" {
        currentIdeExecutable(home, "idea", Platform.MACOS) shouldBe home.resolve("MacOS/idea")
        currentIdeExecutable(home, "pycharm", Platform.MACOS) shouldBe home.resolve("MacOS/pycharm")
    }

    "Linux uses the shell script under bin" {
        currentIdeExecutable(home, "idea", Platform.LINUX) shouldBe home.resolve("bin/idea.sh")
        currentIdeExecutable(home, "pycharm", Platform.LINUX) shouldBe home.resolve("bin/pycharm.sh")
    }

    "Windows uses the 64-bit exe under bin" {
        currentIdeExecutable(home, "idea", Platform.WINDOWS) shouldBe home.resolve("bin/idea64.exe")
        currentIdeExecutable(home, "pycharm", Platform.WINDOWS) shouldBe home.resolve("bin/pycharm64.exe")
    }
})
