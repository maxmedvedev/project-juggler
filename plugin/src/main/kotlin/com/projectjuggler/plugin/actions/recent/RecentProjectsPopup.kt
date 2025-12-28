package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
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
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ProjectLauncherHelper
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
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

            // Add main project at the top if configured
            itemsList.addIfNotNull(createMainProjectItem(configRepository))
            itemsList.addAll(items)
            itemsList.add(OpenFileChooserItem)
            itemsList.add(SyncAllProjectsItem)

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

    private fun createProjectSubmenu(item: RecentProjectItem): PopupStep<String> {
        val openProjectAction = ProjectJugglerBundle.message("popup.recent.projects.action.open.project")
        val syncSettingsAction = ProjectJugglerBundle.message("popup.recent.projects.action.sync.settings")
        val actions = listOf(openProjectAction, syncSettingsAction)
        return object : BaseListPopupStep<String>(null, actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                when (selectedValue) {
                    openProjectAction -> ProjectLauncherHelper.launchProject(project, configRepository, item.projectPath)
                    syncSettingsAction -> syncProjectSettings(item.projectPath)
                }
                return FINAL_CHOICE
            }

            override fun getTextFor(value: String): String = value
        }
    }

    private fun handleItemSelection(item: PopupListItem) {
        when (item) {
            is RecentProjectItem -> ProjectLauncherHelper.launchProject(project, configRepository, item.projectPath)
            is OpenFileChooserItem -> showFileChooserAndLaunch()
            is SyncAllProjectsItem -> syncAllProjects()
        }
    }

    private fun syncAllProjects() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            ProjectJugglerBundle.message("progress.sync.all.projects")
        ) {
            override fun run(p0: ProgressIndicator) {
                try {
                    p0.isIndeterminate = false
                    val allProjects = configRepository.loadAllProjects()
                    allProjects.forEachIndexed { index, projectMetadata ->
                        p0.text = ProjectJugglerBundle.message("progress.sync.project.settings", projectMetadata.path.name)

                        p0.fraction = index.toDouble() / allProjects.size
                        ProjectLauncher(configRepository).syncProject(projectMetadata, syncVmOptions = true, syncConfig = false, syncPlugins = false)

                        p0.fraction = (3 * index.toDouble() +  1) / (allProjects.size * 3)
                        ProjectLauncher(configRepository).syncProject(projectMetadata, syncVmOptions = false, syncConfig = true, syncPlugins = false)

                        p0.fraction = (3* index.toDouble() + 2) / (allProjects.size * 3)
                        ProjectLauncher(configRepository).syncProject(projectMetadata, syncVmOptions = false, syncConfig = false, syncPlugins = true)
                    }
                    showInfoNotification(
                        ProjectJugglerBundle.message("notification.success.sync.all.projects", allProjects.size),
                        project
                    )
                } catch (e: Exception) {
                    showErrorNotification(
                        ProjectJugglerBundle.message("notification.error.sync.projects.failed", e.message ?: ""),
                        project
                    )
                }
            }


        })
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

    private fun syncProjectSettings(projectPath: ProjectPath) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            ProjectJugglerBundle.message("progress.sync.project.settings", projectPath.name)
        ) {
            override fun run(p0: ProgressIndicator) {
                val metadata = ProjectManager.getInstance(configRepository).get(projectPath) ?: return
                try {
                    ProjectLauncher(configRepository).syncProject(metadata, true, true, true)
                    showInfoNotification(
                        ProjectJugglerBundle.message("notification.success.sync.project.settings", metadata.path.name),
                        project
                    )
                } catch (e: Exception) {
                    showErrorNotification(
                        ProjectJugglerBundle.message("notification.error.sync.settings.failed", e.message ?: ""),
                        project
                    )
                }
            }
        })
    }

    override fun hasSubstep(selectedValue: PopupListItem): Boolean =
        selectedValue is RecentProjectItem

    override fun getTextFor(value: PopupListItem): String = when (value) {
        is RecentProjectItem -> formatDisplayText(value.projectPath, value.gitBranch)
        is OpenFileChooserItem -> ProjectJugglerBundle.message("popup.open.file.chooser.label")
        is SyncAllProjectsItem -> ProjectJugglerBundle.message("popup.sync.all.projects.label")
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
            is SyncAllProjectsItem -> ProjectJugglerBundle.message("popup.sync.all.projects.label")
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
