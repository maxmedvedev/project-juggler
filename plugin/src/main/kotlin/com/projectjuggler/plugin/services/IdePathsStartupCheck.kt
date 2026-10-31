package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides whether the startup path check reports drift: only once per IDE session (the first project opened),
 * and only in the main instance ([IdePathSyncDetector.detectMismatches] is empty in an isolated one).
 */
internal object IdePathsStartupCheck {
    private val checked = AtomicBoolean(false)

    /** Mismatches to report, or empty; non-empty only for the first call per IDE session. */
    fun mismatchesToReport(repository: IdeConfigRepository, current: CurrentIdePaths): List<IdePathKind> {
        if (checked.getAndSet(true)) return emptyList()
        return IdePathSyncDetector.detectMismatches(repository, current)
    }

    @TestOnly
    internal fun resetForTests() {
        checked.set(false)
    }
}
