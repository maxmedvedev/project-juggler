package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** What is wrong with a stored base path at startup. */
internal enum class IdePathProblem {
    /** The stored path no longer exists. */
    MISSING,

    /** The stored path exists but differs from the running IDE's ([IdePathSyncDetector.detectMismatches]). */
    OUT_OF_SYNC,
}

/**
 * Decides what the startup path check reports: only once per IDE session (the first project opened), and only
 * in the main instance.
 */
internal object IdePathsStartupCheck {
    private val checked = AtomicBoolean(false)

    /**
     * The problem of each stored base path, in [IdePathKind] order; non-empty only for the first call per IDE
     * session and only in the main instance.
     */
    fun problemsToReport(repository: IdeConfigRepository, current: CurrentIdePaths): Map<IdePathKind, IdePathProblem> {
        if (checked.getAndSet(true)) return emptyMap()
        if (!IdePathSyncDetector.isMainInstance(repository, current)) return emptyMap()

        val config = repository.load()
        val mismatches = IdePathSyncDetector.detectMismatches(repository, current)
        return IdePathKind.entries.mapNotNull { kind ->
            val stored = kind.stored(config)
            when {
                stored != null && !Files.exists(Path.of(stored)) -> kind to IdePathProblem.MISSING
                kind in mismatches -> kind to IdePathProblem.OUT_OF_SYNC
                else -> null
            }
        }.toMap()
    }

    @TestOnly
    internal fun resetForTests() {
        checked.set(false)
    }
}
