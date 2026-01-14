package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import com.projectjuggler.core.*
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.util.BundledCliManager
import com.projectjuggler.plugin.util.IdeJuggler
import com.projectjuggler.plugin.services.MainProjectService
import com.projectjuggler.util.GitUtils
import com.projectjuggler.util.ProjectLockUtils
import java.awt.Component
import java.nio.file.Files.exists
import javax.swing.JList
import kotlin.io.path.Path
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

/**
 * Determines if the given project is the currently running project.
 * Returns true if syncing this project would require self-shutdown.
 */
private fun isCurrentProject(configRepository: ConfigRepository, projectPath: ProjectPath): Boolean {
    val configPathStr = System.getProperty("idea.config.path") ?: return false
    val configPath = Path(configPathStr)

    // Check if this is an isolated project:
    // Path should be: ~/.project-juggler/projects/<project-id>/config
    val parts = configPath.toString().split("/").filter { it.isNotEmpty() }
    val projectsIndex = parts.indexOf("projects")

    if (projectsIndex >= 0 && projectsIndex + 2 < parts.size && parts[projectsIndex + 2] == "config") {
        val currentProjectId = parts[projectsIndex + 1]

        // Get the project ID for the target path
        val targetMetadata = ProjectManager.getInstance(configRepository).get(projectPath)
        return targetMetadata?.id?.id == currentProjectId
    }

    return false
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
            ProjectAction.SyncSettings(SyncType.Plugins),
            ProjectAction.ToggleMainProject,
            ProjectAction.RemoveProject
        )

        return object : BaseListPopupStep<ProjectAction>(null, actions) {
            override fun onChosen(selectedValue: ProjectAction, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                when (selectedValue) {
                    ProjectAction.OpenProject -> {
                        launchOrFocusProject(item.projectPath)
                        return FINAL_CHOICE
                    }
                    is ProjectAction.SyncSettings -> {
                        syncSingleProjectWithType(item.projectPath, selectedValue.syncType)
                        return FINAL_CHOICE
                    }
                    ProjectAction.ToggleMainProject -> {
                        toggleMainProject(item.projectPath)
                        return FINAL_CHOICE
                    }
                    ProjectAction.RemoveProject -> {
                        removeProject(item.projectPath)
                        return doFinalStep {
                            RecentProjectsPopup(project).show()
                        }
                    }
                }
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
                ProjectAction.ToggleMainProject -> {
                    val isMain = MainProjectService.isMainProject(configRepository, item.projectPath)
                    if (isMain) ProjectJugglerBundle.message("popup.recent.projects.action.unset.main")
                    else ProjectJugglerBundle.message("popup.recent.projects.action.set.main")
                }
                ProjectAction.RemoveProject ->
                    ProjectJugglerBundle.message("popup.recent.projects.action.remove")
            }
        }
    }

    private fun handleItemSelection(item: PopupListItem) {
        when (item) {
            is RecentProjectItem -> launchOrFocusProject(item.projectPath)
            is OpenFileChooserItem -> showFileChooserAndLaunch()
            is SyncProjectsItem -> syncAllProjectsWithType(item.syncType)
        }
    }

    /**
     * Handles launching or focusing a project based on whether it's already open.
     * If project is open, attempts to focus the window.
     * If project is closed, launches it normally.
     */
    private fun launchOrFocusProject(projectPath: ProjectPath) {
        val isOpen = ProjectLockUtils.isProjectOpen(configRepository, projectPath)

        if (isOpen) {
            // Try to focus the existing window
            IdeJuggler.focusExistingProject(project, configRepository, projectPath)
        } else {
            // Launch new instance
            IdeJuggler.launchProject(project, configRepository, projectPath)
        }
    }

    private fun syncAllProjectsWithType(syncType: SyncType) {
        if (isSyncingAllIncludingMe()) {
            handleSelfShutdownSyncAll(syncType)
            return
        }

        val allProjects = configRepository.loadAllProjects()
        performSyncWithProgress(
            projects = allProjects,
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message("progress.sync.all.projects.type", syncType.displayName),
            successMessage = {
                ProjectJugglerBundle.message(
                    "notification.success.sync.all.projects.type",
                    syncType.displayName,
                    it.size
                )
            },
            errorMessage = { e ->
                ProjectJugglerBundle.message("notification.error.sync.projects.failed", e.message ?: "")
            }
        )
    }

    private fun isSyncingAllIncludingMe(): Boolean {
        // Check if current project is in the list (self-shutdown case)
        val allProjects = configRepository.loadAllProjects()
        val currentProjectInList = allProjects.any { isCurrentProject(configRepository, it.path) }
        return currentProjectInList
    }

    /**
     * Handles syncing all projects when current project is in the list.
     * Spawns CLI with --all-projects and shuts down.
     */
    private fun handleSelfShutdownSyncAll(syncType: SyncType) {
        performSelfShutdownSync(
            notificationMessage = "IntelliJ will close to sync all projects and reopen automatically...",
            cliArgs = listOf("sync", "--all-projects"),
            syncType = syncType
        )
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

        IdeJuggler.launchProject(project, configRepository, projectPath)
    }

    private fun syncSingleProjectWithType(projectPath: ProjectPath, syncType: SyncType) {
        // Check if syncing current project (self-shutdown case)
        if (isCurrentProject(configRepository, projectPath)) {
            handleSelfShutdownSync(projectPath, syncType)
            return
        }

        val metadata = ProjectManager.getInstance(configRepository).get(projectPath) ?: return
        performSyncWithProgress(
            projects = listOf(metadata),
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message("progress.sync.project.type", syncType.displayName, projectPath.name),
            successMessage = { projects ->
                ProjectJugglerBundle.message(
                    "notification.success.sync.single.project.type",
                    syncType.displayName,
                    projects.first().path.name
                )
            },
            errorMessage = { e ->
                ProjectJugglerBundle.message("notification.error.sync.settings.failed", e.message ?: "")
            }
        )
    }

    private fun removeProject(projectPath: ProjectPath) {
        val metadata = ProjectManager.getInstance(configRepository).get(projectPath) ?: return

        // cleaning recent projects eagerly so that we can show a new recent popup right away
        RecentProjectsIndex.getInstance(configRepository).remove(metadata.id)

        application.executeOnPooledThread {
            ProjectCleaner.getInstance(configRepository).cleanProject(metadata)
        }
    }

    private fun toggleMainProject(projectPath: ProjectPath) {
        val isMain = MainProjectService.isMainProject(configRepository, projectPath)
        application.invokeLater {
            val message = if (isMain) {
                ProjectJugglerBundle.message("dialog.confirm.unset.main.message", projectPath.name)
            } else {
                ProjectJugglerBundle.message("dialog.confirm.set.main.message", projectPath.name)
            }
            val title = if (isMain) {
                ProjectJugglerBundle.message("dialog.confirm.unset.main.title")
            } else {
                ProjectJugglerBundle.message("dialog.confirm.set.main.title")
            }

            val result = Messages.showOkCancelDialog(
                project,
                message,
                title,
                Messages.getOkButton(),
                Messages.getCancelButton(),
                Messages.getQuestionIcon()
            )

            if (result == Messages.OK) {
                if (isMain) {
                    MainProjectService.clearMainProject(configRepository)
                    showInfoNotification(
                        ProjectJugglerBundle.message("notification.success.unset.main"),
                        project
                    )
                } else {
                    MainProjectService.setMainProject(configRepository, projectPath)
                    showInfoNotification(
                        ProjectJugglerBundle.message("notification.success.set.main", projectPath.name),
                        project
                    )
                }
            }
        }
    }

    /**
     * Common sync logic with progress indicator.
     * Performs sync for a list of projects with progress tracking and notifications.
     */
    private fun performSyncWithProgress(
        projects: List<ProjectMetadata>,
        syncType: SyncType,
        taskTitle: String,
        successMessage: (List<ProjectMetadata>) -> String,
        errorMessage: (Exception) -> String
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = projects.size == 1
                    val launcher = ProjectLauncher(configRepository)

                    val syncOptions = SyncOptions(
                        stopIfRunning = true,
                        autoRestart = true,
                        shutdownTimeout = 60,
                        onProgress = { progress ->
                            when (progress) {
                                is SyncProgress.Stopping -> {
                                    indicator.text = "Stopping IntelliJ..."
                                }
                                is SyncProgress.Syncing -> {
                                    indicator.text = "Syncing ${syncType.displayName}..."
                                }
                                is SyncProgress.Restarting -> {
                                    indicator.text = "Restarting IntelliJ..."
                                }
                                is SyncProgress.Error -> {
                                    // Error handled in catch block
                                }
                            }
                        }
                    )

                    projects.forEachIndexed { index, projectMetadata ->
                        ProgressManager.checkCanceled()
                        indicator.text = ProjectJugglerBundle.message(
                            "progress.sync.project.type",
                            syncType.displayName,
                            projectMetadata.path.name
                        )

                        if (projects.size > 1) {
                            indicator.fraction = index.toDouble() / projects.size
                        }

                        launcher.syncProject(
                            projectMetadata,
                            syncVmOptions = syncType.syncVmOptions,
                            syncConfig = syncType.syncConfig,
                            syncPlugins = syncType.syncPlugins,
                            syncOptions
                        )
                    }

                    showInfoNotification(successMessage(projects), project)
                } catch (e: Exception) {
                    if (e is ControlFlowException) throw e
                    showErrorNotification(errorMessage(e), project)
                }
            }
        })
    }

    /**
     * Handles syncing the current project by spawning CLI and shutting down.
     */
    private fun handleSelfShutdownSync(projectPath: ProjectPath, syncType: SyncType) {
        performSelfShutdownSync(
            notificationMessage = "IntelliJ will close to sync ${syncType.displayName} and reopen automatically...",
            cliArgs = listOf("sync", "--path", projectPath.pathString),
            syncType = syncType
        )
    }

    /**
     * Common logic for self-shutdown sync operations.
     * Spawns CLI process and exits IntelliJ gracefully.
     */
    private fun performSelfShutdownSync(
        notificationMessage: String,
        cliArgs: List<String>,
        syncType: SyncType
    ) {
        try {
            showInfoNotification(notificationMessage, project)

            // Get bundled CLI executable
            val cliExecutable = BundledCliManager.getCliExecutable()

            val arg = when(syncType) {
                SyncType.All -> "--all"
                SyncType.VmOptions -> "--vmoptions"
                SyncType.Config -> "--config"
                SyncType.Plugins -> "--plugins"
            }

            // Spawn CLI process
            ProcessBuilder(cliExecutable.toString(), *(cliArgs + arg).toTypedArray())
                .inheritIO()
                .start()

            // Wait briefly to ensure CLI started
            Thread.sleep(100)

            // Exit IntelliJ
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().exit()
            }
        } catch (e: Exception) {
            showErrorNotification(
                "Failed to initiate self-shutdown sync: ${e.message}",
                project
            )
        }
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
