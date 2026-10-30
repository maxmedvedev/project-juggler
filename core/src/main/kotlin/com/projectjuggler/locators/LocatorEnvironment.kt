package com.projectjuggler.locators

import com.projectjuggler.platform.Platform
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Roots the locators scan from. Tests pass fake roots in temp dirs; production uses [current].
 *
 * @param fsRoot replaces the absolute `/` of `/Applications`, `/opt` and `/snap`
 * @param getenv environment lookup (`ProgramFiles`, `LOCALAPPDATA`, `APPDATA`)
 */
internal data class LocatorEnvironment(
    val platform: Platform,
    val userHome: Path,
    val fsRoot: Path,
    val getenv: (String) -> String?,
) {
    companion object {
        fun current() = LocatorEnvironment(
            Platform.current(), Paths.get(System.getProperty("user.home")),
            Paths.get("/"), System::getenv,
        )
    }
}
