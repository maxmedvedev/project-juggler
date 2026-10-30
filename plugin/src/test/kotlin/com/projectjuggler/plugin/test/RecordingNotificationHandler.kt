package com.projectjuggler.plugin.test

import com.projectjuggler.core.NotificationHandler

/**
 * [NotificationHandler] fake that records the messages it receives.
 */
class RecordingNotificationHandler : NotificationHandler {
    val infos = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun showInfoNotification(message: String) {
        infos += message
    }

    override fun showErrorNotification(message: String) {
        errors += message
    }
}
