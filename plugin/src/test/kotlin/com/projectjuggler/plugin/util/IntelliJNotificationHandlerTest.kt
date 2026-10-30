package com.projectjuggler.plugin.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.projectjuggler.plugin.test.TestApplicationExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class IntelliJNotificationHandlerTest : StringSpec({
    extensions(TestApplicationExtension())

    /** Runs [block] while recording every notification posted on the application message bus. */
    fun recordNotifications(block: () -> Unit): List<Notification> {
        val recorded = mutableListOf<Notification>()
        val connection = ApplicationManager.getApplication().messageBus.connect()
        try {
            connection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    recorded += notification
                }
            })
            block()
        } finally {
            connection.disconnect()
        }
        return recorded
    }

    "info notification goes to the plugin group as INFORMATION" {
        val notifications = recordNotifications {
            IntelliJNotificationHandler(project = null).showInfoNotification("hello")
        }

        notifications shouldHaveSize 1
        notifications.single().groupId shouldBe "project-juggler.notifications"
        notifications.single().type shouldBe NotificationType.INFORMATION
        notifications.single().content shouldBe "hello"
    }

    "error notification goes to the plugin group as ERROR" {
        val notifications = recordNotifications {
            IntelliJNotificationHandler(project = null).showErrorNotification("boom")
        }

        notifications shouldHaveSize 1
        notifications.single().groupId shouldBe "project-juggler.notifications"
        notifications.single().type shouldBe NotificationType.ERROR
        notifications.single().content shouldBe "boom"
    }
})
