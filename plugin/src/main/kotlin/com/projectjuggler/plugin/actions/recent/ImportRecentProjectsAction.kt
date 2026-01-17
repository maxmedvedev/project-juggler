package com.projectjuggler.plugin.actions.recent

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import java.nio.file.Files
import java.nio.file.Paths

object ImportRecentProjectsAction : RecentProjectPopupAction {
    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        step.doFinalStep {
            importRecentProjects(project)
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String =
        ProjectJugglerBundle.message("popup.import.recent.projects.label")

    private fun importRecentProjects(project: Project?) {
        // Get IntelliJ's recent projects
        val manager = RecentProjectsManager.getInstance() as RecentProjectsManagerBase
        val recentPaths = manager.getRecentPaths()

        // Filter to valid existing directories
        val validPaths = recentPaths.mapNotNull { pathStr ->
            val path = Paths.get(pathStr)
            if (Files.isDirectory(path)) pathStr else null
        }

        if (validPaths.isEmpty()) {
            Messages.showInfoMessage(
                project,
                ProjectJugglerBundle.message("dialog.import.projects.no.projects"),
                ProjectJugglerBundle.message("dialog.import.projects.title")
            )
            return
        }

        // Ask for confirmation
        val result = Messages.showYesNoDialog(
            project,
            ProjectJugglerBundle.message("dialog.import.projects.confirm", validPaths.size),
            ProjectJugglerBundle.message("dialog.import.projects.title"),
            Messages.getQuestionIcon()
        )

        if (result != Messages.YES) {
            return
        }

        application.executeOnPooledThread {
            // Import all projects
            val ideConfigRepository = IdeInstallationService.currentIdeConfigRepository
            val projectManager = ProjectManager.getInstance(ideConfigRepository)
            val recentIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

            validPaths.forEach { pathStr ->
                val projectPath = ProjectPath(pathStr)
                // Register project metadata (creates ProjectMetadata with directories)
                projectManager.registerOrUpdate(projectPath)
                // Record in recent projects index
                recentIndex.recordOpen(projectPath)
            }

            RecentProjectPopupBuilder(project).show()
        }
    }
}
