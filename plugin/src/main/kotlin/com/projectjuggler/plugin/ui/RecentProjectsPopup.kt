package com.projectjuggler.plugin.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ProjectLauncherHelper
import com.projectjuggler.plugin.model.OpenFileChooserItem
import com.projectjuggler.plugin.model.PopupListItem
import com.projectjuggler.plugin.model.RecentProjectItem
import com.projectjuggler.plugin.model.SyncAllProjectsItem
import com.projectjuggler.util.GitUtils
import java.awt.Component
import java.nio.file.Files.exists
import javax.swing.JList
import kotlin.io.path.isDirectory

internal class RecentProjectsPopup(
    private val project: Project?,
) {
    @RequiresBackgroundThread
    fun show() {
        try {
            val configRepository = ConfigRepository.create()

            // Load recent projects
            val recentIndex = RecentProjectsIndex.getInstance(configRepository)
            val recentMetadata = recentIndex.getRecent(20)

            // Filter out non-existent paths (deleted projects)
            val validProjects = recentMetadata.filter { metadata ->
                exists(metadata.path.path)
            }

            // Create items with git branch info (even if empty, we'll still show the "Browse..." item)
            val items = validProjects.map { metadata ->
                createRecentProjectItem(metadata)
            }

            // Create popup using ListPopupImpl with custom renderer and submenu support
            val itemsList = mutableListOf<PopupListItem>()
            itemsList.addAll(items)
            itemsList.add(OpenFileChooserItem)
            itemsList.add(SyncAllProjectsItem)

            // Show popup on EDT
            application.invokeLater {
                val popupStep = RecentProjectPopupStep(items, project, configRepository)
                val popup = RecentProjectPopup(popupStep, project)
                popup.showInFocusCenter()
            }
        } catch (ex: Exception) {
            showNotification("Failed to load recent projects: ${ex.message}", project, NotificationType.ERROR)
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
}

@Suppress("jol")
private class RecentProjectPopup(popupStep: BaseListPopupStep<PopupListItem>, project: Project?) : ListPopupImpl(project, popupStep) {
    override fun getListElementRenderer(): PopupListElementRenderer<*> {
        @Suppress("UNCHECKED_CAST")
        return ProjectItemRenderer(this) as PopupListElementRenderer<*>
    }
}

private class ProjectItemRenderer(popup: ListPopupImpl) : PopupListElementRenderer<PopupListItem>(popup) {
    private val customRenderer = PopupListItemRenderer()

    override fun getListCellRendererComponent(
        list: JList<out PopupListItem>,
        value: PopupListItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component = customRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
}

private class RecentProjectPopupStep(
    items: List<PopupListItem>,
    val project: Project?,
    val configRepository: ConfigRepository
) : BaseListPopupStep<PopupListItem>(
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

    private fun createProjectSubmenu(item: RecentProjectItem): PopupStep<String> {
        val actions = listOf("Open Project", "Sync All Settings")
        return object : BaseListPopupStep<String>(null, actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                when (selectedValue) {
                    "Open Project" -> launchProject(item, project, configRepository)
                    "Sync All Settings" -> syncProjectSettings(item.metadata)
                }
                return FINAL_CHOICE
            }

            override fun getTextFor(value: String): String = value
        }
    }

    private fun handleItemSelection(item: PopupListItem) {
        when (item) {
            is RecentProjectItem -> launchProject(item, project, configRepository)
            is OpenFileChooserItem -> showFileChooserAndLaunch()
            is SyncAllProjectsItem -> syncAllProjects()
        }
    }

    private fun syncAllProjects() {
        application.executeOnPooledThread {
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
                showNotification("Synced ${allProjects.size} projects successfully", project, NotificationType.INFORMATION)
            } catch (e: Exception) {
                showNotification("Failed to sync projects: ${e.message}", project, NotificationType.ERROR)
            }
        }
    }

    private fun showFileChooserAndLaunch() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = ProjectJugglerBundle.message("file.chooser.title")
            description = ProjectJugglerBundle.message("file.chooser.description")
        }

        val selectedFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        val projectPath = ProjectManager.getInstance(configRepository).resolvePath(selectedFile.path)
        if (!projectPath.path.isDirectory()) {
            showNotification(ProjectJugglerBundle.message("notification.error.not.directory", selectedFile.path), project, NotificationType.ERROR)
            return
        }

        ProjectLauncherHelper.launchProject(project, configRepository, projectPath)
    }

    private fun syncProjectSettings(metadata: ProjectMetadata) {
        application.executeOnPooledThread {
            try {
                ProjectLauncher(configRepository).syncProject(metadata, syncVmOptions = true, syncConfig = true, syncPlugins = true)
                showNotification("Settings synced successfully for ${metadata.path.name}", project, NotificationType.INFORMATION)
            } catch (e: Exception) {
                showNotification("Failed to sync settings: ${e.message}", project, NotificationType.ERROR)
            }
        }
    }

    override fun hasSubstep(selectedValue: PopupListItem): Boolean = selectedValue is RecentProjectItem

    override fun getTextFor(value: PopupListItem): String = when (value) {
        is RecentProjectItem -> value.displayText
        is OpenFileChooserItem -> ProjectJugglerBundle.message("popup.open.file.chooser.label")
        is SyncAllProjectsItem -> "Sync all projects"
    }

    override fun isSpeedSearchEnabled(): Boolean = true

    override fun getIndexedString(value: PopupListItem): String {
        return when (value) {
            is RecentProjectItem -> {
                // Return searchable text: project name, branch, and path
                buildString {
                    append(value.metadata.name)
                    append(" ")
                    value.gitBranch?.let {
                        append(it)
                        append(" ")
                    }
                    append(value.metadata.path.pathString)
                }
            }
            is OpenFileChooserItem -> "Browse"
            is SyncAllProjectsItem -> "Sync all projects"
        }
    }
}

private fun launchProject(item: RecentProjectItem, project: Project?, configRepository: ConfigRepository) {
    ProjectLauncherHelper.launchProject(
        project,
        configRepository,
        item.metadata.path,
    )
}

private fun showNotification(message: String, project: Project?, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("project-juggler.notifications")
        .createNotification(message, type)
        .notify(project)
}
