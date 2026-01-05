package com.projectjuggler.plugin.util

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.MessageOutput
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification

internal object IdeJuggler {
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
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Launching ${projectPath.name}...") {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val launcher = ProjectLauncher.Companion.getInstance(configRepository)

                    // Silent message output for plugin context
                    val messageOutput = object : MessageOutput {
                        override fun echo(message: String) {
                            // Suppress console output in plugin context
                            // Notifications are handled separately
                        }
                    }

                    launcher.launch(messageOutput, projectPath)

                    showInfoNotification(
                        ProjectJugglerBundle.message(
                            "notification.success.launched",
                            projectPath.name
                        ), project
                    )
                } catch (ex: Exception) {
                    showErrorNotification(
                        ProjectJugglerBundle.message(
                            "notification.error.launch.failed",
                            ex.message ?: "Unknown error"
                        ), project
                    )
                    ex.printStackTrace()
                }
            }
        })
    }
}