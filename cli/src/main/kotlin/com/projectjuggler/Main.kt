package com.projectjuggler

import com.projectjuggler.cli.*
import com.projectjuggler.cli.framework.CliApp
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.platform.ConfigLocator
import com.projectjuggler.platform.IntelliJLocator
import com.projectjuggler.platform.PluginLocator
import com.projectjuggler.platform.VMOptionsLocator

fun main(args: Array<String>) {
    val app = CliApp(
        name = "project-juggler",
        version = CLI_VERSION,
        commands = listOf(
            OpenCommand(),
            OpenCopyCommand(),
            MainCommand(),
            ListCommand(),
            CleanCommand(),
            ConfigCommand(),
            RecentCommand(),
            SyncCommand()
        )
    )

    app.run(args)

    // Show auto-detect advertisement when no args are provided
    if (args.isEmpty()) {
        showAutoDetectAdvertisement()
    }
}

private fun showAutoDetectAdvertisement() {
    try {
        val configRepository = ConfigRepository.create()
        val config = configRepository.load()

        // Check if any settings could be auto-detected but aren't configured
        val canDetectIntelliJ = config.intellijPath == null && IntelliJLocator.findIntelliJ() != null
        val canDetectVmOptions = config.baseVmOptionsPath == null && VMOptionsLocator.findDefaultVMOptions() != null
        val canDetectConfig = config.baseConfigPath == null && ConfigLocator.findDefaultConfigDirectory() != null
        val canDetectPlugins = config.basePluginsPath == null && PluginLocator.findDefaultPluginsDirectory() != null

        if (canDetectIntelliJ || canDetectVmOptions || canDetectConfig || canDetectPlugins) {
            println()
            println("Tip: Run 'project-juggler config --auto-detect' to automatically configure:")
            if (canDetectIntelliJ) println("  - IntelliJ IDEA path")
            if (canDetectVmOptions) println("  - Base VM options")
            if (canDetectConfig) println("  - Base config directory")
            if (canDetectPlugins) println("  - Base plugins directory")
        }
    } catch (e: Exception) {
        // Silently ignore errors in advertisement
    }
}
