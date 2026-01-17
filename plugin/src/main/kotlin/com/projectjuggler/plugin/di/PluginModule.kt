package com.projectjuggler.plugin.di

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.plugin.services.IdeInstallationService
import org.koin.dsl.module

/**
 * Plugin-specific Koin module for IntelliJ plugin services.
 */
val pluginModule = module {
    // IdeInstallationService is a singleton (one per plugin instance)
    single {
        val ideRegistry = get<IdeRegistry>()
        IdeInstallationService(ideRegistry)
    }
}
