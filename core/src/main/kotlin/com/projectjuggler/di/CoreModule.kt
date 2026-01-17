package com.projectjuggler.di

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.*
import com.projectjuggler.process.IntelliJLauncher
import com.projectjuggler.process.ProjectLauncher
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Scope qualifier for IDE-specific dependency graphs.
 * Each IdeConfigRepository gets its own scope with this qualifier.
 */
val IDE_SCOPE = named("IDE_SCOPE")

/**
 * Core Koin module defining application-wide singletons.
 */
val coreModule = module {
    // IdeRegistry is a true singleton (manages all IDE installations)
    single { IdeRegistry() }
}

/**
 * Scoped module for per-IDE-installation dependencies.
 * All services within a scope share the same IdeConfigRepository.
 * Note: IdeConfigRepository is declared into the scope when it's created (see IdeScopeManager).
 */
val ideScopedModule = module {
    scope(IDE_SCOPE) {
        // Core services - one instance per IDE scope
        // Using direct constructor calls to avoid circular dependency with getInstance
        scoped { ProjectManager(get()) }
        scoped { DirectoryManager(get()) }
        scoped { BaseVMOptionsTracker(get()) }
        scoped { ShutdownSignalManager(get()) }
        scoped { RecentProjectsIndex(get()) }
        scoped { IntelliJLauncher(get()) }
        scoped { ProjectLauncher(get()) }
        scoped { ProjectCleaner(get()) }
    }
}
