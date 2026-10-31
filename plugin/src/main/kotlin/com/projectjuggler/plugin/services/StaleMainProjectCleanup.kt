package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ProjectCleaner
import java.nio.file.Path

/**
 * Removes the stale isolated entry of the main project when running in the main instance
 * (see [ProjectCleaner.cleanStaleMainProjectEntry]). An isolated instance has no business touching
 * other entries, so it returns null there without doing anything.
 *
 * @return the removed metadata, or null if nothing was removed
 */
internal fun cleanStaleMainProjectEntryIfMainInstance(
    repository: IdeConfigRepository,
    currentConfigDir: Path,
): ProjectMetadata? {
    if (!MainProjectService.isRunningInMainInstance(repository, currentConfigDir)) return null
    return ProjectCleaner.getInstance(repository).cleanStaleMainProjectEntry()
}
