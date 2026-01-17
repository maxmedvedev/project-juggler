package com.projectjuggler.di

import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * Koin initialization for project-juggler.
 */
object KoinInit {

    /**
     * Initializes Koin with project-juggler modules.
     * Safe to call multiple times (checks if already started).
     *
     * @param additionalModules optional modules to load (e.g., plugin-specific modules)
     */
    fun init(vararg additionalModules: Module) {
        if (GlobalContext.getOrNull() != null) {
            // Already initialized, just load additional modules if any
            if (additionalModules.isNotEmpty()) {
                loadKoinModules(additionalModules.toList())
            }
            return
        }

        startKoin {
            modules(coreModule, ideScopedModule)
            if (additionalModules.isNotEmpty()) {
                modules(additionalModules.toList())
            }
        }
    }

    /**
     * Stops Koin (for cleanup/testing).
     */
    fun stop() {
        IdeScopeManager.getInstance().closeAllScopes()
        IdeScopeManager.reset()
        stopKoin()
    }
}
