package com.projectjuggler.locators

import java.nio.file.Files
import java.nio.file.Path

object PluginLocator {

    /**
     * Find the default IntelliJ plugins directory.
     * Returns null if not found.
     */
    fun findDefaultPluginsDirectory(): Path? {
        val configDir = ConfigLocator.findDefaultConfigDirectory() ?: return null
        val pluginsDir = configDir.resolve("plugins")

        return if (Files.exists(pluginsDir) && Files.isDirectory(pluginsDir)) {
            pluginsDir
        } else {
            null
        }
    }
}