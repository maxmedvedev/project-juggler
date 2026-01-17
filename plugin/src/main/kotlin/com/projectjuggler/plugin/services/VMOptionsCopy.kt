package com.projectjuggler.plugin.services

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.util.system.OS
import java.nio.file.Path

/**
 * copy of VMOptions from intellij-community because it does not allow calling `getUserOptionsFile` and `getPlatformOptionsFile`
 */
internal object VMOptionsCopy {
    fun getUserOptionsFile(): Path? {
        val vmOptionsFile = System.getProperty("jb.vmOptionsFile")
            ?: return null // launchers should specify a path to a VM options file used to configure a JVM

        val candidate = Path.of(vmOptionsFile).toAbsolutePath()
        if (!PathManager.isUnderHomeDirectory(candidate)) {
            // a file is located outside the IDE installation - meaning it is safe to overwrite
            return candidate
        }

        val location = PathManager.getCustomOptionsDirectory() ?: return null

        return Path.of(location, getFileName())
    }

    fun getPlatformOptionsFile(): Path {
        return Path.of(PathManager.getBinPath()).resolve(getFileName())
    }

    private fun getFileName(): String {
        var fileName = ApplicationNamesInfo.getInstance().getScriptName()
        if (OS.CURRENT !== OS.macOS) fileName += "64"
        if (OS.CURRENT === OS.Windows) fileName += ".exe"
        fileName += ".vmoptions"
        return fileName
    }

}