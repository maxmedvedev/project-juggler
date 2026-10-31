package com.projectjuggler.plugin.di

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.services.IdeInstallationService
import org.koin.core.context.GlobalContext
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

/**
 * Starts Koin with [pluginModule] unless the plugin's services are already registered. Called only from
 * [IdeInstallationService.getInstance], which every plugin path goes through; safe on any thread.
 */
internal fun ensurePluginKoin() {
    if (GlobalContext.getOrNull()?.getOrNull<IdeInstallationService>() == null) KoinInit.init(pluginModule)
}
