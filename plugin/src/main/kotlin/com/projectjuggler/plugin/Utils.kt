package com.projectjuggler.plugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.NotificationContent
import kotlin.coroutines.cancellation.CancellationException

fun showNotification(@NotificationContent message: String, project: Project?, type: NotificationType) {
    showNotification(message, project, type, actions = emptyArray())
}

/** Shows a balloon in the plugin's notification group with [actions] as its links. */
fun showNotification(
    @NotificationContent message: String,
    project: Project?,
    type: NotificationType,
    vararg actions: AnAction,
) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("project-juggler.notifications")
        .createNotification(message, type)
        .apply { actions.forEach(::addAction) }
        .notify(project)
}

fun showInfoNotification(@NotificationContent message: String, project: Project?) {
    showNotification(message, project, NotificationType.INFORMATION)
}

fun showErrorNotification(@NotificationContent message: String, project: Project?) {
    showNotification(message, project, NotificationType.ERROR)
}

/** Rethrows exceptions that must never be swallowed. Call first in every `catch (t: Throwable)`. */
internal fun Throwable.rethrowIfControlFlow() {
    if (this is ControlFlowException || this is CancellationException) throw this
}

/** The exception message for a notification, or the bundle's "Unknown error". */
internal fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: ProjectJugglerBundle.message("error.unknown")
