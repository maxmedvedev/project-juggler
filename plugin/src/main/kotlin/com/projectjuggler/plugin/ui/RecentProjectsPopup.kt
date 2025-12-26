package com.projectjuggler.plugin.ui

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ProjectLauncherHelper
import com.projectjuggler.plugin.model.OpenFileChooserItem
import com.projectjuggler.plugin.model.PopupListItem
import com.projectjuggler.plugin.model.RecentProjectItem
import com.projectjuggler.util.GitUtils
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.projectjuggler.core.ProjectManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.nio.file.Files
import javax.swing.DefaultListModel
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
        // Create a JBList with custom renderer
        val listModel = DefaultListModel<PopupListItem>()
        items.forEach { listModel.addElement(it) }

        // Add the "Browse..." item at the end
        listModel.addElement(OpenFileChooserItem)

        val list = JBList(listModel)
        list.cellRenderer = PopupListItemRenderer()

        // Create popup using PopupChooserBuilder
        val popup = PopupChooserBuilder(list)
            .setTitle(ProjectJugglerBundle.message("popup.recent.projects.title"))
            .setItemChosenCallback(Runnable {
                list.selectedValue?.let { handleItemSelection(it) }
            })
            .setFilterAlwaysVisible(true)
            .setNamerForFiltering { item ->
                when (item) {
                    is RecentProjectItem -> {
                        // Return searchable text: project name, branch, and path
                        buildString {
                            append(item.metadata.name)
                            append(" ")
                            item.gitBranch?.let {
                                append(it)
                                append(" ")
                            }
                            append(item.metadata.path.pathString)
                        }
                    }
                    is OpenFileChooserItem -> {
                        // Always visible by making it searchable
                        "Browse"
                    }
                }
            }
            .createPopup()

        popup.showInFocusCenter()
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

    private fun showNotification(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("project-juggler.notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
