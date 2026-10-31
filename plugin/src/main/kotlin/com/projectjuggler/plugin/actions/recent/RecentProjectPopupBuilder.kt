package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.containers.addIfNotNull
import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.CurrentIdePaths
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IdePathSyncDetector
import com.projectjuggler.plugin.services.isOpenInCurrentIde
import com.projectjuggler.plugin.services.isProjectRunning
import com.projectjuggler.plugin.services.openWithInstallations
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.util.GitUtils
import java.nio.file.Files.exists
import java.nio.file.Path

internal class RecentProjectPopupBuilder(
    private val project: Project?,
) {
    @RequiresBackgroundThread
    fun show() {
        try {
            val ideConfigRepository = IdeInstallationService.currentIdeConfigRepository
            val itemsList = buildItems(ideConfigRepository, CurrentIdePaths.detect())
            // Scans the filesystem for installations, hence here on the background thread
            val service = IdeInstallationService.getInstance()
            val openWith = openWithInstallations(
                service.getAllInstallations(),
                service.currentInstallation,
                Path.of(PathManager.getHomePath()).toAbsolutePath().normalize(),
                service::getRepository,
            )

            // Show popup on EDT
            application.invokeLater {
                val popup = RecentProjectPopup(itemsList, project, openWith)
                popup.showInFocusCenter()
            }
        } catch (ex: Throwable) {
            ex.rethrowIfControlFlow()

            val message = ProjectJugglerBundle.message("notification.error.recent.projects.load.failed", ex.userMessage())
            showErrorNotification(message, project)
            logger<RecentProjectPopup>().error(message, ex)
        }
    }

    companion object {
        /**
         * The popup items for [repository], in display order. Pure apart from reading the repository,
         * the project directories, their lock files and [openInCurrentIde] (the open state of the current
         * IDE's own projects, see [isProjectRunning]).
         */
        internal fun buildItems(
            repository: IdeConfigRepository,
            currentPaths: CurrentIdePaths,
            openInCurrentIde: (ProjectPath) -> Boolean = ::isOpenInCurrentIde,
        ): List<RecentProjectPopupAction> {
            val config = repository.load()
            val isOpen = { path: ProjectPath ->
                isProjectRunning(repository, path, currentPaths.configDir, openInCurrentIde)
            }

            // Load recent projects; the index is trimmed to the same limit on every recordOpen
            val recentIndex = RecentProjectsIndex.getInstance(repository)
            val recentMetadata = recentIndex.getRecent(config.maxRecentProjects)

            // Filter out non-existent paths (deleted projects)
            val validProjects = recentMetadata.filter { metadata ->
                exists(metadata.path.path)
            }

            // Create items with git branch info (even if empty, we'll still show the "Browse..." item)
            val items = validProjects.map { metadata ->
                createRecentProjectItem(metadata, repository, isOpen)
            }

            // Create popup using ListPopupImpl with custom renderer and submenu support
            val itemsList = mutableListOf<RecentProjectPopupAction>()

            // Add path sync warning if paths are out of sync (only in main instance)
            val mismatches = IdePathSyncDetector.detectMismatches(repository, currentPaths)
            if (mismatches.isNotEmpty()) {
                itemsList.add(UpdatePathsAction(mismatches))
            }

            // Add browse button at the top
            itemsList.add(OpenFileChooserAction)
            // Add main project if configured
            val mainProjectItem = createMainProjectItem(config, isOpen)
            itemsList.addIfNotNull(mainProjectItem)
            itemsList.addAll(items.filterNot { it.isMainProject })

            // Check if there are any projects (recent or main)
            val hasProjects = items.isNotEmpty() || mainProjectItem != null

            if (hasProjects) {
                // Add sync actions when there are projects to sync
                itemsList.add(SyncAllProjectsAction(SyncType.All))
                itemsList.add(SyncAllProjectsAction(SyncType.VmOptions))
                itemsList.add(SyncAllProjectsAction(SyncType.Config))
                itemsList.add(SyncAllProjectsAction(SyncType.Plugins))
                itemsList.add(ToggleSyncOnUpdateAction(config.syncPluginsOnIdeUpdate))
            } else {
                // Show import action when no projects
                itemsList.add(ImportRecentProjectsAction)
            }

            return itemsList
        }

        private fun createMainProjectItem(
            config: IdeConfig,
            isOpen: (ProjectPath) -> Boolean,
        ): OpenRecentProjectAction? {
            val mainProjectPathStr = config.mainProjectPath ?: return null
            val path = ProjectPath(mainProjectPathStr)
            val gitBranch = GitUtils.detectGitBranch(path.path)
            return OpenRecentProjectAction(path, gitBranch, isOpen(path), isMainProject = true)
        }

        private fun createRecentProjectItem(
            metadata: ProjectMetadata,
            ideConfigRepository: IdeConfigRepository,
            isOpen: (ProjectPath) -> Boolean,
        ): OpenRecentProjectAction {
            val gitBranch = GitUtils.detectGitBranch(metadata.path.path)
            val isMainProject = MainProjectService.isMainProject(ideConfigRepository, metadata.path)
            return OpenRecentProjectAction(metadata.path, gitBranch, isOpen(metadata.path), isMainProject)
        }
    }
}
