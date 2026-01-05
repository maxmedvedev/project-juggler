package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.containers.addIfNotNull
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ProjectLauncherHelper
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.util.GitUtils
import com.projectjuggler.util.ProjectLockUtils
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
                createRecentProjectItem(metadata, configRepository)
            }

            // Create popup using ListPopupImpl with custom renderer and submenu support
            val itemsList = mutableListOf<PopupListItem>()

            // Add browse button at the top
            itemsList.add(OpenFileChooserItem)
            // Add main project if configured
            itemsList.addIfNotNull(createMainProjectItem(configRepository))
            itemsList.addAll(items)
            itemsList.add(SyncProjectsItem(SyncType.All))
            itemsList.add(SyncProjectsItem(SyncType.VmOptions))
            itemsList.add(SyncProjectsItem(SyncType.Config))
            itemsList.add(SyncProjectsItem(SyncType.Plugins))

            // Show popup on EDT
            application.invokeLater {
                val popupStep = RecentProjectPopupStep(itemsList, project, configRepository)
                val popup = RecentProjectPopup(popupStep, project)
                popup.showInFocusCenter()
            }
        } catch (ex: Exception) {
            showErrorNotification(
                ProjectJugglerBundle.message("notification.error.recent.projects.load.failed", ex.message ?: ""),
                project
            )
            ex.printStackTrace()
        }
    }

    private fun createMainProjectItem(configRepository: ConfigRepository): RecentProjectItem? {
        val mainProjectPathStr = configRepository.load().mainProjectPath ?: return null
        val path = ProjectPath(mainProjectPathStr)
        val gitBranch = GitUtils.detectGitBranch(path.path)
        val isOpen = detectIfProjectOpen(configRepository, path)
        return RecentProjectItem(path, gitBranch, isOpen)
    }

    private fun createRecentProjectItem(
        metadata: ProjectMetadata,
        configRepository: ConfigRepository
    ): RecentProjectItem {
        val gitBranch = GitUtils.detectGitBranch(metadata.path.path)
        val isOpen = detectIfProjectOpen(configRepository, metadata.path)
        return RecentProjectItem(metadata.path, gitBranch, isOpen)
    }

    private fun detectIfProjectOpen(configRepository: ConfigRepository, projectPath: ProjectPath): Boolean {
        return ProjectLockUtils.isProjectOpen(configRepository, projectPath)
    }
}

@Suppress("jol")
private class RecentProjectPopup(popupStep: BaseListPopupStep<PopupListItem>, project: Project?) :
    ListPopupImpl(project, popupStep) {
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

    private fun createProjectSubmenu(item: RecentProjectItem): PopupStep<ProjectAction> {
        val actions = listOf(
            ProjectAction.OpenProject,
            ProjectAction.SyncSettings(SyncType.All),
            ProjectAction.SyncSettings(SyncType.VmOptions),
            ProjectAction.SyncSettings(SyncType.Config),
            ProjectAction.SyncSettings(SyncType.Plugins)
        )

        return object : BaseListPopupStep<ProjectAction>(null, actions) {
            override fun onChosen(selectedValue: ProjectAction, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                when (selectedValue) {
                    ProjectAction.OpenProject ->
                        ProjectLauncherHelper.launchProject(project, configRepository, item.projectPath)
                    is ProjectAction.SyncSettings ->
                        SyncSettingsService.getInstance(project).syncProject(item.projectPath, selectedValue.syncType)
                }
                return FINAL_CHOICE
            }

            override fun getTextFor(value: ProjectAction): String = when (value) {
                ProjectAction.OpenProject ->
                    ProjectJugglerBundle.message("popup.recent.projects.action.open.project")
                is ProjectAction.SyncSettings -> when (value.syncType) {
                    SyncType.All -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.all")
                    SyncType.VmOptions -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.vmoptions")
                    SyncType.Config -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.config")
                    SyncType.Plugins -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.plugins")
                }
            }
        }
    }

    private fun handleItemSelection(item: PopupListItem) {
        when (item) {
            is RecentProjectItem -> ProjectLauncherHelper.launchProject(project, configRepository, item.projectPath)
            is OpenFileChooserItem -> showFileChooserAndLaunch()
            is SyncProjectsItem -> SyncSettingsService.getInstance(project).syncAllProjects(item.syncType)
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
            showErrorNotification(
                ProjectJugglerBundle.message("notification.error.not.directory", selectedFile.path),
                project
            )
            return
        }

        ProjectLauncherHelper.launchProject(project, configRepository, projectPath)
    }

    override fun hasSubstep(selectedValue: PopupListItem): Boolean =
        selectedValue is RecentProjectItem

    override fun getTextFor(value: PopupListItem): String = when (value) {
        is RecentProjectItem -> formatDisplayText(value.projectPath, value.gitBranch)
        is OpenFileChooserItem -> ProjectJugglerBundle.message("popup.open.file.chooser.label")
        is SyncProjectsItem -> when (value.syncType) {
            SyncType.All -> ProjectJugglerBundle.message("popup.sync.all.projects.label")
            SyncType.VmOptions -> ProjectJugglerBundle.message("popup.sync.vmoptions.label")
            SyncType.Config -> ProjectJugglerBundle.message("popup.sync.config.label")
            SyncType.Plugins -> ProjectJugglerBundle.message("popup.sync.plugins.label")
        }
    }

    private fun formatDisplayText(projectPath: ProjectPath, gitBranch: String?): String {
        val name = projectPath.name
        val path = projectPath.pathString

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

    override fun isSpeedSearchEnabled(): Boolean = true

    override fun getIndexedString(value: PopupListItem): String {
        return when (value) {
            is RecentProjectItem -> buildProjectSearchString(value.projectPath, value.gitBranch)
            is OpenFileChooserItem -> ProjectJugglerBundle.message("popup.open.file.chooser.search")
            is SyncProjectsItem -> when (value.syncType) {
                SyncType.All -> ProjectJugglerBundle.message("popup.sync.all.projects.label")
                SyncType.VmOptions -> ProjectJugglerBundle.message("popup.sync.vmoptions.label")
                SyncType.Config -> ProjectJugglerBundle.message("popup.sync.config.label")
                SyncType.Plugins -> ProjectJugglerBundle.message("popup.sync.plugins.label")
            }
        }
    }

    private fun buildProjectSearchString(projectPath: ProjectPath, gitBranch: String?): String {
        return buildString {
            append(projectPath.name)
            append(" ")
            gitBranch?.let {
                append(it)
                append(" ")
            }
            append(projectPath.pathString)
        }
    }
}
