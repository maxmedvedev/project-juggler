package com.projectjuggler.plugin

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.MessageOutput
import com.projectjuggler.core.ProjectLauncher
import com.intellij.openapi.project.Project
import com.intellij.util.application

internal object ProjectLauncherHelper {
    /**
     * Launches a project with Project Juggler in a background thread and shows notifications.
     *
     * @param project The current IDE project (for notifications, can be null)
     * @param configRepository The configuration repository
     * @param projectPath The path to the project to launch
     */
    fun launchProject(
        project: Project?,
        configRepository: ConfigRepository,
        projectPath: ProjectPath,
    ) {
        application.executeOnPooledThread {
            try {
                val launcher = ProjectLauncher.getInstance(configRepository)

                // Silent message output for plugin context
                val messageOutput = object : MessageOutput {
                    override fun echo(message: String) {
                        // Suppress console output in plugin context
                        // Notifications are handled separately
                    }
                }

                launcher.launch(messageOutput, projectPath)

                showInfoNotification(ProjectJugglerBundle.message("notification.success.launched", projectPath.name), project)
            } catch (ex: Exception) {
                showErrorNotification(ProjectJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error"), project)
                ex.printStackTrace()
            }
        }
    }
}
