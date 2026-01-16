package com.projectjuggler.plugin.util

import com.intellij.openapi.project.Project
import com.projectjuggler.core.NotificationHandler

internal class IntelliJNotificationHandler(
    val project: Project?
) : NotificationHandler {
    override fun showInfoNotification(message: String) {
        com.projectjuggler.plugin.showInfoNotification(message, project)
    }

    override fun showErrorNotification(message: String) {
        com.projectjuggler.plugin.showErrorNotification(message, project)
    }
}