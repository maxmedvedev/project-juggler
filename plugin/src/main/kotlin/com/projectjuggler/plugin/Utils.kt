package com.projectjuggler.plugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.NotificationContent

fun showNotification(@NotificationContent message: String, project: Project?, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("project-juggler.notifications")
        .createNotification(message, type)
        .notify(project)
}

fun showInfoNotification(@NotificationContent message: String, project: Project?) {
    showNotification(message, project, NotificationType.INFORMATION)
}

fun showErrorNotification(@NotificationContent message: String, project: Project?) {
    showNotification(message, project, NotificationType.ERROR)
}

/** The exception message for a notification, or the bundle's "Unknown error". */
internal fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: ProjectJugglerBundle.message("error.unknown")
