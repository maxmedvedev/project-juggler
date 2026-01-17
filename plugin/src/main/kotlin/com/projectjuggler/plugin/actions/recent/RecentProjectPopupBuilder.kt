package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.containers.addIfNotNull
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.util.GitUtils
import com.projectjuggler.util.ProjectLockUtils
import java.nio.file.Files.exists

internal class RecentProjectPopupBuilder(
    private val project: Project?,
) {
    @RequiresBackgroundThread
    fun show() {
        try {
            val ideConfigRepository = IdeInstallationService.currentIdeConfigRepository

            // Load recent projects
            val recentIndex = RecentProjectsIndex.getInstance(ideConfigRepository)
            val recentMetadata = recentIndex.getRecent(20)

            // Filter out non-existent paths (deleted projects)
            val validProjects = recentMetadata.filter { metadata ->
                exists(metadata.path.path)
            }

            // Create items with git branch info (even if empty, we'll still show the "Browse..." item)
            val items = validProjects.map { metadata ->
                createRecentProjectItem(metadata, ideConfigRepository)
            }

            // Create popup using ListPopupImpl with custom renderer and submenu support
            val itemsList = mutableListOf<RecentProjectPopupAction>()

            // Add browse button at the top
            itemsList.add(OpenFileChooserAction)
            // Add main project if configured
            itemsList.addIfNotNull(createMainProjectItem(ideConfigRepository))
            itemsList.addAll(items)
            itemsList.add(SyncAllProjectsAction(SyncType.All))
            itemsList.add(SyncAllProjectsAction(SyncType.VmOptions))
            itemsList.add(SyncAllProjectsAction(SyncType.Config))
            itemsList.add(SyncAllProjectsAction(SyncType.Plugins))

            // Show popup on EDT
            application.invokeLater {
                val popup = RecentProjectPopup(itemsList, project)
                popup.showInFocusCenter()
            }
        } catch (ex: Throwable) {
            if (ex is ControlFlowException) throw ex

            val message = ProjectJugglerBundle.message("notification.error.recent.projects.load.failed", ex.message ?: "")
            showErrorNotification(message, project)
            logger<RecentProjectPopup>().error(message, ex)
        }
    }

    private fun createMainProjectItem(ideConfigRepository: IdeConfigRepository): OpenRecentProjectAction? {
        val mainProjectPathStr = ideConfigRepository.load().mainProjectPath ?: return null
        val path = ProjectPath(mainProjectPathStr)
        val gitBranch = GitUtils.detectGitBranch(path.path)
        val isOpen = detectIfProjectOpen(ideConfigRepository, path)
        return OpenRecentProjectAction(path, gitBranch, isOpen, isMainProject = true)
    }

    private fun createRecentProjectItem(
        metadata: ProjectMetadata,
        ideConfigRepository: IdeConfigRepository
    ): OpenRecentProjectAction {
        val gitBranch = GitUtils.detectGitBranch(metadata.path.path)
        val isOpen = detectIfProjectOpen(ideConfigRepository, metadata.path)
        val isMainProject = MainProjectService.isMainProject(ideConfigRepository, metadata.path)
        return OpenRecentProjectAction(metadata.path, gitBranch, isOpen, isMainProject)
    }

    private fun detectIfProjectOpen(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Boolean {
        return ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath)
    }
}
