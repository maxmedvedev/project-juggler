package com.projectjuggler.locators

import java.nio.file.Files
import java.nio.file.Path

object PluginLocator {

    /**
     * Find the default IntelliJ plugins directory.
     * Returns null if not found.
     */
    fun findDefaultPluginsDirectory(): Path? = findDefaultPluginsDirectory(LocatorEnvironment.current())

    internal fun findDefaultPluginsDirectory(env: LocatorEnvironment): Path? {
        val configDir = ConfigLocator.findDefaultConfigDirectory(env) ?: return null
        val pluginsDir = configDir.resolve("plugins")

        return if (Files.exists(pluginsDir) && Files.isDirectory(pluginsDir)) {
            pluginsDir
        } else {
            null
        }
    }
}