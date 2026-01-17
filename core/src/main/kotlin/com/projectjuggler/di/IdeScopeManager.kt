package com.projectjuggler.di

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import org.koin.core.context.GlobalContext
import org.koin.core.scope.Scope
import java.util.concurrent.ConcurrentHashMap

/**
 * Extension function to get the Koin scope for this repository.
 */
fun IdeConfigRepository.getScope(): Scope =
    IdeScopeManager.getInstance().getScope(this)

/**
 * Manages Koin scopes for IDE installations.
 * Each IdeConfigRepository gets its own scope for dependency injection.
 */
class IdeScopeManager {

    private val scopes = ConcurrentHashMap<String, Scope>()

    /**
     * Gets or creates a Koin scope for the given IDE installation.
     * The scope contains all services scoped to this specific IDE.
     */
    fun getScope(repository: IdeConfigRepository): Scope {
        val scopeId = repository.installation.directoryName

        return scopes.computeIfAbsent(scopeId) {
            createScope(scopeId, repository)
        }
    }

    private fun createScope(scopeId: String, repository: IdeConfigRepository): Scope {
        val koin = GlobalContext.get()
        val scope = koin.createScope(scopeId, IDE_SCOPE)

        // Provide the IdeConfigRepository to this scope
        scope.declare(repository)

        return scope
    }

    /**
     * Closes a scope when an IDE installation is no longer needed.
     */
    fun closeScope(installation: IdeInstallation) {
        val scopeId = installation.directoryName
        scopes.remove(scopeId)?.close()
    }

    /**
     * Closes all scopes (for cleanup/shutdown).
     */
    fun closeAllScopes() {
        scopes.values.forEach { it.close() }
        scopes.clear()
    }

    companion object {
        @Volatile
        private var instance: IdeScopeManager? = null

        fun getInstance(): IdeScopeManager {
            return instance ?: synchronized(this) {
                instance ?: IdeScopeManager().also { instance = it }
            }
        }

        /**
         * Resets the singleton instance (for testing).
         */
        internal fun reset() {
            instance?.closeAllScopes()
            instance = null
        }
    }
}
