package com.projectjuggler.plugin.services

import com.intellij.diagnostic.VMOptions
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object AutoConfigPopulator {
    fun populate(configRepository: ConfigRepository) {
        try {
            val intellijPath = getIntelliJExecutablePath()
            val configPath = PathManager.getConfigDir()
            val vmOptionsPath = VMOptions.getUserOptionsFile()
            val pluginsPath = PathManager.getPluginsDir()

            // Update config with detected values
            configRepository.update { config ->
                var updated = config

                if (intellijPath != null) {
                    updated = updated.copy(intellijPath = intellijPath.toString())
                }

                if (vmOptionsPath != null && Files.exists(vmOptionsPath)) {
                    updated = updated.copy(baseVmOptionsPath = vmOptionsPath.toString())
                }

                updated = updated.copy(baseConfigPath = configPath.toString())
                updated = updated.copy(basePluginsPath = pluginsPath.toString())

                updated
            }

            // Update VM options hash if path exists
            if (vmOptionsPath != null && Files.exists(vmOptionsPath)) {
                BaseVMOptionsTracker.getInstance(configRepository).updateHash()
            }

        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e

            logger<AutoConfigPopulator>().error(e)
        }
    }

    private fun getIntelliJExecutablePath(): Path? {
        try {
            val homeDir = Paths.get(PathManager.getHomePath())
            val platform = Platform.current()

            val executablePath = when (platform) {
                Platform.MACOS -> homeDir.resolve("MacOS").resolve("idea")
                Platform.LINUX -> homeDir.resolve("bin").resolve("idea.sh")
                Platform.WINDOWS -> homeDir.resolve("bin").resolve("idea64.exe")
            }

            return if (Files.exists(executablePath)) {
                executablePath
            } else null
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e

            logger<AutoConfigPopulator>().error(e)
            return null
        }
    }
}