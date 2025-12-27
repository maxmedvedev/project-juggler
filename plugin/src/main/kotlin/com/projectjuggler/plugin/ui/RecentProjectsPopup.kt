package com.projectjuggler.plugin.ui

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ProjectLauncherHelper
import com.projectjuggler.plugin.model.OpenFileChooserItem
import com.projectjuggler.plugin.model.PopupListItem
import com.projectjuggler.plugin.model.RecentProjectItem
import com.projectjuggler.plugin.model.SyncAllProjectsItem
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.util.GitUtils
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.projectjuggler.core.ProjectManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.nio.file.Files
import kotlin.io.path.isDirectory

internal class RecentProjectsPopup(
    private val project: Project?,
) {
    val configRepository = ConfigRepository.create()

    @RequiresBackgroundThread
    fun show() {
        computeDataOnBGT()
    }

    @RequiresEdt
    private fun createAndShowItems(items: List<RecentProjectItem>) {
        // Create popup using JBPopupFactory with submenu support
        val itemsList = mutableListOf<PopupListItem>()
        itemsList.addAll(items)
        itemsList.add(OpenFileChooserItem)
        itemsList.add(SyncAllProjectsItem)

        val popup = JBPopupFactory.getInstance().createListPopup(
            createPopupStep(itemsList)
        )
        popup.showInFocusCenter()
    }

    private fun createPopupStep(items: List<PopupListItem>): BaseListPopupStep<PopupListItem> {
        return object : BaseListPopupStep<PopupListItem>(
            ProjectJugglerBundle.message("popup.recent.projects.title"),
            items
        ) {
            override fun onChosen(selectedValue: PopupListItem, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    handleItemSelection(selectedValue)
                    return FINAL_CHOICE
                }
                return when (selectedValue) {
                    is RecentProjectItem -> createProjectSubmenu(selectedValue)
                    else -> FINAL_CHOICE
                }
            }

            override fun hasSubstep(selectedValue: PopupListItem): Boolean {
                return selectedValue is RecentProjectItem
            }

            override fun getTextFor(value: PopupListItem): String {
                return when (value) {
                    is RecentProjectItem -> value.displayText
                    is OpenFileChooserItem -> ProjectJugglerBundle.message("popup.open.file.chooser.label")
                    is SyncAllProjectsItem -> "Sync all projects"
                }
            }
        }
    }

    private fun createProjectSubmenu(item: RecentProjectItem): PopupStep<String> {
        val actions = listOf("Open Project", "Sync All Settings")
        return object : BaseListPopupStep<String>(null, actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                when (selectedValue) {
                    "Open Project" -> launchProject(item)
                    "Sync All Settings" -> syncProjectSettings(item.metadata)
                }
                return FINAL_CHOICE
            }

            override fun getTextFor(value: String): String = value
        }
    }

    @RequiresBackgroundThread
    private fun computeDataOnBGT() {
        try {
            // Load recent projects
            val recentIndex = RecentProjectsIndex.getInstance(configRepository)
            val recentMetadata = recentIndex.getRecent(20)

            // Filter out non-existent paths (deleted projects)
            val validProjects = recentMetadata.filter { metadata ->
                Files.exists(metadata.path.path)
            }

            // Create items with git branch info (even if empty, we'll still show the "Browse..." item)
            val items = validProjects.map { metadata ->
                createRecentProjectItem(metadata)
            }

            // Show popup on EDT
            ApplicationManager.getApplication().invokeLater {
                createAndShowItems(items)
            }
        } catch (ex: Exception) {
            ApplicationManager.getApplication().invokeLater {
                showNotification("Failed to load recent projects: ${ex.message}")
            }
            ex.printStackTrace()
        }
    }

    private fun createRecentProjectItem(metadata: ProjectMetadata): RecentProjectItem {
        val gitBranch = GitUtils.detectGitBranch(metadata.path.path)
        val displayText = formatDisplayText(metadata, gitBranch)
        return RecentProjectItem(metadata, gitBranch, displayText)
    }

    private fun formatDisplayText(metadata: ProjectMetadata, gitBranch: String?): String {
        val name = metadata.name
        val path = metadata.path.pathString

        // Format: "ProjectName - [branch] - /path/to/project"
        return buildString {
            append(name)
            if (gitBranch != null) {
                append(" - [")
                append(gitBranch)
                append("]")
            }
            append(" - ")
            append(path)
        }
    }

    private fun handleItemSelection(item: PopupListItem) {
        when (item) {
            is RecentProjectItem -> launchProject(item)
            is OpenFileChooserItem -> showFileChooserAndLaunch()
            is SyncAllProjectsItem -> syncAllProjects()
        }
    }

    private fun showFileChooserAndLaunch() {
        // Show directory chooser dialog
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = ProjectJugglerBundle.message("file.chooser.title")
            description = ProjectJugglerBundle.message("file.chooser.description")
        }

        val selectedFile = FileChooser.chooseFile(descriptor, project, null)
            ?: return // User cancelled the dialog

        val projectPath = ProjectManager.getInstance(configRepository).resolvePath(selectedFile.path)
        if (!projectPath.path.isDirectory()) {
            showNotification(ProjectJugglerBundle.message("notification.error.not.directory", selectedFile.path))
            return
        }

        // Launch project using shared helper
        ProjectLauncherHelper.launchProject(project, configRepository, projectPath)
    }

    private fun launchProject(item: RecentProjectItem) {
        ProjectLauncherHelper.launchProject(
            project,
            configRepository,
            item.metadata.path,
        )
    }

    private fun syncProjectSettings(metadata: ProjectMetadata) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ProjectLauncher(configRepository).syncProject(
                    metadata,
                    syncVmOptions = true,
                    syncConfig = true,
                    syncPlugins = true
                )

                showNotification(
                    "Settings synced successfully for ${metadata.path.name}",
                    NotificationType.INFORMATION
                )
            } catch (e: Exception) {
                showNotification(
                    "Failed to sync settings: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }
    }

    private fun syncAllProjects() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val allProjects = configRepository.loadAllProjects()
                allProjects.forEach { projectMetadata ->
                    ProjectLauncher(configRepository).syncProject(
                        projectMetadata,
                        syncVmOptions = true,
                        syncConfig = true,
                        syncPlugins = true
                    )
                }
                showNotification(
                    "Synced ${allProjects.size} projects successfully",
                    NotificationType.INFORMATION
                )
            } catch (e: Exception) {
                showNotification(
                    "Failed to sync projects: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }
    }

    private fun showNotification(message: String, type: NotificationType = NotificationType.ERROR) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("project-juggler.notifications")
                .createNotification(message, type)
                .notify(project)
        }
    }
}
