package com.projectjuggler.plugin.services

import com.projectjuggler.platform.Platform
import java.nio.file.Path

/**
 * The launcher of the IDE installed at [homePath] whose product script is [scriptName]
 * (`ApplicationNamesInfo.scriptName`: `idea`, `pycharm`, `studio`, ...): `MacOS/<script>` on macOS,
 * `bin/<script>.sh` on Linux, `bin/<script>64.exe` on Windows. Matches every layout `IntelliJLocator` knows.
 * Does not check that the file exists.
 */
internal fun currentIdeExecutable(homePath: Path, scriptName: String, platform: Platform): Path =
    when (platform) {
        Platform.MACOS -> homePath.resolve("MacOS").resolve(scriptName)
        Platform.LINUX -> homePath.resolve("bin").resolve("$scriptName.sh")
        Platform.WINDOWS -> homePath.resolve("bin").resolve("${scriptName}64.exe")
    }
